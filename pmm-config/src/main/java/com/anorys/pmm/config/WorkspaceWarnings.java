package com.anorys.pmm.config;

import com.anorys.pmm.config.PmmConfig.RepositoryConfig;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a valid configuration against the disk: repositories that are missing, not git
 * repositories or without a pom.xml. These are warnings, because a repository may simply
 * not be cloned yet.
 */
public final class WorkspaceWarnings {

    private WorkspaceWarnings() {
    }

    public static List<String> of(PmmConfig config) {
        List<String> warnings = new ArrayList<>();
        if (!Files.isDirectory(config.workspace().root())) {
            warnings.add("workspace root " + config.workspace().root() + " does not exist");
        }
        for (RepositoryConfig repo : config.monitoredRepositories()) {
            if (!Files.isDirectory(repo.path())) {
                warnings.add(repo.name() + ": " + repo.path() + " does not exist (not cloned yet?)");
                continue;
            }
            if (!Files.exists(repo.path().resolve(".git"))) {
                warnings.add(repo.name() + ": " + repo.path() + " is not a git repository");
            }
            if (!Files.isRegularFile(repo.path().resolve("pom.xml"))) {
                warnings.add(repo.name() + ": no pom.xml in " + repo.path());
            }
        }
        if (config.maven().settings() != null && !Files.isRegularFile(config.maven().settings())) {
            warnings.add("maven.settings " + config.maven().settings() + " does not exist");
        }
        return warnings;
    }
}
