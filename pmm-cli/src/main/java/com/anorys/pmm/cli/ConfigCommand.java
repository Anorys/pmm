package com.anorys.pmm.cli;

import com.anorys.pmm.config.ConfigWriter;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.WorkspaceWarnings;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "config",
        description = {
                "Validates the configuration and prints the effective values (pmm.yaml + pmm.local.yaml + defaults).",
                "Warnings about missing repositories are printed but do not fail the command, unless --strict is given."})
public class ConfigCommand implements Callable<Integer> {

    @ParentCommand
    PmmCommand pmm;

    @Spec
    CommandSpec spec;

    @Option(names = "--validate", description = "Only validate; do not print the effective configuration.")
    boolean validateOnly;

    @Option(names = "--strict", description = "Exit with code 1 when there are warnings.")
    boolean strict;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        PmmConfig config = pmm.loadConfig();

        if (validateOnly) {
            out.printf("%s is valid: %d repositories, %d monitored.%n", config.file(),
                    config.repositories().size(), config.monitoredRepositories().size());
        } else {
            out.printf("# Effective configuration from %s%n", config.file());
            out.print(ConfigWriter.toYaml(config));
        }
        out.flush();

        List<String> warnings = WorkspaceWarnings.of(config);
        for (String warning : warnings) {
            err.println(spec.commandLine().getColorScheme().text("@|yellow warning:|@ " + warning));
        }
        err.flush();
        return strict && !warnings.isEmpty() ? ExitCodes.WARNINGS : ExitCodes.OK;
    }
}
