package com.anorys.pmm.build;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult.Status;
import com.anorys.pmm.build.BuildRunner.Options;
import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Platform;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.ExecutableResolver;
import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessSpec;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.DependencyRef.Kind;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fake Maven records each call and fails with the content of fail.txt when the repository has one.
 * core builds; billing cannot download core-api 3.1.5; reporting is not cloned.
 */
class BuildRunnerTest {

    private static final SystemProcessRunner RUNNER = new SystemProcessRunner();

    @TempDir
    Path ws;

    private PmmConfig config;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws IOException {
        Path fake = fakeMaven(ws.resolve("tools"));
        Files.createDirectories(ws.resolve("core"));
        Files.writeString(ws.resolve("core/pom.xml"), "<project/>");
        git(ws.resolve("core"), "init", "--quiet");
        git(ws.resolve("core"), "add", "pom.xml");
        git(ws.resolve("core"), "commit", "--quiet", "-m", "init");
        Files.createDirectories(ws.resolve("billing"));
        Files.writeString(ws.resolve("billing/pom.xml"), "<project/>");
        Files.writeString(ws.resolve("billing/fail.txt"), """
                [INFO] BUILD FAILURE
                [ERROR] Failed to execute goal on project billing-api: Could not resolve dependencies for project \
                com.anorys.billing:billing-api:jar:1.0-SNAPSHOT: Could not find artifact com.anorys.core:core-api:jar:3.1.5 in nexus
                """);
        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: billing
                    modules: [billing-api]
                  - name: reporting
                maven:
                  executable: %s
                  profiles: [ci]
                  extraArgs: [-Denforcer.skip]
                build:
                  memoryPerBuild: 512m
                """.formatted(fake.toString().replace('\\', '/')));
        config = ConfigLoader.load(ws.resolve("pmm.yaml"));

        ArtifactKey coreApi = new ArtifactKey("com.anorys.core", "core-api");
        ArtifactKey billingApi = new ArtifactKey("com.anorys.billing", "billing-api");
        workspace = new Workspace(List.of(
                new RepositoryInfo("core", ws.resolve("core"), "3.1.5-SNAPSHOT", List.of(new ModuleInfo("core", coreApi,
                        "3.1.5-SNAPSHOT", "jar", ws.resolve("core/pom.xml"), List.of(), true))),
                new RepositoryInfo("billing", ws.resolve("billing"), "1.0-SNAPSHOT", List.of(new ModuleInfo("billing",
                        billingApi, "1.0-SNAPSHOT", "jar", ws.resolve("billing/pom.xml"),
                        List.of(new DependencyRef(coreApi, "3.1.5", Kind.DEPENDENCY, "compile")), true)))));
    }

    @Test
    void buildsExplainsBlockersAndCachesSuccesses() throws IOException {
        List<BuildResult> results = build(true);

        assertThat(results).extracting(BuildResult::repository, BuildResult::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("core", Status.SUCCESS),
                org.assertj.core.groups.Tuple.tuple("billing", Status.BLOCKED),
                org.assertj.core.groups.Tuple.tuple("reporting", Status.SKIPPED));
        assertThat(results.get(2).reason()).isEqualTo("not cloned");
        assertThat(results.get(1).log()).isEqualTo(ws.resolve(".pmm/logs/billing.log"));
        assertThat(Files.readString(results.get(1).log())).contains("Could not find artifact");

        List<String> calls = calls();
        assertThat(calls).hasSize(2);
        String billingCall = calls.stream().filter(c -> c.contains("billing")).findFirst().orElseThrow();
        assertThat(billingCall).doesNotContain(" -o ", " -s ")
                .contains("-B -ntp -P ci -T 1C -pl :billing-api -am test-compile -Denforcer.skip");

        List<Finding> findings = new BuildChecks(config).evaluate(results);
        assertThat(findings).containsExactly(new Finding(KnownChecks.BUILD_BLOCKED, Severity.ERROR, "billing",
                "cannot build: core-api 3.1.5 from core could not be downloaded", "release core 3.1.5 if it is not in Nexus yet"));
        assertThat(BuildChecks.blockers(results))
                .containsExactly(new Blocker("core", Set.of("billing"), Set.of("core-api 3.1.5")));

        // Unchanged sources: core comes from the cache, billing (failed) is built again.
        List<BuildResult> second = build(true);
        assertThat(second.get(0).status()).isEqualTo(Status.CACHED);
        assertThat(second.get(1).status()).isEqualTo(Status.BLOCKED);
        assertThat(calls()).hasSize(3);

        // A local change invalidates the cache; --no-cache ignores it.
        Files.writeString(ws.resolve("core/Feature.java"), "class Feature {}");
        assertThat(build(true).get(0).status()).isEqualTo(Status.SUCCESS);
        assertThat(build(false).get(0).status()).isEqualTo(Status.SUCCESS);
        assertThat(calls()).hasSize(7);
    }

    @Test
    void compilationFailureIsReportedWithTheFirstError() throws IOException {
        Files.writeString(ws.resolve("core/fail.txt"), """
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile \
                (default-compile) on project core-api: Compilation failure
                [ERROR] /ws/core/src/main/java/Api.java:[3,8] class Apy is public, should be declared in a file named Apy.java
                """);

        BuildResult core = build(true).get(0);

        assertThat(core.status()).isEqualTo(Status.FAILED);
        assertThat(new BuildChecks(config).evaluate(List.of(core))).singleElement()
                .extracting(Finding::message)
                .isEqualTo("core-api does not compile: Api.java:[3,8] class Apy is public, should be declared in a file named Apy.java");
    }

    private List<BuildResult> build(boolean useCache) {
        List<BuildResult> results = Collections.synchronizedList(new ArrayList<>());
        List<BuildResult> returned = new BuildRunner(config, workspace, RUNNER,
                new MavenCommand(config, ExecutableResolver.fromEnvironment()), Clock.systemUTC())
                .build(config.repositories(), new Options(2, useCache, false), new BuildRunner.Listener() {
                    @Override
                    public void finished(BuildResult result) {
                        results.add(result);
                    }
                });
        assertThat(results).hasSameElementsAs(returned);
        return returned;
    }

    private List<String> calls() throws IOException {
        Path file = ws.resolve("tools/calls.txt");
        return Files.exists(file) ? Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList() : List.of();
    }

    /** A Maven stand-in, as a .cmd script on Windows and a shell script elsewhere. */
    private static Path fakeMaven(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (Platform.isWindows()) {
            Path script = dir.resolve("fake-mvn.cmd");
            Files.writeString(script, String.join("\r\n",
                    "@echo off",
                    "echo %CD% %*>> \"%~dp0calls.txt\"",
                    "if exist fail.txt (",
                    "  type fail.txt",
                    "  exit /b 1",
                    ")",
                    "echo [INFO] BUILD SUCCESS",
                    ""));
            return script;
        }
        Path script = dir.resolve("fake-mvn");
        Files.writeString(script, """
                #!/bin/sh
                echo "$PWD $*" >> "$(dirname "$0")/calls.txt"
                if [ -f fail.txt ]; then cat fail.txt; exit 1; fi
                echo "[INFO] BUILD SUCCESS"
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static void git(Path dir, String... args) {
        ProcessResult result = RUNNER.run(ProcessSpec.of("git", List.of(args)).in(dir)
                .env("GIT_AUTHOR_NAME", "Test").env("GIT_AUTHOR_EMAIL", "test@example.com")
                .env("GIT_COMMITTER_NAME", "Test").env("GIT_COMMITTER_EMAIL", "test@example.com")
                .env("GIT_CONFIG_NOSYSTEM", "1")
                .build());
        if (!result.succeeded()) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + result.output());
        }
    }
}
