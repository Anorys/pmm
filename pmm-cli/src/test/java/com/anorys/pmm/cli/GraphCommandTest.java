package com.anorys.pmm.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** core 2.0.0-SNAPSHOT &lt;- billing (uses core-api 1.9.0) &lt;- reporting */
class GraphCommandTest {

    @TempDir
    Path ws;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void createWorkspace() throws IOException {
        pom("core", "core", "2.0.0-SNAPSHOT", "<packaging>pom</packaging><modules><module>core-api</module></modules>");
        Files.writeString(Files.createDirectories(ws.resolve("core/core-api")).resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <parent><groupId>com.anorys</groupId><artifactId>core</artifactId><version>2.0.0-SNAPSHOT</version></parent>
                <artifactId>core-api</artifactId></project>
                """);
        pom("billing", "billing", "1.5.0-SNAPSHOT", dependency("core-api", "1.9.0"));
        pom("reporting", "reporting", "0.3.0-SNAPSHOT", dependency("billing", "1.4.2"));
        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: billing
                  - name: reporting
                """);
    }

    @Test
    void printsBuildOrderUsagesAndCycles() {
        int code = run("graph");

        assertThat(code).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .contains("Workspace: 3 repositories, 4 modules")
                .containsSubsequence("1. core", "2. billing", "3. reporting")
                .contains("billing -> core", "billing uses core-api 1.9.0 (workspace: 2.0.0-SNAPSHOT)")
                .contains("reporting uses billing 1.4.2 (workspace: 1.5.0-SNAPSHOT)")
                .contains("Cycles: none");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void focusesOnOneRepository() {
        assertThat(run("graph", "--repo", "billing")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString())
                .contains("billing 1.5.0-SNAPSHOT")
                .containsSubsequence("Depends on:", "directly:   core", "Needed by:", "directly:   reporting");

        assertThat(run("graph", "--repo", "core")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("indirectly: reporting");

        assertThat(run("graph", "--repo", "nope")).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(err.toString()).contains("Unknown repository 'nope'", "core, billing, reporting");
    }

    @Test
    void exportsMermaidAndDot() {
        assertThat(run("graph", "--format", "mermaid")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).startsWith("graph LR").contains("billing --> core", "reporting --> billing");

        assertThat(run("graph", "--format", "mermaid", "--modules")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("subgraph repo_core", "com_anorys_core_api --> com_anorys_core");

        assertThat(run("graph", "-f", "dot")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).startsWith("digraph workspace").contains("\"billing\" -> \"core\"");

        assertThat(run("graph", "-f", "dot", "--modules")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("cluster_0", "\"com.anorys:core-api\" -> \"com.anorys:core\"");
    }

    @Test
    void cyclesAreErrorsByDefaultAndFollowTheConfiguredSeverity() throws IOException {
        pom("core", "core", "2.0.0-SNAPSHOT", "<packaging>pom</packaging><modules><module>core-api</module></modules>"
                + dependency("reporting", "0.2.0"));

        assertThat(run("graph")).isEqualTo(ExitCodes.ERRORS);
        assertThat(out.toString()).contains("Cycles: 1");
        assertThat(err.toString()).contains("error:", "repositories depend on each other");

        Files.writeString(ws.resolve("pmm.local.yaml"), "checks:\n  graph.cycle: warn\n");
        assertThat(run("graph")).isEqualTo(ExitCodes.OK);
        assertThat(run("graph", "--strict")).isEqualTo(ExitCodes.WARNINGS);
    }

    @Test
    void scanWarningsGoToStandardError() throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n  - name: not-cloned\n");

        assertThat(run("graph")).isEqualTo(ExitCodes.OK);
        assertThat(err.toString()).contains("warning:", "not-cloned: no pom.xml");
        assertThat(run("graph", "--strict")).isEqualTo(ExitCodes.WARNINGS);
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

    private static String dependency(String artifactId, String version) {
        return "<dependencies><dependency><groupId>com.anorys</groupId><artifactId>" + artifactId
                + "</artifactId><version>" + version + "</version></dependency></dependencies>";
    }

    private void pom(String dir, String artifactId, String version, String extra) throws IOException {
        Files.writeString(Files.createDirectories(ws.resolve(dir)).resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.anorys</groupId><artifactId>" + artifactId
                        + "</artifactId><version>" + version + "</version>" + extra + "</project>\n");
    }
}
