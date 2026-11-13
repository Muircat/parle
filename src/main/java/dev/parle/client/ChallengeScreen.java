package dev.parle.client;

import dev.parle.Parle;
import dev.parle.core.Content.Challenge;
import dev.parle.core.Grader;
import dev.parle.core.Profile;
import dev.parle.core.Profile.Mode;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One challenge, always typed. Gameplay challenges can't be skipped or escaped: you answer correctly,
 * or you look at the solution and type it out yourself before you can carry on. A correct answer may lead
 * straight into a follow-up that reworks the sentence you just wrote.
 */
final class ChallengeScreen extends Screen {
    private enum State { ASK, COPY, DONE }
    /** The hint ladder: each rung gives a bit more away (and costs a bit more XP). */
    private enum Rung { RULE, ENGLISH, START, BANK }
    private static final String ACCENTS = "éèêëàâçùûîïôœ";
    private static final int FIELD_H = 20, ACCENT_H = 14;

    private final Challenge c;
    private final String source, prev; // prev: the sentence a follow-up reworks
    private final int depth;
    private final List<String> bank;
    private final List<Rung> rungs = new ArrayList<>();
    private Mode mode;
    private State state = State.ASK;
    private boolean retried, ok;
    private int hints, earned;
    private String draft = "", given = "";
    private Grader.Result result;
    private Challenge follow;
    private final String speaker, reg; // villager name and job, and "tu"/"vous", for trade dialogues
    private final float pitch;
    private final Runnable onSolved;
    private boolean wrongRegister;

    private int px, py, pw, ph;
    private final List<Block> top = new ArrayList<>(), bottom = new ArrayList<>();
    private interface Draw { void at(DrawContext ctx, int x, int y); }
    private record Block(int h, Draw d) {}

    ChallengeScreen(Challenge c, String source) { this(c, source, null, 0); }

    ChallengeScreen(Challenge c, String source, String prev, int depth) { this(c, source, prev, depth, null, null, 1f, null); }

    ChallengeScreen(Challenge c, String source, String speaker, String reg, float pitch, Runnable onSolved) {
        this(c, source, null, 0, speaker, reg, pitch, onSolved);
    }

    private ChallengeScreen(Challenge c, String source, String prev, int depth, String speaker, String reg, float pitch, Runnable onSolved) {
        super(Text.literal("Défi"));
        this.c = c;
        this.source = source;
        this.prev = prev;
        this.depth = depth;
        this.speaker = speaker;
        this.reg = reg;
        this.pitch = pitch;
        this.onSolved = onSolved;
        this.mode = ParleClient.profile.mode(c);
        this.bank = c.tiles(ParleClient.RND);
        ladder();
    }

    private void ladder() {
        rungs.clear();
        if (!c.hint.isEmpty()) rungs.add(Rung.RULE);
        if (mode.ordinal() >= Mode.FRENCH.ordinal()) rungs.add(Rung.ENGLISH);
        rungs.add(Rung.START);
        if (mode != Mode.GUIDED) rungs.add(Rung.BANK);
        hints = Math.min(hints, rungs.size());
    }

    private boolean shown(Rung r) { int i = rungs.indexOf(r); return i >= 0 && i < hints; }

    private boolean forced() { return !source.equals("review") && speaker == null; }

    /** Hints used, plus 2 if the first try was wrong. */
    private int help() { return hints + (retried ? 2 : 0); }

    @Override
    protected void applyBlur(float delta) { if (speaker == null) super.applyBlur(delta); } // keep the villager visible

