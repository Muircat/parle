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
 * or you look at the solution and type it out yourself before you can carry on.
 */
final class ChallengeScreen extends Screen {
    private enum State { ASK, COPY, DONE }
    private static final String ACCENTS = "éèêëàâçùûîïôœ";

    private final Challenge c;
    private final String source;
    private final List<String> bank;
    private Mode mode;
    private State state = State.ASK;
    private boolean hinted, retried, ok;
    private int earned;
    private String draft = "", given = "";
    private Grader.Result result;
    private final String speaker, reg; // villager name and job, and "tu"/"vous", for trade dialogues
    private final float pitch;
    private final Runnable onSolved;
    private int px, py, pw, ph = 234;

    private boolean wrongRegister;
    private String copyChecked;
    private boolean copyOk;

    ChallengeScreen(Challenge c, String source) { this(c, source, null, null, 1f, null); }

    ChallengeScreen(Challenge c, String source, String speaker, String reg, float pitch, Runnable onSolved) {
        super(Text.literal("Défi"));
        this.c = c;
        this.source = source;
        this.speaker = speaker;
        this.reg = reg;
        this.pitch = pitch;
        this.onSolved = onSolved;
        this.mode = ParleClient.profile.mode(c);
        this.bank = c.tiles(ParleClient.RND);
    }

    private boolean forced() { return !source.equals("review") && speaker == null; }

    @Override
    protected void applyBlur(float delta) { if (speaker == null) super.applyBlur(delta); } // keep the villager visible

