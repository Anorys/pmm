package com.anorys.pmm.cli;

import com.anorys.pmm.build.BuildChecks;
import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.build.BuildRunner;
import com.anorys.pmm.build.BuildRunner.Options;
import com.anorys.pmm.build.MavenCommand;
import com.anorys.pmm.cli.render.Table;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.ExecutableResolver;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.core.workspace.Workspace;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** The builds shared by {@code pmm build} and {@code pmm doctor --build}, with progress lines as they finish. */
final class BuildRun {

    record Result(List<BuildResult> results, List<Finding> findings, List<Blocker> blockers) {
    }

    private BuildRun() {
    }

    static Result run(PmmConfig config, Workspace workspace, List<RepositoryConfig> repositories, Options options,
                      ProcessRunner runner, PrintWriter progress) {
        int total = repositories.size();
        AtomicInteger done = new AtomicInteger();
        int width = repositories.stream().mapToInt(r -> r.name().length()).max().orElse(1);
        progress.println("Building " + total + " repositor" + (total == 1 ? "y" : "ies") + ", "
                + options.parallel() + " at a time" + (options.useCache() ? "" : ", without the cache") + "...");
        progress.flush();
        BuildRunner builder = new BuildRunner(config, workspace, runner,
                new MavenCommand(config, ExecutableResolver.fromEnvironment()), Clock.systemUTC());
        List<BuildResult> results = builder.build(repositories, options, new BuildRunner.Listener() {
            @Override
            public void finished(BuildResult result) {
                synchronized (progress) {
                    String name = result.repository();
                    progress.println("  [" + done.incrementAndGet() + "/" + total + "] " + name
                            + " ".repeat(width - name.length()) + "  " + (result.status() == BuildResult.Status.SKIPPED
                            ? label(result) : String.format(Locale.ROOT, "%-11s  %s", label(result), duration(result.duration()))));
                    progress.flush();
                }
            }
        });
        return new Result(results, new BuildChecks(config).evaluate(results), BuildChecks.blockers(results));
    }

    static String table(List<BuildResult> results, Path root) {
        Table table = new Table("REPO", "RESULT", "TIME", "LOG");
        for (BuildResult result : results) {
            table.row(result.repository(), label(result),
                    result.status() == BuildResult.Status.SKIPPED ? "-" : duration(result.duration()),
                    result.log() == null ? "-" : root.relativize(result.log()).toString());
        }
        return table.render();
    }

    /** "platform-core blocks billing, reporting (release core-api 3.1.5)". */
    static void printBlockers(List<Blocker> blockers, PrintWriter out) {
        if (blockers.isEmpty()) {
            return;
        }
        out.println("Blockers:");
        for (Blocker blocker : blockers) {
            out.println("  " + blocker.producer() + " blocks " + String.join(", ", blocker.blocked().stream().sorted().toList())
                    + " (to release: " + String.join(", ", blocker.artifacts().stream().sorted().toList()) + ")");
        }
        out.println();
    }

    static String label(BuildResult result) {
        return switch (result.status()) {
            case SUCCESS -> "ok";
            case CACHED -> "ok (cached)";
            case SKIPPED -> "skipped: " + result.reason();
            default -> result.status().name().toLowerCase(Locale.ROOT);
        };
    }

    static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds >= 60 ? seconds / 60 + "m" + String.format(Locale.ROOT, "%02d", seconds % 60) + "s" : seconds + "s";
    }
}
