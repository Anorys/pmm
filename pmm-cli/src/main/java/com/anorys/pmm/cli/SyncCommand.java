package com.anorys.pmm.cli;

import com.anorys.pmm.cli.render.Table;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.git.BranchProtection;
import com.anorys.pmm.git.GitClient;
import com.anorys.pmm.git.GitClient.Operation;
import com.anorys.pmm.git.GitException;
import com.anorys.pmm.git.Rebaser;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.git.StatusCollector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "sync", mixinStandardHelpOptions = true,
        description = {
                "Fetches every monitored repository and says what each branch needs to catch up with its base",
                "branch: nothing, a fast-forward, or a rebase, with the files a rebase would conflict on",
                "(predicted with git merge-tree, without touching the working tree)."})
public class SyncCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--repo", paramLabel = "<name>", description = "Only these repositories (repeatable).")
    List<String> repositories = new ArrayList<>();

    @Option(names = "--no-fetch", description = "Do not fetch; use the last fetched state.")
    boolean noFetch;

    @Option(names = "--fast-forward",
            description = "Fast-forward the branches that have no commit of their own and a clean working tree.")
    boolean fastForward;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        List<RepositoryConfig> selected = Selection.repositories(spec, config, repositories);
        SystemProcessRunner runner = new SystemProcessRunner();
        Rebaser rebaser = new Rebaser(config, runner, Clock.systemDefaultZone());
        BranchProtection protection = new BranchProtection(config);

        List<RepositoryStatus> statuses = new StatusCollector(runner).collect(config, selected, !noFetch);
        Table table = new Table("REPO", "BRANCH", "BASE", "AHEAD", "BEHIND", "NEXT STEP").numeric(3, 4);
        List<String> commands = new ArrayList<>();
        boolean failed = false;
        for (RepositoryStatus status : statuses) {
            if (!status.readable() || status.baseMissing()) {
                table.row(status.name(), "-", status.baseRef(), "-", "-", status.readable() ? "base missing" : "unavailable");
                continue;
            }
            String branch = status.tree().detached() ? "(detached)" : status.tree().branch();
            String step;
            if (status.behind() == 0) {
                step = "up to date";
            } else if (status.canFastForward()) {
                step = "fast-forward";
                if (fastForward) {
                    String done = fastForward(status);
                    failed |= !done.equals("fast-forwarded");
                    step = done;
                }
            } else if (status.tree().detached()) {
                step = "diverged; check out a branch first";
            } else if (protection.updateProtected(branch)) {
                step = "diverged; " + branch + " is update-protected, update it by hand";
            } else if (rebaser.isProtected(branch) && !status.baseRef().equals(status.tree().upstream())) {
                step = "diverged; " + branch + " is push-protected, merge rather than rebase";
                commands.add("pmm update " + status.name() + " --from base --strategy merge");
            } else {
                step = prediction(status);
                commands.add("pmm rebase " + status.name());
            }
            table.row(status.name(), branch, status.baseRef(), String.valueOf(status.ahead()),
                    String.valueOf(status.behind()), step);
        }
        out.print(table.render());
        if (!commands.isEmpty()) {
            out.println();
            out.println("To catch up:");
            commands.forEach(c -> out.println("  " + c));
        }
        if (!fastForward && statuses.stream().anyMatch(RepositoryStatus::canFastForward)) {
            out.println();
            out.println("To fast-forward the branches that can be: pmm sync --fast-forward");
        }
        out.flush();
        return failed ? ExitCodes.ERRORS : ExitCodes.OK;
    }

    private static String prediction(RepositoryStatus status) {
        GitClient git = new GitClient(new SystemProcessRunner(), status.path());
        Optional<List<String>> conflicts = git.predictConflicts(status.baseRef(), "HEAD");
        if (conflicts.isEmpty()) {
            return "rebase (conflicts not predictable: needs git 2.38+)";
        }
        return conflicts.get().isEmpty() ? "rebase, no conflict expected"
                : "rebase, conflicts expected in " + String.join(", ", conflicts.get());
    }

    private static String fastForward(RepositoryStatus status) {
        if (status.tree().operation() != Operation.NONE || !status.tree().clean()) {
            return "fast-forward skipped: uncommitted changes";
        }
        try {
            new GitClient(new SystemProcessRunner(), status.path()).fastForward(status.baseRef());
            return "fast-forwarded";
        } catch (GitException e) {
            return "fast-forward failed: " + e.getMessage();
        }
    }
}
