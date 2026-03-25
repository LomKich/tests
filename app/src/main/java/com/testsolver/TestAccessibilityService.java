package com.testsolver;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
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
import java.util.Collections;
import java.util.List;

public class TestAccessibilityService extends AccessibilityService {

    public static final String ACTION_TOGGLE_PAUSE = "com.testsolver.TOGGLE_PAUSE";
    public static TestAccessibilityService instance;

    private AnswerDatabase db;
    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private TextView tvAnswer;
    private TextView tvPauseLabel;

    public static boolean isPaused = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastEventTime = 0;
    private String lastScreenHash = "";

    private final BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            isPaused = !isPaused;
            updatePauseVisual();
            if (isPaused) overlayView.setVisibility(View.GONE);
        }
    };

    @Override
    public void onServiceConnected() {
        instance = this;
        db = new AnswerDatabase();
        db.load(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
        registerReceiver(toggleReceiver, new IntentFilter(ACTION_TOGGLE_PAUSE));
        showToast("✅ TestSolver активен — " + db.size() + " вопросов");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isPaused) return;
        long now = System.currentTimeMillis();
        if (now - lastEventTime < 600) return;
        lastEventTime = now;
        handler.postDelayed(this::analyzeScreen, 250);
    }

    private void analyzeScreen() {
        if (isPaused) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        root.recycle();
        if (texts.isEmpty()) return;

        String screenText = String.join(" ", texts);

        // Debounce: skip if screen didn't change significantly
        String hash = String.valueOf(screenText.hashCode());
        if (hash.equals(lastScreenHash)) return;
        lastScreenHash = hash;

        AnswerDatabase.Answer answer = db.findAnswer(screenText);
        updateOverlay(answer);
        if (answer != null) {
            handler.postDelayed(() -> autoAct(answer), 350);
        }
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 1) out.add(t.toString());
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 1) out.add(d.toString());
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
    }

    private void autoFillText(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo input = findEditText(root);
        if (input != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
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
            if (found != null) { if (child != found) child.recycle(); return found; }
            if (child != null) child.recycle();
        }
        return null;
    }

    private void autoClick(AnswerDatabase.Answer answer) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<String> targets = answer.answerList != null
                ? answer.answerList : Collections.singletonList(answer.answerText);
        for (String target : targets) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
            if (nodes.isEmpty() && target.contains(" ")) {
                String firstWord = target.split("\\s+")[0];
                if (firstWord.length() > 3)
                    nodes = root.findAccessibilityNodeInfosByText(firstWord);
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
        for (int i = 0; i < maxDepth; i++) {
            if (cur == null) return null;
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (i > 0) cur.recycle();
            cur = parent;
        }
        return null;
    }

    // ─── Overlay ────────────────────────────────────────────────────────────

    private void showOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null);
        tvAnswer = overlayView.findViewById(R.id.tv_answer);
        tvPauseLabel = overlayView.findViewById(R.id.tv_pause_label);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.BOTTOM | Gravity.START;
        overlayParams.x = 0;
        overlayParams.y = 60;
        overlayView.setVisibility(View.GONE);

        // Close button
        overlayView.findViewById(R.id.btn_close).setOnClickListener(v ->
                overlayView.setVisibility(View.GONE));

        // Drag handle — drag overlay by touching the header strip
        View dragHandle = overlayView.findViewById(R.id.drag_handle);
        dragHandle.setOnTouchListener(new DragTouchListener());

        windowManager.addView(overlayView, overlayParams);
    }

    /** Touch listener that drags the overlay window */
    private class DragTouchListener implements View.OnTouchListener {
        float startRawX, startRawY;
        int startX, startY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = e.getRawX();
                    startRawY = e.getRawY();
                    startX = overlayParams.x;
                    startY = overlayParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = startX + (int)(e.getRawX() - startRawX);
                    overlayParams.y = startY - (int)(e.getRawY() - startRawY);
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }

    private void updateOverlay(AnswerDatabase.Answer answer) {
        handler.post(() -> {
            if (overlayView == null || isPaused) return;
            if (answer == null) {
                overlayView.setVisibility(View.GONE);
                return;
            }
            StringBuilder sb = new StringBuilder();
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

    private void updatePauseVisual() {
        handler.post(() -> {
            if (tvPauseLabel != null)
                tvPauseLabel.setText(isPaused ? "⏸ ПАУЗА" : "▶ TestSolver");
        });
    }

    private void showToast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        try { unregisterReceiver(toggleReceiver); } catch (Exception ignored) {}
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
    }


}
