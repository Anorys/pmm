package com.anorys.pmm.tui.view;

import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.theme.Theme;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;

/** The bottom bar: the keys that work on the current tab, as chips. */
final class FooterView {

    private FooterView() {
    }

    static void render(Paint paint, Rect area, DashboardState state) {
        Theme theme = paint.theme();
        paint.fill(area, Theme.CRUST);
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(" ", theme.bg(Theme.CRUST)));
        spans.addAll(theme.keyChip(theme.glyphs().unicode() ? "↑↓" : "up/dn", "select"));
        spans.addAll(theme.keyChip("f", "fetch"));
        spans.addAll(theme.keyChip("b", "build all"));
        spans.addAll(theme.keyChip("B", "build this"));
        spans.addAll(theme.keyChip("r", "rebase"));
        spans.addAll(theme.keyChip("d", "deps"));
        spans.addAll(theme.keyChip("?", "help"));
        spans.addAll(theme.keyChip("q", "quit"));
        paint.spans(area.x(), area.y(), area.width(), spans);
    }
}
