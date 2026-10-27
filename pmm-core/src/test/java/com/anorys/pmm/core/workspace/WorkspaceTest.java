package com.anorys.pmm.core.workspace;

import com.anorys.pmm.core.workspace.DependencyRef.Kind;
import com.anorys.pmm.core.workspace.Workspace.RepositoryDependency;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTest {

    private static final String G = "com.anorys";

    @Test
    void linksRepositoriesThroughProducedArtifacts() {
        ModuleInfo coreApi = module("core", "core-api", "3.2.0-SNAPSHOT");
        ModuleInfo coreImpl = module("core", "core-impl", "3.2.0-SNAPSHOT",
                ref("core-api", "3.2.0-SNAPSHOT", Kind.DEPENDENCY));
        ModuleInfo billing = module("billing", "billing-service", "2.5.0",
                ref("core-api", "3.1.4", Kind.DEPENDENCY),
                ref("core-impl", "3.1.4", Kind.DEPENDENCY),
                new DependencyRef(ArtifactKey.parse("org.slf4j:slf4j-api"), "2.0.13", Kind.DEPENDENCY, "compile"));
        Workspace workspace = new Workspace(List.of(
                repo("core", coreApi, coreImpl),
                repo("billing", billing)));

        List<RepositoryDependency> dependencies = workspace.repositoryDependencies();

        assertThat(dependencies).hasSize(1);
        RepositoryDependency dependency = dependencies.get(0);
        assertThat(dependency.consumer()).isEqualTo("billing");
        assertThat(dependency.producer()).isEqualTo("core");
        assertThat(dependency.usages()).extracting(u -> u.reference().version()).containsExactly("3.1.4", "3.1.4");
        assertThat(workspace.repositoryGraph().hasEdge("billing", "core")).isTrue();
        assertThat(workspace.moduleGraph().hasEdge(coreImpl.artifact(), coreApi.artifact())).isTrue();
        assertThat(workspace.moduleGraph().nodes()).hasSize(3);
        assertThat(workspace.producer(ArtifactKey.parse(G + ":core-api"))).contains(coreApi);
    }

    @Test
    void matchesGroupPatterns() {
        ArtifactKey key = ArtifactKey.parse("com.anorys.billing:billing-api");

        assertThat(key.groupMatches("com.anorys.*")).isTrue();
        assertThat(key.groupMatches("com.anorys.billing")).isTrue();
        assertThat(ArtifactKey.parse("com.anorys:x").groupMatches("com.anorys.*")).isTrue();
        assertThat(ArtifactKey.parse("com.anorysx:x").groupMatches("com.anorys.*")).isFalse();
        assertThat(key.groupMatches("com.anorys")).isFalse();
    }

    private static DependencyRef ref(String artifactId, String version, Kind kind) {
        return new DependencyRef(new ArtifactKey(G, artifactId), version, kind, kind == Kind.DEPENDENCY ? "compile" : null);
    }

    private static ModuleInfo module(String repo, String artifactId, String version, DependencyRef... refs) {
        return new ModuleInfo(repo, new ArtifactKey(G, artifactId), version, "jar", Path.of(repo, artifactId, "pom.xml"),
                List.of(refs), true);
    }

    private static RepositoryInfo repo(String name, ModuleInfo... modules) {
        return new RepositoryInfo(name, Path.of(name), modules[0].version(), List.of(modules));
    }
}
