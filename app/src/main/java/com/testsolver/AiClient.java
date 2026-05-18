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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI-клиент с тремя режимами (приоритет: Groq > Gemini > Pollinations):
 *   1. Groq API     — стриминг, ~0.3–0.8 сек, бесплатный tier
 *   2. Gemini API   — быстро, надёжно, бесплатный tier
 *   3. Pollinations — без ключа, чуть медленнее
 */
public class AiClient {

    public interface Callback {
        void onResult(String answer);
        void onError(String error);
        /** Вызывается во время стриминга с накопленным текстом. По умолчанию — ничего. */
        default void onPartial(String partial) {}
    }

    // SharedPreferences
    public static final String PREFS       = "ai_prefs";
    public static final String KEY_GEMINI  = "gemini_api_key";
    public static final String KEY_GROQ    = "groq_api_key";
    public static final String KEY_ENABLED  = "ai_enabled";
    public static final String KEY_COOLDOWN = "ai_cooldown_ms";
    public static final long   DEFAULT_COOLDOWN_MS = 7_000;

    // Endpoints
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";
    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions";
    private static final String POLLINATIONS_URL =
            "https://text.pollinations.ai/";
    private static final String GROQ_MODEL = "llama-3.1-8b-instant";

    private static final String SYSTEM_PROMPT_BASE =
            "Ты решаешь тест. Текст с экрана содержит вопрос и варианты ответа. " +
            "Выведи ТОЛЬКО правильный ответ (или несколько — каждый с новой строки). " +
            "Никаких пояснений, никаких вводных слов, никаких знаков препинания в конце. " +
            "Если в тексте нет вопроса — ответь одним словом: нет.";

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private final String  geminiKey;
    private final String  groqKey;
    private final boolean enabled;

    public AiClient(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        geminiKey = prefs.getString(KEY_GEMINI, "").trim();
        groqKey   = prefs.getString(KEY_GROQ,   "").trim();
        enabled   = prefs.getBoolean(KEY_ENABLED, true);
    }

    // ─── Static helpers ───────────────────────────────────────────────────────

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getBoolean(KEY_ENABLED, true);
    }

    public static long getCooldownMs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getLong(KEY_COOLDOWN, DEFAULT_COOLDOWN_MS);
    }

    public static void setCooldownMs(Context ctx, long ms) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putLong(KEY_COOLDOWN, ms).apply();
    }

    public static void saveGeminiKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_GEMINI, key.trim()).apply();
    }

    public static String getGeminiKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getString(KEY_GEMINI, "").trim();
    }

    public static void saveGroqKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putString(KEY_GROQ, key.trim()).apply();
    }

    public static String getGroqKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                  .getString(KEY_GROQ, "").trim();
    }

    public boolean usingGroq()   { return groqKey   != null && !groqKey.isEmpty(); }
    public boolean usingGemini() { return geminiKey != null && !geminiKey.isEmpty(); }

    // ─── Public ask ───────────────────────────────────────────────────────────

    public void ask(String screenText, List<AnswerDatabase.Answer> localCandidates, Callback callback) {
        if (!enabled) return;
        String prompt = buildPrompt(screenText, localCandidates);
        executor.execute(() -> {
            try {
                if (usingGroq()) {
                    callGroqStream(prompt, callback);
                } else if (usingGemini()) {
                    String answer = callGemini(prompt);
                    mainHandler.post(() -> callback.onResult(answer));
                } else {
                    String answer = callPollinations(prompt);
                    mainHandler.post(() -> callback.onResult(answer));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Формирует промпт: если есть кандидаты из базы — просим AI сначала проверить их. */
    private String buildPrompt(String screenText, List<AnswerDatabase.Answer> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Текст с экрана:\n\n").append(screenText);

        if (candidates != null && !candidates.isEmpty()) {
            sb.append("\n\n---\nЛОКАЛЬНАЯ БАЗА (возможные совпадения, топ по сходству):\n");
            for (int i = 0; i < candidates.size(); i++) {
                AnswerDatabase.Answer a = candidates.get(i);
                String ans = (a.answerList != null && !a.answerList.isEmpty())
                        ? String.join(" / ", a.answerList)
                        : a.answerText;
                sb.append(i + 1).append(". Q: \"").append(a.question)
                  .append("\" → A: \"").append(ans).append("\"\n");
            }
            sb.append("\nЕсли один из вариантов выше точно соответствует вопросу на экране — ")
              .append("верни его ответ ДОСЛОВНО (из поля A:). ")
              .append("Иначе — дай свой краткий ответ.");
        }

        return sb.toString();
    }

    // ─── Groq streaming ───────────────────────────────────────────────────────

    private void callGroqStream(String screenText, Callback callback) throws Exception {
        URL url = new URL(GROQ_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",  "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + groqKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        JSONObject body = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("stream", true)
                .put("max_tokens", 256)
                .put("temperature", 0.1)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT_BASE))
                        .put(new JSONObject().put("role", "user")
                                .put("content", "Текст с экрана:\n\n" + screenText)));

        writeBody(conn, body.toString());

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readFully(new BufferedReader(new InputStreamReader(
                    conn.getErrorStream(), StandardCharsets.UTF_8)));
            conn.disconnect();
            throw new Exception("Groq " + code + ": " + err);
        }

        // Читаем SSE-поток
        StringBuilder accumulated = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    JSONObject chunk = new JSONObject(data);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String token = delta.optString("content", "");
                    if (token.isEmpty()) continue;

                    accumulated.append(token);
                    final String partial = accumulated.toString();
                    mainHandler.post(() -> callback.onPartial("🤖 " + partial));
                } catch (Exception ignored) {}
            }
        }
        conn.disconnect();

        final String result = accumulated.toString().trim();
        mainHandler.post(() -> callback.onResult(result));
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

        JSONObject sysPart    = new JSONObject().put("text", SYSTEM_PROMPT_BASE);
        JSONObject sysContent = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(sysPart));

        JSONObject userPart    = new JSONObject().put("text", screenText);
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
        String resp = readFully(new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8)));
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
                        .put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT_BASE))
                        .put(new JSONObject().put("role", "user")
                                .put("content", "Текст с экрана:\n\n" + screenText)));

        writeBody(conn, body.toString());

        int code = conn.getResponseCode();
        String resp = readFully(new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8)));
        conn.disconnect();

        if (code != 200) throw new Exception("Pollinations " + code + ": " + resp);

        resp = resp.trim();
        if (resp.startsWith("{")) {
            return new JSONObject(resp)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
        }
        return resp;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void writeBody(HttpURLConnection conn, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
    }

    private String readFully(BufferedReader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();
        return sb.toString().trim();
    }
}
