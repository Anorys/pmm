package com.anorys.pmm.tui.view;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.model.RepoState.BuildPhase;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Tab 2: the builds, live: one progress bar per repository and the log of the selected one. */
final class BuildView {

    private BuildView() {
    }

    static void render(Paint paint, Rect area, DashboardState state, Instant now) {
        int listHeight = Math.min(state.repositories().size() + 3, Math.max(6, area.height() / 2 + 2));
        summary(paint, new Rect(area.x(), area.y(), area.width(), 4), state, now);
        list(paint, new Rect(area.x(), area.y() + 4, area.width(), listHeight), state, now);
        Rect logArea = new Rect(area.x(), area.y() + 4 + listHeight, area.width(), area.height() - 4 - listHeight);
        if (logArea.height() >= 4) {
            state.selected().ifPresent(repo -> log(paint, logArea, repo));
        }
    }

    private static void summary(Paint paint, Rect area, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        Rect inner = paint.panel(area, "Build", null, false);
        List<RepoState> repos = state.repositories();
        long done = repos.stream().filter(r -> r.buildPhase() == BuildPhase.DONE).count();
        long running = repos.stream().filter(r -> r.buildPhase() == BuildPhase.RUNNING).count();
        long queued = repos.stream().filter(r -> r.buildPhase() == BuildPhase.QUEUED).count();
        long failed = repos.stream().filter(r -> r.build() != null && !r.build().status().ok()
                && r.build().status() != BuildResult.Status.SKIPPED && r.buildPhase() == BuildPhase.DONE).count();
        long total = done + running + queued;
        int y = inner.y() + 1;
        if (total == 0) {
            paint.spans(inner.x(), y, inner.width(),
                    Span.styled("No build yet. ", theme.subtext()),
                    Span.styled("b", theme.fg(Theme.ACCENT).bold()),
                    Span.styled(" builds every repository, ", theme.subtext()),
                    Span.styled("B", theme.fg(Theme.ACCENT).bold()),
                    Span.styled(" the selected one.", theme.subtext()));
            return;
        }
        // Running builds count for the part they have done.
        double progress = done;
        for (RepoState repo : repos) {
            if (repo.buildPhase() == BuildPhase.RUNNING) {
                progress += ratio(repo, now);
            }
        }
        String label = String.format(Locale.ROOT, "%d/%d", done, total);
        paint.text(inner.x(), y, label, theme.fg(Theme.TEXT).bold(), 7);
        int barWidth = Math.max(10, inner.width() / 2 - 8);
        double phase = (state.tick() % 60) / 60.0;
        Color solid = running + queued == 0 ? (failed > 0 ? Theme.RED : Theme.GREEN) : null;
        paint.bar(inner.x() + 7, y, barWidth, progress / total, phase, solid);

        int x = inner.x() + 9 + barWidth;
        List<Span> counts = new java.util.ArrayList<>();
        if (running > 0) {
            counts.add(Span.styled(theme.glyphs().spinner(state.tick()) + " " + running + " running   ", theme.state(Tone.RUNNING)));
        }
        if (queued > 0) {
            counts.add(Span.styled(theme.glyphs().idle() + " " + queued + " queued   ", theme.muted()));
        }
        long ok = repos.stream().filter(r -> r.buildPhase() == BuildPhase.DONE && r.build().status().ok()).count();
        counts.add(Span.styled(theme.glyphs().ok() + " " + ok + " ok   ", theme.state(Tone.OK)));
        if (failed > 0) {
            counts.add(Span.styled(theme.glyphs().error() + " " + failed + " failed or blocked", theme.state(Tone.ERROR)));
        }
        paint.spans(x, y, inner.right() - x, counts);
    }

