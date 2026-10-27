package com.anorys.pmm.cli;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.ConfigTemplate;
import com.anorys.pmm.config.ConfigTemplate.DiscoveredRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Finds the git repositories with a pom.xml in a workspace folder and writes a starter pmm.yaml there.")
public class InitCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<workspace>",
            description = "Folder containing the repositories. Default: the current directory.")
    Path workspace = Path.of("");

    @Option(names = "--base-branch", paramLabel = "<branch>", defaultValue = "develop",
            description = "Default base branch. Default: ${DEFAULT-VALUE}.")
    String baseBranch;

    @Option(names = "--force", description = "Overwrite an existing pmm.yaml.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            err.println(spec.commandLine().getColorScheme().errorText(root + " is not a directory"));
            return ExitCodes.TOOL_ERROR;
        }
        Path target = root.resolve(ConfigLoader.CONFIG_FILE);
        if (Files.exists(target) && !force) {
            err.println(spec.commandLine().getColorScheme().errorText(
                    target + " already exists; use --force to overwrite it"));
            return ExitCodes.TOOL_ERROR;
        }

        List<DiscoveredRepository> repositories = ConfigTemplate.discover(root);
        Files.writeString(target, ConfigTemplate.render(repositories, baseBranch));

        out.printf("Wrote %s%n", target);
        if (repositories.isEmpty()) {
            out.println("No git repository with a pom.xml was found; edit the 'repositories' list by hand.");
        } else {
            out.printf("Found %d repositories:%n", repositories.size());
            repositories.forEach(repo -> out.printf("  %s%n", repo.name()));
        }
        out.println("Next: review the file, then run 'pmm config' to check it.");
        out.flush();
        return ExitCodes.OK;
    }
}
