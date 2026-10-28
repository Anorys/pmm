package com.anorys.pmm.cli;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.git.BranchUpdater;
import com.anorys.pmm.git.BranchUpdater.Outcome;
import com.anorys.pmm.git.BranchUpdater.Plan;
import com.anorys.pmm.git.BranchUpdater.Result;
import com.anorys.pmm.git.BranchUpdater.Target;
import com.anorys.pmm.git.GitException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = {
                "Brings the commits of the upstream (origin/<branch>) or of the base branch into the current branch",
                "of each repository, with the chosen strategy: ff, merge, rebase, reset-soft, reset-mixed or reset-hard.",
                "Without --strategy, the one of branches.strategies (default: ff on push-protected branches, rebase",
                "elsewhere). The branch is saved under refs/pmm/backup/ first, merges and rebases that conflict are",
                "aborted, and a hard reset that drops work asks first (or --yes). --dry-run shows every strategy."})
public class UpdateCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<repo>", arity = "0..*", description = "Repositories to update; all monitored ones when none is named.")
    List<String> repositories = new ArrayList<>();

    @Option(names = {"-s", "--strategy"}, paramLabel = "<strategy>",
            description = "ff, merge, rebase, reset-soft, reset-mixed or reset-hard.")
    String strategy;

    @Option(names = "--from", paramLabel = "<target>", defaultValue = "upstream",
            description = "upstream (origin/<branch>, the default) or base (the base branch, origin/develop).")
    String from;

    @Option(names = "--no-fetch", description = "Do not fetch first; use the last fetched state.")
    boolean noFetch;

    @Option(names = "--dry-run", description = "Only show what each strategy would do.")
    boolean dryRun;

    @Option(names = "--autostash", description = "Stash uncommitted changes around a fast-forward, merge or rebase.")
    boolean autostash;

    @Option(names = {"-y", "--yes"}, description = "Do not ask before a hard reset that drops commits or changes.")
    boolean yes;

    @Option(names = "--push", negatable = true, description = {
            "Push the updated branch to its upstream with --force-with-lease (--no-push: never); never a push-protected one.",
            "Default: rebase.push in pmm.yaml (ask, never or always)."})
    Boolean push;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        UpdateStrategy chosen = strategy == null ? null : UpdateStrategy.parse(strategy);
        if (strategy != null && chosen == null) {
            throw new ParameterException(spec.commandLine(), "Unknown strategy '" + strategy + "'; use one of " + UpdateStrategy.ids());
        }
        Target target = switch (from.toLowerCase(Locale.ROOT)) {
            case "upstream" -> Target.UPSTREAM;
            case "base" -> Target.BASE;
            default -> throw new ParameterException(spec.commandLine(), "--from must be upstream or base, not '" + from + "'");
        };
        List<RepositoryConfig> selected = Selection.repositories(spec, config, repositories);
        BranchUpdater updater = new BranchUpdater(config, new SystemProcessRunner(), Clock.systemDefaultZone());

        int exitCode = ExitCodes.OK;
        for (RepositoryConfig repository : selected) {
            Plan plan = updater.plan(repository, target, !noFetch, autostash);
            out.println(describe(plan));
            if (plan.fetchError() != null) {
                out.println("  warning: " + plan.fetchError() + "; using the last fetched state");
            }
            if (!plan.allowed()) {
                out.println("  refused: " + String.join("; ", plan.refusals()));
                exitCode = ExitCodes.ERRORS;
                continue;
            }
            UpdateStrategy use = chosen != null ? chosen : plan.suggested();
            if (dryRun) {
                plan.options().forEach(o -> out.println(option(o, o.strategy() == use)));
                continue;
            }
            if (plan.upToDate()) {
                out.println("  up to date");
                continue;
            }
            BranchUpdater.Option option = plan.option(use);
            if (option.allowed() && option.danger() && !yes
                    && !PushChoice.confirm(out, use.label() + " " + option.effect() + ". Continue?")) {
                out.println("  not updated: " + use.label() + " " + option.effect() + "; confirm with --yes");
                exitCode = ExitCodes.ERRORS;
                continue;
            }
            Result result = updater.update(plan, use);
            out.println("  " + label(result.outcome()) + ": " + result.message());
            if (!result.conflicts().isEmpty()) {
                out.println("  conflicts: " + String.join(", ", result.conflicts()));
            }
            if (result.outcome() == Outcome.UPDATED && result.backupRef() != null) {
                out.println("  backup: " + result.backupRef() + " (undo: git reset --hard " + result.backupRef() + ")");
            }
            if (result.wipRef() != null) {
                out.println("  uncommitted changes saved in " + result.wipRef() + " (restore: git stash apply " + result.wipRef() + ")");
            }
            if (result.outcome() == Outcome.UPDATED && updater.pushable(result) && use.rewrites()) {
                pushIfWanted(config, updater, result, out);
            }
            if (result.outcome() != Outcome.UPDATED && result.outcome() != Outcome.UP_TO_DATE) {
                exitCode = ExitCodes.ERRORS;
            }
            out.flush();
        }
        out.flush();
        return exitCode;
    }

    private void pushIfWanted(PmmConfig config, BranchUpdater updater, Result result, PrintWriter out) {
        String branch = result.plan().branch();
        String remote = config.workspace().remote();
        if (!PushChoice.wanted(push, config, branch, out)) {
            out.println("  not pushed; to push: git push --force-with-lease " + remote + " " + branch);
            return;
        }
        try {
            String notPushed = updater.push(result);
            out.println(notPushed == null ? "  pushed to " + remote + "/" + branch + " (with lease)" : "  not pushed: " + notPushed);
        } catch (GitException e) {
            out.println("  not pushed: " + e.getMessage()
                    + " (someone else pushed to " + remote + "/" + branch + "? fetch and check before pushing)");
        }
    }

    static String describe(Plan plan) {
        StringBuilder text = new StringBuilder(plan.repository().name());
        if (plan.branch() != null && plan.targetRef() != null) {
            text.append(": ").append(plan.branch()).append(" <- ").append(plan.targetRef());
            if (plan.allowed()) {
                text.append(", ").append(plan.ahead()).append(" ahead, ").append(plan.behind()).append(" behind");
                if (plan.uncommitted() > 0) {
                    text.append(", ").append(plan.uncommitted()).append(" uncommitted");
                }
                if (plan.pushProtected()) {
                    text.append(" (push-protected)");
                }
            }
        }
        return text.toString();
    }

    private static String option(BranchUpdater.Option option, boolean selected) {
        String head = (selected ? "  * " : "    ") + String.format(Locale.ROOT, "%-12s ", option.strategy().id());
        if (!option.allowed()) {
            return head + "refused: " + option.refusal();
        }
        return head + option.effect() + (option.warning() == null ? "" : " (" + option.warning() + ")");
    }

    private static String label(Outcome outcome) {
        return switch (outcome) {
            case UPDATED -> "updated";
            case UP_TO_DATE -> "up to date";
            case CONFLICTS -> "not updated";
            case REFUSED -> "refused";
            case FAILED -> "failed";
        };
    }
}
