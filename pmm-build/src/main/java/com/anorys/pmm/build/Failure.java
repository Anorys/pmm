package com.anorys.pmm.build;

import com.anorys.pmm.core.workspace.ArtifactKey;

import java.util.List;

/**
 * Why a build failed, read from its output.
 *
 * @param module  the module Maven reported as failing, or {@code null}
 * @param missing artifacts Maven could not download; non-empty for {@link Kind#MISSING_ARTIFACT}
 * @param detail  the most useful error line, for example the first compilation error
 */
public record Failure(Kind kind, String module, List<MissingArtifact> missing, String detail) {

    public enum Kind {
        /** A dependency, parent, BOM or plugin is not in the repository: the build is blocked. */
        MISSING_ARTIFACT,
        COMPILATION,
        TIMEOUT,
        OTHER
    }

    /**
     * @param producer the monitored repository that produces it, or {@code null}
     */
    public record MissingArtifact(ArtifactKey artifact, String version, String producer) {

        @Override
        public String toString() {
            return artifact.artifactId() + " " + version;
        }
    }

    public Failure {
        missing = List.copyOf(missing);
    }
}
