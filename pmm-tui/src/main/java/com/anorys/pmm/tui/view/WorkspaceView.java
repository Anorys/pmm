package com.anorys.pmm.tui.view;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.build.Failure;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import dev.tamboui.layout.Rect;
import dev.tamboui.text.Span;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Tab 1: one row per repository, and the selected repository in detail on the right. */
final class WorkspaceView {

    private WorkspaceView() {
    }

    static void render(Paint paint, Rect area, DashboardState state, Instant now) {
        boolean side = area.width() >= 110;
        int detailWidth = side ? Math.max(44, area.width() * 2 / 5) : 0;
        Rect tableArea = new Rect(area.x(), area.y(), area.width() - detailWidth, area.height());
        table(paint, tableArea, state, now);
        if (side) {
            Rect detail = new Rect(area.right() - detailWidth, area.y(), detailWidth, area.height());
            state.selected().ifPresent(repo -> detail(paint, detail, state, repo, now));
        }
    }

    private static void table(Paint paint, Rect area, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        Rect inner = paint.panel(area, "Repositories", state.repositories().size() + " monitored", true);
        int nameWidth = Math.max(10, state.repositories().stream().mapToInt(r -> r.name().length()).max().orElse(8));
        int[] widths = {2, nameWidth, 16, 10, 8, 11, 5};
        int flexible = inner.width() - (widths[0] + widths[1] + widths[3] + widths[4] + widths[5] + widths[6] + 6);
        widths[2] = Math.max(6, flexible);
        String[] headers = {"", "REPOSITORY", "BRANCH", "VS BASE", "LOCAL", "BUILD", "DEPS"};

        int x = inner.x();
        for (int i = 0; i < headers.length; i++) {
            paint.text(x, inner.y(), headers[i], theme.header(), widths[i]);
            x += widths[i] + 1;
        }

        boolean depsChecked = state.depsCheckedAt() != null;
        int rows = inner.height() - 1;
        int selected = state.selectedIndex();
        int offset = Math.max(0, selected - rows + 1);
        for (int i = offset; i < state.repositories().size() && i - offset < rows; i++) {
            RepoState repo = state.repositories().get(i);
            int y = inner.y() + 1 + i - offset;
            boolean isSelected = i == selected;
            if (isSelected) {
                paint.buffer().setStyle(new Rect(inner.x() - 1, y, inner.width() + 2, 1), theme.selection());
                paint.text(inner.x() - 1, y, theme.glyphs().marker(), theme.fg(Theme.SPARK), 1);
            }
            Tone tone = repo.tone();
            String dot = tone == Tone.RUNNING ? theme.glyphs().spinner(state.tick()) : theme.glyphs().symbol(tone);
            List<List<Span>> cells = List.of(
                    List.of(Span.styled(dot, theme.state(tone))),
                    List.of(Span.styled(repo.name(), isSelected ? theme.fg(Theme.TEXT).bold() : theme.text())),
                    Cells.branch(theme, repo),
                    Cells.sync(theme, repo),
                    Cells.local(theme, repo),
                    Cells.build(theme, repo, state.tick(), now),
                    Cells.deps(theme, repo, depsChecked));
            x = inner.x();
            for (int c = 0; c < cells.size(); c++) {
                paint.spans(x, y, widths[c], cells.get(c));
                x += widths[c] + 1;
            }
        }
        if (state.repositories().isEmpty()) {
            paint.text(inner.x(), inner.y() + 2, "No repository in pmm.yaml.", theme.muted(), inner.width());
        }
        int free = inner.bottom() - (inner.y() + 1 + state.repositories().size()) - 1;
        if (free >= state.repositories().size() + 4) {
            summary(paint, inner, state, inner.bottom() - state.repositories().size() - 4, nameWidth);
        }
    }

