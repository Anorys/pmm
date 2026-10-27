package com.anorys.pmm.config;

import com.anorys.pmm.config.PmmConfig.PushPolicy;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.Platform;
import com.anorys.pmm.core.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void minimalConfigGetsDefaults() throws IOException {
        Path file = write("pmm.yaml", """
                repositories:
                  - name: platform-core
                """);

        PmmConfig config = ConfigLoader.load(file);

        assertThat(config.workspace().root()).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(config.workspace().baseBranch()).isEqualTo("develop");
        assertThat(config.workspace().remote()).isEqualTo("origin");
        RepositoryConfig repo = config.repositories().get(0);
        assertThat(repo.path()).isEqualTo(config.workspace().root().resolve("platform-core"));
        assertThat(repo.modules()).containsExactly("*");
        assertThat(repo.monitorsAllModules()).isTrue();
        assertThat(repo.enabled()).isTrue();
        assertThat(config.baseBranch(repo)).isEqualTo("develop");
        assertThat(config.maven().executable()).isEqualTo("auto");
        assertThat(config.maven().compileGoal()).isEqualTo("test-compile");
        assertThat(config.check(KnownChecks.GIT_BEHIND).intOption("maxCommits", 0)).isEqualTo(20);
        assertThat(config.check(KnownChecks.DEPS_RELEASE_MISSING).severity()).isEqualTo(Severity.ERROR);
        assertThat(config.rebase().push()).isEqualTo(PushPolicy.ASK);
        assertThat(config.rebase().protectedBranches()).contains("develop", "release/*");
        assertThat(config.build().parallelRepos()).isZero();
        assertThat(config.build().effectiveParallelRepos()).isBetween(1, 4);
    }

    @Test
    void fullConfigIsParsed() throws IOException {
        Path file = write("pmm.yaml", """
                workspace:
                  root: workspace
                  baseBranch: develop
                  remote: upstream
                repositories:
                  - name: platform-core
                    path: core
                    url: git@github.com:anorys/platform-core.git
                  - name: billing
                    baseBranch: release/2.4
                    modules: [billing-api, billing-service]
                    releaseTagPattern: "billing-{version}"
                  - name: legacy-adapter
                    enabled: false
                maven:
                  executable: mvnd
                  settings: ~/.m2/settings.xml
                  profiles: dev
                  offline: true
                  threads: 2
                  extraArgs: ["-Dmaven.javadoc.skip", "-Denforcer.skip"]
                  compileGoal: compile
                nexus:
                  repositoryId: nexus-releases
                internalGroups: [com.anorys.*]
                checks:
                  git.dirty: off
                  git.behind: { severity: error, maxCommits: 5 }
                rebase:
                  push: always
                  protectedBranches: [develop]
                build:
                  parallelRepos: 3
                  memoryPerBuild: 768m
                  timeoutMinutes: 20
                  cache: false
                """);

        PmmConfig config = ConfigLoader.load(file);

        Path root = tmp.resolve("workspace").toAbsolutePath().normalize();
        assertThat(config.workspace().root()).isEqualTo(root);
        assertThat(config.workspace().remote()).isEqualTo("upstream");
        assertThat(config.repository("platform-core").orElseThrow().path()).isEqualTo(root.resolve("core"));
        RepositoryConfig billing = config.repository("billing").orElseThrow();
        assertThat(config.baseBranch(billing)).isEqualTo("release/2.4");
        assertThat(billing.modules()).containsExactly("billing-api", "billing-service");
        assertThat(billing.releaseTagPattern()).isEqualTo("billing-{version}");
        assertThat(config.monitoredRepositories()).extracting(RepositoryConfig::name)
                .containsExactly("platform-core", "billing");
        assertThat(config.maven().settings()).isEqualTo(Platform.userHome().resolve(".m2/settings.xml").normalize());
        assertThat(config.maven().profiles()).containsExactly("dev");
        assertThat(config.maven().threads()).isEqualTo("2");
        assertThat(config.maven().extraArgs()).hasSize(2);
        assertThat(config.nexus().repositoryId()).isEqualTo("nexus-releases");
        assertThat(config.internalGroups()).containsExactly("com.anorys.*");
        assertThat(config.check(KnownChecks.GIT_DIRTY).enabled()).isFalse();
        CheckSetting behind = config.check(KnownChecks.GIT_BEHIND);
        assertThat(behind.severity()).isEqualTo(Severity.ERROR);
        assertThat(behind.intOption("maxCommits", 0)).isEqualTo(5);
        assertThat(behind.intOption("maxDays", 0)).isEqualTo(7);
        assertThat(config.rebase().push()).isEqualTo(PushPolicy.ALWAYS);
        assertThat(config.build().effectiveParallelRepos()).isEqualTo(3);
        assertThat(config.build().cache()).isFalse();
    }

    @Test
    void localFileOverridesSharedFile() throws IOException {
        Path file = write("pmm.yaml", """
                workspace:
                  baseBranch: develop
                repositories:
                  - name: platform-core
                  - name: billing
                    modules: [billing-api]
                maven:
                  profiles: [dev]
                """);
        write("pmm.local.yaml", """
                workspace:
                  root: C:/work/projects/anorys
                repositories:
                  - name: billing
                    enabled: false
                  - name: sandbox
                maven:
                  profiles: [local]
                """);

        PmmConfig config = ConfigLoader.load(file);

        assertThat(config.workspace().baseBranch()).isEqualTo("develop");
        assertThat(config.repositories()).extracting(RepositoryConfig::name)
                .containsExactly("platform-core", "billing", "sandbox");
        RepositoryConfig billing = config.repository("billing").orElseThrow();
        assertThat(billing.enabled()).isFalse();
        assertThat(billing.modules()).containsExactly("billing-api");
        assertThat(config.maven().profiles()).containsExactly("local");
    }

    @Test
    void reportsEveryProblemAtOnce() throws IOException {
        Path file = write("pmm.yaml", """
                workspace:
                  baseBrach: develop
                repositories:
                  - path: somewhere
                  - name: dup
                  - name: dup
                    releaseTagPattern: v1
                checks:
                  git.behnd: warn
                  git.dirty: loud
                  git.behind: { maxComits: 3 }
                rebase:
                  push: sometimes
                build:
                  parallelRepos: -1
                  memoryPerBuild: lots
                """);

        assertThatThrownBy(() -> ConfigLoader.load(file))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems()).containsExactlyInAnyOrder(
                        "workspace.baseBrach is not a known setting",
                        "repositories[0].name is required",
                        "repositories[2].name 'dup' is declared more than once",
                        "repositories[2].releaseTagPattern must contain the {version} placeholder, for example \"v{version}\"",
                        "checks.git.behnd is not a known check (known: " + String.join(", ", KnownChecks.ids()) + ")",
                        "checks.git.dirty must be one of off, info, warn, error (was 'loud')",
                        "checks.git.behind.maxComits is not an option of git.behind",
                        "rebase.push must be one of never, ask, always",
                        "build.parallelRepos must be at least 0",
                        "build.memoryPerBuild must look like 512m or 1g"));
    }

    @Test
    void requiresRepositories() throws IOException {
        Path file = write("pmm.yaml", "workspace:\n  baseBranch: develop\n");

        assertThatThrownBy(() -> ConfigLoader.load(file))
                .isInstanceOfSatisfying(ConfigException.class, e -> assertThat(e.problems())
                        .containsExactly("repositories is required: list at least one repository"));
    }

    @Test
    void reportsYamlSyntaxErrorsWithLocation() throws IOException {
        Path file = write("pmm.yaml", "repositories:\n  - name: [unclosed\n");

        assertThatThrownBy(() -> ConfigLoader.load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("YAML syntax error at line");
    }

    @Test
    void reportsEmptyAndMissingFiles() throws IOException {
        Path empty = write("pmm.yaml", "");

        assertThatThrownBy(() -> ConfigLoader.load(empty)).hasMessageContaining("file is empty");
        assertThatThrownBy(() -> ConfigLoader.load(tmp.resolve("missing.yaml"))).hasMessageContaining("file not found");
    }

    @Test
    void locatesConfigInParentDirectories() throws IOException {
        Path file = write("pmm.yaml", "repositories:\n  - name: a\n");
        Path nested = Files.createDirectories(tmp.resolve("a/b/c"));

        assertThat(ConfigLoader.locate(nested)).contains(file.toAbsolutePath().normalize());
    }

    @Test
    void writerOutputParsesBackToTheSameConfig() throws IOException {
        Path file = write("pmm.yaml", """
                repositories:
                  - name: platform-core
                    url: git@github.com:anorys/platform-core.git
                  - name: billing
                    baseBranch: release/2.4
                    releaseTagPattern: "billing-{version}"
                maven:
                  settings: settings.xml
                checks:
                  git.behind: { severity: error, maxDays: 3 }
                """);
        PmmConfig original = ConfigLoader.load(file);

        String yaml = ConfigWriter.toYaml(original);
        PmmConfig reparsed = ConfigLoader.parse(yaml, file);

        assertThat(reparsed).isEqualTo(original);
        assertThat(yaml).contains("baseBranch: release/2.4").doesNotContain("\\");
    }

    @Test
    void warnsAboutMissingRepositories() throws IOException {
        Path core = Files.createDirectories(tmp.resolve("platform-core/.git"));
        Files.writeString(core.getParent().resolve("pom.xml"), "<project/>");
        Files.createDirectories(tmp.resolve("billing"));
        Path file = write("pmm.yaml", """
                repositories:
                  - name: platform-core
                  - name: billing
                  - name: reporting
                  - name: ignored
                    enabled: false
                """);

        List<String> warnings = WorkspaceWarnings.of(ConfigLoader.load(file));

        assertThat(warnings).hasSize(3);
        assertThat(warnings).anySatisfy(w -> assertThat(w).startsWith("billing:").contains("not a git repository"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).startsWith("billing:").contains("no pom.xml"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).startsWith("reporting:").contains("does not exist"));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(tmp.resolve(name), content);
    }
}
