package com.anorys.pmm.tui.view;

import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.DashboardState.Tab;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The two header rows (mark, wordmark, workspace, health) and the tab bar under them. */
final class HeaderView {

    static final int HEIGHT = 4;

    private static final String[] WORDMARK = {"█▀█ █▀▄▀█ █▀▄▀█", "█▀▀ █ ▀ █ █ ▀ █"};
    private static final String[] WORDMARK_ASCII = {"pmm", "   "};

    private HeaderView() {
    }

    static void render(Paint paint, Rect area, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        paint.fill(new Rect(area.x(), area.y(), area.width(), 2), Theme.CRUST);
        int x = area.x() + 2;
        int y = area.y();

        x += mark(paint, x, y) + 2;

        // Wordmark: the gradient drifts while something runs.
        double phase = state.animating() ? (state.tick() % 120) / 120.0 : 0;
        String[] word = theme.glyphs().unicode() ? WORDMARK : WORDMARK_ASCII;
        for (int row = 0; row < 2; row++) {
            List<Span> spans = new ArrayList<>();
            for (int i = 0; i < word[row].length(); i++) {
                Color c = Theme.brand(phase + i / (double) word[row].length() * 0.6);
                spans.add(Span.styled(String.valueOf(word[row].charAt(i)), theme.on(c, Theme.CRUST)));
            }
            paint.spans(x, y + row, word[row].length(), spans);
        }
        x += word[0].length() + 3;

        // Right block: health gauge and what runs.
        int rightWidth = Math.min(40, Math.max(0, area.right() - x - 30));
        int rightX = area.right() - rightWidth - 2;
        int textWidth = rightX - x - 2;

        paint.spans(x, y, textWidth,
                Span.styled("project modules manager", theme.on(Theme.TEXT, Theme.CRUST).bold()));
        long repos = state.repositories().size();
        paint.spans(x, y + 1, textWidth,
                Span.styled(Paint.ellipsisStart(state.root().toString(), Math.max(10, textWidth - 22)), theme.on(Theme.SUBTEXT, Theme.CRUST)),
                Span.styled("  " + theme.glyphs().separator() + "  ", theme.on(Theme.OVERLAY, Theme.CRUST)),
                Span.styled(repos + (repos == 1 ? " repository" : " repositories"), theme.on(Theme.SUBTEXT, Theme.CRUST)));

        if (rightWidth >= 20) {
            health(paint, rightX, y, rightWidth, state);
            activity(paint, rightX, y + 1, rightWidth, state, now);
        }

        tabs(paint, new Rect(area.x(), y + 2, area.width(), 2), state);
    }

    /**
     * The Anorys faceted hexagon in five cells: the three greens around an orange point made of the lower
     * half of one cell and the upper half of the cell below it.
     */
    private static int mark(Paint paint, int x, int y) {
        Theme theme = paint.theme();
        if (!theme.glyphs().unicode()) {
            paint.text(x, y, "<#>", theme.on(Theme.FACET_LIGHT, Theme.CRUST).bold(), 3);
            return 3;
        }
        paint.spans(x, y, 5,
                Span.styled("▗", theme.on(Theme.FACET_LIGHT, Theme.CRUST)),
                Span.styled("▟", theme.on(Theme.FACET_LIGHT, Theme.CRUST)),
                Span.styled("▄", theme.on(Theme.SPARK, Theme.FACET_LIGHT)),
                Span.styled("▙", theme.on(Theme.FACET, Theme.CRUST)),
                Span.styled("▖", theme.on(Theme.FACET, Theme.CRUST)));
        paint.spans(x, y + 1, 5,
                Span.styled("▝", theme.on(Theme.FACET_DARK, Theme.CRUST)),
                Span.styled("▜", theme.on(Theme.FACET_DARK, Theme.CRUST)),
                Span.styled("▀", theme.on(Theme.SPARK, Theme.FACET)),
                Span.styled("▛", theme.on(Theme.FACET, Theme.CRUST)),
                Span.styled("▘", theme.on(Theme.FACET, Theme.CRUST)));
        return 5;
    }

    private static void health(Paint paint, int x, int y, int width, DashboardState state) {
        Theme theme = paint.theme();
        long healthy = state.repositories().stream().filter(r -> r.tone() == Tone.OK || r.tone() == Tone.RUNNING).count();
        long known = state.repositories().stream().filter(r -> r.tone() != Tone.IDLE).count();
        String label = known == 0 ? "health  …" : "health " + healthy + "/" + known;
        int labelWidth = 12;
        paint.text(x, y, Paint.pad(label, labelWidth), theme.on(Theme.SUBTEXT, Theme.CRUST), labelWidth);
        double ratio = state.health();
        Color solid = known == 0 ? Theme.SURFACE1 : ratio >= 0.999 ? Theme.GREEN : null;
        paint.bar(x + labelWidth, y, width - labelWidth - 5, ratio, 0, solid);
        paint.right(x, y, width, Line.from(Span.styled(String.format(java.util.Locale.ROOT, "%3d%%", Math.round(ratio * 100)),
                theme.on(ratio >= 0.999 ? Theme.GREEN : Theme.TEXT, Theme.CRUST).bold())));
    }

    private static void activity(Paint paint, int x, int y, int width, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        Line line;
        if (!state.activity().isEmpty()) {
            line = Line.from(
                    Span.styled(theme.glyphs().spinner(state.tick()) + " ", theme.on(Theme.ACCENT, Theme.CRUST)),
                    Span.styled(String.join(", ", state.activity()) + "…", theme.on(Theme.ACCENT, Theme.CRUST)));
        } else {
            line = Line.from(
                    Span.styled("fetched ", theme.on(Theme.OVERLAY, Theme.CRUST)),
                    Span.styled(Format.ago(state.lastFetch(), now), theme.on(Theme.SUBTEXT, Theme.CRUST)));
        }
        paint.right(x, y, width, line);
    }

    private static void tabs(Paint paint, Rect area, DashboardState state) {
        Theme theme = paint.theme();
        paint.fill(area, Theme.BASE);
        int x = area.x() + 2;
        int y = area.y();
        String rule = theme.glyphs().unicode() ? "─" : "-";
        paint.text(area.x(), y + 1, rule.repeat(area.width()), theme.fg(Theme.SURFACE1), area.width());
        for (Tab tab : Tab.values()) {
            boolean active = tab == state.tab();
            String key = String.valueOf(tab.ordinal() + 1);
            String label = " " + tab.title() + " ";
            paint.spans(x, y, key.length() + label.length() + 1,
                    Span.styled(" " + key, active ? theme.fg(Theme.SPARK).bold() : theme.muted()),
                    Span.styled(label, active ? theme.fg(Theme.ACCENT).bold() : theme.fg(Theme.SUBTEXT)));
            int width = key.length() + label.length() + 1;
            if (active) {
                String thick = theme.glyphs().unicode() ? "━" : "=";
                List<Span> underline = new ArrayList<>();
                for (int i = 0; i < width; i++) {
                    underline.add(Span.styled(thick, theme.fg(Theme.brand(i / (double) width * 0.7))));
                }
                paint.spans(x, y + 1, width, underline);
            }
            x += width + 2;
        }
    }
}
