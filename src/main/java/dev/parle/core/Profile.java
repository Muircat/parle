package dev.parle.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The learner's progress, saved as JSON. Each topic has a mastery score (0–10); each challenge has a Leitner
 * box (0–5) that decides how often it comes back, and its own stage on the guided → spontaneous ladder.
 * Skills (from the mistakes the grader recognises) that keep going wrong make their challenges come back sooner.
 */
public final class Profile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int MAX = 10;

    /**
     * Guided → spontaneous: type it with a word bank, type the key part, write it all from an English
     * instruction, then from a French one, then just answer a French question about what happened.
     */
    public enum Mode {
        GUIDED("Guidé", 3), FILL("Compléter", 6), WRITE("Écrire", 10), FRENCH("En français", 12), FREE("Spontané", 14);
        public final String label; public final int xp;
        Mode(String label, int xp) { this.label = label; this.xp = xp; }
        /** Where a challenge you haven't met yet starts, from its topic's mastery. */
        public static Mode of(int score) { return score < 4 ? GUIDED : score < 8 ? FILL : WRITE; }
        /** Whether this challenge can be asked this way (it needs a blank, a French instruction or a cue). */
        public boolean fits(Content.Challenge c) {
            return switch (this) { case FILL -> c.hasBlank(); case FRENCH -> c.hasFrench(); case FREE -> c.hasCue(); default -> true; };
        }
    }

    public static final class Mistake {
        public String id, given, why, want;
        Mistake(String id, String given, String why, String want) { this.id = id; this.given = given; this.why = why; this.want = want; }
    }

    public Map<String, Integer> score = new HashMap<>(), box = new HashMap<>(), stage = new HashMap<>(), weak = new HashMap<>();
    public Map<String, Long> seen = new HashMap<>();
    public List<Mistake> mistakes = new ArrayList<>();
    /** The last few challenges met, newest first: none of them comes back straight away. */
    public List<String> recent = new ArrayList<>();
    public String lastTopic;
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

    /** How this challenge is asked now: its own stage, or where its topic's mastery puts it if it's new. */
    public Mode mode(Content.Challenge c) {
        Mode[] all = Mode.values();
        int s = Math.min(all.length - 1, stage.getOrDefault(c.id, Mode.of(score(c.topic)).ordinal()));
        for (int i = s; i >= 0; i--) if (all[i].fits(c)) return all[i];
        return Mode.GUIDED;
    }

    /** The next easier way to ask c, or null if m is already the easiest. */
    public static Mode easier(Mode m, Content.Challenge c) {
        for (int i = m.ordinal() - 1; i >= 0; i--) if (Mode.values()[i].fits(c)) return Mode.values()[i];
        return null;
    }

    public static Mode harder(Mode m, Content.Challenge c) {
        for (int i = m.ordinal() + 1; i < Mode.values().length; i++) if (Mode.values()[i].fits(c)) return Mode.values()[i];
        return m;
    }

    /** How hard this topic's challenges may get: 1 → 2 → 3 as its mastery grows. */
    public int tier(String topic) { return 1 + Math.min(2, score(topic) / 4); }

    /** Overall level from all practised topics, so strong players also meet advanced-only topics. */
    public int level() {
        double avg = score.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        return score.size() < 3 ? 1 : avg >= 7 ? 3 : avg >= 4 ? 2 : 1;
    }

    public static String levelName(int level) { return level >= 3 ? "Avancé" : level == 2 ? "Intermédiaire" : "Débutant"; }

    public boolean missed(String id) { return mistakes.stream().anyMatch(m -> m.id.equals(id)); }

    /** Minutes before a challenge may come back: soon after a miss, otherwise longer the better you know it. */
    static long rest(int box, boolean missed) { return missed ? 4 : 20L + 15L * box; }

    /**
     * How likely c is to be picked. fresh = leave out anything met recently (the last few challenges, or one
     * still resting), so the same sentence doesn't keep coming back; without it recent ones are only rarer.
     */
    double weight(Content.Challenge c, boolean fresh) {
        int reach = Math.max(tier(c.topic), level());
        if (c.level > reach) return 0; // too hard for now
        int bx = box.getOrDefault(c.id, 0), b = 6 - bx;
        double w = b * b * (1 + (MAX - score(c.topic)) / 5.0) * (c.level == reach ? 3 : 1); // favour the learner's edge
        int shaky = 0;
        for (String k : c.skills()) shaky += weak.getOrDefault(k, 0);
        w *= 1 + shaky / 2.0; // practise the habits that keep going wrong
        boolean miss = missed(c.id);
        long ago = System.currentTimeMillis() - seen.getOrDefault(c.id, 0L);
        boolean resting = ago < rest(bx, miss) * 60_000;
        if (fresh && (resting || recent.contains(c.id))) return 0;
        if (resting) w *= 0.05;
        if (!recent.isEmpty() && c.topic.equals(lastTopic)) w *= 0.4; // mix grammar topics up
        if (c.isTemplate() || c.vars != null) w *= 1.5; // filled-in templates are different every time
        return miss ? w * 3 : w;
    }

    double weight(Content.Challenge c) { return weight(c, false); }

    /**
     * ok = answered correctly; help = hints used plus 2 for a wrong try first; asked = how it was asked;
     * trap = the known mistake made, if any. A clean answer moves the challenge one step up the ladder and
     * raises its topic; a little help keeps it where it is; a miss moves it one step down.
     */
    public void record(Content.Challenge c, boolean ok, int help, String given, Mode asked, Content.Trap trap) {
        seen.put(c.id, System.currentTimeMillis());
        recent.remove(c.id);
        recent.add(0, c.id);
        while (recent.size() > 8) recent.remove(recent.size() - 1);
        lastTopic = c.topic;
        mistakes.removeIf(m -> m.id.equals(c.id));
        if (trap != null && trap.skill != null) weak.merge(trap.skill, 2, (a, b) -> Math.min(6, a + b));
        if (ok) {
            box.merge(c.id, 1, (a, b) -> Math.min(5, a + b));
            if (help <= 1) score.merge(c.topic, 1, (a, b) -> Math.min(MAX, a + b));
            if (help == 0) {
                stage.put(c.id, harder(asked, c).ordinal());
                for (String k : c.skills()) weak.computeIfPresent(k, (a, b) -> b > 1 ? b - 1 : null);
            } else stage.put(c.id, asked.ordinal());
            solved++;
            best = Math.max(best, ++streak);
        } else {
            box.put(c.id, 0);
            Mode down = easier(asked, c);
            stage.put(c.id, (down != null ? down : asked).ordinal());
            score.put(c.topic, Math.max(0, score(c.topic) - 2));
            streak = 0;
            mistakes.add(0, new Mistake(c.id, given, trap != null ? trap.why : null, c.sentence()));
            if (mistakes.size() > 12) mistakes.remove(12);
        }
        save();
    }
}
