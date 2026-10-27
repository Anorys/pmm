package com.anorys.pmm.core.workspace;

/**
 * A reference from a module to another artifact.
 *
 * @param version the version after property interpolation; {@code null} when the POM does not say
 *                and no dependencyManagement in the workspace provides it (for example a version
 *                managed by an external parent or BOM)
 * @param scope   Maven scope for dependencies ({@code compile} when absent); {@code null} for other kinds
 */
public record DependencyRef(ArtifactKey artifact, String version, Kind kind, String scope) {

    public enum Kind {
        /** {@code <parent>} */
        PARENT,
        /** {@code <dependencies>} */
        DEPENDENCY,
        /** a BOM in {@code <dependencyManagement>} with {@code <scope>import</scope>} */
        IMPORT,
        /** {@code <build><plugins>} */
        PLUGIN
    }

    /** True when the version still contains an unresolved {@code ${...}} expression. */
    public boolean hasUnresolvedVersion() {
        return version != null && version.contains("${");
    }
}
