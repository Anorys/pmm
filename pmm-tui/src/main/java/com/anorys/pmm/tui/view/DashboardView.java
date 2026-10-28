package com.anorys.pmm.tui.view;

import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.DashboardState.Dialog;
import com.anorys.pmm.tui.model.DashboardState.DialogLine;
import com.anorys.pmm.tui.model.DashboardState.Toast;
import com.anorys.pmm.tui.model.Menu;
import com.anorys.pmm.tui.theme.Tone;
import com.anorys.pmm.tui.theme.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
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
        } else if (state.menu() != null) {
            menu(paint, area, state.menu());
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

    /** A list to pick from: header, optional filter, rows, the selected row described, then the keys. */
    private void menu(Paint paint, Rect screen, Menu menu) {
        List<Menu.Item> items = menu.visible();
        int labelWidth = Math.max(14, items.stream().mapToInt(i -> i.label().length()).max().orElse(14) + 2);
        int noteWidth = Math.max(8, items.stream().mapToInt(i -> i.note() == null ? 0 : i.note().length()).max().orElse(8) + 2);
        int hintsWidth = menu.hints().stream().mapToInt(h -> h.key().length() + h.label().length() + 5).sum();
        int content = Math.max(Math.max(64, hintsWidth), Math.max(menu.header().stream().mapToInt(l -> Math.min(90, l.text().length())).max().orElse(0),
                labelWidth + noteWidth + 4 + items.stream().mapToInt(i -> i.detail() == null ? 0 : i.detail().length()).max().orElse(0)));
        int width = Math.min(screen.width() - 8, Math.min(120, content + 6));
        List<DialogLine> description = new ArrayList<>();
        for (DialogLine line : menu.description()) {
            Paint.wrap(line.text(), width - 6).forEach(text -> description.add(new DialogLine(line.tone(), text)));
        }
        List<DialogLine> header = new ArrayList<>();
        for (DialogLine line : menu.header()) {
            Paint.wrap(line.text(), width - 6).forEach(text -> header.add(new DialogLine(line.tone(), text)));
        }
        int fixed = header.size() + (menu.filterable() ? 2 : 1) + 1 + Math.max(3, description.size()) + 4;
        int rows = Math.max(1, Math.min(Math.max(1, items.size()), Math.min(12, screen.height() - 4 - fixed)));
        int height = Math.min(screen.height() - 4, fixed + rows);
        Rect area = new Rect(screen.x() + (screen.width() - width) / 2, screen.y() + (screen.height() - height) / 2, width, height);
        Rect inner = modal(paint, area, menu.title());
        int y = inner.y() + 1;
        for (DialogLine line : header) {
            paint.text(inner.x(), y++, Paint.ellipsis(line.text(), inner.width()),
                    line.tone() == null ? theme.text() : theme.state(line.tone()), inner.width());
        }
        if (menu.filterable()) {
            paint.spans(inner.x(), y++, inner.width(),
                    Span.styled(theme.glyphs().unicode() ? "⌕ " : "> ", theme.fg(Theme.ACCENT)),
                    Span.styled(menu.filter(), theme.fg(Theme.TEXT).bold()),
                    Span.styled(theme.glyphs().unicode() ? "▏" : "_", theme.fg(Theme.SPARK)),
                    Span.styled(menu.filter().isEmpty() ? "type to filter" : "", theme.muted()));
        }
        y++;
        int selected = menu.selectedIndex();
        int offset = Math.max(0, selected - rows + 1);
        if (items.isEmpty()) {
            paint.text(inner.x() + 2, y, menu.filterable() && !menu.filter().isBlank()
                    ? "no match; enter creates " + menu.filter().trim() : "nothing to choose", theme.subtext(), inner.width() - 2);
        }
        for (int i = offset; i < items.size() && i - offset < rows; i++) {
            Menu.Item item = items.get(i);
            int row = y + i - offset;
            boolean isSelected = i == selected;
            if (isSelected) {
                paint.buffer().setStyle(new Rect(inner.x() - 1, row, inner.width() + 2, 1), theme.selection());
                paint.text(inner.x() - 1, row, theme.glyphs().marker(), theme.fg(Theme.SPARK), 1);
            }
            Tone tone = item.tone() == null ? Tone.IDLE : item.tone();
            paint.text(inner.x() + 1, row, theme.glyphs().symbol(tone), theme.state(tone), 2);
            paint.text(inner.x() + 3, row, Paint.pad(item.label(), labelWidth),
                    !item.enabled() ? theme.muted() : isSelected ? theme.fg(Theme.TEXT).bold() : theme.text(), labelWidth);
            int detailWidth = inner.width() - 3 - labelWidth - noteWidth;
            if (item.detail() != null && detailWidth > 4) {
                paint.text(inner.x() + 3 + labelWidth, row, Paint.ellipsis(item.detail(), detailWidth - 1), theme.subtext(), detailWidth - 1);
            }
            if (item.note() != null && !item.note().isEmpty()) {
                paint.right(inner.x(), row, inner.width(), Line.from(Span.styled(item.note(), theme.state(tone))));
            }
        }
        y += rows + 1;
        paint.text(inner.x(), y - 1, (theme.glyphs().unicode() ? "─" : "-").repeat(inner.width()), theme.fg(Theme.SURFACE1), inner.width());
        for (DialogLine line : description) {
            if (y >= inner.bottom() - 2) {
                break;
            }
            paint.text(inner.x(), y++, Paint.ellipsis(line.text(), inner.width()),
                    line.tone() == null ? theme.text() : theme.state(line.tone()), inner.width());
        }
        List<Span> keys = new ArrayList<>();
        for (Menu.Hint hint : menu.hints()) {
            keys.addAll(theme.keyChip(hint.key(), hint.label()));
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
                {"u", "update the branch: ff, merge, rebase or reset"},
                {"r", "the same, from the base branch, rebase selected"},
                {"U", "fast-forward every branch behind its upstream"},
                {"s", "switch branch (park or carry your changes)"},
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
