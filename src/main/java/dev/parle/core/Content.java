package dev.parle.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.Reader;
import java.util.*;
import java.util.regex.Pattern;

/** The curated library: grammar topics and hand-written challenges, loaded from JSON packs. */
public final class Content {
    private static final Gson GSON = new Gson();

    public record Topic(String id, String name, String rule) {}

    /**
     * A typical wrong answer and why it's wrong. match uses the same markup as answers ({*} = any one word) and
     * is compared without accents. On a challenge it must match the whole sentence; in a pack's shared "traps" list it may match
     * anywhere in the sentence (e.g. "{ai|as|a} {allé|venu|tombé}"). skill tags the habit to practise.
     */
    public static final class Trap {
        public String match, why, skill;
        private transient Map<String, Pattern> patterns;

        boolean test(String foldedLine, Map<String, List<String>> book, String reg, boolean anywhere) {
            if (patterns == null) patterns = new HashMap<>();
            Pattern p = patterns.computeIfAbsent(String.valueOf(reg), k -> {
                StringJoiner all = new StringJoiner("|", "(?:", ")");
                for (String r : Challenge.regs(reg)) all.add(regex(match, book, r, true));
                return Pattern.compile(anywhere ? "(?:^|(?<= ))" + all : all.toString());
            });
            return anywhere ? p.matcher(foldedLine).find() : p.matcher(foldedLine).matches();
        }
    }

    /**
     * One challenge. Answers support two bits of markup:
     * {a|b} lists alternatives (first one is canonical), [..] marks the part blanked out in "fill in" mode,
     * {@name} inserts every phrasing from the shared phrasebook (e.g. all the polite ways to ask for something).
     * Phrasebook lists named "name.tu" / "name.vous" only apply in that register, and one sentence never mixes the two.
     * The first answer should be a plain canonical sentence (it is the one shown and blanked out).
     */
    public static final class Challenge {
        public String id, topic, say, hint = "", npc; // npc: what a villager says first, for trade dialogues
        /** sayFr: the same instruction in French; cue: a French question that doesn't give the answer away. */
        @SerializedName("say_fr") public String sayFr;
        public String cue;
        public int level = 1; // 1 beginner, 2 intermediate, 3 advanced
        public List<String> on = List.of(), answers = List.of(), extra = List.of();
        /** next: follow-up challenges that rework this sentence (negate it, use a pronoun…). */
        public List<String> next = List.of();
        public List<Trap> traps = List.of();
        private transient Map<String, List<String>> book;
        private transient List<Trap> shared;
        private transient Map<String, Pattern[]> patterns;
        private transient String prefix, focus, suffix;

        void init(Map<String, List<String>> book, List<Trap> shared) {
            this.book = book;
            this.shared = shared;
            patterns = new HashMap<>();
            String c = expand(answers.get(0), book, "vous").get(0);
            int i = c.indexOf('['), j = c.indexOf(']');
            if (i < 0 || j < i) { prefix = ""; focus = c; suffix = ""; }
            else { prefix = c.substring(0, i); focus = c.substring(i + 1, j); suffix = c.substring(j + 1); }
        }

        /** "vous" allows only formal phrasings; "tu" or null also accepts (polite) vous ones. Never mixed in one sentence. */
        static List<String> regs(String reg) { return "vous".equals(reg) ? List.of("vous") : List.of("vous", "tu"); }

        /**
         * Does this word line (from {@link Grader#line}) match an accepted answer? Answers are compiled to regexes
         * instead of listed out, so thousands of phrasings cost nothing. fold = ignore accents.
         */
        public boolean matches(String line, String reg, boolean fold) {
            Pattern[] p = patterns.computeIfAbsent(String.valueOf(reg), k -> new Pattern[]{compile(reg, false), compile(reg, true)});
            return p[fold ? 1 : 0].matcher(line).matches();
        }

        private Pattern compile(String reg, boolean fold) {
            StringJoiner all = new StringJoiner("|", "(?:", ")");
            for (String r : regs(reg)) for (String a : answers) all.add(regex(a, book, r, fold));
            return Pattern.compile(all.toString());
        }

