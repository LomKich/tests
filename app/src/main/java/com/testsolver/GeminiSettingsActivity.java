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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gemini_settings);

        etKey    = findViewById(R.id.et_gemini_key);
        tvStatus = findViewById(R.id.tv_gemini_status);

        // Показываем текущий режим
        updateStatus();

        // Ссылка на получение ключа — просто войти через Google
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
            reloadService();
            etKey.setText("");
            updateStatus();
            Toast.makeText(this, "✅ Ключ сохранён, используется Gemini", Toast.LENGTH_SHORT).show();
        });

        // Удалить ключ → вернуться на Pollinations
        findViewById(R.id.btn_clear_key).setOnClickListener(v -> {
            AiClient.saveGeminiKey(this, "");
            reloadService();
            updateStatus();
            Toast.makeText(this, "Ключ удалён, используется Pollinations.ai", Toast.LENGTH_SHORT).show();
        });

        // Проверить ключ
        findViewById(R.id.btn_test_key).setOnClickListener(v -> {
            String key = AiClient.getGeminiKey(this);
            if (key.isEmpty()) {
                tvStatus.append("\n\n⏳ Тестирую Pollinations.ai...");
            } else {
                tvStatus.append("\n\n⏳ Тестирую Gemini...");
            }

            new AiClient(this).ask("Скажи одно слово: Привет", new AiClient.Callback() {
                @Override
                public void onResult(String answer) {
                    tvStatus.append("\n✅ Работает! Ответ: " + answer);
                }
                @Override
                public void onError(String error) {
                    tvStatus.append("\n❌ Ошибка: " + error);
                }
            });
        });
    }

    private void updateStatus() {
        String key = AiClient.getGeminiKey(this);
        if (key.isEmpty()) {
            tvStatus.setText("Режим: 🌐 Pollinations.ai\n(без ключа, бесплатно)");
        } else {
            String hint = key.length() > 6 ? "…" + key.substring(key.length() - 4) : "****";
            tvStatus.setText("Режим: ✨ Gemini API\nКлюч: " + hint);
        }
    }

    private void reloadService() {
        if (TestAccessibilityService.instance != null) {
            TestAccessibilityService.instance.reloadAi(this);
        }
    }
}
