package dev.parle.client;

import dev.parle.Parle;
import dev.parle.core.Content.Challenge;
import dev.parle.core.Grader;
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

/** One challenge: build the sentence from tiles, fill in its key part, or write it all, depending on mastery. */
final class ChallengeScreen extends Screen {
    private enum State { ASK, WRONG, DONE }
    private static final String ACCENTS = "éèêëàâçùûîïôœ";

    private final Challenge c;
    private final String source;
    private Mode mode;
    private State state = State.ASK;
    private boolean hinted, retried, ok;
    private int earned;
    private String draft = "", given = "";
    private Grader.Result result;
    private TextFieldWidget field;
    private final List<String> pool, built = new ArrayList<>();
    private final List<int[]> poolRects = new ArrayList<>(), builtRects = new ArrayList<>();
    private int px, py, pw, ph = 214;

    ChallengeScreen(Challenge c, String source) {
        super(Text.literal("Défi"));
        this.c = c;
        this.source = source;
        this.mode = ParleClient.profile.mode(c);
        this.pool = c.tiles(ParleClient.RND);
    }

    @Override
    protected void init() {
        pw = Math.min(340, width - 24);
        px = (width - pw) / 2;
        py = Math.max(6, (height - ph) / 2);
        int ix = px + 12, iw = pw - 24;
        field = null;
        if (state == State.ASK && mode != Mode.BUILD) {
            int fy = py + (mode == Mode.FILL ? 88 : 66);
            field = addDrawableChild(new TextFieldWidget(textRenderer, ix, fy, iw, 20, Text.empty()));
            field.setMaxLength(160);
            field.setText(draft);
            field.setChangedListener(s -> draft = s);
            field.setPlaceholder(UI.color(mode == Mode.FILL ? "Complète la phrase…" : "Écris ta phrase en français…", UI.MUTED));
            setInitialFocus(field);
            for (int i = 0; i < ACCENTS.length(); i++) {
                String ch = String.valueOf(ACCENTS.charAt(i));
                addDrawableChild(ButtonWidget.builder(Text.literal(ch), b -> { field.write(ch); setFocused(field); })
                        .dimensions(ix + i * 18, fy + 24, 16, 14).build());
            }
        }
        List<Map.Entry<String, Runnable>> row = new ArrayList<>();
        switch (state) {
            case ASK -> {
                if (!hinted && !c.hint.isEmpty()) row.add(Map.entry("Indice", () -> { hinted = true; rebuild(); }));
                if (mode != Mode.BUILD) row.add(Map.entry("Plus facile", () -> { mode = Mode.values()[mode.ordinal() - 1]; rebuild(); }));
                row.add(Map.entry("Passer", this::close));
                row.add(Map.entry("Vérifier ✔", this::check));
            }
            case WRONG -> {
                row.add(Map.entry("Voir la solution", () -> finish(false)));
                row.add(Map.entry("Réessayer", this::retry));
            }
            case DONE -> row.add(Map.entry(ParleClient.QUEUE.isEmpty() ? "Continuer" : "Défi suivant (" + ParleClient.QUEUE.size() + ")", this::next));
        }
        int bw = (iw - 4 * (row.size() - 1)) / row.size();
        for (int i = 0; i < row.size(); i++) {
            Runnable r = row.get(i).getValue();
            addDrawableChild(ButtonWidget.builder(Text.literal(row.get(i).getKey()), b -> r.run()).dimensions(ix + i * (bw + 4), py + ph - 28, bw, 20).build());
        }
    }

    private void retry() { retried = true; state = State.ASK; rebuild(); }

    private void next() {
        close();
        if (!ParleClient.QUEUE.isEmpty()) ParleClient.openNext(client);
    }

    private void rebuild() { clearAndInit(); }

    private void check() {
        String input = switch (mode) {
            case BUILD -> String.join(" ", built);
            case FILL -> draft.isBlank() ? "" : c.prefix() + draft.trim() + c.suffix();
            case WRITE -> draft;
        };
        if (input.isBlank()) return;
        given = input;
        result = Grader.grade(input, c.accepted());
        if (result.verdict() != Grader.Verdict.WRONG) finish(true);
        else {
            state = State.WRONG;
            hinted = true;
            sound(false);
            rebuild();
        }
    }