        /** The first known mistake this (wrong) answer makes: this challenge's own traps, then the shared ones. */
        public Trap trap(String input, String reg) {
            String line = Grader.line(input, true, false);
            for (Trap t : traps) if (t.match != null && t.test(line, book, reg, false)) return t;
            if (shared != null) for (Trap t : shared) if (t.match != null && t.test(line, book, reg, true)) return t;
            return null;
        }

        /** Skill tags this challenge exercises, from its traps. */
        public Set<String> skills() {
            Set<String> out = new HashSet<>();
            for (Trap t : traps) if (t.skill != null) out.add(t.skill);
            return out;
        }

        public boolean hasFrench() { return sayFr != null && !sayFr.isBlank(); }
        public boolean hasCue() { return cue != null && !cue.isBlank(); }

        /** Builds, per answer, the phrasing closest to what the player typed, to explain mistakes against. */
        public List<String> nearest(String input, String reg) {
            Set<String> words = new HashSet<>(Grader.tokens(Grader.line(input, true, false)));
            List<String> out = new ArrayList<>(List.of(sentence()));
            for (String r : regs(reg)) for (String a : answers) {
                StringBuilder sb = new StringBuilder();
                int i = 0, a0;
                while ((a0 = a.indexOf('{', i)) >= 0) {
                    int b = a.indexOf('}', a0);
                    String best = "";
                    double top = -1e9;
                    for (String o : options(a.substring(a0 + 1, b), book, r)) {
                        double sc = 0;
                        for (String t : Grader.tokens(Grader.line(o, true, false))) sc += words.contains(t) ? 1 : -1.1;
                        if (sc > top) { top = sc; best = o; }
                    }
                    sb.append(a, i, a0).append(best);
                    i = b + 1;
                }
                out.add(tidy(sb.append(a.substring(i)).toString().replace("[", "").replace("]", "")));
            }
            return out;
        }

        public String sentence() { return prefix + focus + suffix; }
        public String prefix() { return prefix; }
        public String suffix() { return suffix; }
        public boolean hasBlank() { return !prefix.isEmpty() || !suffix.isEmpty(); }

        /** Word tiles for "build" mode: the canonical sentence's words plus distractors, shuffled. */
        public List<String> tiles(Random rnd) {
            List<String> words = words(), out = new ArrayList<>(words);
            out.addAll(extra);
            for (int n = 0; n < 8 && (out.size() < 2 || out.subList(0, words.size()).equals(words)); n++) Collections.shuffle(out, rnd);
            return out;
        }

        public List<String> words() {
            List<String> w = new ArrayList<>();
            for (String s : sentence().split("\\s+")) {
                s = s.replaceAll("[.,;:!?…]+$", "");
                if (!s.isEmpty() && !s.matches("\\p{Punct}+")) w.add(s);
            }
            if (!w.isEmpty()) w.set(0, Character.toLowerCase(w.get(0).charAt(0)) + w.get(0).substring(1));
            return w;
        }
    }

    public final Map<String, Topic> topics = new LinkedHashMap<>();
    public final Map<String, List<String>> phrases = new HashMap<>();
    public final List<Challenge> challenges = new ArrayList<>();
    public final List<Trap> traps = new ArrayList<>();

    /** Loads a pack. Later packs may add topics or replace challenges by id. */
    public void load(Reader r) {
        JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
        if (o.has("topics")) for (var e : o.getAsJsonObject("topics").entrySet()) {
            JsonObject t = e.getValue().getAsJsonObject();
            topics.put(e.getKey(), new Topic(e.getKey(), t.get("name").getAsString(), t.get("rule").getAsString()));
        }
        if (o.has("phrases")) for (var e : o.getAsJsonObject("phrases").entrySet()) {
            List<String> list = new ArrayList<>();
            e.getValue().getAsJsonArray().forEach(x -> list.add(x.getAsString()));
            phrases.put(e.getKey(), list);
        }
        if (o.has("traps")) for (JsonElement e : o.getAsJsonArray("traps")) {
            Trap t = GSON.fromJson(e, Trap.class);
            if (t.match != null && t.why != null) traps.add(t);
        }
        if (o.has("challenges")) for (JsonElement e : o.getAsJsonArray("challenges")) {
            Challenge c = GSON.fromJson(e, Challenge.class);
            if (c.id == null || c.answers.isEmpty()) continue;
            c.init(phrases, traps);
            challenges.removeIf(x -> x.id.equals(c.id));
            challenges.add(c);
        }
    }

