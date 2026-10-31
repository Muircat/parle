package dev.parle.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.*;
import java.util.regex.Pattern;

/** The curated library: grammar topics and hand-written challenges, loaded from JSON packs. */
public final class Content {
    private static final Gson GSON = new Gson();

    public record Topic(String id, String name, String rule) {}

    /**
     * One challenge. Answers support two bits of markup:
     * {a|b} lists alternatives (first one is canonical), [..] marks the part blanked out in "fill in" mode.
     */
    public static final class Challenge {
        public String id, topic, say, hint = "", npc; // npc: what a villager says first, for trade dialogues
        public int level = 1; // 1 beginner, 2 intermediate, 3 advanced
        public List<String> on = List.of(), answers = List.of(), extra = List.of();
        private transient List<String> accepted;
        private transient String prefix, focus, suffix;

        void init() {
            accepted = new ArrayList<>();
            for (String a : answers) for (String e : expand(a)) accepted.add(e.replace("[", "").replace("]", ""));
            String c = expand(answers.get(0)).get(0);
            int i = c.indexOf('['), j = c.indexOf(']');
            if (i < 0 || j < i) { prefix = ""; focus = c; suffix = ""; }
            else { prefix = c.substring(0, i); focus = c.substring(i + 1, j); suffix = c.substring(j + 1); }
        }

        public List<String> accepted() { return accepted; }
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
    public final List<Challenge> challenges = new ArrayList<>();

    /** Loads a pack. Later packs may add topics or replace challenges by id. */
    public void load(Reader r) {
        JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
        if (o.has("topics")) for (var e : o.getAsJsonObject("topics").entrySet()) {
            JsonObject t = e.getValue().getAsJsonObject();
            topics.put(e.getKey(), new Topic(e.getKey(), t.get("name").getAsString(), t.get("rule").getAsString()));
        }
        if (o.has("challenges")) for (JsonElement e : o.getAsJsonArray("challenges")) {
            Challenge c = GSON.fromJson(e, Challenge.class);
            if (c.id == null || c.answers.isEmpty()) continue;
            c.init();
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

    public Challenge byId(String id) {
        for (Challenge c : challenges) if (c.id.equals(id)) return c;
        return null;
    }

    static boolean glob(String pattern, String key) {
        return pattern.equals(key) || pattern.contains("*") && key.matches(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
    }

    static List<String> expand(String s) {
        int i = s.indexOf('{'), j = s.indexOf('}', i);
        if (i < 0 || j < 0) return List.of(s);
        List<String> out = new ArrayList<>();
        for (String opt : s.substring(i + 1, j).split("\\|", -1))
            for (String rest : expand(s.substring(j + 1))) out.add(s.substring(0, i) + opt + rest);
        return out;
    }
}
