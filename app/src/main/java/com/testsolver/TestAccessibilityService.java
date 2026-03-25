package com.testsolver;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class TestAccessibilityService extends AccessibilityService {

    public static TestAccessibilityService instance;

    private AnswerDatabase db;
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvAnswer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastMatchedQuestion = "";
    private long lastEventTime = 0;

    @Override
    public void onServiceConnected() {
        instance = this;
        db = new AnswerDatabase();
        db.load(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        showToast("✅ TestSolver активен (" + db.size() + " ответов)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastEventTime < 600) return; // debounce
        lastEventTime = now;

        handler.postDelayed(() -> analyzeScreen(), 300);
    }

    private void analyzeScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // Collect all visible text
        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        root.recycle();

        String screenText = String.join(" ", texts);
        if (screenText.length() < 10) return;

        AnswerDatabase.Answer answer = db.findAnswer(screenText);
        if (answer == null) {
            // No match — clear overlay quietly
            updateOverlay(null, null);
            return;
        }

        // Same question — don't spam
        String questionKey = answer.num + answer.answerText;
        if (questionKey.equals(lastMatchedQuestion)) return;
        lastMatchedQuestion = questionKey;

        updateOverlay(answer, screenText);

        // Try to auto-fill/auto-click
        if ("text".equals(answer.type)) {
            autoFillText(answer.answerText);
        } else if ("radio".equals(answer.type) || "checkbox".equals(answer.type)) {
            autoClick(answer);
        }
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 1) out.add(text.toString());
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 1) out.add(desc.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectText(child, out);
            if (child != null) child.recycle();
        }
    }

    /** Auto-fill the first visible EditText / INPUT field */
    private void autoFillText(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo input = findEditText(root);
        if (input != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) {
                handler.postDelayed(() -> showToast("✏️ Вставлено: " + value), 100);
            }
            input.recycle();
        }
        root.recycle();
    }

    private AccessibilityNodeInfo findEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".equals(node.getClassName())
                || node.isEditable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findEditText(child);
            if (found != null) {
                if (child != found) child.recycle();
                return found;
            }
            if (child != null) child.recycle();
        }
        return null;
    }

    /** Auto-click checkbox/radio matching the answer text */
    private void autoClick(AnswerDatabase.Answer answer) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<String> targets = answer.answerList != null ? answer.answerList
                : java.util.Collections.singletonList(answer.answerText);

        int clicked = 0;
        for (String target : targets) {
            // Try to find node by text containing the target
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
            for (AccessibilityNodeInfo n : nodes) {
                // Walk up to find clickable parent (the label row/checkbox)
                AccessibilityNodeInfo clickable = findClickable(n);
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    clicked++;
                    clickable.recycle();
                }
                n.recycle();
            }
        }
        root.recycle();

        if (clicked == 0) {
            // Can't find by node — inform user via overlay, they click manually
        }
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (depth > 0) cur.recycle();
            cur = parent;
            depth++;
        }
        return null;
    }

    // ─── Overlay ────────────────────────────────────────────────

    private void showOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null);
        tvAnswer = overlayView.findViewById(R.id.tv_answer);

        int layoutFlag = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM;
        params.y = 60;

        overlayView.setVisibility(View.GONE);

        Button btnClose = overlayView.findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> {
            overlayView.setVisibility(View.GONE);
            lastMatchedQuestion = "";
        });

        windowManager.addView(overlayView, params);
    }

    private void updateOverlay(AnswerDatabase.Answer answer, String screenText) {
        handler.post(() -> {
            if (overlayView == null) return;
            if (answer == null) {
                overlayView.setVisibility(View.GONE);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📖 Вопрос ").append(answer.num).append("\n");

            String typeLabel;
            switch (answer.type) {
                case "text": typeLabel = "✏️ Введи:"; break;
                case "radio": typeLabel = "🔘 Выбери:"; break;
                case "checkbox": typeLabel = "☑️ Отметь:"; break;
                case "match": typeLabel = "🔗 Соответствие:"; break;
                default: typeLabel = "➡️";
            }
            sb.append(typeLabel).append("\n");

            if (answer.answerList != null) {
                for (String a : answer.answerList) sb.append("  • ").append(a).append("\n");
            } else {
                sb.append("  ").append(answer.answerText);
            }

            tvAnswer.setText(sb.toString().trim());
            overlayView.setVisibility(View.VISIBLE);
        });
    }

    private void showToast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
    }
}
