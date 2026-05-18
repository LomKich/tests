package com.testsolver;

import android.content.Context;
import android.content.SharedPreferences;
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
 * AI-клиент с двумя режимами:
 *   1. Gemini API (если задан ключ) — быстро, надёжно, бесплатный tier
 *   2. Pollinations.ai (без ключа)  — вообще без регистрации, чуть медленнее
 */
public class AiClient {

    public interface Callback {
        void onResult(String answer);
        void onError(String error);
    }

    // SharedPreferences
    public static final String PREFS       = "ai_prefs";
    public static final String KEY_GEMINI  = "gemini_api_key";
    public static final String KEY_ENABLED = "ai_enabled";

    // Endpoints
    // gemini-1.5-flash — гарантированный бесплатный tier (15 RPM, 1500 RPD) во всех регионах
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";
    private static final String POLLINATIONS_URL =
            "https://text.pollinations.ai/";

    private static final String SYSTEM_PROMPT =
            "Ты помощник для решения тестов. Тебе дают текст с экрана (вопрос + варианты). " +
            "Дай КРАТКИЙ ответ — только правильные варианты, без пояснений. " +
            "Несколько вариантов — каждый с новой строки. " +
            "Если вопрос не ясен — ответь: Вопрос не определён.";

    private final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private final String  geminiKey; // null или пустой → Pollinations
    private final boolean enabled;

    public AiClient(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        geminiKey = prefs.getString(KEY_GEMINI, "").trim();
        enabled   = prefs.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getBoolean(KEY_ENABLED, true);
    }

    /** Сохраняет ключ Gemini. Пустая строка = отключить Gemini, использовать Pollinations. */
    public static void saveGeminiKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_GEMINI, key.trim()).apply();
    }

    public static String getGeminiKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getString(KEY_GEMINI, "").trim();
    }

    public boolean usingGemini() {
        return geminiKey != null && !geminiKey.isEmpty();
    }

    // ─── Public ask ───────────────────────────────────────────────────────────

    public void ask(String screenText, Callback callback) {
        if (!enabled) {
            // AI отключён — молча игнорируем
            return;
        }
        executor.execute(() -> {
            try {
                String answer = usingGemini()
                        ? callGemini(screenText)
                        : callPollinations(screenText);
                mainHandler.post(() -> callback.onResult(answer));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    // ─── Gemini API ───────────────────────────────────────────────────────────

    private String callGemini(String screenText) throws Exception {
        URL url = new URL(GEMINI_URL + geminiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);

        JSONObject sysPart = new JSONObject().put("text", SYSTEM_PROMPT);
        JSONObject sysContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(sysPart));

        JSONObject userPart = new JSONObject().put("text", "Текст с экрана:\n\n" + screenText);
        JSONObject userContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(userPart));

        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(sysContent).put(userContent))
                .put("generationConfig", new JSONObject()
                        .put("maxOutputTokens", 256)
                        .put("temperature", 0.1));

        writeBody(conn, body.toString());

        int code = conn.getResponseCode();
        String resp = readResponse(conn, code);
        conn.disconnect();

        if (code != 200) throw new Exception("Gemini " + code + ": " + resp);

        JSONObject json = new JSONObject(resp);
        JSONArray parts = json.getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++)
            sb.append(parts.getJSONObject(i).optString("text", ""));
        return sb.toString().trim();
    }

    // ─── Pollinations.ai (без ключа) ─────────────────────────────────────────

    private String callPollinations(String screenText) throws Exception {
        URL url = new URL(POLLINATIONS_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(25_000);

        JSONObject body = new JSONObject()
                .put("model", "openai")
                .put("private", true)
                .put("seed", 42)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(new JSONObject().put("role", "user")
                                .put("content", "Текст с экрана:\n\n" + screenText)));

        writeBody(conn, body.toString());

        int code = conn.getResponseCode();
        String resp = readResponse(conn, code);
        conn.disconnect();

        if (code != 200) throw new Exception("Pollinations " + code + ": " + resp);

        // Pollinations иногда возвращает plain text, иногда JSON — обрабатываем оба случая
        resp = resp.trim();
        if (resp.startsWith("{")) {
            // JSON-ответ (OpenAI-совместимый формат)
            return new JSONObject(resp)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
        } else {
            // Plain text — просто возвращаем как есть
            return resp;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void writeBody(HttpURLConnection conn, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
    }

    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString().trim();
    }
}
