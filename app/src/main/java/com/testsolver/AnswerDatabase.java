package com.testsolver;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnswerDatabase {

    public static class Answer {
        public int num;
        public List<String> keys;
        public String type; // text, radio, checkbox, match
        public String answerText;
        public List<String> answerList;
    }

    private final List<Answer> all = new ArrayList<>();
    private final Map<Integer, Answer> byNum = new HashMap<>();

    // Pattern to find question number: "44 из 55" or "44 иэ 55"
    private static final Pattern Q_NUM_PATTERN =
            Pattern.compile("\\b(\\d{1,2})\\s+(?:из|иэ|из|of)\\s+55\\b");

    public void load(Context ctx) {
        try {
            InputStream is = ctx.getAssets().open("answers.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Answer a = new Answer();
                a.num = o.getInt("num");
                a.type = o.getString("type");
                a.keys = new ArrayList<>();
                JSONArray ks = o.getJSONArray("keys");
                for (int j = 0; j < ks.length(); j++)
                    a.keys.add(ks.getString(j).toLowerCase(Locale.ROOT));

                if (o.get("answer") instanceof JSONArray) {
                    JSONArray ans = o.getJSONArray("answer");
                    a.answerList = new ArrayList<>();
                    for (int j = 0; j < ans.length(); j++) a.answerList.add(ans.getString(j));
                    a.answerText = String.join(", ", a.answerList);
                } else {
                    a.answerText = o.getString("answer");
                    a.answerList = null;
                }
                all.add(a);
                byNum.put(a.num, a);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Extract question number from screen text like "44 из 55"
     * Returns -1 if not found.
     */
    public int extractQuestionNumber(String screenText) {
        if (screenText == null) return -1;
        Matcher m = Q_NUM_PATTERN.matcher(screenText);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * Find answer. PRIORITY:
     * 1. Question number from "X из 55" on screen → direct lookup
     * 2. Keyword matching (fallback)
     */
    public Answer findAnswer(String screenText) {
        if (screenText == null || screenText.isEmpty()) return null;

        // 1. Try by question number first
        int qNum = extractQuestionNumber(screenText);
        if (qNum >= 1 && qNum <= 55) {
            Answer a = byNum.get(qNum);
            if (a != null) return a;
        }

        // 2. Keyword fallback
        String lower = screenText.toLowerCase(Locale.ROOT);
        Answer best = null;
        int bestScore = 0;

        for (Answer a : all) {
            int score = 0;
            for (String key : a.keys) {
                if (lower.contains(key)) score++;
            }
            // Need at least 2 keys OR all keys if there's only 1
            int minMatch = Math.max(2, (int) Math.ceil(a.keys.size() * 0.5));
            if (score >= minMatch && score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best;
    }

    public int size() { return all.size(); }
}
