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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);

        Button btnAccessibility = findViewById(R.id.btn_accessibility);
        Button btnOverlay = findViewById(R.id.btn_overlay);

        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this,
                "Найди 'TestSolver' и включи", Toast.LENGTH_LONG).show();
        });

        btnOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "✅ Разрешение уже есть", Toast.LENGTH_SHORT).show();
            }
        });
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

        StringBuilder sb = new StringBuilder();
        sb.append(serviceOn ? "✅" : "❌").append(" Сервис доступности\n");
        sb.append(overlayOn ? "✅" : "❌").append(" Разрешение оверлея\n\n");

        if (serviceOn && overlayOn) {
            sb.append("🟢 Всё готово!\nОткрой тест в Chrome — ответ\nпоявится снизу автоматически.");
        } else {
            sb.append("👆 Включи оба пункта выше.");
        }
        tvStatus.setText(sb.toString());
    }
}
