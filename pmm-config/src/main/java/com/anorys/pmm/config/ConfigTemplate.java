package com.anorys.pmm.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Discovers repositories in a workspace folder and writes a commented starter {@code pmm.yaml}. */
public final class ConfigTemplate {

    /** A git repository with a pom.xml, found directly under the workspace root. */
    public record DiscoveredRepository(String name, String relativePath) {
    }

    private ConfigTemplate() {
    }

    /** Lists sub-directories of {@code root} that contain both {@code .git} and {@code pom.xml}, sorted by name. */
    public static List<DiscoveredRepository> discover(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(".git")) && Files.isRegularFile(dir.resolve("pom.xml")))
                    .sorted(Comparator.comparing(dir -> dir.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(dir -> {
                        String folder = dir.getFileName().toString();
                        return new DiscoveredRepository(toName(folder), folder);
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + root, e);
        }
    }

    public static String render(List<DiscoveredRepository> repositories, String baseBranch) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("""
                # pmm configuration. Reference: https://github.com/aureg/project-modules-manager#configuration
                # Personal overrides (local paths, disabled repositories) go in pmm.local.yaml next to this file.

                workspace:
                  root: .                 # repository paths are relative to this folder
                """);
        yaml.append("  baseBranch: ").append(baseBranch).append("       # default base branch of every repository\n");
        yaml.append("""
                  remote: origin

                repositories:
                """);
        for (DiscoveredRepository repo : repositories) {
            yaml.append("  - name: ").append(repo.name()).append('\n');
            if (!repo.name().equals(repo.relativePath())) {
                yaml.append("    path: \"").append(repo.relativePath()).append("\"\n");
            }
        }
        if (repositories.isEmpty()) {
            yaml.append("  - name: my-repository   # folder name under workspace.root\n");
        }
        yaml.append("""
                    # Per repository options:
                    #   modules: [module-a, module-b]       # default: all modules ("*")
                    #   baseBranch: release/2.4             # overrides workspace.baseBranch
                    #   releaseTagPattern: "v{version}"     # default: auto-detected
                    #   enabled: false                      # keep listed but do not monitor

                # Group ids of artifacts produced by these repositories.
                internalGroups: []

                maven:
                  executable: auto        # auto (mvnd when installed), mvn, mvnd, or a path
                  compileGoal: test-compile
                  extraArgs: []

                nexus:
                  repositoryId:           # server id from settings.xml, used to look up releases

                # Severity of each check: off, info, warn, error. See 'pmm config' for all checks.
                checks:
                  git.behind: { severity: warn, maxCommits: 20, maxDays: 7 }

                rebase:
                  push: ask               # never, ask, always (force-with-lease, never on protected branches)

                build:
                  parallelRepos: 0        # 0 = automatic: min(4, cores / 2)
                  memoryPerBuild: 1g
                """);
        return yaml.toString();
    }

    /** Repository names may only use letters, digits, '.', '_' and '-'. */
    static String toName(String folder) {
        String name = folder.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return name.isEmpty() ? "repository" : name;
    }
}
