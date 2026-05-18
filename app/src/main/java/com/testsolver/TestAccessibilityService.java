package com.testsolver;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
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
import android.widget.TextView;

public class TestAccessibilityService extends AccessibilityService {

    public static final String ACTION_TOGGLE_PAUSE = "com.testsolver.TOGGLE_PAUSE";

    public static TestAccessibilityService instance = null;
    public static boolean isPaused = false;

    private WindowManager windowManager;
    private View overlayView;
    private TextView tvAnswer;
    private TextView tvPauseLabel;
    private boolean isOverlayShown = false;

    private AnswerDatabase db;
    private String lastQuestion = "";

    private int overlayX = 0, overlayY = 120;

    private BroadcastReceiver pauseReceiver;

    // Debounce
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::doHideOverlay;
    private static final long HIDE_DELAY_MS = 2500;

    // AI
    private AiClient aiClient;
    private String pendingAiText = "";

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        db = new AnswerDatabase();
        db.load(this);

        aiClient = new AiClient();

        pauseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                isPaused = !isPaused;
                updatePauseLabel();
                if (isPaused) {
                    cancelHide();
                    doHideOverlay();
                }
            }
        };
        registerReceiver(pauseReceiver, new IntentFilter(ACTION_TOGGLE_PAUSE));
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        isPaused = false;
        cancelHide();
        doHideOverlay();
        try { unregisterReceiver(pauseReceiver); } catch (Exception ignored) {}
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {}

    // ─── Accessibility event ──────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isPaused) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        root.recycle();

        String screenText = sb.toString().trim();
        if (screenText.isEmpty()) return;

        AnswerDatabase.Answer ans = db.findAnswer(screenText);

        if (ans == null) {
            // Ответа в базе нет — спрашиваем AI
            askAi(screenText);
            return;
        }

        // Нашли в базе — отменяем всё
        cancelHide();
        pendingAiText = "";

        if (ans.question.equals(lastQuestion)) return;
        lastQuestion = ans.question;

        showOverlay(buildDisplay(ans));

        if ("text".equals(ans.type) && ans.answerText != null && !ans.answerText.isEmpty()) {
            autoFillText(ans.answerText);
        }
    }

    // ─── AI fallback ──────────────────────────────────────────────────────────

    private void askAi(String screenText) {
        if (screenText.equals(pendingAiText)) return;
        if (screenText.length() < 20) { scheduleHide(); return; }

        pendingAiText = screenText;
        showOverlay("🤖 AI думает...");
        cancelHide();

        aiClient.ask(screenText, new AiClient.Callback() {
            @Override
            public void onResult(String answer) {
                if (!screenText.equals(pendingAiText)) return;
                pendingAiText = "";
                lastQuestion  = screenText;

                if (answer == null || answer.trim().isEmpty()
                        || answer.contains("Вопрос не определён")) {
                    scheduleHide();
                    return;
                }
                showOverlay("🤖 " + answer.trim());
            }

            @Override
            public void onError(String error) {
                if (!screenText.equals(pendingAiText)) return;
                pendingAiText = "";
                showOverlay("🤖 Нет связи");
                scheduleHide();
            }
        });
    }

    public void reloadAi() {
        aiClient = new AiClient();
    }

    // ─── Hide debounce ────────────────────────────────────────────────────────

    private void scheduleHide() {
        mainHandler.removeCallbacks(hideRunnable);
        mainHandler.postDelayed(hideRunnable, HIDE_DELAY_MS);
    }

    private void cancelHide() {
        mainHandler.removeCallbacks(hideRunnable);
    }

    // ─── Text collection ──────────────────────────────────────────────────────

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) sb.append(text).append(" ");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, sb);
                child.recycle();
            }
        }
    }

    // ─── Answer display ───────────────────────────────────────────────────────

    private String buildDisplay(AnswerDatabase.Answer ans) {
        StringBuilder sb = new StringBuilder();
        String prefix;
        if ("radio".equals(ans.type))         prefix = "○ ";
        else if ("checkbox".equals(ans.type)) prefix = "☑ ";
        else if ("match".equals(ans.type))    prefix = "↔ ";
        else                                   prefix = "✎ ";

        sb.append(prefix);

        if (ans.answerList != null && !ans.answerList.isEmpty()) {
            sb.append(ans.answerList.get(0));
            for (int i = 1; i < ans.answerList.size(); i++) {
                sb.append("\n").append(prefix).append(ans.answerList.get(i));
            }
        } else if (ans.answerText != null) {
            if ("match".equals(ans.type)) {
                sb.append(ans.answerText.replace("\n", "\n↔ "));
            } else {
                sb.append(ans.answerText);
            }
        }
        return sb.toString();
    }

    // ─── Auto-fill ────────────────────────────────────────────────────────────

    private void autoFillText(String answer) {
        mainHandler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            fillFirstEditText(root, answer);
            root.recycle();
        }, 400);
    }

    private boolean fillFirstEditText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.isEditable()) {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean filled = fillFirstEditText(child, text);
                child.recycle();
                if (filled) return true;
            }
        }
        return false;
    }

    // ─── Overlay ──────────────────────────────────────────────────────────────

    private void showOverlay(String text) {
        mainHandler.post(() -> {
            if (!isOverlayShown) createOverlay();
            if (tvAnswer != null) tvAnswer.setText(text);
        });
    }

    private void createOverlay() {
        try {
            overlayView  = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null);
            tvAnswer     = overlayView.findViewById(R.id.tv_answer);
            tvPauseLabel = overlayView.findViewById(R.id.tv_pause_label);

            overlayView.findViewById(R.id.btn_close)
                    .setOnClickListener(v -> {
                        cancelHide();
                        doHideOverlay();
                    });

            View handle = overlayView.findViewById(R.id.drag_handle);
            final int[] downRawX = {0}, downRawY = {0};
            final int[] downOvX  = {0}, downOvY  = {0};

            handle.setOnTouchListener((v, ev) -> {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX[0] = (int) ev.getRawX();
                        downRawY[0] = (int) ev.getRawY();
                        downOvX[0]  = overlayX;
                        downOvY[0]  = overlayY;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        overlayX = downOvX[0] + (int) ev.getRawX() - downRawX[0];
                        overlayY = downOvY[0] + (int) ev.getRawY() - downRawY[0];
                        updateOverlayPosition();
                        break;
                }
                return true;
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = overlayX;
            params.y = overlayY;

            windowManager.addView(overlayView, params);
            isOverlayShown = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateOverlayPosition() {
        if (overlayView == null || !isOverlayShown) return;
        WindowManager.LayoutParams p =
                (WindowManager.LayoutParams) overlayView.getLayoutParams();
        p.x = overlayX;
        p.y = overlayY;
        try { windowManager.updateViewLayout(overlayView, p); } catch (Exception ignored) {}
    }

    private void updatePauseLabel() {
        mainHandler.post(() -> {
            if (tvPauseLabel != null)
                tvPauseLabel.setText(isPaused ? "⏸ TestSolver" : "▶ TestSolver");
        });
    }

    private void doHideOverlay() {
        try {
            if (overlayView != null) {
                windowManager.removeView(overlayView);
                overlayView = null;
            }
            tvAnswer     = null;
            tvPauseLabel = null;
            isOverlayShown = false;
            lastQuestion   = "";
            pendingAiText  = "";
        } catch (Exception ignored) {}
    }

    public void hideOverlay() {
        mainHandler.post(this::doHideOverlay);
    }
}
