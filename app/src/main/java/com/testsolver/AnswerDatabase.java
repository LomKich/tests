package com.testsolver;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class AnswerDatabase {

    public static class Answer {
        public String question;
        public String type;
        public String answerText;
        public List<String> answerList;
        List<String> questionWords; // tokenized for matching
    }

    private final List<Answer> all = new ArrayList<>();
    private static final String USER_FILE = "user_answers.json";

    // ─── Loading ──────────────────────────────────────────────────────────────

    public void load(Context ctx) {
        loadStream(assetStream(ctx));
        loadStream(userStream(ctx));
    }

    private void loadStream(InputStream is) {
        if (is == null) return;
        try {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                tryParse(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tryParse(JSONObject o) {
        try {
            Answer a = new Answer();
            a.question = o.optString("question", "").trim();
            a.type     = o.optString("type", "text");
            Object ans = o.get("answer");
            if (ans instanceof JSONArray) {
                JSONArray ja = (JSONArray) ans;
                a.answerList = new ArrayList<>();
                for (int j = 0; j < ja.length(); j++) a.answerList.add(ja.getString(j));
                a.answerText = String.join(", ", a.answerList);
            } else {
                a.answerText = ans.toString();
                a.answerList = null;
            }
            a.questionWords = tokenize(a.question);
            if (!a.questionWords.isEmpty()) all.add(a);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Save user answer ─────────────────────────────────────────────────────

    public void addUserAnswer(Context ctx, String question, String type, String rawAnswer) {
        try {
            JSONArray existing = new JSONArray();
            InputStream is = userStream(ctx);
            if (is != null) {
                byte[] buf = new byte[is.available()];
                is.read(buf); is.close();
                existing = new JSONArray(new String(buf, "UTF-8"));
            }

            JSONObject o = new JSONObject();
            o.put("question", question.trim().toLowerCase());
            o.put("type", type);

            if ("checkbox".equals(type) && rawAnswer.contains(",")) {
                JSONArray parts = new JSONArray();
                for (String p : rawAnswer.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) parts.put(t);
                }
                o.put("answer", parts);
            } else {
                o.put("answer", rawAnswer.trim());
            }

            existing.put(o);

            FileOutputStream fos = ctx.openFileOutput(USER_FILE, Context.MODE_PRIVATE);
            fos.write(existing.toString(2).getBytes("UTF-8"));
            fos.close();

            tryParse(o); // add to in-memory list immediately
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Matching ─────────────────────────────────────────────────────────────

    /**
     * Full-text fuzzy match using Jaccard word similarity.
     * Threshold: 0.30 — fairly lenient but filters random noise.
     */
    public Answer findAnswer(String screenText) {
        if (screenText == null || screenText.isEmpty()) return null;

        List<String> screenWords = tokenize(screenText);
        Set<String> screenSet = new HashSet<>(screenWords);

        Answer best = null;
        double bestScore = 0.0;

        for (Answer a : all) {
            double score = jaccard(a.questionWords, screenSet);
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }

        return (bestScore >= 0.30) ? best : null;
    }

    private double jaccard(List<String> qWords, Set<String> screen) {
        if (qWords.isEmpty()) return 0;
        int inter = 0;
        for (String w : qWords) if (screen.contains(w)) inter++;
        int union = qWords.size() + screen.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    // ─── Tokenizer ────────────────────────────────────────────────────────────

    public static List<String> tokenize(String text) {
        if (text == null) return Collections.emptyList();
        String norm = text.toLowerCase()
                .replace("ё", "е")
                .replaceAll("[^а-яa-z0-9,%.\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> words = new ArrayList<>();
        for (String w : norm.split("\\s+")) {
            if (w.length() >= 3) words.add(w);
        }
        return words;
    }

    // ─── Streams ──────────────────────────────────────────────────────────────

    private InputStream assetStream(Context ctx) {
        try { return ctx.getAssets().open("answers.json"); }
        catch (Exception e) { return null; }
    }

    private InputStream userStream(Context ctx) {
        try { return ctx.openFileInput(USER_FILE); }
        catch (Exception e) { return null; }
    }

    public int size() { return all.size(); }
}