    /** Below the table when there is room: the count of each state, and the build times side by side. */
    private static void summary(Paint paint, Rect inner, DashboardState state, int y, int nameWidth) {
        Theme theme = paint.theme();
        int x = inner.x();
        int w = inner.width();
        List<Span> counts = new ArrayList<>();
        for (Tone tone : new Tone[]{Tone.OK, Tone.WARN, Tone.ERROR, Tone.BLOCKED, Tone.RUNNING}) {
            long n = state.repositories().stream().filter(r -> r.tone() == tone).count();
            if (n > 0) {
                counts.add(theme.badge(tone, n + " " + word(tone)));
                counts.add(Span.raw("  "));
            }
        }
        paint.spans(x, y, w, counts);
        y += 2;
        y = section(paint, x, y, w, "LAST BUILD TIMES");
        long longest = state.repositories().stream().map(WorkspaceView::buildMillis).mapToLong(Long::longValue).max().orElse(0);
        int barWidth = Math.max(10, w - nameWidth - 12);
        for (RepoState repo : state.repositories()) {
            long millis = buildMillis(repo);
            paint.text(x, y, repo.name(), theme.subtext(), nameWidth);
            if (millis > 0) {
                double ratio = longest == 0 ? 0 : (double) millis / longest;
                int cells = Math.max(1, (int) Math.round(ratio * barWidth));
                List<Span> bar = new ArrayList<>();
                String block = theme.glyphs().unicode() ? "■" : "#";
                for (int i = 0; i < cells; i++) {
                    bar.add(Span.styled(block, theme.fg(Theme.brand(i / (double) barWidth * 0.5))));
                }
                paint.spans(x + nameWidth + 1, y, barWidth, bar);
                paint.text(x + nameWidth + 2 + cells, y, Format.duration(java.time.Duration.ofMillis(millis)), theme.muted(), 8);
            } else {
                paint.text(x + nameWidth + 1, y, "–", theme.muted(), 2);
            }
            y++;
        }
    }

    private static long buildMillis(RepoState repo) {
        if (repo.build() != null && repo.build().status().ok()) {
            return repo.build().duration().toMillis();
        }
        return repo.expectedBuild() == null ? 0 : repo.expectedBuild().toMillis();
    }

