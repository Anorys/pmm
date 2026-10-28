package com.anorys.pmm.cli;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.git.GitException;
import com.anorys.pmm.git.Rebaser;
import com.anorys.pmm.git.Rebaser.Outcome;
import com.anorys.pmm.git.Rebaser.Plan;
import com.anorys.pmm.git.Rebaser.Result;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "rebase", mixinStandardHelpOptions = true,
        description = {
                "Rebases the current branch of each named repository onto its base branch, safely: never an",
                "update-protected branch, never rewriting the published commits of a push-protected one, a clean",
                "working tree (or --autostash), a backup ref before, an abort on conflicts (unless --keep-conflicts),",
                "and a push with --force-with-lease only when allowed (rebase.push or --push), never of a push-protected",
                "branch. For other strategies (fast-forward, merge, reset), see 'pmm update'."})
public class RebaseCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<repo>", arity = "1..*", description = "Repositories to rebase.")
    List<String> repositories;

    @Option(names = "--no-fetch", description = "Do not fetch first; rebase onto the last fetched base branch.")
    boolean noFetch;

    @Option(names = "--dry-run", description = "Only say what would happen, including the predicted conflicts.")
    boolean dryRun;

    @Option(names = "--autostash", description = "Stash uncommitted changes before, and restore them after.")
    boolean autostash;

    @Option(names = "--keep-conflicts", description = "On conflicts, leave the rebase in progress to resolve them by hand.")
    boolean keepConflicts;

    @Option(names = "--push", negatable = true, description = {
            "Push the rebased branch to its upstream with --force-with-lease (--no-push: never).",
            "Default: rebase.push in pmm.yaml (ask, never or always)."})
    Boolean push;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        List<RepositoryConfig> selected = Selection.repositories(spec, config, repositories);
        Rebaser rebaser = new Rebaser(config, new SystemProcessRunner(), Clock.systemDefaultZone());

        int exitCode = ExitCodes.OK;
        for (RepositoryConfig repository : selected) {
            Plan plan = rebaser.plan(repository, !noFetch, autostash);
            out.println(describe(plan));
            if (plan.fetchError() != null) {
                out.println("  warning: " + plan.fetchError() + "; using the last fetched state");
            }
            if (dryRun) {
                if (!plan.allowed()) {
                    out.println("  would refuse: " + String.join("; ", plan.refusals()));
                    exitCode = ExitCodes.ERRORS;
                }
                continue;
            }
            Result result = rebaser.rebase(plan, autostash, keepConflicts);
            out.println("  " + label(result.outcome()) + ": " + result.message());
            if (!result.conflicts().isEmpty()) {
                out.println("  conflicts: " + String.join(", ", result.conflicts()));
            }
            if (result.backupRef() != null && result.outcome() == Outcome.REBASED) {
                out.println("  backup: " + result.backupRef() + " (undo: git reset --hard " + result.backupRef() + ")");
            }
            if (result.outcome() == Outcome.REBASED) {
                pushIfWanted(config, rebaser, result, out);
            } else if (result.outcome() != Outcome.UP_TO_DATE) {
                exitCode = ExitCodes.ERRORS;
            }
            out.flush();
        }
        out.flush();
        return exitCode;
    }

    private void pushIfWanted(PmmConfig config, Rebaser rebaser, Result result, PrintWriter out) {
        String branch = result.plan().branch();
        String remote = config.workspace().remote();
        String command = "git push --force-with-lease " + remote + " " + branch;
        if (rebaser.isProtected(branch)) {
            out.println("  not pushed: " + branch + " is push-protected (branches.pushProtected)");
            return;
        }
        boolean wanted = PushChoice.wanted(push, config, branch, out);
        if (!wanted) {
            out.println("  not pushed; to push: " + command);
            return;
        }
        try {
            String notPushed = rebaser.push(result);
            out.println(notPushed == null ? "  pushed to " + remote + "/" + branch + " (with lease)" : "  not pushed: " + notPushed);
        } catch (GitException e) {
            out.println("  not pushed: " + e.getMessage()
                    + " (someone else pushed to " + remote + "/" + branch + "? fetch and check before pushing)");
        }
    }

    static String describe(Plan plan) {
        StringBuilder text = new StringBuilder(plan.repository().name());
        if (plan.branch() != null) {
            text.append(": ").append(plan.branch()).append(", ").append(plan.ahead()).append(" ahead, ")
                    .append(plan.behind()).append(" behind ").append(plan.baseRef());
        }
        if (plan.behind() > 0 && plan.ahead() > 0) {
            text.append(plan.predictedConflicts() == null ? "; conflicts not predictable (git 2.38+ needed)"
                    : plan.predictedConflicts().isEmpty() ? "; no conflict expected"
                    : "; conflicts expected in " + String.join(", ", plan.predictedConflicts()));
        }
        return text.toString();
    }

    private static String label(Outcome outcome) {
        return switch (outcome) {
            case REBASED -> "rebased";
            case UP_TO_DATE -> "up to date";
            case CONFLICTS -> "not rebased";
            case REFUSED -> "refused";
        };
    }
}
