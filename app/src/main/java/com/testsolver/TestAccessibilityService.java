package com.testsolver;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestAccessibilityService extends AccessibilityService {

    public static TestAccessibilityService instance;

    private AnswerDatabase db;
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvAnswer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Track current question number to detect changes
    private int currentQuestionNum = -1;
    private long lastEventTime = 0;
    private static final long DEBOUNCE_MS = 500;

    @Override
    public void onServiceConnected() {
        instance = this;
        db = new AnswerDatabase();
        db.load(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        showToast("✅ TestSolver активен — " + db.size() + " вопросов");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastEventTime < DEBOUNCE_MS) return;
        lastEventTime = now;
        handler.postDelayed(this::analyzeScreen, 200);
    }

    private void analyzeScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        root.recycle();

        if (texts.isEmpty()) return;
        String screenText = String.join(" ", texts);

        // Extract question number from screen ("44 из 55")
        int qNum = db.extractQuestionNumber(screenText);

        // If question number changed → reset and find new answer
        if (qNum != currentQuestionNum) {
            currentQuestionNum = qNum;
            AnswerDatabase.Answer answer = db.findAnswer(screenText);
            updateOverlay(answer);
            if (answer != null) {
                // Auto-act after a short delay to let page settle
                handler.postDelayed(() -> autoAct(answer), 400);
            }
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

    private void autoAct(AnswerDatabase.Answer answer) {
        if ("text".equals(answer.type)) {
            autoFillText(answer.answerText);
        } else if ("radio".equals(answer.type) || "checkbox".equals(answer.type)) {
            autoClick(answer);
        }
        // match type — just show in overlay, can't auto-click dropdowns
    }

    private void autoFillText(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo input = findEditText(root);
        if (input != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (!ok) {
                // Fallback: focus then paste
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            input.recycle();
        }
        root.recycle();
    }

    private AccessibilityNodeInfo findEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
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

    private void autoClick(AnswerDatabase.Answer answer) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<String> targets = answer.answerList != null
                ? answer.answerList
                : Collections.singletonList(answer.answerText);

        for (String target : targets) {
            // Try exact text match first
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
            // If no result, try with first significant word
            if (nodes.isEmpty() && target.length() > 3) {
                String[] words = target.split("\\s+");
                if (words.length > 0) {
                    nodes = root.findAccessibilityNodeInfosByText(words[0]);
                }
            }
            for (AccessibilityNodeInfo n : nodes) {
                AccessibilityNodeInfo clickable = findClickableParent(n, 6);
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (clickable != n) clickable.recycle();
                }
                n.recycle();
            }
        }
        root.recycle();
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node, int maxDepth) {
        AccessibilityNodeInfo cur = node;
        int depth = 0;
        while (cur != null && depth < maxDepth) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (depth > 0) cur.recycle();
            cur = parent;
            depth++;
        }
        return null;
    }

    // ─── Overlay ───────────────────────────────────────────────────────────────

    private void showOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null);
        tvAnswer = overlayView.findViewById(R.id.tv_answer);
        overlayView.setVisibility(View.GONE);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
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

        Button btnClose = overlayView.findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> overlayView.setVisibility(View.GONE));

        windowManager.addView(overlayView, params);
    }

    private void updateOverlay(AnswerDatabase.Answer answer) {
        handler.post(() -> {
            if (overlayView == null) return;
            if (answer == null) {
                overlayView.setVisibility(View.GONE);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📖 Вопрос ").append(answer.num).append("/55\n");

            switch (answer.type) {
                case "text":     sb.append("✏️ Введи:\n"); break;
                case "radio":    sb.append("🔘 Выбери:\n"); break;
                case "checkbox": sb.append("☑️ Отметь:\n"); break;
                case "match":    sb.append("🔗 Соответствие:\n"); break;
            }

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
