package com.testsolver;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
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
import java.util.List;

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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::doHideOverlay;
    private static final long HIDE_DELAY_MS = 2500;

    // AI
    private AiClient aiClient;
    private String pendingAiText   = "";
    private long   lastAiRequestMs = 0;

    // ─── Notification channel (нужен для MIUI — держит сервис живым) ─────────

    private static final String NOTIF_CHANNEL_ID = "testsolver_service";
    private static final int    NOTIF_ID         = 1001;

    private void startForegroundCompat() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "TestSolver",
                    NotificationManager.IMPORTANCE_MIN   // тихое, без звука
            );
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIF_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notif = builder
                .setContentTitle("TestSolver активен")
                .setContentText("Сервис работает в фоне")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(Notification.PRIORITY_MIN)
                .build();

        startForeground(NOTIF_ID, notif);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        try {
            super.onServiceConnected();
            instance = this;
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            db = new AnswerDatabase();
            db.load(this);

            aiClient = new AiClient(this);

            // Foreground-уведомление — критично для MIUI, чтобы сервис не убивался
            startForegroundCompat();

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

            // ФИX: на Android 12+ нужен флаг RECEIVER_NOT_EXPORTED,
            // иначе сервис крэшится и пишет "not working"
            IntentFilter filter = new IntentFilter(ACTION_TOGGLE_PAUSE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pauseReceiver, filter, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pauseReceiver, filter);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        isPaused = false;
        cancelHide();
        doHideOverlay();
        try { unregisterReceiver(pauseReceiver); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
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

        int mode = AiClient.getMode(this);

        // ── Режим: только AI ─────────────────────────────────────────────────
        if (mode == AiClient.MODE_AI) {
            askAi(screenText);
            return;
        }

        // ── Режим: база (± AI) ───────────────────────────────────────────────
        AnswerDatabase.Answer ans = db.findAnswer(screenText);

        if (ans != null) {
            cancelHide();
            pendingAiText = "";
            if (ans.question.equals(lastQuestion)) return;
            lastQuestion = ans.question;
            showOverlay(buildDisplay(ans));
            return;
        }

        // Ответ в базе не найден
        if (mode == AiClient.MODE_DB) {
            // Только база — скрываем оверлей и ждём
            scheduleHide();
            return;
        }

        // MODE_BOTH — идём в AI
        askAi(screenText);
    }

    // ─── AI fallback ──────────────────────────────────────────────────────────

    private void askAi(String screenText) {
        if (!AiClient.isEnabled(this)) { scheduleHide(); return; }
        if (screenText.length() < 20) { scheduleHide(); return; }

        // Cooldown: не отправляем запрос чаще чем раз в N секунд
        long now = System.currentTimeMillis();
        if (now - lastAiRequestMs < AiClient.getCooldownMs(this)) return;

        // Если тот же текст уже обрабатывается — не дублируем
        if (screenText.equals(pendingAiText)) return;

        lastAiRequestMs = now;
        pendingAiText = screenText;
        showOverlay("🤖 AI думает...");
        cancelHide();

        List<AnswerDatabase.Answer> candidates = db.getTopCandidates(screenText, 5);
        aiClient.ask(screenText, candidates, new AiClient.Callback() {
            @Override
            public void onPartial(String partial) {
                if (!screenText.equals(pendingAiText)) return;
                showOverlay(partial);
            }

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

    public void reloadAi(Context ctx) {
        aiClient = new AiClient(ctx);
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
