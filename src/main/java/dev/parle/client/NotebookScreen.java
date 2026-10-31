package dev.parle.client;

import dev.parle.core.Content;
import dev.parle.core.Profile;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** "Carnet": mastery per grammar topic, recent mistakes to revisit, and how often challenges come. */
final class NotebookScreen extends Screen {
    private static final int[] COOLDOWNS = {30, 60, 120, 300};
    private final List<Content.Topic> topics = new ArrayList<>(ParleClient.CONTENT.topics.values());
    private int px, py, pw, ph;

    NotebookScreen() { super(Text.literal("Carnet de français")); }

    private Profile p() { return ParleClient.profile; }

    @Override
    protected void init() {
        pw = Math.min(440, width - 24);
        ph = Math.min(40 + topics.size() * 12 + 38, height - 12);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int bw = (pw - 24 - 8) / 3, by = py + ph - 28, x = px + 12;
        addDrawableChild(ButtonWidget.builder(Text.literal("Un défi toutes les " + p().cooldown + " s"), b -> {
            int i = 0;
            while (i < COOLDOWNS.length && COOLDOWNS[i] <= p().cooldown) i++;
            p().cooldown = COOLDOWNS[i % COOLDOWNS.length]; p().save(); clearAndInit();
        }).dimensions(x, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Réviser"), b -> ParleClient.openNext(client)).dimensions(x + bw + 4, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Fermer"), b -> close()).dimensions(x + 2 * (bw + 4), by, bw, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        UI.panel(ctx, px, py, pw, ph);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        int x = px + 12, split = pw >= 380 ? pw * 11 / 20 : pw - 24;
        ctx.drawText(textRenderer, title, x, py + 10, UI.WHITE, false);
        String stats = p().solved + " réussis · série " + p().streak + " · record " + p().best;
        ctx.drawText(textRenderer, stats, px + pw - 12 - textRenderer.getWidth(stats), py + 10, UI.MUTED, false);
        ctx.drawText(textRenderer, "Grammaire — clique pour t'entraîner", x, py + 26, UI.MUTED, false);

        Content.Topic hover = null;
        for (int i = 0; i < topics.size(); i++) {
            Content.Topic t = topics.get(i);
            int y = py + 42 + i * 12, s = p().score(t.id());
            if (y > py + ph - 44) break;
            int[] row = {x - 4, y - 2, split - 4, 12};
            if (UI.in(row, mx, my)) { UI.round(ctx, row[0], row[1], row[2], row[3], UI.TILE); hover = t; }
            ctx.drawText(textRenderer, textRenderer.trimToWidth(t.name(), split - 130), x, y, UI.WHITE, false);
            int bx = x + split - 122;
            for (int k = 0; k < 5; k++) ctx.fill(bx + k * 12, y + 1, bx + k * 12 + 10, y + 7, s >= (k + 1) * 2 ? UI.BLUE : s > k * 2 ? 0xFF1F3570 : UI.INSET);
            ctx.drawText(textRenderer, Profile.Mode.of(s).label, bx + 64, y, UI.MUTED, false);
        }

        if (split < pw - 24) {
            int rx = px + split + 16, rw = pw - split - 28, y = py + 26;
            ctx.drawText(textRenderer, "À revoir", rx, y, UI.MUTED, false);
            if (p().mistakes.isEmpty()) ctx.drawText(textRenderer, "Rien pour l'instant. Bravo !", rx, y + 14, UI.GREEN, false);
            for (Profile.Mistake m : p().mistakes) {
                Content.Challenge c = ParleClient.CONTENT.byId(m.id);
                if (c == null) continue;
                if ((y += 12) > py + ph - 62) break;
                ctx.drawText(textRenderer, textRenderer.trimToWidth("✗ " + m.given, rw), rx, y, UI.RED, false);
                ctx.drawText(textRenderer, textRenderer.trimToWidth("✓ " + c.sentence(), rw), rx, y += 10, UI.GREEN, false);
            }
        }
        if (hover != null)
            ctx.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(hover.rule()), 220), mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int split = pw >= 380 ? pw * 11 / 20 : pw - 24;
        for (int i = 0; i < topics.size(); i++) {
            int y = py + 42 + i * 12;
            if (y <= py + ph - 44 && UI.in(new int[]{px + 8, y - 2, split - 4, 12}, mx, my)) {
                Content.Challenge c = ParleClient.CONTENT.pick(null, topics.get(i).id(), p(), ParleClient.RND);
                if (c != null) client.setScreen(new ChallengeScreen(c, "review"));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
