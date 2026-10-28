package com.anorys.pmm.git;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.git.BranchUpdater.Outcome;
import com.anorys.pmm.git.BranchUpdater.Plan;
import com.anorys.pmm.git.BranchUpdater.Result;
import com.anorys.pmm.git.BranchUpdater.Target;
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

import static com.anorys.pmm.git.GitFixture.gitOk;
import static org.assertj.core.api.Assertions.assertThat;

/** "core" is a clone on develop; origin/develop then moves on, as when colleagues merge their work. */
class BranchUpdaterTest {

    private static final Instant DATE = Instant.parse("2026-02-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path ws;

    private GitFixture fixture;
    private Path core;
    private PmmConfig config;
    private RepositoryConfig repository;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new GitFixture(ws.resolve("remotes"));
        core = fixture.cloneAs("../core");
        config(List.of());
        fixture.advanceDevelop(2, DATE);
        gitOk(core, "fetch", "--quiet", "origin");
    }

    private void config(List<String> extra) throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n" + String.join("\n", extra) + "\n");
        config = ConfigLoader.load(ws.resolve("pmm.yaml"));
        repository = config.repositories().get(0);
    }

    private BranchUpdater updater() {
        return new BranchUpdater(config, GitFixture.RUNNER, CLOCK);
    }

    private String head() {
        return gitOk(core, "rev-parse", "HEAD").strip();
    }

    @Test
    void theProtectedDevelopCanBeFastForwardedFromItsRemote() {
        List<Plan> plans = updater().plans(repository, false, false);

        assertThat(plans).hasSize(1);
        Plan plan = plans.get(0);
        assertThat(plan.targetRef()).isEqualTo("origin/develop");
        assertThat(plan.pushProtected()).isTrue();
        assertThat(plan.behind()).isEqualTo(2);
        assertThat(plan.suggested()).isEqualTo(UpdateStrategy.FF);
        assertThat(plan.option(UpdateStrategy.FF).allowed()).isTrue();
        assertThat(plan.option(UpdateStrategy.RESET_SOFT).refusal()).startsWith("would stage the reverse");

        Result result = updater().update(plan, UpdateStrategy.FF);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.message()).isEqualTo("develop fast-forwarded by 2 commits of origin/develop");
        assertThat(head()).isEqualTo(gitOk(core, "rev-parse", "origin/develop").strip());
        assertThat(updater().pushable(result)).isFalse();
    }

    @Test
    void localCommitsOnDevelopAreRebasedButPublishedOnesAreNeverRewritten() {
        fixture.commit(core, "local work", DATE);
        Plan plan = updater().plan(repository, Target.UPSTREAM, false, false);

        assertThat(plan.ahead()).isEqualTo(1);
        assertThat(plan.published()).isZero();
        assertThat(plan.option(UpdateStrategy.FF).refusal()).isEqualTo("not a fast-forward: 1 local commit not on origin/develop");
        assertThat(plan.suggested()).isEqualTo(UpdateStrategy.REBASE);

        Result result = updater().update(plan, UpdateStrategy.REBASE);
        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.backupRef()).isEqualTo("refs/pmm/backup/develop/20260301-120000");
        assertThat(new GitClient(GitFixture.RUNNER, core).aheadBehind("origin/develop", "HEAD").behind()).isZero();
        assertThat(updater().push(result)).contains("push-protected");

        // A branch "release/2.4" pushed with a commit: rewriting it onto develop would rewrite published history.
        gitOk(core, "checkout", "--quiet", "-b", "release/2.4", "HEAD~3");
        fixture.commit(core, "release fix", DATE);
        gitOk(core, "push", "--quiet", "-u", "origin", "release/2.4");
        Plan onBase = updater().plan(repository, Target.BASE, false, false);
        assertThat(onBase.published()).isEqualTo(1);
        assertThat(onBase.option(UpdateStrategy.REBASE).refusal())
                .isEqualTo("release/2.4 is push-protected and 1 commit already on origin/release/2.4 would be rewritten");
        assertThat(onBase.option(UpdateStrategy.MERGE).allowed()).isTrue();
    }

    @Test
    void resetHardToTheRemoteKeepsWhatItDropsInBackupRefs() throws IOException {
        fixture.commit(core, "local mistake", DATE);
        String before = head();
        Files.writeString(core.resolve("file-1.txt"), "edited");
        Plan plan = updater().plan(repository, Target.UPSTREAM, false, false);

        assertThat(plan.option(UpdateStrategy.RESET_HARD).danger()).isTrue();
        assertThat(plan.option(UpdateStrategy.REBASE).refusal()).startsWith("1 uncommitted change; commit or stash them");

        Result result = updater().update(plan, UpdateStrategy.RESET_HARD);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(head()).isEqualTo(gitOk(core, "rev-parse", "origin/develop").strip());
        assertThat(gitOk(core, "rev-parse", result.backupRef()).strip()).isEqualTo(before);
        assertThat(result.wipRef()).isEqualTo("refs/pmm/wip/develop/20260301-120000");
        assertThat(gitOk(core, "show", result.wipRef() + ":file-1.txt").strip()).isEqualTo("edited");
        assertThat(new GitClient(GitFixture.RUNNER, core).status().clean()).isTrue();
    }

    @Test
    void resetSoftSquashesTheLocalCommitsIntoStagedChanges() {
        gitOk(core, "reset", "--quiet", "--hard", "origin/develop");
        fixture.commit(core, "one", DATE);
        fixture.commit(core, "two", DATE);

        Result result = updater().update(updater().plan(repository, Target.UPSTREAM, false, false), UpdateStrategy.RESET_SOFT);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(head()).isEqualTo(gitOk(core, "rev-parse", "origin/develop").strip());
        assertThat(new GitClient(GitFixture.RUNNER, core).status().changed()).isEqualTo(2);
    }

    @Test
    void featureBranchesChooseBetweenTheirUpstreamAndTheBaseAndMayBePushed() {
        gitOk(core, "checkout", "--quiet", "-b", "feature/x", "HEAD");
        fixture.commit(core, "feature", DATE);
        gitOk(core, "push", "--quiet", "-u", "origin", "feature/x");

        List<Plan> plans = updater().plans(repository, false, false);
        assertThat(plans).extracting(Plan::targetRef).containsExactly("origin/feature/x", "origin/develop");
        Plan onBase = plans.get(1);
        assertThat(onBase.behind()).isEqualTo(2);
        assertThat(onBase.suggested()).isEqualTo(UpdateStrategy.REBASE);
        assertThat(onBase.option(UpdateStrategy.REBASE).warning()).contains("rewrites 1 commit already on origin/feature/x: needs a force push");

        Result merged = updater().update(onBase, UpdateStrategy.MERGE);
        assertThat(merged.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(merged.message()).isEqualTo("2 commits of origin/develop merged into feature/x");
        assertThat(updater().pushable(merged)).isTrue();
        assertThat(updater().push(merged)).isNull();
        assertThat(gitOk(fixture.origin(), "rev-parse", "feature/x").strip()).isEqualTo(head());
    }

    @Test
    void updateProtectedBranchesAreNeverTouchedAndStrategiesAreConfigurable() throws IOException {
        config(List.of("branches:", "  updateProtected: [develop]"));
        assertThat(updater().plan(repository, Target.UPSTREAM, false, false).refusals())
                .containsExactly("develop is update-protected (branches.updateProtected); pmm never changes it locally");

        config(List.of("branches:", "  strategies:", "    develop: merge"));
        assertThat(updater().plan(repository, Target.UPSTREAM, false, false).suggested()).isEqualTo(UpdateStrategy.MERGE);
    }

    @Test
    void mergeConflictsAreAborted() {
        fixture.commitFile(core, "file-2.txt", "mine", "conflicting", DATE);
        String before = head();
        Plan plan = updater().plan(repository, Target.UPSTREAM, false, false);
        assertThat(plan.predictedConflicts()).containsExactly("file-2.txt");

        Result result = updater().update(plan, UpdateStrategy.MERGE);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICTS);
        assertThat(result.conflicts()).containsExactly("file-2.txt");
        assertThat(head()).isEqualTo(before);
        assertThat(new GitClient(GitFixture.RUNNER, core).operationInProgress()).isEqualTo(GitClient.Operation.NONE);
    }
}
