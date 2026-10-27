package com.anorys.pmm.tui.view;

import com.anorys.pmm.core.graph.DirectedGraph;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.core.workspace.Workspace.RepositoryDependency;
import com.anorys.pmm.core.workspace.Workspace.Usage;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.Span;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tab 4: the repositories in build layers, upstream on the left. The selected repository is outlined in
 * orange, what it needs in green and what needs it in blue.
 */
final class GraphView {

    private GraphView() {
    }

    static void render(Paint paint, Rect area, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        Workspace workspace = state.workspace();
        int sideWidth = area.width() >= 110 ? 36 : 0;
        Rect graphArea = new Rect(area.x(), area.y(), area.width() - sideWidth, area.height());
        Rect inner = paint.panel(graphArea, "Dependency graph", "upstream → downstream", true);
        if (workspace == null) {
            paint.text(inner.x(), inner.y() + 1, "Reading the POMs…", theme.muted(), inner.width());
            return;
        }
        DirectedGraph<String> graph = workspace.repositoryGraph();
        String selected = state.selected().map(RepoState::name).orElse(null);
        Set<String> upstream = selected == null ? Set.of() : graph.allSuccessors(selected);
        Set<String> downstream = selected == null ? Set.of() : graph.allPredecessors(selected);

        List<Set<String>> layers = graph.layers();
        int columns = layers.size();
        int boxWidth = Math.max(12, workspace.repositories().stream().mapToInt(r -> r.name().length()).max().orElse(10) + 5);
        int gap = columns <= 1 ? 0 : Math.min(8, Math.max(2, (inner.width() - columns * boxWidth) / Math.max(1, columns - 1)));
        if (columns * boxWidth + (columns - 1) * gap > inner.width()) {
            boxWidth = Math.max(8, (inner.width() - (columns - 1) * 2) / Math.max(1, columns));
            gap = 2;
        }
        for (int c = 0; c < columns; c++) {
            List<String> names = new ArrayList<>(layers.get(c));
            names.sort(String::compareTo);
            int x = inner.x() + c * (boxWidth + gap);
            paint.text(x, inner.y(), Paint.pad("LAYER " + (c + 1), boxWidth), theme.header(), boxWidth);
            int y = inner.y() + 2;
            for (String name : names) {
                if (y + 3 > inner.bottom()) {
                    break;
                }
                RepoState repo = state.repository(name).orElse(null);
                Tone tone = repo == null ? Tone.IDLE : repo.tone();
                Color border;
                boolean dim = false;
                if (name.equals(selected)) {
                    border = Theme.SPARK;
                } else if (upstream.contains(name)) {
                    border = Theme.ACCENT;
                } else if (downstream.contains(name)) {
                    border = Theme.BLUE;
                } else {
                    border = Theme.SURFACE1;
                    dim = selected != null;
                }
                Rect box = new Rect(x, y, boxWidth, 3);
                Rect boxInner = paint.frame(box, name.equals(selected) ? Theme.SURFACE0 : Theme.BASE, border);
                String symbol = tone == Tone.RUNNING ? theme.glyphs().spinner(state.tick()) : theme.glyphs().symbol(tone);
                paint.spans(boxInner.x() + 1, boxInner.y(), boxInner.width() - 1,
                        Span.styled(symbol + " ", theme.state(tone)),
                        Span.styled(Paint.ellipsis(name, boxInner.width() - 3),
                                dim ? theme.muted() : theme.fg(Theme.TEXT).bold()));
                if (c > 0) {
                    String arrow = gap >= 3 ? (theme.glyphs().unicode() ? "─▶" : "->") : (theme.glyphs().unicode() ? "▶" : ">");
                    Color arrowColor = name.equals(selected) || downstream.contains(name) && !upstream.contains(name)
                            ? (dim ? Theme.SURFACE1 : border) : Theme.SURFACE1;
                    paint.text(x - arrow.length() - 1, y + 1, arrow, theme.fg(arrowColor), arrow.length());
                }
                y += 3;
            }
        }
        // Legend
        paint.spans(inner.x(), inner.bottom() - 1, inner.width(),
                Span.styled("■ ", theme.fg(Theme.SPARK)), Span.styled("selected   ", theme.subtext()),
                Span.styled("■ ", theme.fg(Theme.ACCENT)), Span.styled("it needs   ", theme.subtext()),
                Span.styled("■ ", theme.fg(Theme.BLUE)), Span.styled("needs it", theme.subtext()));

        if (sideWidth > 0 && selected != null) {
            side(paint, new Rect(area.right() - sideWidth, area.y(), sideWidth, area.height()), workspace, graph, selected);
        }
    }

    private static void side(Paint paint, Rect area, Workspace workspace, DirectedGraph<String> graph, String selected) {
        Theme theme = paint.theme();
        Rect inner = paint.panel(area, selected, null, false);
        int x = inner.x();
        int w = inner.width();
        int y = inner.y();
        y = WorkspaceView.section(paint, x, y, w, "NEEDS");
        if (graph.successors(selected).isEmpty()) {
            paint.text(x, y++, "nothing in the workspace", theme.muted(), w);
        }
        for (RepositoryDependency dependency : workspace.repositoryDependencies()) {
            if (!dependency.consumer().equals(selected) || y >= inner.bottom() - 4) {
                continue;
            }
            paint.spans(x, y++, w, Span.styled("● ", theme.fg(Theme.ACCENT)), Span.styled(dependency.producer(), theme.fg(Theme.TEXT).bold()));
            for (Usage usage : dependency.usages()) {
                if (y >= inner.bottom() - 4) {
                    break;
                }
                paint.spans(x, y++, w, Span.styled("  " + usage.reference().artifact().artifactId() + " ", theme.subtext()),
                        Span.styled(java.util.Objects.toString(usage.reference().version(), "?"), theme.text()));
            }
        }
        y++;
        if (y < inner.bottom() - 2) {
            y = WorkspaceView.section(paint, x, y, w, "NEEDED BY");
            Set<String> direct = graph.predecessors(selected);
            Set<String> all = graph.allPredecessors(selected);
            if (all.isEmpty()) {
                paint.text(x, y++, "nothing in the workspace", theme.muted(), w);
            }
            for (String name : direct.stream().sorted().toList()) {
                if (y >= inner.bottom()) {
                    break;
                }
                paint.spans(x, y++, w, Span.styled("● ", theme.fg(Theme.BLUE)), Span.styled(name, theme.fg(Theme.TEXT).bold()));
            }
            long indirect = all.size() - direct.size();
            if (indirect > 0 && y < inner.bottom()) {
                paint.text(x, y, "+ " + indirect + " through them", theme.muted(), w);
            }
        }
    }
}
