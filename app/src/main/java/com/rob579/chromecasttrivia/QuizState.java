package com.rob579.chromecasttrivia;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The quiz document and the small changes ("ops") that can be made to it.
 *
 * Every control page sends only the change it made (one answer, one score,
 * one round name...), so several people can edit at once without overwriting
 * each other. Rounds are addressed by a permanent id rather than by position,
 * so an edit still lands on the right round if someone else adds or removes
 * rounds at the same moment.
 */
final class QuizState {

    static final int MAX_QUESTIONS = 30;
    static final int MAX_ROUNDS = 40;

    /** Returns a clean copy: every field present, every round with an id. */
    static JSONObject normalise(JSONObject in) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("videoId", in.optString("videoId", "").trim());
        out.put("listUrl", in.optString("listUrl", "").trim());
        out.put("captions", in.optBoolean("captions", false));

        JSONArray rounds = in.optJSONArray("rounds");
        JSONArray clean = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; rounds != null && i < rounds.length() && i < MAX_ROUNDS; i++) {
            JSONObject r = rounds.optJSONObject(i);
            if (r == null) continue;
            JSONObject cr = new JSONObject();
            String id = r.optString("id", "");
            if (id.isEmpty() || seen.contains(id)) id = newId();
            seen.add(id);
            cr.put("id", id);
            cr.put("name", r.optString("name", ""));
            JSONArray answers = r.optJSONArray("answers");
            JSONArray scores = r.optJSONArray("scores");
            JSONArray cleanAnswers = new JSONArray(), cleanScores = new JSONArray();
            int n = answers == null ? 0 : Math.min(answers.length(), MAX_QUESTIONS);
            for (int j = 0; j < n; j++) {
                cleanAnswers.put(answers.optString(j, ""));
                cleanScores.put(scores == null ? "" : scores.optString(j, ""));
            }
            cr.put("answers", cleanAnswers);
            cr.put("scores", cleanScores);
            double points = r.optDouble("points", Double.NaN);
            cr.put("points", validPoints(points) ? points : n);
            clean.put(cr);
        }
        out.put("rounds", clean);
        out.put("current", clamp(in.optInt("current", 0), 0, Math.max(0, clean.length() - 1)));
        return out;
    }

    /** Applies one change to a normalised state. Unknown or stale changes are ignored. */
    static void apply(JSONObject state, JSONObject op) throws JSONException {
        String kind = op.optString("op", "");
        JSONArray rounds = state.optJSONArray("rounds");

        switch (kind) {
            case "set": {
                String key = op.optString("key", "");
                if (key.equals("videoId") || key.equals("listUrl")) {
                    state.put(key, op.optString("value", "").trim());
                } else if (key.equals("captions")) {
                    state.put("captions", op.optBoolean("value", false));
                }
                return;
            }
            case "addRound": {
                String id = op.optString("id", "");
                if (id.isEmpty() || indexOf(rounds, id) >= 0 || rounds.length() >= MAX_ROUNDS) return;
                int n = clamp(op.optInt("n", 10), 1, MAX_QUESTIONS);
                JSONObject r = new JSONObject();
                r.put("id", id);
                r.put("name", "");
                r.put("answers", blanks(n));
                r.put("scores", blanks(n));
                r.put("points", n);
                rounds.put(r);
                return;
            }
            case "clear": {
                for (int i = 0; i < rounds.length(); i++) {
                    JSONObject r = rounds.optJSONObject(i);
                    int n = r.optJSONArray("answers").length();
                    r.put("answers", blanks(n));
                    r.put("scores", blanks(n));
                }
                state.put("current", 0);
                return;
            }
            default:
                break;
        }

        // Everything else targets one round.
        int index = indexOf(rounds, op.optString("id", ""));
        if (index < 0) return;                       // the round was removed meanwhile
        JSONObject round = rounds.optJSONObject(index);
        JSONArray answers = round.optJSONArray("answers");
        JSONArray scores = round.optJSONArray("scores");

        switch (kind) {
            case "current":
                state.put("current", index);
                break;
            case "name":
                round.put("name", op.optString("value", ""));
                break;
            case "points": {
                double points = op.optDouble("value", Double.NaN);
                if (validPoints(points)) round.put("points", points);
                break;
            }
            case "answer":
            case "score": {
                int i = op.optInt("i", -1);
                if (i < 0 || i >= answers.length()) break;   // the round was shortened meanwhile
                (kind.equals("answer") ? answers : scores).put(i, op.optString("value", ""));
                break;
            }
            case "resize": {
                int old = answers.length();
                int n = clamp(op.optInt("n", old), 1, MAX_QUESTIONS);
                JSONArray newAnswers = new JSONArray(), newScores = new JSONArray();
                for (int i = 0; i < n; i++) {
                    newAnswers.put(i < old ? answers.optString(i, "") : "");
                    newScores.put(i < old ? scores.optString(i, "") : "");
                }
                round.put("answers", newAnswers);
                round.put("scores", newScores);
                // Points follow the question count while they are still "one per question".
                if (round.optDouble("points", old) == old) round.put("points", n);
                break;
            }
            case "delRound": {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < rounds.length(); i++) {
                    if (i != index) kept.put(rounds.optJSONObject(i));
                }
                state.put("rounds", kept);
                int current = state.optInt("current", 0);
                if (index < current) current--;
                state.put("current", clamp(current, 0, Math.max(0, kept.length() - 1)));
                break;
            }
            default:
                break;
        }
    }

    private static int indexOf(JSONArray rounds, String id) {
        if (id.isEmpty()) return -1;
        for (int i = 0; i < rounds.length(); i++) {
            JSONObject r = rounds.optJSONObject(i);
            if (r != null && id.equals(r.optString("id", ""))) return i;
        }
        return -1;
    }

    private static JSONArray blanks(int n) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < n; i++) a.put("");
        return a;
    }

    private static boolean validPoints(double p) {
        return !Double.isNaN(p) && !Double.isInfinite(p) && p >= 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private QuizState() {}
}
