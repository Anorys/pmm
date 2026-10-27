package com.anorys.pmm.tui.view;

import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.DashboardState.Dialog;
import com.anorys.pmm.tui.model.DashboardState.DialogLine;
import com.anorys.pmm.tui.model.DashboardState.Toast;
import com.anorys.pmm.tui.theme.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Span;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Draws the whole dashboard from its state; holds no state of its own. */
public final class DashboardView {

    public static final int MIN_WIDTH = 80;
    public static final int MIN_HEIGHT = 24;

    private final Theme theme;

    public DashboardView(Theme theme) {
        this.theme = theme;
    }

    public void render(Frame frame, DashboardState state, Instant now) {
        Rect area = frame.area();
        Paint paint = new Paint(frame.buffer(), theme);
        paint.fill(area, Theme.BASE);
        if (area.width() < MIN_WIDTH || area.height() < MIN_HEIGHT) {
            String message = "Enlarge the terminal to at least " + MIN_WIDTH + " x " + MIN_HEIGHT;
            paint.text(area.x() + Math.max(0, (area.width() - message.length()) / 2), area.y() + area.height() / 2,
                    message, theme.subtext(), area.width());
            return;
        }
        HeaderView.render(paint, area, state, now);
        Rect body = new Rect(area.x() + 1, area.y() + HeaderView.HEIGHT + 1, area.width() - 2,
                area.height() - HeaderView.HEIGHT - 3);
        switch (state.tab()) {
            case WORKSPACE -> WorkspaceView.render(paint, body, state, now);
            case BUILD -> BuildView.render(paint, body, state, now);
            case DEPENDENCIES -> DependenciesView.render(paint, body, state, now);
            case GRAPH -> GraphView.render(paint, body, state, now);
        }
        toast(paint, new Rect(area.x(), area.bottom() - 2, area.width(), 1), state, now);
        FooterView.render(paint, new Rect(area.x(), area.bottom() - 1, area.width(), 1), state);
        if (state.dialog() != null) {
            dialog(paint, area, state.dialog());
        } else if (state.help()) {
            help(paint, area);
        }
    }

    private void toast(Paint paint, Rect area, DashboardState state, Instant now) {
        Toast toast = state.toast();
        if (toast == null || now.isAfter(toast.until())) {
            return;
        }
        paint.spans(area.x() + 2, area.y(), area.width() - 4,
                Span.styled(theme.glyphs().symbol(toast.tone()) + " ", theme.state(toast.tone())),
                Span.styled(toast.message(), theme.text()));
    }

    private void dialog(Paint paint, Rect screen, Dialog dialog) {
        int width = Math.min(screen.width() - 8, Math.max(50,
                dialog.lines().stream().mapToInt(l -> l.text().length() + 6).max().orElse(50)));
        int height = Math.min(screen.height() - 4, dialog.lines().size() + 6);
        Rect area = new Rect(screen.x() + (screen.width() - width) / 2, screen.y() + (screen.height() - height) / 2, width, height);
        Rect inner = modal(paint, area, dialog.title());
        int y = inner.y() + 1;
        for (DialogLine line : dialog.lines()) {
            if (y >= inner.bottom() - 2) {
                break;
            }
            paint.text(inner.x(), y++, Paint.ellipsis(line.text(), inner.width()),
                    line.tone() == null ? theme.text() : theme.state(line.tone()), inner.width());
        }
        List<Span> keys = new ArrayList<>();
        if (dialog.confirmLabel() != null) {
            keys.addAll(theme.keyChip("y", dialog.confirmLabel()));
            keys.addAll(theme.keyChip("esc", "cancel"));
        } else {
            keys.addAll(theme.keyChip("esc", "close"));
        }
        paint.spans(inner.x(), inner.bottom() - 1, inner.width(), keys);
    }

    private void help(Paint paint, Rect screen) {
        String[][] keys = {
                {"↑ ↓  j k", "select a repository"},
                {"1 2 3 4  tab", "switch tab"},
                {"f", "fetch every repository and refresh git"},
                {"b", "build all (skips unchanged repositories)"},
                {"B", "build the selected one, without the cache"},
                {"r", "rebase the selected branch (asks first)"},
                {"d", "check the dependencies against Nexus again"},
                {"enter", "follow the selected repository's build"},
                {"?", "show or hide this help"},
                {"q  ctrl-c", "quit"}};
        int width = Math.min(screen.width() - 8, 70);
        int height = keys.length + 5;
        Rect area = new Rect(screen.x() + (screen.width() - width) / 2, screen.y() + (screen.height() - height) / 2, width, height);
        Rect inner = modal(paint, area, "Keys");
        int y = inner.y() + 1;
        for (String[] key : keys) {
            paint.spans(inner.x(), y++, inner.width(),
                    Span.styled(Paint.pad(key[0], 16), theme.fg(Theme.ACCENT).bold()),
                    Span.styled(key[1], theme.text()));
        }
    }

    private Rect modal(Paint paint, Rect area, String title) {
        Rect inner = paint.frame(area, Theme.MANTLE, Theme.ACCENT);
        paint.spans(area.x() + 2, area.y(), area.width() - 4,
                Span.styled(" " + theme.glyphs().bullet() + " ", theme.on(Theme.SPARK, Theme.MANTLE)),
                Span.styled(title + " ", theme.on(Theme.TEXT, Theme.MANTLE).bold()));
        return new Rect(inner.x() + 2, inner.y(), Math.max(0, inner.width() - 4), inner.height());
    }
}
