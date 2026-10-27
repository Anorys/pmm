package com.anorys.pmm.cli;

import com.anorys.pmm.config.ConfigException;
import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;

@Command(name = "pmm",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Health checks for a multi-repository Maven workspace: dependencies, builds, blockers and branch freshness.",
        subcommands = {InitCommand.class, ConfigCommand.class, GraphCommand.class, HelpCommand.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:healthy",
                "1:warnings only (with --strict)",
                "2:errors found",
                "3:tool error (configuration, arguments, missing executable)"})
public class PmmCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"}, scope = ScopeType.INHERIT, paramLabel = "<file>",
            description = "Configuration file. Default: pmm.yaml in the current directory or its parents.")
    Path configFile;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT, description = "Print more details.")
    boolean verbose;

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing command");
    }

    /** Loads the configuration given with --config, or found from the current directory. */
    PmmConfig loadConfig() {
        Path file = configFile != null
                ? configFile
                : ConfigLoader.locate(Path.of("")).orElseThrow(() -> new ConfigException(null, List.of(
                        "no " + ConfigLoader.CONFIG_FILE + " found in " + Path.of("").toAbsolutePath()
                                + " or its parent directories; run 'pmm init' to create one")));
        return ConfigLoader.load(file);
    }
}
