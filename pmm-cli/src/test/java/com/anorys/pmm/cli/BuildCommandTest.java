package com.anorys.pmm.cli;

import com.anorys.pmm.core.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/** A fake Maven fails with the content of fail.txt; billing cannot download core-api 3.1.5 produced by core. */
class BuildCommandTest {

    @TempDir
    Path ws;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void createWorkspace() throws IOException {
        Path fake = fakeMaven(ws.resolve("tools"));
        pom("core", "com.anorys.core", "core-api", "3.1.5-SNAPSHOT", "");
        pom("billing", "com.anorys.billing", "billing", "1.0-SNAPSHOT",
                "<dependency><groupId>com.anorys.core</groupId><artifactId>core-api</artifactId><version>3.1.5</version></dependency>");
        Files.writeString(ws.resolve("billing/fail.txt"), """
                [ERROR] Failed to execute goal on project billing: Could not resolve dependencies for project com.anorys.billing:billing:jar:1.0-SNAPSHOT
                [ERROR] dependency: com.anorys.core:core-api:jar:3.1.5 (compile)
                [ERROR] \tCould not find artifact com.anorys.core:core-api:jar:3.1.5 in nexus (https://nexus/)
                """);
        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: billing
                maven:
                  executable: %s
                """.formatted(fake.toString().replace('\\', '/')));
    }

    @Test
    void buildsAndNamesTheBlocker() {
        int code = run("build", "-j", "2");

        assertThat(out.toString())
                .contains("Building 2 repositories, 2 at a time...")
                .containsPattern("core\\s+ok\\s+\\d+s\\s+\\.pmm[/\\\\]logs[/\\\\]core\\.log")
                .containsPattern("billing\\s+blocked")
                .contains("core blocks billing (to release: core-api 3.1.5)")
                .contains("error  billing  cannot build: core-api 3.1.5 from core could not be downloaded")
                .contains("-> release core 3.1.5 if it is not in Nexus yet")
                .contains("1 error, 0 warnings, 0 info");
        assertThat(code).isEqualTo(ExitCodes.ERRORS);

        assertThat(run("build", "--repo", "core")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).doesNotContain("billing");
        assertThat(run("build", "-j", "0")).isEqualTo(ExitCodes.TOOL_ERROR);
    }

    @Test
    void doctorBuildsOnRequest() {
        int code = run("doctor", "--offline", "--build");

        assertThat(out.toString()).contains("Builds", "core blocks billing");
        assertThat(code).isEqualTo(ExitCodes.ERRORS);
    }

    private void pom(String repository, String groupId, String artifactId, String version, String dependencies)
            throws IOException {
        Files.createDirectories(ws.resolve(repository));
        Files.writeString(ws.resolve(repository).resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId>
                <version>%s</version><dependencies>%s</dependencies></project>
                """.formatted(groupId, artifactId, version, dependencies));
    }

    private static Path fakeMaven(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (Platform.isWindows()) {
            Path script = dir.resolve("fake-mvn.cmd");
            Files.writeString(script, String.join("\r\n",
                    "@echo off", "if exist fail.txt (", "  type fail.txt", "  exit /b 1", ")", "echo [INFO] BUILD SUCCESS", ""));
            return script;
        }
        Path script = dir.resolve("fake-mvn");
        Files.writeString(script, """
                #!/bin/sh
                if [ -f fail.txt ]; then cat fail.txt; exit 1; fi
                echo "[INFO] BUILD SUCCESS"
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
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
}
