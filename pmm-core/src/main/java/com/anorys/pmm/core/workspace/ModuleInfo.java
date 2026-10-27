package com.anorys.pmm.core.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * One Maven project (a pom.xml) of a repository, with its references to other artifacts.
 *
 * @param repository name of the repository it belongs to
 * @param monitored  whether the configuration asks pmm to monitor this module
 */
public record ModuleInfo(String repository, ArtifactKey artifact, String version, String packaging, Path pom,
                         List<DependencyRef> references, boolean monitored) {

    public ModuleInfo {
        references = List.copyOf(references);
    }

    public String artifactId() {
        return artifact.artifactId();
    }
}
