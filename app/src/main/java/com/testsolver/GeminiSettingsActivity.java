package com.testsolver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class GeminiSettingsActivity extends AppCompatActivity {

    private EditText etKey;
    private TextView tvStatus;
    private Button   btnToggleAi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gemini_settings);

        etKey       = findViewById(R.id.et_gemini_key);
        tvStatus    = findViewById(R.id.tv_gemini_status);
        btnToggleAi = findViewById(R.id.btn_toggle_ai);

        updateStatus();

        // Включить / выключить AI полностью
        btnToggleAi.setOnClickListener(v -> {
            boolean nowEnabled = AiClient.isEnabled(this);
            AiClient.setEnabled(this, !nowEnabled);
            reloadService();
            updateStatus();
            Toast.makeText(this,
                    nowEnabled ? "🤖 AI выключен" : "🤖 AI включён",
                    Toast.LENGTH_SHORT).show();
        });

        // Получить ключ Gemini
        findViewById(R.id.btn_get_key).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://aistudio.google.com/app/apikey"))));

        // Сохранить ключ
        findViewById(R.id.btn_save_key).setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Введите ключ", Toast.LENGTH_SHORT).show();
                return;
            }
            AiClient.saveGeminiKey(this, key);
            // При сохранении ключа — автоматически включаем AI
            AiClient.setEnabled(this, true);
            reloadService();
            etKey.setText("");
            updateStatus();
            Toast.makeText(this, "✅ Ключ сохранён, используется Gemini", Toast.LENGTH_SHORT).show();
        });

        // Удалить ключ → Pollinations
        findViewById(R.id.btn_clear_key).setOnClickListener(v -> {
            AiClient.saveGeminiKey(this, "");
            reloadService();
            updateStatus();
            Toast.makeText(this, "Ключ удалён, используется Pollinations.ai", Toast.LENGTH_SHORT).show();
        });

        // Проверить текущий AI
        findViewById(R.id.btn_test_key).setOnClickListener(v -> {
            if (!AiClient.isEnabled(this)) {
                Toast.makeText(this, "AI выключен — сначала включи", Toast.LENGTH_SHORT).show();
                return;
            }
            tvStatus.append("\n\n⏳ Тестирую...");
            new AiClient(this).ask("Скажи одно слово: Привет", new AiClient.Callback() {
                @Override public void onResult(String answer) {
                    tvStatus.append("\n✅ Работает! Ответ: " + answer);
                }
                @Override public void onError(String error) {
                    tvStatus.append("\n❌ Ошибка: " + error);
                }
            });
        });
    }

    private void updateStatus() {
        boolean enabled = AiClient.isEnabled(this);
        String  key     = AiClient.getGeminiKey(this);

        StringBuilder sb = new StringBuilder();
        if (!enabled) {
            sb.append("🚫 AI отключён\n(оверлей будет показывать только ответы из базы)");
        } else if (key.isEmpty()) {
            sb.append("Режим: 🌐 Pollinations.ai\n(без ключа, бесплатно)");
        } else {
            String hint = key.length() > 6 ? "…" + key.substring(key.length() - 4) : "****";
            sb.append("Режим: ✨ Gemini API\nКлюч: ").append(hint);
        }

        tvStatus.setText(sb.toString());
        btnToggleAi.setText(enabled ? "🚫 Выключить AI" : "✅ Включить AI");
        btnToggleAi.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        enabled ? 0xFF6e3030 : 0xFF238636));
    }

    private void reloadService() {
        if (TestAccessibilityService.instance != null) {
            TestAccessibilityService.instance.reloadAi(this);
        }
    }
}
