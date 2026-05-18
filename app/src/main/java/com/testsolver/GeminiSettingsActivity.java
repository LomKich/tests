package com.testsolver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class GeminiSettingsActivity extends AppCompatActivity {

    private EditText etGeminiKey;
    private EditText etGroqKey;
    private TextView tvStatus;
    private Button   btnToggleAi;
    private SeekBar  sbCooldown;
    private TextView tvCooldownVal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gemini_settings);

        etGeminiKey  = findViewById(R.id.et_gemini_key);
        etGroqKey    = findViewById(R.id.et_groq_key);
        tvStatus     = findViewById(R.id.tv_gemini_status);
        btnToggleAi  = findViewById(R.id.btn_toggle_ai);
        sbCooldown   = findViewById(R.id.sb_cooldown);
        tvCooldownVal = findViewById(R.id.tv_cooldown_val);

        updateStatus();
        initCooldownSlider();

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

        // ── Groq ──────────────────────────────────────────────────────────────

        findViewById(R.id.btn_get_groq_key).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://console.groq.com/keys"))));

        findViewById(R.id.btn_save_groq_key).setOnClickListener(v -> {
            String key = etGroqKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Введите ключ Groq", Toast.LENGTH_SHORT).show();
                return;
            }
            AiClient.saveGroqKey(this, key);
            AiClient.setEnabled(this, true);
            reloadService();
            etGroqKey.setText("");
            updateStatus();
            Toast.makeText(this, "✅ Ключ Groq сохранён — стриминг активен!", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_clear_groq_key).setOnClickListener(v -> {
            AiClient.saveGroqKey(this, "");
            reloadService();
            updateStatus();
            Toast.makeText(this, "Groq-ключ удалён", Toast.LENGTH_SHORT).show();
        });

        // ── Gemini ────────────────────────────────────────────────────────────

        findViewById(R.id.btn_get_key).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://aistudio.google.com/app/apikey"))));

        findViewById(R.id.btn_save_key).setOnClickListener(v -> {
            String key = etGeminiKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Введите ключ Gemini", Toast.LENGTH_SHORT).show();
                return;
            }
            AiClient.saveGeminiKey(this, key);
            AiClient.setEnabled(this, true);
            reloadService();
            etGeminiKey.setText("");
            updateStatus();
            Toast.makeText(this, "✅ Ключ Gemini сохранён", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_clear_key).setOnClickListener(v -> {
            AiClient.saveGeminiKey(this, "");
            reloadService();
            updateStatus();
            Toast.makeText(this, "Gemini-ключ удалён", Toast.LENGTH_SHORT).show();
        });

        // ── Тест ─────────────────────────────────────────────────────────────

        findViewById(R.id.btn_test_key).setOnClickListener(v -> {
            if (!AiClient.isEnabled(this)) {
                Toast.makeText(this, "AI выключен — сначала включи", Toast.LENGTH_SHORT).show();
                return;
            }
            tvStatus.append("\n\n⏳ Тестирую...");
            new AiClient(this).ask("Скажи одно слово: Привет", new AiClient.Callback() {
                @Override public void onPartial(String p) {
                    tvStatus.setText(getStatusText() + "\n\n⚡ " + p);
                }
                @Override public void onResult(String answer) {
                    tvStatus.setText(getStatusText() + "\n\n✅ Работает! Ответ: " + answer);
                }
                @Override public void onError(String error) {
                    tvStatus.setText(getStatusText() + "\n\n❌ Ошибка: " + error);
                }
            });
        });
    }

    private void initCooldownSlider() {
        // Диапазон 3–15 секунд, шаг 1 сек
        int currentSec = (int)(AiClient.getCooldownMs(this) / 1000);
        currentSec = Math.max(3, Math.min(15, currentSec));
        sbCooldown.setMax(12); // 0..12 → 3..15 сек
        sbCooldown.setProgress(currentSec - 3);
        tvCooldownVal.setText(currentSec + " сек");

        sbCooldown.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int sec = progress + 3;
                tvCooldownVal.setText(sec + " сек");
                AiClient.setCooldownMs(GeminiSettingsActivity.this, sec * 1000L);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private String getStatusText() {
        boolean enabled  = AiClient.isEnabled(this);
        String  groqKey  = AiClient.getGroqKey(this);
        String  geminiKey = AiClient.getGeminiKey(this);

        if (!enabled) return "🚫 AI отключён\n(оверлей будет показывать только ответы из базы)";
        if (!groqKey.isEmpty()) {
            String hint = groqKey.length() > 6 ? "…" + groqKey.substring(groqKey.length() - 4) : "****";
            return "Режим: ⚡ Groq (стриминг)\nКлюч: " + hint;
        }
        if (!geminiKey.isEmpty()) {
            String hint = geminiKey.length() > 6 ? "…" + geminiKey.substring(geminiKey.length() - 4) : "****";
            return "Режим: ✨ Gemini API\nКлюч: " + hint;
        }
        return "Режим: 🌐 Pollinations.ai\n(без ключа, бесплатно)";
    }

    private void updateStatus() {
        boolean enabled = AiClient.isEnabled(this);
        tvStatus.setText(getStatusText());
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