    private void finish(boolean correct) {
        ok = correct;
        earned = correct ? Math.max(1, hinted || retried ? mode.xp / 2 : mode.xp) : 0;
        ParleClient.profile.record(c, correct, correct && !hinted && !retried, given);
        if (earned > 0 && ClientPlayNetworking.canSend(Parle.Reward.ID)) ClientPlayNetworking.send(new Parle.Reward(earned));
        if (correct) sound(true);
        state = State.DONE;
        rebuild();
    }

    private void sound(boolean good) {
        client.getSoundManager().play(PositionedSoundInstance.master(good ? SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP : SoundEvents.ENTITY_VILLAGER_NO, good ? 1.3f : 1f, 0.6f));
    }

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
        String chip = mode.label + " · +" + mode.xp + " XP";
        int cw = textRenderer.getWidth(chip) + 8;
        UI.round(ctx, px + pw - 12 - cw, py + 7, cw, 13, UI.BLUE);
        ctx.drawText(textRenderer, chip, px + pw - 8 - cw, py + 10, UI.WHITE, false);
        ctx.drawTextWrapped(textRenderer, Text.literal(c.say), ix, py + 26, iw, UI.WHITE);

        if (state == State.ASK && mode == Mode.BUILD) {
            UI.round(ctx, ix, py + 62, iw, 36, UI.INSET);
            if (built.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, "Clique sur les mots pour construire la phrase", px + pw / 2, py + 76, UI.MUTED);
            tiles(ctx, built, builtRects, ix + 4, py + 66, iw - 8, UI.BLUE, mx, my);
            tiles(ctx, pool, poolRects, ix, py + 104, iw, UI.TILE, mx, my);
        } else if (state == State.ASK && mode == Mode.FILL) {
            ctx.drawTextWrapped(textRenderer, Text.literal(c.prefix()).append(UI.color("_____", UI.AMBER)).append(c.suffix()), ix, py + 66, iw, UI.MUTED);
        } else if (state != State.ASK) {
            ctx.drawText(textRenderer, "Ta réponse :", ix, py + 64, UI.MUTED, false);
            ctx.drawTextWrapped(textRenderer, UI.marked(result != null ? result.given() : List.of()), ix, py + 76, iw, UI.WHITE);
        }

        int fy = py + 140;
        if (state == State.WRONG) {
            ctx.drawText(textRenderer, "Pas tout à fait — les mots soulignés sont à revoir.", ix, fy, UI.RED, false);
            fy += 12;
        } else if (state == State.DONE) {
            boolean accents = ok && result.verdict() == Grader.Verdict.ACCENTS;
            String head = !ok ? "Solution (en vert : ce qui manquait) :" : (accents ? "Presque ! Attention aux accents." : "Parfait !") + " +" + earned + " XP";
            ctx.drawText(textRenderer, head, ix, fy, ok ? (accents ? UI.AMBER : UI.GREEN) : UI.MUTED, false);
            ctx.drawTextWrapped(textRenderer, ok ? Text.literal(result.expected()) : UI.marked(result != null ? result.wanted() : List.of()), ix, fy + 12, iw, UI.WHITE);
        }
        if (hinted && state != State.DONE)
            ctx.drawTextWrapped(textRenderer, Text.literal("Indice : " + c.hint), ix, fy, iw, UI.AMBER);
    }

    /** Lays out words as wrapping tiles and remembers their rectangles for clicks. */
    private void tiles(DrawContext ctx, List<String> words, List<int[]> rects, int x, int y, int w, int color, int mx, int my) {
        rects.clear();
        int cx = x, cy = y;
        for (String s : words) {
            int tw = textRenderer.getWidth(s) + 10;
            if (cx + tw > x + w && cx > x) { cx = x; cy += 16; }
            int[] r = {cx, cy, tw, 13};
            rects.add(r);
            UI.round(ctx, cx, cy, tw, 13, UI.in(r, mx, my) ? UI.TILE_HOVER : color);
            ctx.drawText(textRenderer, s, cx + 5, cy + 3, UI.WHITE, false);
            cx += tw + 3;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (state == State.ASK && mode == Mode.BUILD) {
            if (move(builtRects, built, pool, mx, my) || move(poolRects, pool, built, mx, my)) return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean move(List<int[]> rects, List<String> from, List<String> to, double mx, double my) {
        for (int i = 0; i < rects.size() && i < from.size(); i++) if (UI.in(rects.get(i), mx, my)) {
            to.add(from.remove(i));
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.2f));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            switch (state) {
                case ASK -> check();
                case WRONG -> retry();
                case DONE -> next();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && state == State.ASK && mode == Mode.BUILD && !built.isEmpty()) {
            pool.add(built.remove(built.size() - 1));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
