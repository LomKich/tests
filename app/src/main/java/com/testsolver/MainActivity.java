package com.testsolver;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private Button btnPause;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus  = findViewById(R.id.tv_status);
        btnPause  = findViewById(R.id.btn_pause);

        findViewById(R.id.btn_accessibility).setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Найди TestSolver → переключатель вправо", Toast.LENGTH_LONG).show();
        });

        findViewById(R.id.btn_overlay).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "✅ Разрешение уже есть", Toast.LENGTH_SHORT).show();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (TestAccessibilityService.instance == null) {
                Toast.makeText(this,
                        "Сервис не запущен — сначала включи в настройках доступности",
                        Toast.LENGTH_LONG).show();
                return;
            }
            sendBroadcast(new Intent(TestAccessibilityService.ACTION_TOGGLE_PAUSE));
            btnPause.postDelayed(this::updateStatus, 150);
        });

        findViewById(R.id.btn_add_question).setOnClickListener(v ->
                startActivity(new Intent(this, AddQuestionActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean serviceOn = TestAccessibilityService.instance != null;
        boolean overlayOn = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);
        boolean paused    = serviceOn && TestAccessibilityService.isPaused;

        StringBuilder sb = new StringBuilder();
        sb.append(serviceOn ? "✅" : "❌").append(" Сервис доступности\n");
        sb.append(overlayOn ? "✅" : "❌").append(" Разрешение оверлея\n");

        if (serviceOn) {
            sb.append(paused ? "⏸ Режим: ПАУЗА" : "▶ Режим: активен").append("\n");
        }

        sb.append("\n");
        if (serviceOn && overlayOn) {
            sb.append("🟢 Готово! Открой Chrome → зайди на тест.");
        } else {
            sb.append("👆 Включи оба пункта выше.");
        }

        tvStatus.setText(sb.toString());
        btnPause.setEnabled(serviceOn);
        btnPause.setText(paused ? "▶ Возобновить" : "⏸ Пауза");
    }
}
