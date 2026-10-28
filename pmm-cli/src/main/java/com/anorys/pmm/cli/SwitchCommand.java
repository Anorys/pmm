package com.anorys.pmm.cli;

import com.anorys.pmm.cli.render.Table;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.git.BranchSwitcher;
import com.anorys.pmm.git.BranchSwitcher.Branch;
import com.anorys.pmm.git.BranchSwitcher.Outcome;
import com.anorys.pmm.git.BranchSwitcher.Result;
import com.anorys.pmm.git.GitException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.Console;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "switch", mixinStandardHelpOptions = true,
        description = {
                "Switches every repository (or those of --repo) to a branch: a local one, or a remote one that gets a",
                "tracking copy, or with --create a new one from the base branch. Repositories without the branch are",
                "skipped. Uncommitted changes are parked (stashed with the branch left, restored when coming back to",
                "it) or carried to the new branch: --stash, else branches.stashOnSwitch. Without a branch, lists them."})
public class SwitchCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "<branch>", arity = "0..1", description = "The branch to switch to; omit it to list the branches.")
    String branch;

    @Option(names = "--repo", paramLabel = "<name>", description = "Only these repositories (repeatable).")
    List<String> repositories = new ArrayList<>();

    @Option(names = "--stash", paramLabel = "<mode>",
            description = "park, carry or never; default: branches.stashOnSwitch (ask: asks on a console).")
    String stash;

    @Option(names = "--create", description = "Create the branch from the base branch where it does not exist.")
    boolean create;

    @Option(names = "--no-restore", description = "Leave the changes parked with the branch in the stash.")
    boolean noRestore;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        List<RepositoryConfig> selected = Selection.repositories(spec, config, repositories);
        BranchSwitcher switcher = new BranchSwitcher(config, new SystemProcessRunner());
        if (branch == null) {
            return list(switcher, selected, out);
        }
        StashMode mode = config.branches().stashOnSwitch();
        if (stash != null) {
            try {
                mode = StashMode.valueOf(stash.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ParameterException(spec.commandLine(), "--stash must be park, carry or never, not '" + stash + "'");
            }
        }
        int exitCode = ExitCodes.OK;
        int switched = 0;
        for (RepositoryConfig repository : selected) {
            Result result;
            try {
                result = switcher.switchTo(repository, branch, mode, !noRestore, create);
                if (result.outcome() == Outcome.REFUSED && mode == StashMode.ASK && result.message().contains("uncommitted change")) {
                    StashMode answer = ask(out, repository.name(), result.message());
                    if (answer != null) {
                        result = switcher.switchTo(repository, branch, answer, !noRestore, create);
                    }
                }
            } catch (GitException e) {
                out.println(repository.name() + ": failed: " + e.getMessage());
                exitCode = ExitCodes.ERRORS;
                continue;
            }
            boolean missing = result.outcome() == Outcome.REFUSED && result.message().startsWith("no branch ");
            if (missing && repositories.isEmpty()) {
                out.println(repository.name() + ": skipped, " + result.message());
                continue;
            }
            out.println(repository.name() + ": " + switch (result.outcome()) {
                case SWITCHED, ALREADY -> result.message();
                case REFUSED -> "refused: " + result.message();
                case FAILED -> "failed: " + result.message();
            });
            if (result.stashed() != null) {
                out.println("  " + result.stashed());
            }
            if (result.restored()) {
                out.println("  the changes parked with " + branch + " are back");
            }
            if (result.warning() != null) {
                out.println("  warning: " + result.warning());
            }
            if (result.outcome() == Outcome.SWITCHED || result.outcome() == Outcome.ALREADY) {
                switched++;
            } else {
                exitCode = ExitCodes.ERRORS;
            }
        }
        if (switched == 0 && exitCode == ExitCodes.OK) {
            out.println("No repository has a branch " + branch + "; create it with --create");
            exitCode = ExitCodes.ERRORS;
        }
        out.flush();
        return exitCode;
    }

    private static StashMode ask(PrintWriter out, String repository, String message) {
        Console console = System.console();
        if (console == null) {
            out.println(repository + ": " + message + " (use --stash park or --stash carry)");
            return null;
        }
        out.flush();
        String answer = console.readLine("%s: %s. [p]ark, [c]arry or [a]bort? ", repository, message);
        String a = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        return a.startsWith("p") ? StashMode.PARK : a.startsWith("c") ? StashMode.CARRY : null;
    }

    private static int list(BranchSwitcher switcher, List<RepositoryConfig> selected, PrintWriter out) {
        Table table = new Table("REPO", "BRANCH", "UPSTREAM", "STATE");
        for (RepositoryConfig repository : selected) {
            List<Branch> branches;
            try {
                branches = switcher.branches(repository);
            } catch (GitException e) {
                table.row(repository.name(), "-", "-", "unavailable: " + e.getMessage());
                continue;
            }
            for (Branch b : branches) {
                List<String> state = new ArrayList<>();
                if (b.current()) {
                    state.add("current");
                }
                if (!b.local()) {
                    state.add("remote only");
                }
                if (!b.track().isEmpty()) {
                    state.add(b.track());
                }
                if (b.parked()) {
                    state.add("parked changes");
                }
                table.row(repository.name(), (b.current() ? "* " : "  ") + b.name(), b.upstream() == null ? "-" : b.upstream(),
                        String.join(", ", state));
            }
        }
        out.print(table.render());
        out.flush();
        return ExitCodes.OK;
    }
}
