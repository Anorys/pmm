package com.anorys.pmm.maven.nexus;

import com.anorys.pmm.core.workspace.ArtifactKey;

import java.util.Optional;

/** Where released versions are looked up: Nexus in practice, a map in tests. */
public interface MetadataSource {

    /**
     * @return the artifact's metadata, or empty when the repository does not know the artifact
     * @throws NexusException when the repository cannot be queried at all (network, authentication)
     */
    Optional<ArtifactMetadata> fetch(ArtifactKey artifact);
}
