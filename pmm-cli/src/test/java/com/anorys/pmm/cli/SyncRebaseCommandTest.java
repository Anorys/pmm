package com.anorys.pmm.cli;

import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessSpec;
import com.anorys.pmm.core.process.SystemProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two clones of one origin: core is on feature/PMM-142 (one commit, pushed), billing is on develop.
 * develop then gets two more commits, so core needs a rebase and billing a fast-forward.
 */
class SyncRebaseCommandTest {

    private static final SystemProcessRunner RUNNER = new SystemProcessRunner();

    @TempDir
    Path ws;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void createWorkspace() throws IOException {
        Path origin = ws.resolve("remotes/origin.git");
        Files.createDirectories(origin.getParent());
        git(ws, "init", "--quiet", "--bare", "--initial-branch=develop", origin.toString());
        git(ws, "clone", "--quiet", origin.toString(), "publisher");
        Path publisher = ws.resolve("publisher");
        commit(publisher, "a.txt");
        git(publisher, "push", "--quiet", "origin", "HEAD:develop");

        git(ws, "clone", "--quiet", "--branch", "develop", origin.toString(), "core");
        git(ws, "clone", "--quiet", "--branch", "develop", origin.toString(), "billing");
        Path core = ws.resolve("core");
        for (Path clone : List.of(core, ws.resolve("billing"))) {
            // pmm runs git without the test variables, and a rebase commits.
            git(clone, "config", "user.name", "Test");
            git(clone, "config", "user.email", "test@example.com");
            git(clone, "config", "commit.gpgsign", "false");
        }
        git(core, "checkout", "--quiet", "-b", "feature/PMM-142");
        commit(core, "feature.txt");
        git(core, "push", "--quiet", "-u", "origin", "feature/PMM-142");

        commit(publisher, "b.txt");
        commit(publisher, "c.txt");
        git(publisher, "push", "--quiet", "origin", "HEAD:develop");

        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: billing
                rebase:
                  push: never
                """);
    }

    @Test
    void syncPredictsAndFastForwards() {
        assertThat(run("sync")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .containsPattern("core\\s+feature/PMM-142\\s+origin/develop\\s+1\\s+2\\s+rebase, no conflict expected")
                .containsPattern("billing\\s+develop\\s+origin/develop\\s+0\\s+2\\s+fast-forward")
                .contains("To catch up:", "  pmm rebase core", "pmm sync --fast-forward");

        assertThat(run("sync", "--fast-forward", "--no-fetch")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).containsPattern("billing\\s+develop\\s+origin/develop\\s+0\\s+2\\s+fast-forwarded");
        assertThat(run("sync", "--no-fetch", "--repo", "billing")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("up to date").doesNotContain("core");
    }

    @Test
    void rebaseReplaysBacksUpAndPushesOnlyWhenAsked() {
        assertThat(run("rebase", "core", "--dry-run")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).isEqualToIgnoringNewLines(
                "core: feature/PMM-142, 1 ahead, 2 behind origin/develop; no conflict expected");

        assertThat(run("rebase", "core")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .contains("rebased: 1 commit of feature/PMM-142 replayed onto origin/develop")
                .contains("backup: refs/pmm/backup/feature/PMM-142/")
                .contains("not pushed; to push: git push --force-with-lease origin feature/PMM-142");

        assertThat(run("rebase", "core")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("up to date: feature/PMM-142 already contains origin/develop");
    }

    @Test
    void rebasePushesWithALeaseWhenAsked() {
        assertThat(run("rebase", "core", "--push")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("pushed to origin/feature/PMM-142 (with lease)");
    }

    @Test
    void aPushProtectedBranchIsUpdatedLocallyButNeverPushed() {
        assertThat(run("rebase", "billing", "--push")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .contains("rebased: develop fast-forwarded to origin/develop")
                .contains("not pushed: develop is push-protected (branches.pushProtected)");
        assertThat(run("rebase", "nope")).isEqualTo(ExitCodes.TOOL_ERROR);
    }

    @Test
    void updateShowsEveryStrategyThenAppliesTheChosenOne() throws IOException {
        assertThat(run("update", "billing", "--dry-run")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .contains("billing: develop <- origin/develop, 0 ahead, 2 behind (push-protected)")
                .containsPattern("\\* ff\\s+moves develop forward by 2 commits of origin/develop")
                .containsPattern("reset-soft\\s+refused: would stage the reverse");

        assertThat(run("update", "billing")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("updated: develop fast-forwarded by 2 commits of origin/develop");
        assertThat(run("update", "billing", "--no-fetch")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("up to date");

        assertThat(run("update", "core", "--from", "base", "--strategy", "merge", "--no-push")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("updated: 2 commits of origin/develop merged into feature/PMM-142");

        Files.writeString(ws.resolve("core/feature.txt"), "changed");
        assertThat(run("update", "core", "--from", "base", "--strategy", "reset-hard")).isEqualTo(ExitCodes.ERRORS);
        assertThat(out.toString()).contains("confirm with --yes");
        assertThat(run("update", "core", "--from", "base", "--strategy", "reset-hard", "--yes", "--no-push")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("updated: feature/PMM-142 reset (hard) to origin/develop", "refs/pmm/wip/feature/PMM-142/");
        assertThat(run("update", "core", "--strategy", "squash")).isEqualTo(ExitCodes.TOOL_ERROR);
    }

    @Test
    void switchParksChangesAndListsBranches() throws IOException {
        Files.writeString(ws.resolve("billing/wip.txt"), "wip");
        assertThat(run("switch", "feature/PMM-142", "--stash", "park")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .contains("core: already on feature/PMM-142")
                .contains("billing: switched to feature/PMM-142 (new local branch tracking origin/feature/PMM-142)")
                .contains("1 change parked with develop");

        assertThat(run("switch", "--repo", "billing")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).containsPattern("billing\\s+develop\\s+origin/develop\\s+.*parked changes")
                .containsPattern("billing\\s+\\* feature/PMM-142");

        assertThat(run("switch", "develop", "--repo", "billing")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("the changes parked with develop are back");
        assertThat(ws.resolve("billing/wip.txt")).exists();

        assertThat(run("switch", "nothing-here")).isEqualTo(ExitCodes.ERRORS);
        assertThat(out.toString()).contains("No repository has a branch nothing-here");
    }

    private int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        String[] withConfig = new String[args.length + 2];
        System.arraycopy(args, 0, withConfig, 0, args.length);
        withConfig[args.length] = "--config";
        withConfig[args.length + 1] = ws.resolve("pmm.yaml").toString();
        CommandLine cli = Main.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        return cli.execute(withConfig);
    }

    private static void commit(Path repo, String file) throws IOException {
        Files.writeString(repo.resolve(file), file);
        git(repo, "add", file);
        git(repo, "commit", "--quiet", "-m", "add " + file);
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
