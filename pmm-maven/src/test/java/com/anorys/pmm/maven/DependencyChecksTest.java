package com.anorys.pmm.maven;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.maven.DependencyChecks.ProducerRelease;
import com.anorys.pmm.maven.DependencyChecks.Report;
import com.anorys.pmm.maven.DependencyChecks.State;
import com.anorys.pmm.maven.nexus.ArtifactMetadata;
import com.anorys.pmm.maven.nexus.MetadataSource;
import com.anorys.pmm.maven.nexus.NexusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core 3.1.5-SNAPSHOT and auth 2.0.1-SNAPSHOT are used by billing and reporting.
 * Nexus has core-api 3.1.3, 3.1.4 and auth-lib 1.2.0, 2.0.0.
 */
class DependencyChecksTest {

    private static final Map<String, List<String>> NEXUS = Map.of(
            "com.anorys.core:core-api", List.of("3.1.3", "3.1.4"),
            "com.anorys.auth:auth-lib", List.of("1.2.0", "2.0.0"),
            "org.slf4j:slf4j-api", List.of("2.0.9", "2.0.13"),
            "com.google.guava:guava", List.of("32.1.3-jre", "33.0.0-jre"));

    private static final MetadataSource SOURCE = artifact -> Optional.ofNullable(NEXUS.get(artifact.toString()))
            .map(versions -> new ArtifactMetadata(artifact, versions));

    @TempDir
    Path ws;

