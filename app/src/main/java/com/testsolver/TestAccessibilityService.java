package com.testsolver;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;

public class TestAccessibilityService extends AccessibilityService {

    private WindowManager windowManager;
    private View overlayView;

    private EditText inputField;
    private TextView resultView;

    private boolean isOverlayShown = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    public void showOverlay() {
        if (isOverlayShown) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                overlayView = LayoutInflater.from(this).inflate(R.layout.overlay, null);

                inputField = overlayView.findViewById(R.id.inputField);
                resultView = overlayView.findViewById(R.id.resultView);

                setupInputLogic();

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

                params.gravity = Gravity.TOP;

                windowManager.addView(overlayView, params);
                isOverlayShown = true;

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void hideOverlay() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (overlayView != null) {
                    windowManager.removeView(overlayView);
                    overlayView = null;
                }
                isOverlayShown = false;
            } catch (Exception ignored) {}
        });
    }

    private void setupInputLogic() {
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            String input = safeText(inputField.getText());
            String result = processText(input);
            resultView.setText(result);
            return true;
        });
    }

    private String safeText(CharSequence text) {
        return text == null ? "" : text.toString();
    }

    private String processText(String text) {
        if (text.isEmpty()) return "";

        text = text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}0-9\\s]", "")
                .trim();

        return "Ответ: " + text;
    }
}