    @Override
    protected void init() {
        pw = Math.min(340, width - 24);
        px = (width - pw) / 2;
        int iw = pw - 24;
        layout(iw);
        boolean field = state != State.DONE;
        int sum = 26;
        for (Block b : top) sum += b.h;
        int fieldAt = sum;
        if (field) sum += FIELD_H + 4 + ACCENT_H + 8;
        for (Block b : bottom) sum += b.h;
        ph = sum + 4 + 20 + 8;
        py = Math.max(6, (height - ph) / 2);
        int ix = px + 12;

        if (field) {
            TextFieldWidget f = addDrawableChild(new TextFieldWidget(textRenderer, ix, py + fieldAt, iw, FIELD_H, Text.empty()));
            f.setMaxLength(200);
            f.setText(draft);
            f.setChangedListener(s -> draft = s);
            f.setPlaceholder(UI.color(state == State.COPY ? "Recopie la solution…" : mode == Mode.FILL ? "Tape la partie manquante…" : "Tape ta phrase en français…", UI.MUTED));
            setInitialFocus(f);
            for (int i = 0; i < ACCENTS.length(); i++) {
                String ch = String.valueOf(ACCENTS.charAt(i));
                addDrawableChild(ButtonWidget.builder(Text.literal(ch), b -> { f.write(ch); setFocused(f); })
                        .dimensions(ix + i * 18, py + fieldAt + FIELD_H + 4, 16, ACCENT_H).build());
            }
        }
        List<Map.Entry<String, Runnable>> row = new ArrayList<>();
        switch (state) {
            case ASK -> {
                if (hints < rungs.size()) row.add(Map.entry("Indice " + (hints + 1) + "/" + rungs.size(), () -> { hints++; rebuild(); }));
                Mode down = Profile.easier(mode, c);
                if (down != null) row.add(Map.entry("Plus facile", () -> { mode = down; draft = ""; ladder(); rebuild(); }));
                if (result != null) row.add(Map.entry("Solution", this::reveal));
                if (!forced()) row.add(Map.entry("Fermer", this::close));
                row.add(Map.entry("Vérifier ✔", this::check));
            }
            case COPY -> row.add(Map.entry("Vérifier ✔", this::check));
            case DONE -> row.add(Map.entry(follow != null ? "Et maintenant… ➜" : onSolved != null ? (ok ? "Continuer ➜" : "Partir")
                    : ParleClient.QUEUE.isEmpty() ? "Continuer" : "Défi suivant (" + ParleClient.QUEUE.size() + ")", this::next));
        }
        int bw = (iw - 4 * (row.size() - 1)) / row.size();
        for (int i = 0; i < row.size(); i++) {
            Runnable r = row.get(i).getValue();
            addDrawableChild(ButtonWidget.builder(Text.literal(row.get(i).getKey()), b -> r.run()).dimensions(ix + i * (bw + 4), py + ph - 28, bw, 20).build());
        }
    }

    /** What the player is asked, in the words this stage of the ladder uses. */
    private String prompt() {
        return switch (mode) { case FREE -> c.cue; case FRENCH -> c.sayFr; default -> c.say; };
    }