    @Override
    protected void init() {
        pw = Math.min(340, width - 24);
        px = (width - pw) / 2;
        py = Math.max(6, (height - ph) / 2);
        int ix = px + 12, iw = pw - 24;
        if (state != State.DONE) {
            TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, ix, py + 116, iw, 20, Text.empty()));
            field.setMaxLength(160);
            field.setText(draft);
            field.setChangedListener(s -> draft = s);
            field.setPlaceholder(UI.color(state == State.COPY ? "Recopie la solution…" : mode == Mode.FILL ? "Tape la partie manquante…" : "Tape ta phrase en français…", UI.MUTED));
            setInitialFocus(field);
            for (int i = 0; i < ACCENTS.length(); i++) {
                String ch = String.valueOf(ACCENTS.charAt(i));
                addDrawableChild(ButtonWidget.builder(Text.literal(ch), b -> { field.write(ch); setFocused(field); })
                        .dimensions(ix + i * 18, py + 140, 16, 14).build());
            }
        }
        List<Map.Entry<String, Runnable>> row = new ArrayList<>();
        switch (state) {
            case ASK -> {
                if (!hinted && !c.hint.isEmpty()) row.add(Map.entry("Indice", () -> { hinted = true; rebuild(); }));
                if (mode != Mode.GUIDED) row.add(Map.entry("Plus facile", () -> { mode = Mode.values()[mode.ordinal() - 1]; draft = ""; rebuild(); }));
                if (result != null) row.add(Map.entry("Solution", this::reveal));
                if (!forced()) row.add(Map.entry("Fermer", this::close));
                row.add(Map.entry("Vérifier ✔", this::check));
            }
            case COPY -> row.add(Map.entry("Vérifier ✔", this::check));
            case DONE -> row.add(Map.entry(onSolved != null ? (ok ? "Continuer ➜" : "Partir") : ParleClient.QUEUE.isEmpty() ? "Continuer" : "Défi suivant (" + ParleClient.QUEUE.size() + ")", this::next));
        }
        int bw = (iw - 4 * (row.size() - 1)) / row.size();
        for (int i = 0; i < row.size(); i++) {
            Runnable r = row.get(i).getValue();
            addDrawableChild(ButtonWidget.builder(Text.literal(row.get(i).getKey()), b -> r.run()).dimensions(ix + i * (bw + 4), py + ph - 28, bw, 20).build());
        }
    }

    private void rebuild() { clearAndInit(); }

    private void next() {
        close();
        if (onSolved != null) {
            if (ok) onSolved.run();
            return;
        }
        if (!ParleClient.QUEUE.isEmpty()) ParleClient.openNext(client);
    }

    /** Gives up on this one: it counts as a mistake, and the player must copy the answer out to move on. */
    private void reveal() {
        ParleClient.profile.record(c, false, false, given);
        state = State.COPY;
        draft = "";
        rebuild();
    }

    private void check() {
        String input = state == State.ASK && mode == Mode.FILL ? (draft.isBlank() ? "" : c.prefix() + draft.trim() + c.suffix()) : draft;
        if (input.isBlank()) return;
        Grader.Result r = Grader.check(input, c, reg);
        wrongRegister = "vous".equals(reg) && r.verdict() == Grader.Verdict.WRONG && Grader.check(input, c, "tu").verdict() != Grader.Verdict.WRONG;
        if (state == State.COPY) {
            if (r.verdict() == Grader.Verdict.WRONG) { sound(false); return; }
            ok = false;
            state = State.DONE;
        } else {
            given = input;
            result = r;
            if (r.verdict() == Grader.Verdict.WRONG) {
                retried = hinted = true;
                sound(false);
                rebuild();
                return;
            }
            ok = true;
            int xp = mode.xp + 3 * (c.level - 1);
            earned = Math.max(1, retried || hinted ? xp / 2 : xp);
            ParleClient.profile.record(c, true, !retried && !hinted, given);
            if (ClientPlayNetworking.canSend(Parle.Reward.ID)) ClientPlayNetworking.send(new Parle.Reward(earned));
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
    public boolean shouldCloseOnEsc() { return state == State.DONE || !forced(); }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        UI.panel(ctx, px, py, pw, ph);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        int ix = px + 12, iw = pw - 24;
        var topic = ParleClient.CONTENT.topics.get(c.topic);
        ctx.drawText(textRenderer, (topic != null ? topic.name() : c.topic) + " · " + ParleClient.label(source), ix, py + 10, UI.MUTED, false);
        String chip = state == State.COPY || state == State.DONE && !ok ? "Recopier · 0 XP" : mode.label + " · " + Profile.levelName(c.level);
        int cw = textRenderer.getWidth(chip) + 8;
        UI.round(ctx, px + pw - 12 - cw, py + 7, cw, 13, state == State.COPY ? UI.RED : UI.BLUE);
        ctx.drawText(textRenderer, chip, px + pw - 8 - cw, py + 10, UI.WHITE, false);
        int y = py + 26;
        if (speaker != null && c.npc != null) {
            ctx.drawText(textRenderer, speaker + (reg != null ? " (on se dit « " + reg + " »)" : "") + " :", ix, y, UI.AMBER, false);
            ctx.drawTextWrapped(textRenderer, Text.literal("« " + c.npc + " »").styled(s -> s.withItalic(true)), ix + 6, y += 11, iw - 6, UI.WHITE);
            y += textRenderer.wrapLines(Text.literal("« " + c.npc + " »"), iw - 6).size() * 9 + 4;
        }
        ctx.drawTextWrapped(textRenderer, Text.literal(c.say), ix, y, iw, speaker != null ? UI.MUTED : UI.WHITE);

        switch (state) {
            case ASK -> {
                if (mode == Mode.GUIDED) words(ctx, ix, py + 84, iw);
                else if (mode == Mode.FILL)
                    ctx.drawTextWrapped(textRenderer, Text.literal(c.prefix()).append(UI.color("_____", UI.AMBER)).append(c.suffix()), ix, py + 90, iw, UI.MUTED);
                int fy = py + 162;
                if (result != null) {
                    ctx.drawText(textRenderer, wrongRegister ? "Bonne phrase, mauvais registre : on vouvoie ce villageois !" : "Pas tout à fait. Corrige les mots soulignés et réessaie :", ix, fy, UI.RED, false);
                    ctx.drawTextWrapped(textRenderer, UI.marked(result.given()), ix, fy + 11, iw, UI.WHITE);
                    fy += 24;
                }
                if (hinted) ctx.drawTextWrapped(textRenderer, Text.literal("Indice : " + c.hint), ix, fy, iw, UI.AMBER);
            }
            case COPY -> {
                ctx.drawText(textRenderer, "Solution (en vert : ce qui manquait). Recopie-la :", ix, py + 84, UI.MUTED, false);
                ctx.drawTextWrapped(textRenderer, UI.marked(Grader.grade(given, List.of(result.expected())).wanted()), ix, py + 96, iw, UI.WHITE);
                if (!draft.equals(copyChecked)) { copyChecked = draft; copyOk = Grader.check(draft, c, reg).verdict() != Grader.Verdict.WRONG; }
                if (!copyOk)
                    ctx.drawTextWrapped(textRenderer, Text.literal(c.hint), ix, py + 162, iw, UI.AMBER);
            }
            case DONE -> {
                boolean accents = ok && result.verdict() == Grader.Verdict.ACCENTS;
                String head = !ok ? (speaker != null ? "« Désolé, je ne comprends pas. » Pas d'échange cette fois." : "C'est noté. Ce défi reviendra bientôt.") : (accents ? "Presque ! Attention aux accents." : "Parfait !") + " +" + earned + " XP";
                ctx.drawText(textRenderer, head, ix, py + 90, ok ? (accents ? UI.AMBER : UI.GREEN) : UI.MUTED, false);
                ctx.drawTextWrapped(textRenderer, ok ? UI.marked(result.wanted()) : Text.literal(result.expected()), ix, py + 104, iw, UI.WHITE);
                ctx.drawTextWrapped(textRenderer, Text.literal(c.hint), ix, py + 140, iw, UI.MUTED);
            }
        }
    }

    /** The word bank for guided mode: every word you need (plus a couple you don't), shuffled. */
    private void words(DrawContext ctx, int x, int y, int w) {
        int cx = x, cy = y;
        for (String s : bank) {
            int tw = textRenderer.getWidth(s) + 10;
            if (cx + tw > x + w && cx > x) { cx = x; cy += 15; }
            UI.round(ctx, cx, cy, tw, 13, UI.TILE);
            ctx.drawText(textRenderer, s, cx + 5, cy + 3, UI.WHITE, false);
            cx += tw + 3;
        }
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
