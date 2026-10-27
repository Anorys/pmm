package com.anorys.pmm.cli;

import com.anorys.pmm.cli.render.FindingsRenderer;
import com.anorys.pmm.cli.render.Table;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.git.GitChecks;
import com.anorys.pmm.git.GitClient.Operation;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.git.StatusCollector;
import com.anorys.pmm.git.WorkingTreeStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "status",
        description = {
                "Fetches every monitored repository (in parallel) and shows its branch, how far it is ahead of and",
                "behind its base branch, and its local changes. Branches that are too far behind are flagged."})
public class StatusCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--no-fetch", description = "Do not fetch; compare with the last fetched state.")
    boolean noFetch;

    @Option(names = "--repo", paramLabel = "<name>", description = "Only these repositories (repeatable).")
    List<String> repositories = new ArrayList<>();

    @Option(names = "--strict", description = "Exit with code 1 when there are warnings.")
    boolean strict;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        List<RepositoryConfig> selected = select(config);

        List<RepositoryStatus> statuses = new StatusCollector(new SystemProcessRunner()).collect(config, selected, !noFetch);
        List<Finding> findings = new GitChecks(config, Clock.systemDefaultZone()).evaluate(statuses);

        out.print(table(statuses, findings));
        out.println();
        out.print(FindingsRenderer.render(findings, spec.commandLine().getColorScheme().ansi()::string));
        out.println();
        out.println(FindingsRenderer.summary(findings) + (noFetch ? " (not fetched)" : ""));
        out.flush();
        return ExitCodes.fromFindings(findings, strict);
    }

    private List<RepositoryConfig> select(PmmConfig config) {
        if (repositories.isEmpty()) {
            return config.monitoredRepositories();
        }
        List<RepositoryConfig> selected = new ArrayList<>();
        for (String name : repositories) {
            selected.add(config.repository(name).orElseThrow(() -> new ParameterException(spec.commandLine(),
                    "Unknown repository '" + name + "'; known: "
                            + String.join(", ", config.repositories().stream().map(RepositoryConfig::name).toList()))));
        }
        return selected;
    }

    static String table(List<RepositoryStatus> statuses, List<Finding> findings) {
        Table table = new Table("REPO", "BRANCH", "BASE", "AHEAD", "BEHIND", "STATE", "LOCAL").numeric(3, 4);
        for (RepositoryStatus status : statuses) {
            if (!status.readable()) {
                table.row(status.name(), "-", status.baseRef(), "-", "-", "unavailable", "-");
                continue;
            }
            WorkingTreeStatus tree = status.tree();
            String branch = tree.detached() ? "(detached)" : tree.branch();
            boolean known = !status.baseMissing();
            table.row(status.name(), branch, status.baseRef(),
                    known ? String.valueOf(status.ahead()) : "-",
                    known ? String.valueOf(status.behind()) : "-",
                    state(status, findings), local(tree));
        }
        return table.render();
    }

    static String state(RepositoryStatus status, List<Finding> findings) {
        if (status.baseMissing()) {
            return "base missing";
        }
        if (status.behind() == 0) {
            return "up to date";
        }
        if (status.canFastForward()) {
            return "fast-forward";
        }
        boolean flagged = findings.stream().anyMatch(f -> status.name().equals(f.repository())
                && f.checkId().equals("git.behind") && f.severity().compareTo(Severity.WARN) >= 0);
        return flagged ? "rebase advised" : "behind";
    }

    static String local(WorkingTreeStatus tree) {
        if (tree.operation() != Operation.NONE) {
            return tree.operation().name().toLowerCase(Locale.ROOT).replace('_', '-') + " in progress";
        }
        if (tree.clean()) {
            return "clean";
        }
        int count = tree.changed() + tree.untracked() + tree.conflicted();
        return count + (count == 1 ? " change" : " changes");
    }
}
