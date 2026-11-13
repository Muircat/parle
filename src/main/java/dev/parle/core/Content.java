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

        Trap bind(Map<String, String> vars, Map<String, String> show) {
            String m = fill(match, vars);
            if (m == null) return null; // e.g. no "wrong gender" form for an elided noun (l'araignée)
            Trap t = new Trap();
            t.match = m; t.why = fill(why, show); t.skill = skill;
            return t.why == null ? null : t;
        }

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
        /** Templates: the trigger must have happened at least this many times today ({$count}). */
        @SerializedName("min_count") public int minCount;
        /** Templates: only fill in with lexicon entries carrying this tag (e.g. "hostile", "ore", "countable"). */
        public String needs;
        private transient boolean template;
        /** The game words this copy of a template was filled in with (null for a plain challenge). */
        public transient Map<String, String> vars;
        private transient Map<String, List<String>> book;
        private transient List<Trap> shared;
        private transient Map<String, Pattern[]> patterns;
        private transient String prefix, focus, suffix;

        void init(Map<String, List<String>> book, List<Trap> shared) {
            this.book = book;
            this.shared = shared;
            template = vars == null && (has(say) || has(sayFr) || has(cue) || has(hint) || answers.stream().anyMatch(Challenge::has));
            patterns = new HashMap<>();
            String c = expand(answers.get(0), book, "vous").get(0);
            int i = c.indexOf('['), j = c.indexOf(']');
            if (i < 0 || j < i) { prefix = ""; focus = c; suffix = ""; }
            else { prefix = c.substring(0, i); focus = c.substring(i + 1, j); suffix = c.substring(j + 1); }
        }

        private static boolean has(String s) { return s != null && s.contains("{$"); }

        /** Whether this challenge has {$…} slots to fill with the mob, block, biome or item at hand. */
        public boolean isTemplate() { return template; }

        /**
         * A copy of this template filled in with one lexicon entry: shown text gets the plain words, answers get
         * every accepted form (e.g. the count as a word or in digits). Null if a slot can't be filled.
         */
        Challenge bind(Map<String, String> show, Map<String, String> match) {
            Challenge c = new Challenge();
            c.id = id; c.topic = topic; c.level = level; c.on = on; c.next = next; c.minCount = minCount; c.needs = needs;
            c.say = fill(say, show); c.sayFr = fill(sayFr, show); c.cue = fill(cue, show); c.hint = fill(hint, show);
            c.npc = fill(npc, show);
            if (c.say == null || sayFr != null && c.sayFr == null || cue != null && c.cue == null || c.hint == null || npc != null && c.npc == null) return null;
            List<String> a = new ArrayList<>();
            for (String x : answers) { String f = fill(x, match); if (f == null) return null; a.add(f); }
            c.answers = a;
            List<String> e = new ArrayList<>();
            for (String x : extra) { String f = fill(x, show); if (f != null) e.add(f); }
            c.extra = e;
            List<Trap> t = new ArrayList<>();
            for (Trap x : traps) { Trap b = x.bind(match, show); if (b != null) t.add(b); }
            c.traps = t;
            c.vars = show;
            c.init(book, shared);
            return c;
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
    /** Game words for templates: each entry says which triggers it is for ("on") and the French noun with its gender. */
    public final List<Map<String, String>> lexicon = new ArrayList<>();

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
        if (o.has("lexicon")) for (JsonElement e : o.getAsJsonArray("lexicon")) {
            Map<String, String> m = new LinkedHashMap<>();
            for (var f : e.getAsJsonObject().entrySet()) {
                JsonElement v = f.getValue();
                if (v.isJsonArray()) { StringJoiner j = new StringJoiner(","); v.getAsJsonArray().forEach(x -> j.add(x.getAsString())); m.put(f.getKey(), j.toString()); }
                else m.put(f.getKey(), v.getAsString());
            }
            if (m.containsKey("on") && m.containsKey("n")) lexicon.add(m);
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
     * (optionally limited to one topic). Weak, unseen and recently-missed challenges are favoured, and anything
     * met recently is left out: for a gameplay moment that can mean no challenge at all, which beats a repeat.
     * count = how many times this trigger has happened today. Templates come back filled in with the mob,
     * block, biome or item at hand.
     */
    public Challenge pick(String trigger, String topic, Profile p, Random rnd, int count) {
        Challenge c = pick(trigger, topic, p, rnd, count, true);
        boolean must = trigger == null || trigger.startsWith("greet:") || trigger.startsWith("trade:"); // practice and villagers always get one
        return c != null || !must ? c : pick(trigger, topic, p, rnd, count, false);
    }

    public Challenge pick(String trigger, String topic, Profile p, Random rnd) { return pick(trigger, topic, p, rnd, 0); }

    private Challenge pick(String trigger, String topic, Profile p, Random rnd, int count, boolean fresh) {
        List<Challenge> pool = new ArrayList<>();
        for (Challenge c : challenges) {
            if (trigger == null ? c.npc != null || topic != null && !c.topic.equals(topic) || c.on.stream().anyMatch(g -> g.startsWith("greet:") || g.startsWith("trade:"))
                    : c.on.stream().noneMatch(g -> glob(g, trigger))) continue;
            if (c.minCount > 0 && count < c.minCount) continue;
            Challenge b = c.isTemplate() ? fillIn(c, trigger, count, rnd) : c;
            if (b != null) pool.add(b);
        }
        double[] w = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) total += w[i] = p.weight(pool.get(i), fresh);
        if (total <= 0) return null;
        double roll = rnd.nextDouble() * total;
        for (int i = 0; i < w.length; i++) if (w[i] > 0 && (roll -= w[i]) <= 0) return pool.get(i);
        for (int i = w.length - 1; i >= 0; i--) if (w[i] > 0) return pool.get(i);
        return null;
    }

    /** Fills a template in with a lexicon entry for this trigger (or, for practice, any trigger it can take). */
    Challenge fillIn(Challenge c, String trigger, int count, Random rnd) {
        List<Map<String, String>> fits = new ArrayList<>();
        for (Map<String, String> e : lexicon)
            for (String on : e.get("on").split(","))
                if (trigger != null ? glob(on, trigger) : c.on.stream().anyMatch(g -> glob(g, on))) { fits.add(e); break; }
        Collections.shuffle(fits, rnd);
        for (Map<String, String> e : fits) {
            if (c.needs != null && !Arrays.asList(e.getOrDefault("tags", "").split(",")).contains(c.needs)) continue;
            Challenge b = c.bind(words(e, count, false), words(e, count, true));
            if (b != null) return b;
        }
        return null;
    }

    private static final String[] NUMBERS = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
            "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf", "vingt"};

    /**
     * Everything a template can say about one noun, worked out from its gender and spelling: {$un} une araignée,
     * {$le} l'araignée, {$ce} cette araignée, {$les}, {$des}, {$du} (partitive), {$de} (after a negation or
     * beaucoup), {$il} elle, {$e} the agreement ending, {$x_…} the wrong-gender forms for traps, {$en} the English,
     * {$count} today's count. Any field in the entry can override these. match = for answers (counts may be digits).
     */
    static Map<String, String> words(Map<String, String> e, int count, boolean match) {
        Map<String, String> v = new HashMap<>();
        String n = e.get("n"), pl = e.getOrDefault("pl", n + "s");
        boolean f = "f".equals(e.get("g")), vowel = n.matches("(?i)^[aeiouyàâéèêëîïôûœ].*") || n.startsWith("h") && !"aspiré".equals(e.get("h"));
        v.put("n", n); v.put("pl", pl);
        v.put("un", (f ? "une " : "un ") + n);
        v.put("le", vowel ? "l'" + n : (f ? "la " : "le ") + n);
        v.put("ce", (f ? "cette " : vowel ? "cet " : "ce ") + n);
        v.put("les", "les " + pl);
        v.put("des", "des " + pl);
        v.put("du", vowel ? "de l'" + n : (f ? "de la " : "du ") + n);
        v.put("de", (vowel ? "d'" : "de ") + n);
        v.put("il", f ? "elle" : "il");
        v.put("e", f ? "e" : "");
        v.put("det_un", f ? "une" : "un");
        v.put("gros", f ? "grosse" : "gros");
        v.put("g_en", f ? "feminine" : "masculine");
        String en = e.getOrDefault("en", n);
        v.put("en", en);
        v.put("en_pl", e.getOrDefault("en_pl", en + "s"));
        v.put("a_en", (en.matches("(?i)^[aeiou].*") ? "an " : "a ") + en);
        v.put("the_en", "the " + en);
        v.put("some_en", "some " + en);
        v.put("coute", "coûte");
        // wrong forms, for traps; none where elision hides the gender (l'araignée)
        v.put("x_un", (f ? "un " : "une ") + n);
        v.put("x_ce", vowel ? null : (f ? "ce " : "cette ") + n);
        v.put("x_le", vowel ? null : (f ? "le " : "la ") + n);
        v.put("x_du", vowel ? null : (f ? "du " : "de la ") + n);
        v.put("x_gros", f ? "gros" : "grosse");
        v.put("x_e", f ? "" : "e");
        for (var x : e.entrySet()) {
            if (x.getKey().equals("on")) continue;
            v.put(x.getKey(), x.getValue());
            v.remove("x_" + x.getKey()); // an overridden form (des carottes) has no simple wrong-gender twin
            if (e.containsKey("x_" + x.getKey())) v.put("x_" + x.getKey(), e.get("x_" + x.getKey()));
        }
        if (count >= 2 && count < NUMBERS.length) {
            String word = NUMBERS[count];
            v.put("count", match ? "{" + word + "|" + count + "}" : word);
            v.put("count_n", (match ? "{" + word + "|" + count + "}" : word) + " " + pl);
        }
        v.values().removeIf(Objects::isNull);
        return v;
    }

    /** Replaces each {$name} with its word; null if a slot has no word. */
    static String fill(String s, Map<String, String> vars) {
        if (s == null || !s.contains("{$")) return s;
        StringBuilder sb = new StringBuilder();
        int i = 0, a;
        while ((a = s.indexOf("{$", i)) >= 0) {
            int b = s.indexOf('}', a);
            String v = vars.get(s.substring(a + 2, b));
            if (v == null) return null;
            sb.append(s, i, a).append(v);
            i = b + 1;
        }
        return sb.append(s.substring(i)).toString();
    }

    /** A follow-up that reworks c's sentence, if c has any the learner is ready for. */
    public Challenge followUp(Challenge c, Profile p, Random rnd) {
        List<Challenge> pool = new ArrayList<>();
        for (String id : c.next) {
            Challenge n = byId(id);
            if (n == null || n.id.equals(c.id)) continue;
            if (n.isTemplate()) n = c.vars != null ? n.bind(c.vars, rematch(c.vars)) : null; // same mob, block or item
            if (n != null && p.weight(n, false) > 0) pool.add(n);
        }
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    /** The answer-side words for a filled-in template: the count may also be typed in digits. */
    private static Map<String, String> rematch(Map<String, String> show) {
        Map<String, String> m = new HashMap<>(show);
        for (int k = 2; k < NUMBERS.length; k++) if (NUMBERS[k].equals(show.get("count"))) {
            m.put("count", "{" + NUMBERS[k] + "|" + k + "}");
            m.put("count_n", "{" + NUMBERS[k] + "|" + k + "} " + show.get("pl"));
        }
        return m;
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