    /**
     * Picks a challenge for a gameplay trigger (e.g. "kill:zombie"), or for practice when trigger is null
     * (optionally limited to one topic). Weak, unseen and recently-missed challenges are favoured.
     */
    public Challenge pick(String trigger, String topic, Profile p, Random rnd) {
        List<Challenge> pool = challenges.stream().filter(c -> trigger == null
                ? c.npc == null && (topic == null || c.topic.equals(topic))
                : c.on.stream().anyMatch(g -> glob(g, trigger))).toList();
        double[] w = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) total += w[i] = p.weight(pool.get(i));
        if (total <= 0) return null;
        double roll = rnd.nextDouble() * total;
        for (int i = 0; i < w.length; i++) if (w[i] > 0 && (roll -= w[i]) <= 0) return pool.get(i);
        return pool.isEmpty() ? null : pool.get(pool.size() - 1);
    }

    /** A follow-up that reworks c's sentence, if c has any the learner is ready for. */
    public Challenge followUp(Challenge c, Profile p, Random rnd) {
        List<Challenge> pool = new ArrayList<>();
        for (String id : c.next) { Challenge n = byId(id); if (n != null && n != c && p.weight(n) > 0) pool.add(n); }
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    public Challenge byId(String id) {
        for (Challenge c : challenges) if (c.id.equals(id)) return c;
        return null;
    }

    static boolean glob(String pattern, String key) {
        return pattern.equals(key) || pattern.contains("*") && key.matches(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
    }

    /** An answer (with its {…} markup) as a regex over a word line from {@link Grader#line}. */
    static String regex(String a, Map<String, List<String>> book, String reg, boolean fold) {
        StringBuilder sb = new StringBuilder();
        int i = 0, a0;
        while ((a0 = a.indexOf('{', i)) >= 0) {
            int b = a.indexOf('}', a0);
            String in = a.substring(a0 + 1, b);
            StringJoiner alt = new StringJoiner("|", "(?:", ")");
            if (in.equals("*")) alt.add("\\S+ "); // any one word (for traps)
            else for (String o : options(in, book, reg)) alt.add(Grader.line(o, fold, true));
            sb.append(Grader.line(a.substring(i, a0), fold, true)).append(alt);
            i = b + 1;
        }
        return sb.append(Grader.line(a.substring(i), fold, true)).toString();
    }

    public static List<String> expand(String s, Map<String, List<String>> book, String reg) {
        return raw(s, book, reg).stream().map(Content::tidy).toList();
    }

    private static List<String> raw(String s, Map<String, List<String>> book, String reg) {
        int i = s.indexOf('{'), j = s.indexOf('}', i);
        if (i < 0 || j < 0) return List.of(s);
        List<String> out = new ArrayList<>();
        for (String opt : options(s.substring(i + 1, j), book, reg)) for (String rest : raw(s.substring(j + 1), book, reg)) out.add(s.substring(0, i) + opt + rest);
        return out;
    }

    /** The choices inside one {…}: listed alternatives, or a phrasebook entry plus its register-specific list. */
    static List<String> options(String in, Map<String, List<String>> book, String reg) {
        if (!in.startsWith("@")) return List.of(in.split("\\|", -1));
        List<String> opts = new ArrayList<>(book.getOrDefault(in.substring(1), List.of()));
        opts.addAll(book.getOrDefault(in.substring(1) + "." + reg, List.of()));
        return opts.isEmpty() ? List.of("") : opts;
    }

    /** Joins phrasebook pieces into a clean sentence: spacing, de + le → du etc., capitals. */
    static String tidy(String s) {
        s = (" " + s + " ").replaceAll("\\s+", " ").replace(" de le ", " du ").replace(" de les ", " des ")
                .replace(" à le ", " au ").replace(" à les ", " aux ").replaceAll(" ([,.])", "$1").replaceAll("^[\\s,.]+", "").trim();
        s = Pattern.compile(", (\\p{Lu})(?=\\p{Ll}|')").matcher(s).replaceAll(m -> ", " + m.group(1).toLowerCase(Locale.ROOT));
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
