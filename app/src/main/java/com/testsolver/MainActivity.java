package com.testsolver;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private Button   btnPause;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnPause = findViewById(R.id.btn_pause);

        // 1. Сервис доступности
        findViewById(R.id.btn_accessibility).setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Найди TestSolver → переключатель вправо", Toast.LENGTH_LONG).show();
        });

        // 2. Разрешение оверлея
        findViewById(R.id.btn_overlay).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "✅ Разрешение уже есть", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. Фоновая работа (MIUI / HyperOS)
        findViewById(R.id.btn_battery).setOnClickListener(v -> {
            // Сначала пробуем прямой запрос на игнор батарейной оптимизации
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } else {
                    Toast.makeText(this, "✅ Батарейная оптимизация уже отключена", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                // Fallback — открываем общие настройки батареи
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ex) {
                    Toast.makeText(this, "Открой Настройки → Батарея → Не оптимизировать → TestSolver",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        // 4. Пауза
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

        // 5. Добавить вопрос
        findViewById(R.id.btn_add_question).setOnClickListener(v ->
                startActivity(new Intent(this, AddQuestionActivity.class)));

        // 6. AI настройки (Gemini / Pollinations)
        findViewById(R.id.btn_ai_settings).setOnClickListener(v ->
                startActivity(new Intent(this, GeminiSettingsActivity.class)));
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

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryOk = pm.isIgnoringBatteryOptimizations(getPackageName());

        StringBuilder sb = new StringBuilder();
        sb.append(serviceOn  ? "✅" : "❌").append(" Сервис доступности\n");
        sb.append(overlayOn  ? "✅" : "❌").append(" Разрешение оверлея\n");
        sb.append(batteryOk  ? "✅" : "⚠️").append(" Фоновая работа (батарея)\n");

        if (serviceOn) {
            sb.append(paused ? "⏸ Режим: ПАУЗА" : "▶ Режим: активен").append("\n");
        }

        sb.append("\n");
        if (serviceOn && overlayOn) {
            if (!batteryOk) {
                sb.append("⚠️ Нажми «Фоновая работа» — иначе MIUI\nможет убить сервис.");
            } else {
                sb.append("🟢 Готово! Открой Chrome → зайди на тест.");
            }
        } else {
            sb.append("👆 Включи пункты выше.\n");
            if (!serviceOn) sb.append("\nЕсли сервис пишет «not working» —\nнажми «Фоновая работа» и попробуй снова.");
        }

        tvStatus.setText(sb.toString());
        btnPause.setEnabled(serviceOn);
        btnPause.setText(paused ? "▶ Возобновить" : "⏸ Пауза");
    }
}
