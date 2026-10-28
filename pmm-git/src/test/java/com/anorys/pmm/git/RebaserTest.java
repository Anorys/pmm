package com.anorys.pmm.git;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.git.GitClient.Operation;
import com.anorys.pmm.git.Rebaser.Outcome;
import com.anorys.pmm.git.Rebaser.Plan;
import com.anorys.pmm.git.Rebaser.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.anorys.pmm.git.GitFixture.gitOk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "core" is on feature/PMM-142 (pushed), with one commit editing shared.txt; develop then moves on. */
class RebaserTest {

    private static final Instant DATE = Instant.parse("2026-02-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path ws;

    private GitFixture fixture;
    private Path publisher;
    private Path core;
    private PmmConfig config;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new GitFixture(ws.resolve("remotes"));
        publisher = ws.resolve("remotes/publisher");
        fixture.commitFile(publisher, "shared.txt", "one\n", "add shared", DATE);
        gitOk(publisher, "push", "--quiet", "origin", "HEAD:develop");
        core = fixture.cloneAs("../core");
        gitOk(core, "checkout", "--quiet", "-b", "feature/PMM-142");
        fixture.commitFile(core, "shared.txt", "one\nfeature\n", "feature edit", DATE);
        gitOk(core, "push", "--quiet", "-u", "origin", "feature/PMM-142");
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n");
        config = ConfigLoader.load(ws.resolve("pmm.yaml"));
    }

    @Test
    void rebasesWithABackupAndPushesWithALease() {
        fixture.advanceDevelop(2, DATE);
        Rebaser rebaser = new Rebaser(config, GitFixture.RUNNER, CLOCK);

        Plan plan = rebaser.plan(config.repositories().get(0), true, false);
        assertThat(plan.allowed()).isTrue();
        assertThat(plan.ahead()).isEqualTo(1);
        assertThat(plan.behind()).isEqualTo(2);
        assertThat(plan.predictedConflicts()).isEmpty();

        String before = gitOk(core, "rev-parse", "HEAD").strip();
        Result result = rebaser.rebase(plan, false, false);

        assertThat(result.outcome()).isEqualTo(Outcome.REBASED);
        assertThat(result.message()).isEqualTo("1 commit of feature/PMM-142 replayed onto origin/develop");
        assertThat(result.backupRef()).isEqualTo("refs/pmm/backup/feature/PMM-142/20260301-120000");
        assertThat(gitOk(core, "rev-parse", result.backupRef()).strip()).isEqualTo(before);
        assertThat(new GitClient(GitFixture.RUNNER, core).aheadBehind("origin/develop", "HEAD").behind()).isZero();

        assertThat(rebaser.push(result)).isNull();
        assertThat(gitOk(fixture.origin(), "rev-parse", "feature/PMM-142").strip())
                .isEqualTo(gitOk(core, "rev-parse", "HEAD").strip());
    }

    @Test
    void theLeaseRefusesToOverwriteSomeoneElsesPush() {
        fixture.advanceDevelop(1, DATE);
        Rebaser rebaser = new Rebaser(config, GitFixture.RUNNER, CLOCK);
        Plan plan = rebaser.plan(config.repositories().get(0), true, false);
        Result result = rebaser.rebase(plan, false, false);

        Path colleague = fixture.cloneAs("../colleague");
        gitOk(colleague, "checkout", "--quiet", "feature/PMM-142");
        fixture.commit(colleague, "colleague work", DATE);
        gitOk(colleague, "push", "--quiet", "origin", "feature/PMM-142");

        assertThatThrownBy(() -> rebaser.push(result)).isInstanceOf(GitException.class).hasMessageContaining("push");
    }

    @Test
    void predictsConflictsAndAbortsOrKeepsTheRebase() {
        gitOk(publisher, "pull", "--quiet", "--ff-only", "origin", "develop");
        fixture.commitFile(publisher, "shared.txt", "one\ndevelop\n", "develop edit", DATE);
        gitOk(publisher, "push", "--quiet", "origin", "HEAD:develop");
        Rebaser rebaser = new Rebaser(config, GitFixture.RUNNER, CLOCK);
        String before = gitOk(core, "rev-parse", "HEAD").strip();

        Plan plan = rebaser.plan(config.repositories().get(0), true, false);
        assertThat(plan.predictedConflicts()).containsExactly("shared.txt");

        Result aborted = rebaser.rebase(plan, false, false);
        assertThat(aborted.outcome()).isEqualTo(Outcome.CONFLICTS);
        assertThat(aborted.conflicts()).containsExactly("shared.txt");
        assertThat(aborted.kept()).isFalse();
        assertThat(gitOk(core, "rev-parse", "HEAD").strip()).isEqualTo(before);
        assertThat(new GitClient(GitFixture.RUNNER, core).operationInProgress()).isEqualTo(Operation.NONE);

        Result kept = rebaser.rebase(rebaser.plan(config.repositories().get(0), false, false), false, true);
        assertThat(kept.kept()).isTrue();
        assertThat(new GitClient(GitFixture.RUNNER, core).operationInProgress()).isEqualTo(Operation.REBASE);
    }

    @Test
    void refusesProtectedBranchesDirtyTreesAndDetachedHeads() throws IOException {
        fixture.advanceDevelop(1, DATE);
        Rebaser rebaser = new Rebaser(config, GitFixture.RUNNER, CLOCK);

        Files.writeString(core.resolve("wip.txt"), "wip");
        assertThat(rebaser.plan(config.repositories().get(0), false, false).refusals())
                .containsExactly("uncommitted changes; commit or stash them, or use --autostash");
        Plan autostash = rebaser.plan(config.repositories().get(0), false, true);
        assertThat(autostash.allowed()).isTrue();
        Files.delete(core.resolve("wip.txt"));

        gitOk(core, "checkout", "--quiet", "develop");
        gitOk(core, "fetch", "--quiet", "origin");
        fixture.commit(core, "local develop work", DATE);
        Plan develop = rebaser.plan(config.repositories().get(0), false, false);
        assertThat(develop.allowed()).as("local commits of develop may be replayed onto origin/develop").isTrue();
        Result rebased = rebaser.rebase(develop, false, false);
        assertThat(rebased.outcome()).isEqualTo(Outcome.REBASED);
        assertThat(rebaser.push(rebased)).isEqualTo("develop is push-protected (branches.pushProtected); pmm never pushes it");

        gitOk(core, "checkout", "--quiet", "--detach");
        assertThat(rebaser.plan(config.repositories().get(0), false, false).refusals())
                .containsExactly("HEAD is detached; check out a branch first");
    }

    @Test
    void upToDateBranchHasNothingToDo() {
        Rebaser rebaser = new Rebaser(config, GitFixture.RUNNER, CLOCK);

        Result result = rebaser.rebase(rebaser.plan(config.repositories().get(0), false, false), false, false);

        assertThat(result.outcome()).isEqualTo(Outcome.UP_TO_DATE);
        assertThat(rebaser.push(result)).isEqualTo("nothing to push");
    }

    @Test
    void protectedBranchPatterns() {
        assertThat(Rebaser.glob("release/*").matcher("release/2.4").matches()).isTrue();
        assertThat(Rebaser.glob("release/*").matcher("release/2.4/hotfix").matches()).isFalse();
        assertThat(Rebaser.glob("release/**").matcher("release/2.4/hotfix").matches()).isTrue();
        assertThat(Rebaser.glob("develop").matcher("develop-old").matches()).isFalse();
    }
}
