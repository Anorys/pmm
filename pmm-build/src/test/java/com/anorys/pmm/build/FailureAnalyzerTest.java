package com.anorys.pmm.build;

import com.anorys.pmm.build.Failure.Kind;
import com.anorys.pmm.build.Failure.MissingArtifact;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FailureAnalyzerTest {

    private static final ArtifactKey CORE_API = new ArtifactKey("com.anorys.core", "core-api");
    private static final ArtifactKey PARENT = new ArtifactKey("com.anorys", "anorys-parent");

    private final FailureAnalyzer analyzer = new FailureAnalyzer(new Workspace(List.of(
            new RepositoryInfo("platform-core", Path.of("core"), "3.1.5-SNAPSHOT", List.of(
                    new ModuleInfo("platform-core", CORE_API, "3.1.5-SNAPSHOT", "jar", Path.of("core/pom.xml"), List.of(), true))),
            new RepositoryInfo("parent", Path.of("parent"), "8-SNAPSHOT", List.of(
                    new ModuleInfo("parent", PARENT, "8-SNAPSHOT", "pom", Path.of("parent/pom.xml"), List.of(), true))))));

    @Test
    void maven39UnresolvedDependency() {
        Failure failure = analyzer.analyze(List.of(
                "[INFO] BUILD FAILURE",
                "[ERROR] Failed to execute goal on project billing-api: Could not resolve dependencies for project "
                        + "com.anorys.billing:billing-api:jar:2.5.0-SNAPSHOT: The following artifacts could not be resolved: "
                        + "com.anorys.core:core-api:jar:3.1.5 (absent), org.acme:widget:jar:tests:1.2 (absent): "
                        + "Could not find artifact com.anorys.core:core-api:jar:3.1.5 in nexus (https://nexus/repository/public)",
                "[ERROR] -> [Help 1]"), false);

        assertThat(failure.kind()).isEqualTo(Kind.MISSING_ARTIFACT);
        assertThat(failure.module()).isEqualTo("billing-api");
        assertThat(failure.missing()).containsExactly(
                new MissingArtifact(CORE_API, "3.1.5", "platform-core"),
                new MissingArtifact(new ArtifactKey("org.acme", "widget"), "1.2", null));
    }

    @Test
    void maven3910DependencyListCachedFailureAndOffline() {
        Failure failure = analyzer.analyze(List.of(
                "[ERROR] Failed to execute goal on project consumer: Could not resolve dependencies for project "
                        + "com.anorys.consumer:consumer:jar:1.0-SNAPSHOT",
                "[ERROR] dependency: com.anorys.core:core-api:jar:3.1.5 (compile)",
                "[ERROR] \tcom.anorys.core:core-api:jar:3.1.5 was not found in https://nexus/ during a previous attempt. "
                        + "This failure was cached in the local repository",
                "[ERROR] dependency: com.example:nope:jar:1.0 (compile)",
                "[ERROR] \tCannot access central (https://repo.maven.apache.org/maven2) in offline mode and the artifact "
                        + "com.example:nope:jar:1.0 has not been downloaded from it before."), false);

        assertThat(failure.kind()).isEqualTo(Kind.MISSING_ARTIFACT);
        assertThat(failure.module()).isEqualTo("consumer");
        assertThat(failure.missing()).containsExactly(
                new MissingArtifact(CORE_API, "3.1.5", "platform-core"),
                new MissingArtifact(new ArtifactKey("com.example", "nope"), "1.0", null));
    }

    @Test
    void missingParentAndPlugin() {
        Failure parent = analyzer.analyze(List.of(
                "[ERROR] Non-resolvable parent POM for com.anorys.billing:billing:1.0-SNAPSHOT: "
                        + "Could not find artifact com.anorys:anorys-parent:pom:8 in nexus (https://nexus/) and "
                        + "'parent.relativePath' points at no local POM @ line 5, column 11"), false);
        Failure plugin = analyzer.analyze(List.of(
                "[ERROR] Plugin com.anorys:codegen-maven-plugin:1.4 or one of its dependencies could not be resolved: ..."), false);

        assertThat(parent.missing()).containsExactly(new MissingArtifact(PARENT, "8", "parent"));
        assertThat(plugin.missing()).singleElement().extracting(MissingArtifact::version).isEqualTo("1.4");
    }

    @Test
    void compilationError() {
        Failure failure = analyzer.analyze(List.of(
                "[INFO] BUILD FAILURE",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile "
                        + "(default-compile) on project billing-service: Compilation failure",
                "[ERROR] C:\\work\\billing\\billing-service\\src\\main\\java\\com\\anorys\\Invoice.java:[42,17] cannot find symbol",
                "[ERROR]   symbol:   method total()"), false);

        assertThat(failure.kind()).isEqualTo(Kind.COMPILATION);
        assertThat(failure.module()).isEqualTo("billing-service");
        assertThat(failure.detail()).isEqualTo("Invoice.java:[42,17] cannot find symbol");
    }

    @Test
    void otherFailuresAndTimeouts() {
        Failure other = analyzer.analyze(List.of(
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.5.0:enforce on project billing: "
                        + "Some Enforcer rules have failed.",
                "[ERROR] -> [Help 1]"), false);

        assertThat(other.kind()).isEqualTo(Kind.OTHER);
        assertThat(other.module()).isEqualTo("billing");
        assertThat(other.detail()).startsWith("Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin");
        assertThat(analyzer.analyze(List.of("[INFO] Building billing"), true).kind()).isEqualTo(Kind.TIMEOUT);
    }
}