    private static void list(Paint paint, Rect area, DashboardState state, Instant now) {
        Theme theme = paint.theme();
        Rect inner = paint.panel(area, "Repositories", "upstream first", true);
        int nameWidth = Math.max(10, state.repositories().stream().mapToInt(r -> r.name().length()).max().orElse(8));
        int timeWidth = 15;
        int statusWidth = 12;
        int barWidth = Math.max(10, Math.min(40, inner.width() - nameWidth - timeWidth - statusWidth - 30));
        int detailX = inner.x() + 3 + nameWidth + 1 + barWidth + 2;
        int detailWidth = inner.right() - detailX - statusWidth - timeWidth - 2;

        int rows = inner.height();
        int selected = state.selectedIndex();
        int offset = Math.max(0, selected - rows + 1);
        for (int i = offset; i < state.repositories().size() && i - offset < rows; i++) {
            RepoState repo = state.repositories().get(i);
            int y = inner.y() + i - offset;
            if (i == selected) {
                paint.buffer().setStyle(new Rect(inner.x() - 1, y, inner.width() + 2, 1), theme.selection());
                paint.text(inner.x() - 1, y, theme.glyphs().marker(), theme.fg(Theme.SPARK), 1);
            }
            Tone tone = tone(repo);
            String symbol = repo.buildPhase() == BuildPhase.RUNNING ? theme.glyphs().spinner(state.tick() + i)
                    : theme.glyphs().symbol(tone);
            paint.text(inner.x(), y, symbol, theme.state(tone), 2);
            paint.text(inner.x() + 2, y, repo.name(), i == selected ? theme.fg(Theme.TEXT).bold() : theme.text(), nameWidth);

            int barX = inner.x() + 3 + nameWidth + 1;
            switch (repo.buildPhase()) {
                case RUNNING -> paint.bar(barX, y, barWidth, ratio(repo, now), (state.tick() + i * 7) % 60 / 60.0);
                case DONE -> paint.bar(barX, y, barWidth, 1, 0, theme.stateColor(tone));
                default -> paint.bar(barX, y, barWidth, 0, 0);
            }

            paint.text(detailX, y, Paint.ellipsis(detail(repo), detailWidth), theme.subtext(), detailWidth);
            int statusX = inner.right() - statusWidth - timeWidth;
            paint.spans(statusX, y, statusWidth, status(theme, repo));
            paint.right(inner.right() - timeWidth, y, timeWidth, time(theme, repo, now));
        }
    }

    private static void log(Paint paint, Rect area, RepoState repo) {
        Theme theme = paint.theme();
        String note = repo.build() != null && repo.build().log() != null ? repo.build().log().getFileName().toString()
                : ".pmm/logs/" + repo.name() + ".log";
        Rect inner = paint.panel(area, "Log " + theme.glyphs().separator() + " " + repo.name(), note, false);
        List<String> lines = repo.logTail();
        if (lines.isEmpty()) {
            String message = switch (repo.buildPhase()) {
                case NONE -> "Not built in this session.";
                case QUEUED -> "Waiting for a free build slot…";
                case RUNNING -> "Starting Maven…";
                case DONE -> repo.build().status() == BuildResult.Status.CACHED
                        ? "Not rebuilt: nothing changed since the last successful build."
                        : "The full output is in the log file.";
            };
            paint.text(inner.x(), inner.y(), message, theme.muted(), inner.width());
            return;
        }
        int start = Math.max(0, lines.size() - inner.height());
        int y = inner.y();
        for (int i = start; i < lines.size(); i++) {
            paint.line(inner.x(), y++, mavenLine(theme, lines.get(i)), inner.width());
        }
    }

    /** Maven output with its levels in color: [ERROR] red, [WARNING] amber, the reactor headers bright. */
    static Line mavenLine(Theme theme, String line) {
        if (line.startsWith("[ERROR]")) {
            return Line.from(Span.styled("[ERROR]", theme.state(Tone.ERROR).bold()), Span.styled(line.substring(7), theme.state(Tone.ERROR)));
        }
        if (line.startsWith("[WARNING]")) {
            return Line.from(Span.styled("[WARNING]", theme.state(Tone.WARN).bold()), Span.styled(line.substring(9), theme.subtext()));
        }
        if (line.startsWith("[INFO]")) {
            String rest = line.substring(6);
            Style style = rest.contains("BUILD SUCCESS") ? theme.state(Tone.OK).bold()
                    : rest.contains("BUILD FAILURE") ? theme.state(Tone.ERROR).bold()
                    : rest.trim().startsWith("Building ") || rest.contains("---") ? theme.text() : theme.muted();
            return Line.from(Span.styled("[INFO]", theme.fg(Theme.FACET_LIGHT)), Span.styled(rest, style));
        }
        return Line.from(Span.styled(line, theme.muted()));
    }

