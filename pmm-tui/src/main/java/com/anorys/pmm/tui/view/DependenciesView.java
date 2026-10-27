package com.anorys.pmm.tui.view;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.maven.DependencyChecks.State;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Span;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Tab 3: what each repository uses from the others against Nexus, and who must release what. */
final class DependenciesView {

    private DependenciesView() {
    }

    static void render(Paint paint, Rect area, DashboardState state, Instant now) {
        int sideWidth = area.width() >= 110 ? Math.max(44, area.width() * 2 / 5) : 0;
        usages(paint, new Rect(area.x(), area.y(), area.width() - sideWidth, area.height()), state, now);
        if (sideWidth > 0) {
            releases(paint, new Rect(area.right() - sideWidth, area.y(), sideWidth, area.height()), state);
        }
    }

    private static void usages(Paint paint, Rect area, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        String note = state.depsCheckedAt() == null ? "not checked yet" : "checked " + Format.ago(state.depsCheckedAt(), now);
        Rect inner = paint.panel(area, "Internal versions", note, true);
        List<Row> rows = new ArrayList<>();
        int selected = state.selectedIndex();
        for (int i = 0; i < state.repositories().size(); i++) {
            RepoState repo = state.repositories().get(i);
            for (InternalUsage usage : repo.usages()) {
                rows.add(new Row(i, usage));
            }
        }
        int consumerWidth = Math.max(8, state.repositories().stream().mapToInt(r -> r.name().length()).max().orElse(8));
        int artifactWidth = Math.max(10, rows.stream().mapToInt(r -> r.usage().artifact().artifactId().length()).max().orElse(10));
        int[] widths = {consumerWidth, artifactWidth, 12, 12, 16};
        String[] headers = {"REPOSITORY", "USES", "VERSION", "LATEST", "STATE"};
        int x = inner.x() + 2;
        for (int i = 0; i < headers.length; i++) {
            paint.text(x, inner.y(), headers[i], theme.header(), widths[i]);
            x += widths[i] + 2;
        }
        if (rows.isEmpty()) {
            paint.text(inner.x(), inner.y() + 2, state.depsCheckedAt() == null ? "Checking…"
                    : "No repository uses an artifact of another one.", theme.muted(), inner.width());
            return;
        }
        int visible = inner.height() - 1;
        int firstOfSelected = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).repo() == selected) {
                firstOfSelected = i;
                break;
            }
        }
        int offset = Math.max(0, Math.min(firstOfSelected - 2, rows.size() - visible));
        int previous = -1;
        for (int i = offset; i < rows.size() && i - offset < visible; i++) {
            Row row = rows.get(i);
            InternalUsage usage = row.usage();
            int y = inner.y() + 1 + i - offset;
            boolean mine = row.repo() == selected;
            if (mine) {
                paint.buffer().setStyle(new Rect(inner.x() - 1, y, inner.width() + 2, 1), theme.selection());
                paint.text(inner.x() - 1, y, theme.glyphs().marker(), theme.fg(Theme.SPARK), 1);
            }
            Tone tone = tone(usage.state());
            paint.text(inner.x(), y, theme.glyphs().symbol(tone), theme.state(tone), 1);
            x = inner.x() + 2;
            String consumer = row.repo() == previous ? "" : usage.consumer();
            previous = row.repo();
            paint.text(x, y, consumer, mine ? theme.fg(Theme.TEXT).bold() : theme.text(), widths[0]);
            x += widths[0] + 2;
            paint.text(x, y, usage.artifact().artifactId(), theme.subtext(), widths[1]);
            x += widths[1] + 2;
            paint.text(x, y, usage.version(), tone == Tone.OK ? theme.text() : theme.state(tone), widths[2]);
            x += widths[2] + 2;
            String latest = usage.latest() != null ? usage.latest() : usage.state() == State.UNKNOWN ? "?" : "–";
            paint.text(x, y, latest, theme.subtext(), widths[3]);
            x += widths[3] + 2;
            paint.text(x, y, usage.state().label(), theme.state(tone), widths[4]);
        }
    }

    private record Row(int repo, InternalUsage usage) {
    }

    private static void releases(Paint paint, Rect area, DashboardState state) {
        Theme theme = paint.theme();
        Rect inner = paint.panel(area, "To unblock", null, false);
        int x = inner.x();
        int w = inner.width();
        int y = inner.y();
        int bottom = inner.bottom();

        y = WorkspaceView.section(paint, x, y, w, "BLOCKERS");
        if (state.blockers().isEmpty()) {
            paint.spans(x, y++, w, theme.stateSpan(Tone.OK, "no build is blocked by a missing release"));
        }
        for (Blocker blocker : state.blockers()) {
            paint.spans(x, y++, w,
                    theme.badge(Tone.BLOCKED, "release"),
                    Span.styled(" " + blocker.producer(), theme.fg(Theme.TEXT).bold()),
                    Span.styled(" " + String.join(", ", blocker.artifacts().stream().sorted().toList()), theme.subtext()));
            paint.spans(x, y++, w,
                    Span.styled("  unblocks ", theme.muted()),
                    Span.styled(String.join(", ", blocker.blocked().stream().sorted().toList()), theme.text()));
        }
        y++;

        List<Finding> findings = state.repositories().stream().flatMap(r -> r.depFindings().stream())
                .filter(f -> f.severity() != Severity.OFF)
                .sorted(Comparator.comparing(Finding::severity).reversed())
                .toList();
        if (y < bottom - 1) {
            y = WorkspaceView.section(paint, x, y, w, "FINDINGS" + (findings.isEmpty() ? "" : "  " + findings.size()));
            for (Finding finding : findings) {
                if (y >= bottom - 2) {
                    break;
                }
                Tone tone = Tone.of(finding.severity());
                paint.spans(x, y++, w,
                        Span.styled(theme.glyphs().symbol(tone) + " ", theme.state(tone)),
                        Span.styled(Objects.toString(finding.repository(), "-") + " ", theme.fg(Theme.TEXT).bold()),
                        Span.styled(Paint.ellipsis(finding.message(), Math.max(1, w - 3 - Objects.toString(finding.repository(), "-").length())), theme.text()));
                if (finding.suggestion() != null && y < bottom - 2) {
                    paint.spans(x, y++, w, Span.styled("  → ", theme.fg(Theme.ACCENT)),
                            Span.styled(Paint.ellipsis(finding.suggestion(), w - 4), theme.subtext()));
                }
            }
        }
        if (!state.notes().isEmpty()) {
            int noteY = bottom - Math.min(state.notes().size(), 2);
            for (String note : state.notes().subList(0, Math.min(2, state.notes().size()))) {
                paint.text(x, noteY++, Paint.ellipsis(note, w), theme.muted(), w);
            }
        }
    }

    static Tone tone(State state) {
        return switch (state) {
            case OK -> Tone.OK;
            case UPDATE_AVAILABLE -> Tone.INFO;
            case OUTDATED -> Tone.WARN;
            case NOT_RELEASED, SNAPSHOT -> Tone.ERROR;
            case UNKNOWN -> Tone.IDLE;
        };
    }
}
