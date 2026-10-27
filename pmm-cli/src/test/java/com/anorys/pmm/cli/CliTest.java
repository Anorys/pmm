package com.anorys.pmm.cli;

import com.anorys.pmm.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void initWritesAConfigThatConfigCommandAccepts() throws IOException {
        mavenRepo("platform-core");
        mavenRepo("billing");

        int initCode = run("init", tmp.toString());

        assertThat(initCode).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("Found 2 repositories", "billing", "platform-core");
        Path config = tmp.resolve("pmm.yaml");
        assertThat(config).exists();

        int configCode = run("config", "--config", config.toString());

        assertThat(configCode).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("name: platform-core", "git.behind:");
        assertThat(err.toString()).doesNotContain("warning");
    }

    @Test
    void everyCommandHasHelp() {
        for (String command : new String[]{"init", "config", "graph", "status", "deps", "build", "sync", "rebase", "doctor", "ui", "self-update"}) {
            assertThat(run(command, "--help")).as(command).isEqualTo(ExitCodes.OK);
            assertThat(out.toString()).as(command).contains("Usage: pmm " + command);
        }
    }

    @Test
    void initRefusesToOverwrite() throws IOException {
        Files.writeString(tmp.resolve("pmm.yaml"), "repositories: []\n");

        assertThat(run("init", tmp.toString())).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(err.toString()).contains("already exists", "--force");
        assertThat(run("init", tmp.toString(), "--force")).isEqualTo(ExitCodes.OK);
    }

    @Test
    void invalidConfigExitsWithToolErrorAndListsProblems() throws IOException {
        Path config = Files.writeString(tmp.resolve("pmm.yaml"), """
                workspace:
                  baseBrach: develop
                repositories:
                  - name: core
                """);

        int code = run("config", "--validate", "-c", config.toString());

        assertThat(code).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(err.toString()).contains("workspace.baseBrach is not a known setting");
    }

    @Test
    void strictModeTurnsWarningsIntoExitCodeOne() throws IOException {
        Path config = Files.writeString(tmp.resolve("pmm.yaml"), "repositories:\n  - name: not-cloned\n");

        assertThat(run("config", "--validate", "-c", config.toString())).isEqualTo(ExitCodes.OK);
        assertThat(err.toString()).contains("not-cloned", "does not exist");
        assertThat(run("config", "--validate", "--strict", "-c", config.toString())).isEqualTo(ExitCodes.WARNINGS);
    }

    @Test
    void unknownArgumentsAreToolErrors() {
        assertThat(run("frobnicate")).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(run()).isEqualTo(ExitCodes.TOOL_ERROR);
    }

    @Test
    void theDashboardNeedsATerminal() {
        // Tests run without a console, as scripts and CI do: a clear error rather than a broken screen.
        assertThat(run("ui", "--demo")).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(err.toString()).contains("needs an interactive terminal", "pmm doctor");
    }

    @Test
    void printsVersion() {
        assertThat(run("--version")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).startsWith("pmm ");
    }

    @Test
    void configFileNameMatchesLoader() {
        assertThat(ConfigLoader.CONFIG_FILE).isEqualTo("pmm.yaml");
    }

    private int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        CommandLine cli = Main.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        return cli.execute(args);
    }

    private void mavenRepo(String name) throws IOException {
        Path repo = Files.createDirectories(tmp.resolve(name).resolve(".git")).getParent();
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
    }
}
