package dev.parle.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The learner's progress, saved as JSON. Each topic has a mastery score (0–10) that decides how much
 * help the player gets; each challenge has a Leitner box (0–5) that decides how often it comes back.
 */
public final class Profile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX = 10;

    /** Guided → spontaneous: type it with a word bank, then type the key part, then type it all from scratch. */
    public enum Mode {
        GUIDED("Guidé", 3), FILL("Compléter", 6), WRITE("Écrire", 10);
        public final String label; public final int xp;
        Mode(String label, int xp) { this.label = label; this.xp = xp; }
        public static Mode of(int score) { return score < 4 ? GUIDED : score < 8 ? FILL : WRITE; }
    }

    public static final class Mistake {
        public String id, given;
        Mistake(String id, String given) { this.id = id; this.given = given; }
    }

    public Map<String, Integer> score = new HashMap<>(), box = new HashMap<>();
    public Map<String, Long> seen = new HashMap<>();
    public List<Mistake> mistakes = new ArrayList<>();
    public int solved, streak, best, cooldown = 60;
    private transient Path file;

    public static Profile load(Path file) {
        Profile p = null;
        try { if (Files.exists(file)) p = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Profile.class); }
        catch (Exception ignored) {}
        if (p == null) p = new Profile();
        p.file = file;
        return p;
    }

    public void save() {
        if (file == null) return;
        try { Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8); } catch (IOException ignored) {}
    }

    public int score(String topic) { return score.getOrDefault(topic, 0); }

    public Mode mode(Content.Challenge c) {
        Mode m = Mode.of(score(c.topic));
        return m == Mode.FILL && !c.hasBlank() ? Mode.WRITE : m;
    }

    public boolean missed(String id) { return mistakes.stream().anyMatch(m -> m.id.equals(id)); }

    double weight(Content.Challenge c) {
        int b = 6 - box.getOrDefault(c.id, 0);
        double w = b * b * (1 + (MAX - score(c.topic)) / 5.0);
        if (System.currentTimeMillis() - seen.getOrDefault(c.id, 0L) < 600_000) w *= 0.05;
        return missed(c.id) ? w * 3 : w;
    }

    /** ok = answered correctly; clean = first try without a hint (only clean answers raise topic mastery). */
    public void record(Content.Challenge c, boolean ok, boolean clean, String given) {
        seen.put(c.id, System.currentTimeMillis());
        mistakes.removeIf(m -> m.id.equals(c.id));
        if (ok) {
            box.merge(c.id, 1, (a, b) -> Math.min(5, a + b));
            if (clean) score.merge(c.topic, 1, (a, b) -> Math.min(MAX, a + b));
            solved++;
            best = Math.max(best, ++streak);
        } else {
            box.put(c.id, 0);
            score.put(c.topic, Math.max(0, score(c.topic) - 2));
            streak = 0;
            mistakes.add(0, new Mistake(c.id, given));
            if (mistakes.size() > 12) mistakes.remove(12);
        }
        save();
    }
}