    private static String detail(RepoState repo) {
        return switch (repo.buildPhase()) {
            case RUNNING -> repo.currentModule() == null ? "starting…"
                    : repo.currentModule() + (repo.moduleCount() > 0 ? "  " + repo.moduleIndex() + "/" + repo.moduleCount() : "");
            case DONE -> {
                BuildResult result = repo.build();
                if (result.failure() != null) {
                    yield result.failure().kind() == com.anorys.pmm.build.Failure.Kind.MISSING_ARTIFACT
                            ? "needs " + String.join(", ", result.failure().missing().stream().map(Object::toString).toList())
                            : java.util.Objects.toString(result.failure().detail(), "");
                }
                yield result.status() == BuildResult.Status.SKIPPED ? java.util.Objects.toString(result.reason(), "") : "";
            }
            default -> "";
        };
    }

    private static List<Span> status(Theme theme, RepoState repo) {
        return switch (repo.buildPhase()) {
            case RUNNING -> List.of(Span.styled("building", theme.state(Tone.RUNNING)));
            case QUEUED -> List.of(Span.styled("queued", theme.muted()));
            case NONE -> List.of(Span.styled("–", theme.muted()));
            case DONE -> switch (repo.build().status()) {
                case SUCCESS -> List.of(theme.stateSpan(Tone.OK, "ok"));
                case CACHED -> List.of(Span.styled(theme.glyphs().ok() + " cached", theme.state(Tone.OK).dim()));
                case FAILED -> List.of(theme.stateSpan(Tone.ERROR, "failed"));
                case BLOCKED -> List.of(theme.stateSpan(Tone.BLOCKED, "blocked"));
                case SKIPPED -> List.of(Span.styled("skipped", theme.muted()));
            };
        };
    }

    private static Line time(Theme theme, RepoState repo, Instant now) {
        Duration expected = repo.expectedBuild();
        return switch (repo.buildPhase()) {
            case RUNNING -> Line.from(
                    Span.styled(Format.duration(Duration.between(repo.buildStarted(), now)), theme.text()),
                    Span.styled(expected == null ? "" : " / ~" + Format.duration(expected), theme.muted()));
            case QUEUED -> Line.from(Span.styled(expected == null ? "" : "~" + Format.duration(expected), theme.muted()));
            case DONE -> Line.from(Span.styled(Format.duration(repo.build().duration()), theme.subtext()));
            case NONE -> Line.from(Span.styled("", theme.muted()));
        };
    }

    static Tone tone(RepoState repo) {
        return switch (repo.buildPhase()) {
            case RUNNING -> Tone.RUNNING;
            case QUEUED, NONE -> Tone.IDLE;
            case DONE -> switch (repo.build().status()) {
                case SUCCESS, CACHED -> Tone.OK;
                case FAILED -> Tone.ERROR;
                case BLOCKED -> Tone.BLOCKED;
                case SKIPPED -> Tone.IDLE;
            };
        };
    }

    /**
     * How far a running build is: the reactor position when Maven printed one ("[6/9]"), else the time
     * spent against the last successful build; never shown as finished before it is.
     */
    static double ratio(RepoState repo, Instant now) {
        if (repo.moduleCount() > 0) {
            return Math.min(0.97, (repo.moduleIndex() - 0.5) / repo.moduleCount());
        }
        Duration expected = repo.expectedBuild();
        if (expected == null || expected.isZero() || repo.buildStarted() == null) {
            return 0.05;
        }
        return Math.min(0.95, Duration.between(repo.buildStarted(), now).toMillis() / (double) expected.toMillis());
    }
}