    static void detail(Paint paint, Rect area, DashboardState state, RepoState repo, Instant now) {
        Theme theme = paint.theme();
        Rect inner = paint.panel(area, repo.name(), repo.modules() > 0 ? repo.modules() + (repo.modules() == 1 ? " module" : " modules") : null, false);
        int w = inner.width();
        int x = inner.x();
        int y = inner.y();
        int bottom = inner.bottom();

        Tone tone = repo.tone();
        paint.spans(x, y, w,
                theme.badge(tone, word(tone)),
                Span.styled("  " + Objects.toString(repo.version(), "?"), theme.subtext()));
        y++;
        paint.text(x, y++, Paint.ellipsisStart(repo.path().toString(), w), theme.muted(), w);
        y++;

        // Git
        y = section(paint, x, y, w, "GIT");
        RepositoryStatus git = repo.git();
        if (git == null) {
            paint.text(x, y++, "reading…", theme.muted(), w);
        } else if (!git.readable()) {
            paint.text(x, y++, Paint.ellipsis(git.error(), w), theme.state(Tone.ERROR), w);
        } else {
            List<Span> line = new ArrayList<>(Cells.branch(theme, repo));
            line.add(Span.styled("  →  ", theme.muted()));
            line.add(Span.styled(git.baseRef(), theme.subtext()));
            paint.spans(x, y++, w, line);
            if (!git.baseMissing()) {
                List<Span> counts = new ArrayList<>();
                counts.add(Span.styled(theme.glyphs().ahead() + git.ahead() + " ahead", git.ahead() > 0 ? theme.fg(Theme.BLUE) : theme.muted()));
                counts.add(Span.styled("   ", theme.muted()));
                counts.add(Span.styled(theme.glyphs().behind() + git.behind() + " behind", git.behind() > 0 ? theme.state(Tone.WARN) : theme.muted()));
                if (git.behindSince() != null) {
                    counts.add(Span.styled("  (" + Format.age(git.behindSince(), now) + ")", theme.muted()));
                }
                paint.spans(x, y++, w, counts);
            }
            List<Span> local = new ArrayList<>(Cells.local(theme, repo));
            local.add(Span.styled(git.tree().clean() ? " working tree" : " local changes", theme.muted()));
            paint.spans(x, y++, w, local);
            if (git.fetchError() != null) {
                paint.text(x, y++, Paint.ellipsis(theme.glyphs().warn() + " fetch failed: " + git.fetchError(), w), theme.state(Tone.WARN), w);
            }
        }
        y++;

        // Build
        if (y < bottom - 2) {
            y = section(paint, x, y, w, "BUILD");
            List<Span> build = new ArrayList<>(Cells.build(theme, repo, state.tick(), now));
            BuildResult result = repo.build();
            if (repo.buildPhase() == RepoState.BuildPhase.RUNNING && repo.currentModule() != null) {
                build.add(Span.styled("  " + repo.currentModule() + " " + repo.moduleIndex() + "/" + repo.moduleCount(), theme.subtext()));
            } else if (result != null && result.status() == BuildResult.Status.SUCCESS) {
                build.add(Span.styled("  compiled", theme.muted()));
            } else if (repo.buildPhase() == RepoState.BuildPhase.NONE) {
                build.set(0, Span.styled("not built yet · press b", theme.muted()));
            }
            paint.spans(x, y++, w, build);
            Failure failure = result == null ? null : result.failure();
            if (failure != null && y < bottom) {
                String detail = failure.kind() == Failure.Kind.MISSING_ARTIFACT
                        ? "missing " + String.join(", ", failure.missing().stream().map(Object::toString).toList())
                        : Objects.toString(failure.detail(), failure.kind().name().toLowerCase(java.util.Locale.ROOT));
                paint.text(x, y++, Paint.ellipsis(detail, w), theme.subtext(), w);
            }
            y++;
        }

        // Findings
        if (y < bottom - 1) {
            List<Finding> findings = repo.findings().stream().filter(f -> f.severity() != Severity.OFF).toList();
            y = section(paint, x, y, w, "FINDINGS" + (findings.isEmpty() ? "" : "  " + findings.size()));
            if (findings.isEmpty()) {
                paint.spans(x, y++, w, theme.stateSpan(Tone.OK, "nothing to report"));
            }
            for (Finding finding : findings) {
                if (y >= bottom) {
                    break;
                }
                Tone t = Tone.of(finding.severity());
                paint.spans(x, y++, w,
                        Span.styled(theme.glyphs().symbol(t) + " ", theme.state(t)),
                        Span.styled(Paint.ellipsis(finding.message(), w - 2), theme.text()));
                if (finding.suggestion() != null && y < bottom) {
                    paint.spans(x, y++, w,
                            Span.styled("  → ", theme.fg(Theme.ACCENT)),
                            Span.styled(Paint.ellipsis(finding.suggestion(), w - 4), theme.subtext()));
                }
            }
        }
    }

    static int section(Paint paint, int x, int y, int w, String title) {
        Theme theme = paint.theme();
        paint.text(x, y, title, theme.header(), w);
        String rule = theme.glyphs().unicode() ? "─" : "-";
        int start = title.length() + 1;
        if (w > start) {
            paint.text(x + start, y, rule.repeat(w - start), theme.fg(Theme.SURFACE1), w - start);
        }
        return y + 1;
    }

    static String word(Tone tone) {
        return switch (tone) {
            case OK -> "healthy";
            case WARN -> "warning";
            case ERROR -> "error";
            case BLOCKED -> "blocked";
            case INFO -> "info";
            case RUNNING -> "building";
            case IDLE -> "loading";
        };
    }
}
