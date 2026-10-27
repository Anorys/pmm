package com.anorys.pmm.cli;

import com.anorys.pmm.build.BuildRunner.Options;
import com.anorys.pmm.cli.render.FindingsRenderer;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.maven.WorkspaceScanner;
import com.anorys.pmm.maven.WorkspaceScanner.ScanResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "build", mixinStandardHelpOptions = true,
        description = {
                "Checks that every monitored repository compiles (maven.compileGoal), several at a time, and explains",
                "failures: compilation errors, or an artifact missing from Nexus, which names the repository that",
                "blocks the build. Repositories whose sources did not change since their last successful build are",
                "skipped. Logs go to .pmm/logs/<repo>.log."})
public class BuildCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--repo", paramLabel = "<name>", description = "Only these repositories (repeatable).")
    List<String> repositories = new ArrayList<>();

    @Option(names = {"-j", "--parallel"}, paramLabel = "<n>",
            description = "Repositories built at the same time. Default: build.parallelRepos.")
    Integer parallel;

    @Option(names = "--no-cache", description = "Build even the repositories that did not change.")
    boolean noCache;

    @Option(names = "--offline", description = "Run Maven offline (-o).")
    boolean offline;

    @Option(names = "--strict", description = "Exit with code 1 when there are warnings.")
    boolean strict;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PmmConfig config = pmm.loadConfig();
        List<RepositoryConfig> selected = select(config);
        if (parallel != null && parallel < 1) {
            throw new ParameterException(spec.commandLine(), "--parallel must be at least 1");
        }
        ScanResult scan = WorkspaceScanner.scan(config);
        Options options = new Options(parallel != null ? parallel : config.build().effectiveParallelRepos(),
                !noCache && config.build().cache(), offline);

        BuildRun.Result result = BuildRun.run(config, scan.workspace(), selected, options, new SystemProcessRunner(), out);

        out.println();
        out.print(BuildRun.table(result.results(), config.workspace().root()));
        out.println();
        BuildRun.printBlockers(result.blockers(), out);
        out.print(FindingsRenderer.render(result.findings(), spec.commandLine().getColorScheme().ansi()::string));
        out.println();
        out.println(FindingsRenderer.summary(result.findings()));
        out.flush();
        ScanWarnings.print(spec.commandLine(), scan.warnings());
        return ScanWarnings.exitCode(ExitCodes.fromFindings(result.findings(), strict), scan.warnings(), strict);
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
}