    @BeforeEach
    void createWorkspace() throws IOException {
        pom("core", "com.anorys.core", "core-api", "3.1.5-SNAPSHOT", "");
        pom("auth", "com.anorys.auth", "auth-lib", "2.0.1-SNAPSHOT", "");
        pom("billing", "com.anorys.billing", "billing", "1.0.0-SNAPSHOT", """
                <dependency><groupId>com.anorys.core</groupId><artifactId>core-api</artifactId><version>3.1.5</version></dependency>
                <dependency><groupId>com.anorys.auth</groupId><artifactId>auth-lib</artifactId><version>1.2.0</version></dependency>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version></dependency>
                <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>99.0</version></dependency>
                <dependency><groupId>com.anorys.legacy</groupId><artifactId>legacy-lib</artifactId><version>1.0</version></dependency>
                """);
        pom("reporting", "com.anorys.reporting", "reporting", "1.0.0-SNAPSHOT", """
                <dependency><groupId>com.anorys.core</groupId><artifactId>core-api</artifactId><version>3.1.3</version></dependency>
                <dependency><groupId>com.anorys.auth</groupId><artifactId>auth-lib</artifactId><version>2.0.0-SNAPSHOT</version></dependency>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
                """);
        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: auth
                  - name: billing
                  - name: reporting
                internalGroups: [com.anorys.*]
                """);
    }

    @Test
    void checksInternalAndExternalVersionsAgainstNexus() {
        Report report = evaluate(SOURCE);

        assertThat(report.findings()).containsExactlyInAnyOrder(
                new Finding(KnownChecks.DEPS_RELEASE_MISSING, Severity.ERROR, "billing",
                        "billing uses core-api 3.1.5 from core, which is not released in Nexus",
                        "release core 3.1.5 (it is at 3.1.5-SNAPSHOT), or use the latest release 3.1.4"),
                new Finding(KnownChecks.DEPS_INTERNAL_OUTDATED, Severity.WARN, "billing",
                        "billing uses auth-lib 1.2.0 from auth; latest release is 2.0.0 (major update)",
                        "upgrade to 2.0.0"),
                new Finding(KnownChecks.DEPS_UNRESOLVABLE, Severity.ERROR, "billing",
                        "billing uses com.google.guava:guava 99.0, which Nexus does not have",
                        "check the version (latest release: 33.0.0-jre)"),
                new Finding(KnownChecks.DEPS_UNDECLARED_INTERNAL, Severity.WARN, "billing",
                        "billing uses legacy-lib 1.0 (com.anorys.legacy), but no monitored repository produces it",
                        "add the repository that produces it to pmm.yaml, or narrow internalGroups"),
                new Finding(KnownChecks.DEPS_RELEASE_MISSING, Severity.ERROR, "billing",
                        "billing uses legacy-lib 1.0 (com.anorys.legacy), which is not released in Nexus",
                        "check the version"),
                new Finding(KnownChecks.DEPS_INTERNAL_SNAPSHOT, Severity.ERROR, "reporting",
                        "reporting uses auth-lib 2.0.0-SNAPSHOT from auth: a SNAPSHOT",
                        "depend on a released version (latest: 2.0.0)"),
                new Finding(KnownChecks.DEPS_CONVERGENCE, Severity.WARN, null,
                        "org.slf4j:slf4j-api: 2.0.13 in billing; 2.0.9 in reporting", "align on 2.0.13"));

        assertThat(report.usages()).extracting(InternalUsage::consumer, u -> u.artifact().artifactId(),
                        InternalUsage::version, InternalUsage::latest, InternalUsage::state)
                .containsExactly(
                        tuple("billing", "core-api", "3.1.5", "3.1.4", State.NOT_RELEASED),
                        tuple("billing", "auth-lib", "1.2.0", "2.0.0", State.OUTDATED),
                        tuple("billing", "legacy-lib", "1.0", null, State.NOT_RELEASED),
                        tuple("reporting", "core-api", "3.1.3", "3.1.4", State.UPDATE_AVAILABLE),
                        tuple("reporting", "auth-lib", "2.0.0-SNAPSHOT", "2.0.0", State.SNAPSHOT));
        assertThat(report.releases()).containsExactly(
                new ProducerRelease("core", List.of("3.1.4", "3.1.3"), List.of("billing", "reporting")),
                new ProducerRelease("auth", List.of("2.0.0", "1.2.0"), List.of("billing", "reporting")));
    }

    @Test
    void allowedLagAndSeveritiesComeFromTheConfiguration() throws IOException {
        Files.writeString(ws.resolve("pmm.local.yaml"), """
                checks:
                  deps.internal-outdated: { severity: error, allowedLag: none }
                  deps.convergence: off
                  deps.unresolvable: off
                """);

        Report report = evaluate(SOURCE);

        assertThat(report.findings()).filteredOn(f -> f.checkId().equals(KnownChecks.DEPS_INTERNAL_OUTDATED))
                .extracting(Finding::repository, Finding::severity)
                .containsExactlyInAnyOrder(tuple("billing", Severity.ERROR), tuple("reporting", Severity.ERROR));
        assertThat(report.findings()).extracting(Finding::checkId)
                .doesNotContain(KnownChecks.DEPS_CONVERGENCE, KnownChecks.DEPS_UNRESOLVABLE);
    }

    @Test
    void offlineRunsOnlyTheChecksThatNeedNoNexus() {
        Report report = evaluate(null);

        assertThat(report.findings()).extracting(Finding::checkId).containsExactlyInAnyOrder(
                KnownChecks.DEPS_UNDECLARED_INTERNAL, KnownChecks.DEPS_INTERNAL_SNAPSHOT, KnownChecks.DEPS_CONVERGENCE);
        assertThat(report.usages()).extracting(InternalUsage::state)
                .containsExactly(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, State.SNAPSHOT);
        assertThat(report.releases()).extracting(ProducerRelease::repository, ProducerRelease::versions)
                .containsExactly(tuple("core", List.of("3.1.5", "3.1.3")), tuple("auth", List.of("1.2.0")));
    }

    @Test
    void unreachableNexusIsOneFindingAndTheRestStillRuns() {
        MetadataSource failing = artifact -> {
            throw new NexusException("cannot connect to https://nexus.example.com/");
        };

        Report report = evaluate(failing);

        assertThat(report.findings()).extracting(Finding::checkId).containsExactlyInAnyOrder(
                KnownChecks.DEPS_NEXUS_UNAVAILABLE, KnownChecks.DEPS_UNDECLARED_INTERNAL,
                KnownChecks.DEPS_INTERNAL_SNAPSHOT, KnownChecks.DEPS_CONVERGENCE);
        assertThat(report.findings()).filteredOn(f -> f.checkId().equals(KnownChecks.DEPS_NEXUS_UNAVAILABLE))
                .singleElement().extracting(Finding::message).asString()
                .startsWith("cannot query Nexus: cannot connect to https://nexus.example.com/");
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private Report evaluate(MetadataSource source) {
        PmmConfig config = ConfigLoader.load(ws.resolve("pmm.yaml"));
        return new DependencyChecks(config, WorkspaceScanner.scan(config).workspace(), source).evaluate();
    }

    private void pom(String repository, String groupId, String artifactId, String version, String dependencies)
            throws IOException {
        Files.createDirectories(ws.resolve(repository));
        Files.writeString(ws.resolve(repository).resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(groupId, artifactId, version, dependencies));
    }
}
