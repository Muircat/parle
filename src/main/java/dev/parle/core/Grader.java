package dev.parle.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compares a typed sentence against the accepted answers, word by word, and explains the differences. */
public final class Grader {
    /** Best first. ACCENTS and TYPO are accepted with a warning. */
    public enum Verdict { EXACT, ACCENTS, TYPO, WRONG }
    public enum Mark { OK, ACCENT, TYPO, BAD, MISSING }
    public record Tok(String text, Mark mark) {}
    /**
     * given = the player's words, marked; wanted = the closest accepted answer's words, marked;
     * trap = the known mistake a wrong answer makes, if any.
     */
    public record Result(Verdict verdict, String expected, List<Tok> given, List<Tok> wanted, Content.Trap trap) {
        Result(Verdict verdict, String expected, List<Tok> given, List<Tok> wanted) { this(verdict, expected, given, wanted, null); }
        public boolean ok() { return verdict != Verdict.WRONG; }
    }

    /** At most this many slips of the fingers per sentence are forgiven. */
    static final int MAX_TYPOS = 2;

    /** Checks an answer against a challenge (with its phrasebook), and explains the mistakes if it's wrong. */
    public static Result check(String input, Content.Challenge c, String reg) {
        if (c.matches(line(input, false, false), reg, false)) return grade(input, List.of(input));
        List<String> near = c.nearest(input, reg);
        Result r = grade(input, near);
        if (c.matches(line(input, true, false), reg, true)) return new Result(Verdict.ACCENTS, r.expected(), r.given(), r.wanted());
        Content.Trap trap = c.trap(input, reg);
        if (trap == null) {
            Result t = grade(input, near, true);
            if (t.verdict() == Verdict.TYPO) return t;
        }
        return new Result(Verdict.WRONG, r.expected(), r.given(), r.wanted(), trap);
    }

    /** Words, lower-cased (and accent-free if fold), each followed by a space; quote = escape for a regex. */
    static String line(String s, boolean fold, boolean quote) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens(s.replace("[", "").replace("]", ""))) {
            String w = (fold ? key(t) : lower(t)) + " ";
            sb.append(quote ? java.util.regex.Pattern.quote(w) : w);
        }
        return sb.toString();
    }

    public static Result grade(String input, List<String> accepted) { return grade(input, accepted, false); }

    /** typos = also pair up words that differ by one slip of the fingers (see {@link #typo}). */
    public static Result grade(String input, List<String> accepted, boolean typos) {
        List<String> u = tokens(input);
        Result best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String a : accepted) {
            List<String> t = tokens(a);
            int n = u.size(), m = t.size();
            int[][] l = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
                l[i][j] = same(u.get(i), t.get(j), typos) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            List<Tok> g = new ArrayList<>(), w = new ArrayList<>();
            boolean accents = false;
            int slips = 0;
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && same(u.get(i), t.get(j), typos)) {
                    boolean exact = lower(u.get(i)).equals(lower(t.get(j))), folded = key(u.get(i)).equals(key(t.get(j)));
                    accents |= !exact && folded;
                    if (!folded) slips++;
                    Mark mk = exact ? Mark.OK : folded ? Mark.ACCENT : Mark.TYPO;
                    g.add(new Tok(u.get(i++), mk));
                    w.add(new Tok(t.get(j++), mk));
                } else if (j < m && (i == n || l[i][j + 1] >= l[i + 1][j])) w.add(new Tok(t.get(j++), Mark.MISSING));
                else g.add(new Tok(u.get(i++), Mark.BAD));
            }
            int score = 2 * l[0][0] - n - m;
            Verdict v = l[0][0] != n || n != m || slips > MAX_TYPOS ? Verdict.WRONG
                    : slips > 0 ? Verdict.TYPO : accents ? Verdict.ACCENTS : Verdict.EXACT;
            if (v == Verdict.EXACT) return new Result(v, a, g, w);
            if (best == null || v.ordinal() < best.verdict().ordinal() || v == best.verdict() && score > bestScore) {
                bestScore = score;
                best = new Result(v, a, g, w);
            }
        }
        return best;
    }

    static boolean same(String a, String b, boolean typos) {
        String x = key(a), y = key(b);
        return x.equals(y) || typos && typo(x, y);
    }

    /**
     * One slip of the fingers: a single wrong letter, or two neighbouring letters swapped, in a word of five or
     * more letters, and never in its last two letters (endings carry the grammar: aurais/aurait, finis/finit).
     */
    static boolean typo(String x, String y) {
        int n = x.length();
        if (n < 5 || n != y.length()) return false;
        int first = -1, diffs = 0;
        for (int k = 0; k < n; k++) if (x.charAt(k) != y.charAt(k)) { if (first < 0) first = k; diffs++; }
        if (first < 0 || first >= n - 2) return false;
        if (diffs == 1) return true;
        return diffs == 2 && first + 1 < n - 2 && x.charAt(first) == y.charAt(first + 1) && x.charAt(first + 1) == y.charAt(first);
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
