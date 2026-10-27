package com.anorys.pmm.cli;

import com.anorys.pmm.cli.render.GraphRenderer;
import com.anorys.pmm.config.CheckSetting;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.maven.WorkspaceScanner;
import com.anorys.pmm.maven.WorkspaceScanner.ScanResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "graph", mixinStandardHelpOptions = true,
        description = {
                "Reads the pom.xml files of the monitored repositories and shows how they depend on each other:",
                "build order, the artifacts and versions each repository uses from the others, and cycles."})
public class GraphCommand implements Callable<Integer> {

    enum Format { text, mermaid, dot }

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = {"-f", "--format"}, defaultValue = "text", paramLabel = "<format>",
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    Format format;

    @Option(names = "--modules", description = "Mermaid and DOT: draw modules instead of repositories.")
    boolean modules;

    @Option(names = "--repo", paramLabel = "<name>",
            description = "Text: show only what this repository depends on and what depends on it.")
    String repository;

    @Option(names = "--strict", description = "Exit with code 1 when there are warnings.")
    boolean strict;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        PmmConfig config = pmm.loadConfig();
        ScanResult scan = WorkspaceScanner.scan(config);
        Workspace workspace = scan.workspace();

        if (repository != null && workspace.repository(repository).isEmpty()) {
            throw new ParameterException(spec.commandLine(), "Unknown repository '" + repository
                    + "'; known: " + String.join(", ", workspace.repositories().stream().map(r -> r.name()).toList()));
        }

        out.print(switch (format) {
            case text -> repository == null ? GraphRenderer.text(workspace) : GraphRenderer.focus(workspace, repository);
            case mermaid -> GraphRenderer.mermaid(workspace, modules);
            case dot -> GraphRenderer.dot(workspace, modules);
        });
        out.flush();

        ScanWarnings.print(spec.commandLine(), scan.warnings());
        List<Set<String>> cycles = workspace.repositoryGraph().cycles();
        CheckSetting cycleCheck = config.check(KnownChecks.GRAPH_CYCLE);
        if (!cycles.isEmpty() && cycleCheck.enabled()) {
            String label = cycleCheck.severity() == Severity.ERROR ? "@|red error:|@ " : "@|yellow warning:|@ ";
            for (Set<String> cycle : cycles) {
                err.println(spec.commandLine().getColorScheme().text(label)
                        + "repositories depend on each other: " + String.join(" <-> ", cycle));
            }
        }
        err.flush();
        return exitCode(cycles.isEmpty() ? null : cycleCheck.severity(), !scan.warnings().isEmpty());
    }

    int exitCode(Severity cycleSeverity, boolean hasWarnings) {
        if (cycleSeverity == Severity.ERROR) {
            return ExitCodes.ERRORS;
        }
        boolean warned = hasWarnings || cycleSeverity == Severity.WARN;
        return strict && warned ? ExitCodes.WARNINGS : ExitCodes.OK;
    }
}
