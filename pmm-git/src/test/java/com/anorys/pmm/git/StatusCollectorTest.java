package com.anorys.pmm.git;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.GitClient.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.anorys.pmm.git.GitFixture.git;
import static com.anorys.pmm.git.GitFixture.gitOk;
import static org.assertj.core.api.Assertions.assertThat;

class StatusCollectorTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path ws;

    private GitFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new GitFixture(ws.resolve("remotes"));
    }

    @Test
    void freshCloneIsUpToDateAndClean() throws IOException {
        fixture.cloneAs("../core");

        RepositoryStatus status = collectOne("core", "");

        assertThat(status.readable()).isTrue();
        assertThat(status.tree().branch()).isEqualTo("develop");
        assertThat(status.tree().upstream()).isEqualTo("origin/develop");
        assertThat(status.baseRef()).isEqualTo("origin/develop");
        assertThat(status.ahead()).isZero();
        assertThat(status.behind()).isZero();
        assertThat(status.tree().clean()).isTrue();
        assertThat(findings(status)).isEmpty();
    }

    @Test
    void featureBranchBehindDevelopNeedsARebase() throws IOException {
        Path core = fixture.cloneAs("../core");
        gitOk(core, "checkout", "--quiet", "-b", "feature/PMM-142");
        fixture.commit(core, "feature work", NOW.minusSeconds(3600));
        fixture.commit(core, "more feature work", NOW.minusSeconds(1800));
        fixture.advanceDevelop(3, NOW.minus(java.time.Duration.ofDays(10)));

        RepositoryStatus status = collectOne("core", "");

        assertThat(status.tree().branch()).isEqualTo("feature/PMM-142");
        assertThat(status.ahead()).isEqualTo(2);
        assertThat(status.behind()).isEqualTo(3);
        assertThat(status.canFastForward()).isFalse();
        assertThat(status.behindSince()).isEqualTo(NOW.minus(java.time.Duration.ofDays(10)));
        List<Finding> findings = findings(status);
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.checkId()).isEqualTo(KnownChecks.GIT_BEHIND);
            assertThat(f.severity()).isEqualTo(Severity.WARN);
            assertThat(f.message()).isEqualTo("3 commits behind origin/develop, oldest missing commit 10 days old");
            assertThat(f.suggestion()).isEqualTo("pmm rebase core (rebases feature/PMM-142 onto origin/develop)");
        });
    }

    @Test
    void recentSmallLagIsOnlyInformationAndFastForwardable() throws IOException {
        fixture.cloneAs("../core");
        fixture.advanceDevelop(2, NOW.minusSeconds(7200));

        RepositoryStatus status = collectOne("core", "");

        assertThat(status.canFastForward()).isTrue();
        assertThat(findings(status)).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.message()).isEqualTo("2 commits behind origin/develop");
            assertThat(f.suggestion()).isEqualTo("fast-forward: pmm sync --fast-forward (git merge --ff-only origin/develop)");
        });
    }

    @Test
    void commitThresholdAppliesWithTheConfiguredSeverity() throws IOException {
        fixture.cloneAs("../core");
        fixture.advanceDevelop(3, NOW.minusSeconds(60));

        RepositoryStatus status = collectOne("core", "");
        PmmConfig strictConfig = config("core", "", "checks:\n  git.behind: { severity: error, maxCommits: 3 }\n");

        List<Finding> findings = new GitChecks(strictConfig, CLOCK).evaluate(List.of(status));

        assertThat(findings).singleElement().extracting(Finding::severity).isEqualTo(Severity.ERROR);
    }

    @Test
    void reportsLocalChanges() throws IOException {
        Path core = fixture.cloneAs("../core");
        GitFixture.write(core.resolve("file-1.txt"), "changed");
        GitFixture.write(core.resolve("new.txt"), "new");

        RepositoryStatus status = collectOne("core", "");

        assertThat(status.tree().changed()).isEqualTo(1);
        assertThat(status.tree().untracked()).isEqualTo(1);
        assertThat(findings(status)).singleElement().satisfies(f -> {
            assertThat(f.checkId()).isEqualTo(KnownChecks.GIT_DIRTY);
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.message()).isEqualTo("uncommitted changes: 1 modified, 1 untracked");
        });
    }

    @Test
    void detectsARebaseInProgress() throws IOException {
        Path core = fixture.cloneAs("../core");
        gitOk(core, "checkout", "--quiet", "-b", "feature/conflict");
        fixture.commitFile(core, "shared.txt", "mine", "mine", NOW.minusSeconds(600));
        Path other = fixture.cloneAs("other");
        fixture.commitFile(other, "shared.txt", "theirs", "theirs", NOW.minusSeconds(300));
        gitOk(other, "push", "--quiet", "origin", "HEAD:develop");
        gitOk(core, "fetch", "--quiet", "origin");
        assertThat(git(core, "rebase", "origin/develop").succeeded()).isFalse();

        RepositoryStatus status = collectOne("core", "");

        assertThat(status.tree().operation()).isEqualTo(Operation.REBASE);
        assertThat(findings(status)).anySatisfy(f -> assertThat(f.message()).isEqualTo("a rebase is in progress"));
    }

    @Test
    void missingBaseBranchIsAnError() throws IOException {
        fixture.cloneAs("../core");

        RepositoryStatus status = collectOne("core", "    baseBranch: release/9.9\n");

        assertThat(status.baseMissing()).isTrue();
        assertThat(findings(status, "    baseBranch: release/9.9\n")).singleElement().satisfies(f -> {
            assertThat(f.checkId()).isEqualTo(KnownChecks.GIT_BASE_MISSING);
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.message()).contains("origin/release/9.9");
        });
    }

    @Test
    void notClonedRepositoryIsReported() throws IOException {
        RepositoryStatus status = collectOne("core", "");

        assertThat(status.readable()).isFalse();
        assertThat(findings(status)).singleElement().satisfies(f -> {
            assertThat(f.checkId()).isEqualTo(KnownChecks.GIT_NOT_CLONED);
            assertThat(f.message()).startsWith("not cloned");
        });
    }

    @Test
    void fetchFailureKeepsTheLastKnownState() throws IOException {
        Path core = fixture.cloneAs("../core");
        gitOk(core, "remote", "set-url", "origin", ws.resolve("gone.git").toString());

        RepositoryStatus status = collectOne("core", "");

        assertThat(status.fetchError()).startsWith("git fetch origin failed");
        assertThat(status.behind()).isZero();
        assertThat(findings(status)).singleElement().extracting(Finding::checkId).isEqualTo(KnownChecks.GIT_FETCH_FAILED);
    }

    @Test
    void collectsSeveralRepositoriesInConfigurationOrder() throws IOException {
        fixture.cloneAs("../core");
        fixture.cloneAs("../billing");
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n  - name: billing\n  - name: absent\n");
        PmmConfig config = ConfigLoader.load(ws.resolve("pmm.yaml"));

        List<RepositoryStatus> statuses = new StatusCollector(GitFixture.RUNNER)
                .collect(config, config.monitoredRepositories(), true);

        assertThat(statuses).extracting(RepositoryStatus::name).containsExactly("core", "billing", "absent");
        assertThat(statuses).extracting(RepositoryStatus::readable).containsExactly(true, true, false);
    }

    private RepositoryStatus collectOne(String name, String repoOptions) throws IOException {
        PmmConfig config = config(name, repoOptions, "");
        return new StatusCollector(GitFixture.RUNNER).collect(config, config.monitoredRepositories().get(0), true);
    }

    private List<Finding> findings(RepositoryStatus status) throws IOException {
        return findings(status, "");
    }

    private List<Finding> findings(RepositoryStatus status, String repoOptions) throws IOException {
        return new GitChecks(config(status.name(), repoOptions, ""), CLOCK).evaluate(List.of(status));
    }

    private PmmConfig config(String name, String repoOptions, String extra) throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: " + name + "\n" + repoOptions + extra);
        return ConfigLoader.load(ws.resolve("pmm.yaml"));
    }
}
