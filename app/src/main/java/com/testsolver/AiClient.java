package com.testsolver;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI-клиент через Pollinations.ai
 * ✅ Бесплатно, без регистрации, без API-ключей
 * Документация: https://pollinations.ai
 */
public class AiClient {

    public interface Callback {
        void onResult(String answer);
        void onError(String error);
    }

    // POST https://text.pollinations.ai/
    private static final String API_URL = "https://text.pollinations.ai/";

    private static final String SYSTEM_PROMPT =
            "Ты помощник для решения тестов и учебных заданий. " +
            "Тебе дают текст с экрана (вопрос теста с вариантами ответов). " +
            "Дай КРАТКИЙ ответ — только сами правильные ответы, без пояснений. " +
            "Если несколько вариантов — каждый с новой строки. " +
            "Если вопрос не ясен — ответь: Вопрос не определён.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler   = new Handler(Looper.getMainLooper());

    /** Отправляет текст с экрана в AI и возвращает ответ через callback на главном потоке. */
    public void ask(String screenText, Callback callback) {
        executor.execute(() -> {
            try {
                String answer = callPollinations(screenText);
                mainHandler.post(() -> callback.onResult(answer));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private String callPollinations(String screenText) throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(25_000);

        // Формируем тело запроса в формате OpenAI-compatible (Pollinations поддерживает)
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", "Текст с экрана:\n\n" + screenText);

        JSONObject body = new JSONObject();
        body.put("model", "openai");          // модель GPT-4o через Pollinations
        body.put("messages", new JSONArray().put(sysMsg).put(userMsg));
        body.put("seed", 42);                 // детерминированные ответы
        body.put("private", true);            // не публиковать в ленте

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        conn.disconnect();

        String raw = sb.toString().trim();

        if (code != 200) {
            throw new Exception("Ошибка сервера " + code + ": " + raw);
        }

        // Парсим ответ (OpenAI-compatible формат)
        JSONObject json   = new JSONObject(raw);
        JSONArray choices = json.getJSONArray("choices");
        if (choices.length() == 0) return "AI не дал ответа";

        return choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();
    }
}
