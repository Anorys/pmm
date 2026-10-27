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

/** core: feature branch behind develop; billing: up to date with a local change; reporting: not cloned. */
class StatusCommandTest {

    private static final SystemProcessRunner RUNNER = new SystemProcessRunner();

    @TempDir
    Path ws;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void createWorkspace() throws IOException {
        Path origin = ws.resolve("remotes/core.git");
        Files.createDirectories(origin.getParent());
        git(ws, "init", "--quiet", "--bare", "--initial-branch=develop", origin.toString());
        git(ws, "clone", "--quiet", origin.toString(), "core");
        Path core = ws.resolve("core");
        commit(core, "a.txt");
        git(core, "push", "--quiet", "origin", "HEAD:develop");
        git(ws, "clone", "--quiet", "--branch", "develop", origin.toString(), "billing");

        git(core, "checkout", "--quiet", "-b", "feature/PMM-142");
        commit(core, "feature.txt");
        git(ws.resolve("billing"), "checkout", "--quiet", "-b", "tmp");
        for (int i = 0; i < 25; i++) {
            commit(ws.resolve("billing"), "develop-" + i + ".txt");
        }
        git(ws.resolve("billing"), "push", "--quiet", "origin", "HEAD:develop");
        git(ws.resolve("billing"), "checkout", "--quiet", "develop");
        git(ws.resolve("billing"), "merge", "--quiet", "--ff-only", "tmp");
        Files.writeString(ws.resolve("billing/a.txt"), "local change");

        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: billing
                  - name: reporting
                """);
    }

    @Test
    void showsBranchesFreshnessAndFindings() {
        int code = run("status");

        assertThat(out.toString())
                .contains("REPO", "BRANCH", "BASE", "AHEAD", "BEHIND", "STATE", "LOCAL")
                .containsPattern("core\\s+feature/PMM-142\\s+origin/develop\\s+1\\s+25\\s+rebase advised\\s+clean")
                .containsPattern("billing\\s+develop\\s+origin/develop\\s+0\\s+0\\s+up to date\\s+1 change")
                .containsPattern("reporting\\s+-\\s+origin/develop\\s+-\\s+-\\s+unavailable")
                .contains("warn   core       25 commits behind origin/develop")
                .contains("-> pmm rebase core (rebases feature/PMM-142 onto origin/develop)")
                .contains("info   billing    uncommitted changes: 1 modified")
                .contains("0 errors, 2 warnings, 1 info");
        assertThat(code).isEqualTo(ExitCodes.OK);
        assertThat(run("status", "--strict")).isEqualTo(ExitCodes.WARNINGS);
    }

    @Test
    void filtersRepositoriesAndSkipsFetch() {
        assertThat(run("status", "--repo", "billing", "--no-fetch")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("billing").doesNotContain("core").contains("(not fetched)");

        assertThat(run("status", "--repo", "nope")).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(err.toString()).contains("Unknown repository 'nope'");
    }

    @Test
    void errorFindingsGiveExitCodeTwo() throws IOException {
        Files.writeString(ws.resolve("pmm.local.yaml"), """
                repositories:
                  - name: core
                    baseBranch: release/1.0
                """);

        assertThat(run("status", "--repo", "core")).isEqualTo(ExitCodes.ERRORS);
        assertThat(out.toString()).contains("base missing", "error  core  base branch origin/release/1.0 does not exist");
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
