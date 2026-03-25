package com.testsolver;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AnswerDatabase {

    public static class Answer {
        public int num;
        public List<String> keys;
        public String type; // text, radio, checkbox, match
        public String answerText;         // for type=text/radio/match
        public List<String> answerList;   // for type=checkbox
    }

    private final List<Answer> db = new ArrayList<>();

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
                for (int j = 0; j < ks.length(); j++) {
                    a.keys.add(ks.getString(j).toLowerCase(Locale.ROOT));
                }
                if (o.get("answer") instanceof JSONArray) {
                    JSONArray ans = o.getJSONArray("answer");
                    a.answerList = new ArrayList<>();
                    for (int j = 0; j < ans.length(); j++) a.answerList.add(ans.getString(j));
                    a.answerText = String.join(", ", a.answerList);
                } else {
                    a.answerText = o.getString("answer");
                    a.answerList = null;
                }
                db.add(a);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Find best matching answer for the given screen text.
     * Returns null if no match found.
     */
    public Answer findAnswer(String screenText) {
        if (screenText == null || screenText.isEmpty()) return null;
        String lower = screenText.toLowerCase(Locale.ROOT);

        Answer best = null;
        int bestScore = 0;

        for (Answer a : db) {
            int score = 0;
            for (String key : a.keys) {
                if (lower.contains(key)) score++;
            }
            // Need at least half the keys to match
            if (score > 0 && score >= Math.ceil(a.keys.size() / 2.0)) {
                if (score > bestScore) {
                    bestScore = score;
                    best = a;
                }
            }
        }
        return best;
    }

    public int size() { return db.size(); }
}
