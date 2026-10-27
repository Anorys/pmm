package com.anorys.pmm.maven.nexus;

import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.maven.Versions;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The versions of an artifact that a Maven repository holds, from its {@code maven-metadata.xml}. */
public record ArtifactMetadata(ArtifactKey artifact, List<String> versions) {

    public ArtifactMetadata {
        versions = List.copyOf(versions);
    }

    public boolean contains(String version) {
        return versions.contains(version);
    }

    /** The highest version that is neither a snapshot nor a pre-release (alpha, beta, RC, milestone). */
    public Optional<String> latestRelease() {
        return versions.stream()
                .filter(v -> !Versions.isSnapshot(v) && !Versions.isPreRelease(v))
                .max(Comparator.comparing(Versions::comparable));
    }
}
