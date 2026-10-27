package com.anorys.pmm.cli;

import com.anorys.pmm.cli.render.FindingsRenderer;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.maven.WorkspaceScanner;
import com.anorys.pmm.maven.WorkspaceScanner.ScanResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

@Command(name = "deps",
        description = {
                "Checks the versions the repositories use from each other against Nexus: unreleased versions",
                "(blockers), SNAPSHOTs, outdated releases, divergent external versions, and upstream repositories",
                "with commits since their last release (from the last fetched state; 'pmm status' fetches)."})
public class DepsCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--offline", description = "Do not query Nexus; only the checks that need no Nexus run.")
    boolean offline;

    @Option(names = "--strict", description = "Exit with code 1 when there are warnings.")
    boolean strict;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        ScanResult scan = WorkspaceScanner.scan(config);
        DependencyRun.Result result = DependencyRun.run(config, scan.workspace(), offline, new SystemProcessRunner());

        result.notes().forEach(note -> out.println(note));
        out.println();
        out.print(DependencyRun.table(result.report().usages()));
        out.println();
        out.print(FindingsRenderer.render(result.findings(), spec.commandLine().getColorScheme().ansi()::string));
        out.println();
        out.println(FindingsRenderer.summary(result.findings()));
        out.flush();
        ScanWarnings.print(spec.commandLine(), scan.warnings());
        return ScanWarnings.exitCode(ExitCodes.fromFindings(result.findings(), strict), scan.warnings(), strict);
    }
}
