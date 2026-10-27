package com.anorys.pmm.cli;

import com.anorys.pmm.build.BuildRunner.Options;
import com.anorys.pmm.cli.render.FindingsRenderer;
import com.anorys.pmm.cli.report.DoctorReport;
import com.anorys.pmm.cli.report.HtmlReport;
import com.anorys.pmm.cli.report.JUnitReport;
import com.anorys.pmm.cli.report.JsonReport;
import com.anorys.pmm.core.process.ProcessException;
import com.anorys.pmm.config.CheckSetting;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.git.GitChecks;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.git.StatusCollector;
import com.anorys.pmm.maven.WorkspaceScanner;
import com.anorys.pmm.maven.WorkspaceScanner.ScanResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "doctor", mixinStandardHelpOptions = true,
        description = {
                "Runs every enabled check: dependency cycles, git status of every repository (after a fetch),",
                "dependencies against Nexus and, with --build, the builds. The exit code sums it up for CI."})
public class DoctorCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--no-fetch", description = "Do not fetch; use the last fetched state.")
    boolean noFetch;

    @Option(names = "--offline", description = "Do not query Nexus; implies --no-fetch.")
    boolean offline;

    @Option(names = "--build", description = "Also build every repository (see 'pmm build').")
    boolean build;

    @Option(names = "--json", paramLabel = "<file>", description = "Also write the report as JSON.")
    Path json;

    @Option(names = "--junit", paramLabel = "<file>",
            description = "Also write the report as JUnit XML (one test per repository and category of checks).")
    Path junit;

    @Option(names = "--html", paramLabel = "<file>", description = "Also write the report as a self-contained HTML page.")
    Path html;

    @Option(names = "--strict", description = "Exit with code 1 when there are warnings.")
    boolean strict;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        SystemProcessRunner runner = new SystemProcessRunner();
        boolean fetch = !noFetch && !offline;

        List<Finding> findings = new ArrayList<>();
        ScanResult scan = WorkspaceScanner.scan(config);
        findings.addAll(cycles(config, scan));

        List<RepositoryStatus> statuses = new StatusCollector(runner).collect(config, config.monitoredRepositories(), fetch);
        List<Finding> gitFindings = new GitChecks(config, Clock.systemDefaultZone()).evaluate(statuses);
        findings.addAll(gitFindings);

        DependencyRun.Result deps = DependencyRun.run(config, scan.workspace(), offline, runner);
        findings.addAll(deps.findings());

        BuildRun.Result builds = null;
        if (build) {
            Options options = new Options(config.build().effectiveParallelRepos(), config.build().cache(), offline);
            builds = BuildRun.run(config, scan.workspace(), config.monitoredRepositories(), options, runner, out);
            out.println();
            findings.addAll(builds.findings());
        }

        out.println("Branches" + (fetch ? "" : " (not fetched)"));
        out.print(StatusCommand.table(statuses, gitFindings));
        out.println();
        out.println("Dependencies between repositories");
        deps.notes().forEach(note -> out.println(note));
        out.print(DependencyRun.table(deps.report().usages()));
        out.println();
        if (builds != null) {
            out.println("Builds");
            out.print(BuildRun.table(builds.results(), config.workspace().root()));
            out.println();
            BuildRun.printBlockers(builds.blockers(), out);
        }
        out.print(FindingsRenderer.render(findings, spec.commandLine().getColorScheme().ansi()::string));
        out.println();
        out.println(FindingsRenderer.summary(findings));
        out.flush();
        ScanWarnings.print(spec.commandLine(), scan.warnings());
        int exitCode = ScanWarnings.exitCode(ExitCodes.fromFindings(findings, strict), scan.warnings(), strict);

        if (json != null || junit != null || html != null) {
            DoctorReport report = new DoctorReport(VersionProvider.version(), Instant.now(), config.workspace().root(),
                    fetch, exitCode, statuses, deps.report().usages(),
                    builds == null ? List.of() : builds.results(), builds == null ? List.of() : builds.blockers(),
                    findings, deps.notes(), scan.warnings());
            write(json, JsonReport.render(report), out);
            write(junit, JUnitReport.render(report), out);
            write(html, HtmlReport.render(report), out);
            out.flush();
        }
        return exitCode;
    }

    private static void write(Path file, String content, PrintWriter out) {
        if (file == null) {
            return;
        }
        try {
            Path absolute = file.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(absolute, content, StandardCharsets.UTF_8);
            out.println("Report written: " + absolute);
        } catch (IOException e) {
            throw new ProcessException("cannot write the report " + file + ": " + e.getMessage(), e);
        }
    }

    private static List<Finding> cycles(PmmConfig config, ScanResult scan) {
        CheckSetting setting = config.check(KnownChecks.GRAPH_CYCLE);
        if (!setting.enabled()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (Set<String> cycle : scan.workspace().repositoryGraph().cycles()) {
            findings.add(new Finding(KnownChecks.GRAPH_CYCLE, setting.severity(), null,
                    "repositories depend on each other: " + String.join(" <-> ", cycle),
                    "run 'pmm graph' to see which artifacts form the cycle"));
        }
        return findings;
    }
}
