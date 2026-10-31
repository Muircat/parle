package dev.parle.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compares a typed sentence against the accepted answers, word by word, and explains the differences. */
public final class Grader {
    public enum Verdict { EXACT, ACCENTS, WRONG }
    public enum Mark { OK, ACCENT, BAD, MISSING }
    public record Tok(String text, Mark mark) {}
    /** given = the player's words, marked; wanted = the closest accepted answer's words, marked. */
    public record Result(Verdict verdict, String expected, List<Tok> given, List<Tok> wanted) {}

    public static Result grade(String input, List<String> accepted) {
        List<String> u = tokens(input);
        Result best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String a : accepted) {
            List<String> t = tokens(a);
            int n = u.size(), m = t.size();
            int[][] l = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
                l[i][j] = key(u.get(i)).equals(key(t.get(j))) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            List<Tok> g = new ArrayList<>(), w = new ArrayList<>();
            boolean accents = false;
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && key(u.get(i)).equals(key(t.get(j)))) {
                    boolean same = lower(u.get(i)).equals(lower(t.get(j)));
                    accents |= !same;
                    Mark mk = same ? Mark.OK : Mark.ACCENT;
                    g.add(new Tok(u.get(i++), mk));
                    w.add(new Tok(t.get(j++), mk));
                } else if (j < m && (i == n || l[i][j + 1] >= l[i + 1][j])) w.add(new Tok(t.get(j++), Mark.MISSING));
                else g.add(new Tok(u.get(i++), Mark.BAD));
            }
            int score = 2 * l[0][0] - n - m;
            Verdict v = l[0][0] == n && n == m ? (accents ? Verdict.ACCENTS : Verdict.EXACT) : Verdict.WRONG;
            if (v == Verdict.EXACT) return new Result(v, a, g, w);
            if (score > bestScore || v == Verdict.ACCENTS && best.verdict() == Verdict.WRONG) {
                bestScore = score;
                best = new Result(v, a, g, w);
            }
        }
        return best;
    }

    /** Splits into words, dropping punctuation and splitting elisions ("j'ai" → "j'", "ai"). */
    public static List<String> tokens(String s) {
        s = s.replace('’', '\'').replace('‘', '\'').replace('`', '\'')
                .replaceAll("[«»\"“”.,!?;:…()]", " ").replaceAll("'\\s*", "' ");
        List<String> out = new ArrayList<>();
        for (String t : s.trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    /** Accent-insensitive comparison key. */
    static String key(String s) {
        return Normalizer.normalize(lower(s).replace("œ", "oe").replace("æ", "ae"), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
