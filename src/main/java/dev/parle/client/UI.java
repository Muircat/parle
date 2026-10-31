package dev.parle.client;

import dev.parle.core.Grader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;

/** Shared palette and drawing helpers for a flat, clean look. */
final class UI {
    static final int BG = 0xF2161A22, INSET = 0xFF0E1117, TILE = 0xFF2A3140,
            BLUE = 0xFF2B59C3, RED = 0xFFE5484D, WHITE = 0xFFF1F3F7, MUTED = 0xFF8C96A8,
            GREEN = 0xFF3FB950, AMBER = 0xFFF5A524;

    static void round(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 1, y, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + w, y + h - 1, color);
    }

    /** Dark card with a thin bleu-blanc-rouge stripe on top. */
    static void panel(DrawContext ctx, int x, int y, int w, int h) {
        round(ctx, x, y, w, h, BG);
        int t = w / 3;
        ctx.fill(x + 1, y, x + t, y + 2, BLUE);
        ctx.fill(x + t, y, x + w - t, y + 2, WHITE);
        ctx.fill(x + w - t, y, x + w - 1, y + 2, RED);
    }

    static boolean in(int[] r, double mx, double my) { return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]; }

    /** Words coloured by how they compare to the answer; elided words ("j'") hug the next word. */
    static Text marked(List<Grader.Tok> toks) {
        MutableText out = Text.empty();
        for (int i = 0; i < toks.size(); i++) {
            Grader.Tok t = toks.get(i);
            int c = switch (t.mark()) { case OK -> WHITE; case ACCENT -> AMBER; case BAD -> RED; case MISSING -> GREEN; };
            String s = t.text() + (t.text().endsWith("'") || i == toks.size() - 1 ? "" : " ");
            out.append(Text.literal(s).styled(st -> st.withColor(c).withUnderline(t.mark() != Grader.Mark.OK)));
        }
        return out;
    }

    static Text color(String s, int c) { return Text.literal(s).styled(st -> st.withColor(c)); }
}