    /** Builds what sits above the text box (top) and below the accent buttons (bottom), so the panel fits it. */
    private void layout(int w) {
        top.clear();
        bottom.clear();
        if (speaker != null && c.npc != null) {
            top.add(text(Text.literal(speaker + (reg != null ? " (on se dit « " + reg + " »)" : "") + " :"), UI.AMBER, w, 2));
            top.add(text(Text.literal("« " + c.npc + " »").styled(s -> s.withItalic(true)), UI.WHITE, w, 6));
        }
        if (prev != null) top.add(text(Text.literal("Ta phrase : « " + prev + " »").styled(s -> s.withItalic(true)), UI.MUTED, w, 4));
        top.add(text(Text.literal(prompt()), speaker != null && mode.ordinal() < Mode.FRENCH.ordinal() ? UI.MUTED : UI.WHITE, w, 8));

        switch (state) {
            case ASK -> {
                if (shown(Rung.ENGLISH)) top.add(text(Text.literal("En anglais : " + c.say), UI.MUTED, w, 4));
                if (shown(Rung.RULE)) top.add(text(Text.literal("Indice : " + c.hint), UI.AMBER, w, 4));
                if (shown(Rung.START)) top.add(text(Text.literal("Ça commence par : « " + start() + "… »"), UI.AMBER, w, 4));
                if (mode == Mode.GUIDED || shown(Rung.BANK)) top.add(words(w));
                if (mode == Mode.FILL)
                    top.add(text(Text.literal(c.prefix()).append(UI.color("_____", UI.AMBER)).append(c.suffix()), UI.MUTED, w, 8));
                if (result != null) {
                    if (result.trap() != null) bottom.add(text(Text.literal("⚠ " + result.trap().why), UI.AMBER, w, 3));
                    else bottom.add(text(Text.literal(wrongRegister ? "Bonne phrase, mauvais registre : on vouvoie ce villageois !"
                            : "Pas tout à fait. Corrige les mots soulignés et réessaie :"), UI.RED, w, 3));
                    bottom.add(text(UI.marked(result.given()), UI.WHITE, w, 2));
                }
            }
            case COPY -> {
                top.add(text(Text.literal("Solution (en vert : ce qui manquait). Recopie-la :"), UI.MUTED, w, 2));
                top.add(text(UI.marked(Grader.grade(given, List.of(result.expected())).wanted()), UI.WHITE, w, 8));
                if (result.trap() != null) bottom.add(text(Text.literal("⚠ " + result.trap().why), UI.AMBER, w, 3));
                else if (!c.hint.isEmpty()) bottom.add(text(Text.literal(c.hint), UI.AMBER, w, 3));
            }
            case DONE -> {
                Grader.Verdict v = result.verdict();
                String head = !ok ? (speaker != null ? "« Désolé, je ne comprends pas. » Pas d'échange cette fois." : "C'est noté. Ce défi reviendra bientôt.")
                        : (v == Grader.Verdict.ACCENTS ? "Presque ! Attention aux accents." : v == Grader.Verdict.TYPO ? "Presque ! Attention à l'orthographe." : "Parfait !") + " +" + earned + " XP";
                top.add(text(Text.literal(head), ok ? (v == Grader.Verdict.EXACT ? UI.GREEN : UI.AMBER) : UI.MUTED, w, 4));
                top.add(text(ok ? UI.marked(result.wanted()) : Text.literal(result.expected()), UI.WHITE, w, 6));
                if (ok && result.verdict() == Grader.Verdict.EXACT && !given.isEmpty() && !Grader.tokens(given).equals(Grader.tokens(c.sentence())))
                    top.add(text(Text.literal("Autre façon de le dire : " + c.sentence()), UI.MUTED, w, 4));
                if (!c.hint.isEmpty()) top.add(text(Text.literal(c.hint), UI.MUTED, w, 4));
                if (follow != null) top.add(text(Text.literal("Et maintenant, va un peu plus loin !"), UI.BLUE_LIGHT, w, 4));
            }
        }
    }

