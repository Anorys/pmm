package com.anorys.pmm.tui.view;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.GitClient.Operation;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.git.WorkingTreeStatus;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.theme.Glyphs;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import dev.tamboui.text.Span;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The short colored values of a repository row, shared by the tables and the detail panel. */
final class Cells {

    private Cells() {
    }

    static List<Span> branch(Theme theme, RepoState repo) {
        RepositoryStatus git = repo.git();
        if (git == null) {
            return List.of(Span.styled("…", theme.muted()));
        }
        if (!git.readable()) {
            return List.of(Span.styled(git.error().startsWith("not cloned") ? "not cloned" : "unreadable", theme.state(Tone.ERROR)));
        }
        WorkingTreeStatus tree = git.tree();
        return List.of(Span.styled(tree.detached() ? "(detached)" : tree.branch(), theme.subtext()));
    }

    /** "↑2 ↓12", or "✔ synced". */
    static List<Span> sync(Theme theme, RepoState repo) {
        RepositoryStatus git = repo.git();
        Glyphs g = theme.glyphs();
        if (git == null || !git.readable()) {
            return List.of(Span.styled("–", theme.muted()));
        }
        if (git.baseMissing()) {
            return List.of(Span.styled(g.error() + " no base", theme.state(Tone.ERROR)));
        }
        if (git.ahead() == 0 && git.behind() == 0) {
            return List.of(Span.styled(g.ok() + " synced", theme.state(Tone.OK)));
        }
        List<Span> spans = new ArrayList<>();
        if (git.ahead() > 0) {
            spans.add(Span.styled(g.ahead() + git.ahead(), theme.fg(Theme.BLUE)));
        }
        if (git.behind() > 0) {
            if (!spans.isEmpty()) {
                spans.add(Span.raw(" "));
            }
            boolean flagged = repo.gitFindings().stream().anyMatch(f -> f.checkId().equals("git.behind")
                    && f.severity().compareTo(Severity.WARN) >= 0);
            spans.add(Span.styled(g.behind() + git.behind(), flagged ? theme.state(Tone.WARN) : theme.subtext()));
        }
        return spans;
    }

    static List<Span> local(Theme theme, RepoState repo) {
        RepositoryStatus git = repo.git();
        if (git == null || !git.readable()) {
            return List.of(Span.styled("–", theme.muted()));
        }
        WorkingTreeStatus tree = git.tree();
        if (tree.operation() != Operation.NONE) {
            return List.of(Span.styled(theme.glyphs().error() + " " + tree.operation().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    theme.state(Tone.ERROR)));
        }
        if (tree.clean()) {
            return List.of(Span.styled("clean", theme.muted()));
        }
        int count = tree.changed() + tree.untracked() + tree.conflicted();
        return List.of(Span.styled(theme.glyphs().changes() + " " + count, theme.state(Tone.WARN)));
    }

    static List<Span> build(Theme theme, RepoState repo, long tick, Instant now) {
        Glyphs g = theme.glyphs();
        switch (repo.buildPhase()) {
            case QUEUED:
                return List.of(Span.styled(g.idle() + " queued", theme.muted()));
            case RUNNING:
                return List.of(Span.styled(g.spinner(tick) + " " + Format.duration(Duration.between(repo.buildStarted(), now)),
                        theme.state(Tone.RUNNING)));
            case NONE:
                return List.of(Span.styled("–", theme.muted()));
            default:
                break;
        }
        BuildResult result = repo.build();
        return switch (result.status()) {
            case SUCCESS -> List.of(Span.styled(g.ok() + " " + Format.duration(result.duration()), theme.state(Tone.OK)));
            case CACHED -> List.of(Span.styled(g.ok() + " cached", theme.state(Tone.OK).dim()));
            case FAILED -> List.of(Span.styled(g.error() + " failed", theme.state(Tone.ERROR)));
            case BLOCKED -> List.of(Span.styled(g.blocked() + " blocked", theme.state(Tone.BLOCKED)));
            case SKIPPED -> List.of(Span.styled(g.idle() + " skipped", theme.muted()));
        };
    }

    /** The worst dependency finding: "✔", "▲ 2", "✘ 1". */
    static List<Span> deps(Theme theme, RepoState repo, boolean checked) {
        if (!checked) {
            return List.of(Span.styled("…", theme.muted()));
        }
        List<Finding> findings = repo.depFindings().stream().filter(f -> f.severity().compareTo(Severity.WARN) >= 0).toList();
        if (findings.isEmpty()) {
            return List.of(Span.styled(theme.glyphs().ok(), theme.state(Tone.OK)));
        }
        Severity worst = Finding.highest(findings);
        long count = findings.stream().filter(f -> f.severity() == worst).count();
        Tone tone = Tone.of(worst);
        return List.of(Span.styled(theme.glyphs().symbol(tone) + " " + count, theme.state(tone)));
    }

    static int width(List<Span> spans) {
        return spans.stream().mapToInt(Span::width).sum();
    }
}
