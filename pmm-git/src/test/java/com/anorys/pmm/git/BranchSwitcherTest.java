package com.anorys.pmm.git;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.git.BranchSwitcher.Branch;
import com.anorys.pmm.git.BranchSwitcher.Outcome;
import com.anorys.pmm.git.BranchSwitcher.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.anorys.pmm.git.GitFixture.gitOk;
import static org.assertj.core.api.Assertions.assertThat;

/** "core" on develop, with a local feature/a; a colleague pushed feature/b. */
class BranchSwitcherTest {

    private static final Instant DATE = Instant.parse("2026-02-01T10:00:00Z");

    @TempDir
    Path ws;

    private Path core;
    private RepositoryConfig repository;
    private BranchSwitcher switcher;

    @BeforeEach
    void setUp() throws IOException {
        GitFixture fixture = new GitFixture(ws.resolve("remotes"));
        Path publisher = ws.resolve("remotes/publisher");
        gitOk(publisher, "checkout", "--quiet", "-b", "feature/b");
        fixture.commit(publisher, "b", Instant.parse("2026-02-02T10:00:00Z"));
        gitOk(publisher, "push", "--quiet", "origin", "feature/b");
        core = fixture.cloneAs("../core");
        gitOk(core, "branch", "feature/a");
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n");
        PmmConfig config = ConfigLoader.load(ws.resolve("pmm.yaml"));
        repository = config.repositories().get(0);
        switcher = new BranchSwitcher(config, GitFixture.RUNNER);
    }

    private String current() {
        return new GitClient(GitFixture.RUNNER, core).status().branch();
    }

    @Test
    void listsTheCurrentThenLocalThenRemoteOnlyBranches() {
        List<Branch> branches = switcher.branches(repository);

        assertThat(branches).extracting(Branch::name).containsExactly("develop", "feature/a", "feature/b");
        assertThat(branches.get(0).current()).isTrue();
        assertThat(branches.get(0).upstream()).isEqualTo("origin/develop");
        assertThat(branches.get(2).local()).isFalse();
    }

    @Test
    void switchesToARemoteBranchWithATrackingCopy() {
        Result result = switcher.switchTo(repository, "feature/b", StashMode.NEVER, true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.SWITCHED);
        assertThat(result.message()).isEqualTo("switched to feature/b (new local branch tracking origin/feature/b)");
        assertThat(new GitClient(GitFixture.RUNNER, core).status().upstream()).isEqualTo("origin/feature/b");
        assertThat(switcher.switchTo(repository, "feature/b", StashMode.NEVER, true, false).outcome()).isEqualTo(Outcome.ALREADY);
        assertThat(switcher.switchTo(repository, "nope", StashMode.NEVER, true, false).message())
                .isEqualTo("no branch nope here, nor origin/nope");
        assertThat(switcher.switchTo(repository, "feature/new", StashMode.NEVER, true, true).message())
                .isEqualTo("switched to feature/new (new branch from origin/develop)");
    }

    @Test
    void parkedChangesStayWithTheirBranchAndComeBack() throws IOException {
        Files.writeString(core.resolve("wip.txt"), "work in progress");

        assertThat(switcher.switchTo(repository, "feature/a", StashMode.ASK, true, false).outcome()).isEqualTo(Outcome.REFUSED);

        Result away = switcher.switchTo(repository, "feature/a", StashMode.PARK, true, false);
        assertThat(away.outcome()).isEqualTo(Outcome.SWITCHED);
        assertThat(away.stashed()).isEqualTo("1 change parked with develop");
        assertThat(Files.exists(core.resolve("wip.txt"))).isFalse();
        assertThat(switcher.branches(repository)).filteredOn(Branch::parked).extracting(Branch::name).containsExactly("develop");

        Result back = switcher.switchTo(repository, "develop", StashMode.PARK, true, false);
        assertThat(back.restored()).isTrue();
        assertThat(current()).isEqualTo("develop");
        assertThat(Files.readString(core.resolve("wip.txt"))).isEqualTo("work in progress");
        assertThat(new GitClient(GitFixture.RUNNER, core).stashes()).isEmpty();
    }

    @Test
    void carriedChangesComeAlong() throws IOException {
        Files.writeString(core.resolve("wip.txt"), "work in progress");

        Result result = switcher.switchTo(repository, "feature/a", StashMode.CARRY, true, false);

        assertThat(result.outcome()).isEqualTo(Outcome.SWITCHED);
        assertThat(result.warning()).isNull();
        assertThat(current()).isEqualTo("feature/a");
        assertThat(Files.readString(core.resolve("wip.txt"))).isEqualTo("work in progress");
        assertThat(new GitClient(GitFixture.RUNNER, core).stashes()).isEmpty();
    }
}