    /** The first words of the answer (of the blank, in fill-in mode). */
    private String start() {
        List<String> w = Grader.tokens(mode == Mode.FILL ? c.sentence().substring(c.prefix().length()) : c.sentence());
        int n = mode == Mode.FILL ? 1 : Math.min(2, Math.max(1, w.size() / 3));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, w.size()); i++) sb.append(w.get(i)).append(w.get(i).endsWith("'") ? "" : " ");
        return sb.toString().trim();
    }

    private Block text(Text t, int color, int w, int gap) {
        int h = textRenderer.wrapLines(t, w).size() * 9;
        return new Block(h + gap, (ctx, x, y) -> ctx.drawTextWrapped(textRenderer, t, x, y, w, color));
    }

    /** The word bank: every word you need (plus a couple you don't), shuffled. */
    private Block words(int w) {
        int cx = 0, rows = 1;
        for (String s : bank) {
            int tw = textRenderer.getWidth(s) + 10;
            if (cx + tw > w && cx > 0) { cx = 0; rows++; }
            cx += tw + 3;
        }
        return new Block(rows * 15 + 6, (ctx, x, y) -> {
            int bx = x, by = y;
            for (String s : bank) {
                int tw = textRenderer.getWidth(s) + 10;
                if (bx + tw > x + w && bx > x) { bx = x; by += 15; }
                UI.round(ctx, bx, by, tw, 13, UI.TILE);
                ctx.drawText(textRenderer, s, bx + 5, by + 3, UI.WHITE, false);
                bx += tw + 3;
            }
        });
    }

    private void rebuild() { clearAndInit(); }

    private void next() {
        if (follow != null) {
            client.setScreen(new ChallengeScreen(follow, source.equals("review") ? "review" : "suite", given, depth + 1));
            return;
        }
        close();
        if (onSolved != null) {
            if (ok) onSolved.run();
            return;
        }
        if (!ParleClient.QUEUE.isEmpty()) ParleClient.openNext(client);
    }

    /** Gives up on this one: it counts as a mistake, and the player must copy the answer out to move on. */
    private void reveal() {
        ParleClient.profile.record(c, false, help(), given, mode, result.trap());
        state = State.COPY;
        draft = "";
        rebuild();
    }

    private void check() {
        String input = state == State.ASK && mode == Mode.FILL ? (draft.isBlank() ? "" : c.prefix() + draft.trim() + c.suffix()) : draft;
        if (input.isBlank()) return;
        Grader.Result r = Grader.check(input, c, reg);
        wrongRegister = "vous".equals(reg) && !r.ok() && Grader.check(input, c, "tu").ok();
        if (state == State.COPY) {
            if (!r.ok()) { sound(false); return; }
            ok = false;
            state = State.DONE;
        } else {
            given = input;
            result = r;
            if (!r.ok()) {
                if (!retried && hints == 0 && rungs.contains(Rung.RULE) && r.trap() == null) hints = 1; // the rule comes free after a miss
                retried = true;
                sound(false);
                rebuild();
                return;
            }
            ok = true;
            int xp = mode.xp + 3 * (c.level - 1);
            double scale = Math.max(0.25, 1 - 0.25 * hints) * (retried ? 0.5 : 1);
            earned = Math.max(1, (int) Math.round(xp * scale));
            ParleClient.profile.record(c, true, help(), given, mode, null);
            if (ClientPlayNetworking.canSend(Parle.Reward.ID)) ClientPlayNetworking.send(new Parle.Reward(earned));
            if (speaker == null && depth < 2) follow = ParleClient.CONTENT.followUp(c, ParleClient.profile, ParleClient.RND);
            sound(true);
            state = State.DONE;
        }
        rebuild();
    }

    private void sound(boolean good) {
        var s = speaker != null ? (good ? SoundEvents.ENTITY_VILLAGER_YES : SoundEvents.ENTITY_VILLAGER_NO) : good ? SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP : SoundEvents.ENTITY_VILLAGER_NO;
        client.getSoundManager().play(PositionedSoundInstance.master(s, speaker != null ? pitch : good ? 1.3f : 1f, 0.6f));
    }

    @Override
    public boolean shouldCloseOnEsc() { return state == State.DONE && follow == null || state != State.DONE && !forced(); }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        UI.panel(ctx, px, py, pw, ph);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        int ix = px + 12;
        var topic = ParleClient.CONTENT.topics.get(c.topic);
        ctx.drawText(textRenderer, (topic != null ? topic.name() : c.topic) + " · " + ParleClient.label(source), ix, py + 10, UI.MUTED, false);
        String chip = state == State.COPY || state == State.DONE && !ok ? "Recopier · 0 XP" : mode.label + " · " + Profile.levelName(c.level);
        int cw = textRenderer.getWidth(chip) + 8;
        UI.round(ctx, px + pw - 12 - cw, py + 7, cw, 13, state == State.COPY ? UI.RED : UI.BLUE);
        ctx.drawText(textRenderer, chip, px + pw - 8 - cw, py + 10, UI.WHITE, false);
        int y = py + 26;
        for (Block b : top) { b.d.at(ctx, ix, y); y += b.h; }
        if (state != State.DONE) y += FIELD_H + 4 + ACCENT_H + 8;
        for (Block b : bottom) { b.d.at(ctx, ix, y); y += b.h; }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (state == State.DONE) next(); else check();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
