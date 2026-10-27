package com.anorys.pmm.cli;

import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessSpec;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core (git clone, 3.1.5-SNAPSHOT, released 3.1.4 then one more commit on develop) is used by billing
 * (core-api 3.1.5: not released) and reporting (core-api 3.1.4: fine). Nexus has core-api 3.1.3 and 3.1.4.
 */
class DepsCommandTest {

    private static final SystemProcessRunner RUNNER = new SystemProcessRunner();

    @TempDir
    Path ws;

    private HttpServer nexus;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void createWorkspace() throws IOException {
        nexus = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        nexus.createContext("/releases/", exchange -> {
            boolean known = exchange.getRequestURI().getPath().endsWith("/com/anorys/core/core-api/maven-metadata.xml");
            byte[] body = """
                    <metadata><versioning><versions><version>3.1.3</version><version>3.1.4</version></versions></versioning></metadata>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 404, known ? body.length : -1);
            if (known) {
                try (OutputStream stream = exchange.getResponseBody()) {
                    stream.write(body);
                }
            }
            exchange.close();
        });
        nexus.start();

        Path origin = ws.resolve("remotes/core.git");
        Files.createDirectories(origin.getParent());
        git(ws, "init", "--quiet", "--bare", "--initial-branch=develop", origin.toString());
        git(ws, "clone", "--quiet", origin.toString(), "core");
        Path core = ws.resolve("core");
        commitPom(core, pom("com.anorys.core", "core-api", "3.1.4", ""), "[maven-release-plugin] prepare release");
        commitPom(core, pom("com.anorys.core", "core-api", "3.1.5-SNAPSHOT", ""), "[maven-release-plugin] next iteration");
        Files.writeString(core.resolve("Feature.java"), "class Feature {}");
        git(core, "add", "Feature.java");
        git(core, "commit", "--quiet", "-m", "add a feature");
        git(core, "push", "--quiet", "origin", "HEAD:develop");

        writePom("billing", pom("com.anorys.billing", "billing", "1.0.0-SNAPSHOT", dependency("3.1.5")));
        writePom("reporting", pom("com.anorys.reporting", "reporting", "1.0.0-SNAPSHOT", dependency("3.1.4")));
        Files.writeString(ws.resolve("pmm.yaml"), """
                repositories:
                  - name: core
                  - name: billing
                  - name: reporting
                internalGroups: [com.anorys.*]
                nexus:
                  url: http://127.0.0.1:%d/releases
                """.formatted(nexus.getAddress().getPort()));
    }

    @AfterEach
    void stopNexus() {
        nexus.stop(0);
    }

    @Test
    void reportsBlockersAndUnreleasedChanges() {
        int code = run("deps");

        assertThat(out.toString())
                .contains("Nexus: http://127.0.0.1:")
                .containsPattern("billing\\s+core-api\\s+core\\s+3.1.5\\s+3.1.4\\s+not released")
                .containsPattern("reporting\\s+core-api\\s+core\\s+3.1.4\\s+3.1.4\\s+ok")
                .contains("error  billing  billing uses core-api 3.1.5 from core, which is not released in Nexus")
                .contains("-> release core 3.1.5 (it is at 3.1.5-SNAPSHOT), or use the latest release 3.1.4")
                .contains("info   core     1 commit on origin/develop since release 3.1.4, used by billing, reporting")
                .contains("1 error, 0 warnings, 1 info");
        assertThat(code).isEqualTo(ExitCodes.ERRORS);
    }

    @Test
    void offlineSkipsNexus() {
        int code = run("deps", "--offline");

        assertThat(out.toString())
                .contains("offline: Nexus not queried")
                .containsPattern("billing\\s+core-api\\s+core\\s+3.1.5\\s+\\?\\s+unknown")
                .contains("0 errors, 0 warnings, 1 info");
        assertThat(code).isEqualTo(ExitCodes.OK);
    }

    @Test
    void doctorCombinesEveryCheck() {
        int code = run("doctor", "--no-fetch");

        assertThat(out.toString())
                .contains("Branches (not fetched)", "Dependencies between repositories")
                .containsPattern("core\\s+develop\\s+origin/develop\\s+0\\s+0\\s+up to date\\s+clean")
                .contains("billing uses core-api 3.1.5 from core, which is not released in Nexus")
                .contains("warn   billing  ")       // not a git repository
                .contains("1 error, 2 warnings, 1 info");
        assertThat(code).isEqualTo(ExitCodes.ERRORS);
    }

    @Test
    void doctorWritesJsonJUnitAndHtmlReports() throws Exception {
        Path reports = ws.resolve("out/reports");
        int code = run("doctor", "--no-fetch", "--json", reports.resolve("pmm.json").toString(),
                "--junit", reports.resolve("pmm-junit.xml").toString(), "--html", reports.resolve("pmm.html").toString());

        assertThat(code).isEqualTo(ExitCodes.ERRORS);
        assertThat(out.toString()).contains("Report written: " + reports.resolve("pmm.json"));

        JsonNode json = new ObjectMapper().readTree(reports.resolve("pmm.json").toFile());
        assertThat(json.get("tool").asText()).isEqualTo("pmm");
        assertThat(json.get("exitCode").asInt()).isEqualTo(2);
        assertThat(json.at("/summary/errors").asInt()).isEqualTo(1);
        assertThat(json.get("fetched").asBoolean()).isFalse();
        assertThat(json.get("repositories")).hasSize(3);
        assertThat(json.at("/dependencies/0/state").asText()).isEqualTo("not_released");
        assertThat(json.at("/dependencies/0/artifact").asText()).isEqualTo("com.anorys.core:core-api");
        assertThat(json.get("findings")).anySatisfy(f -> {
            assertThat(f.get("check").asText()).isEqualTo("deps.release-missing");
            assertThat(f.get("severity").asText()).isEqualTo("error");
            assertThat(f.get("repository").asText()).isEqualTo("billing");
        });

        Document junit = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(reports.resolve("pmm-junit.xml").toFile());
        Element suites = junit.getDocumentElement();
        assertThat(suites.getAttribute("failures")).isEqualTo("1");
        NodeList failures = junit.getElementsByTagName("failure");
        assertThat(failures.getLength()).isEqualTo(1);
        Element failed = (Element) failures.item(0).getParentNode();
        assertThat(failed.getAttribute("classname")).isEqualTo("pmm.deps");
        assertThat(failed.getAttribute("name")).isEqualTo("billing");
        assertThat(((Element) failures.item(0)).getAttribute("type")).isEqualTo("deps.release-missing");

        assertThat(Files.readString(reports.resolve("pmm.html")))
                .startsWith("<!doctype html>")
                .contains("<title>pmm doctor</title>", "billing uses core-api 3.1.5 from core, which is not released in Nexus")
                .contains("not released")
                .doesNotContain("<script", "<link", " src=");
    }

    private static String dependency(String version) {
        return "<dependency><groupId>com.anorys.core</groupId><artifactId>core-api</artifactId><version>"
                + version + "</version></dependency>";
    }

    private static String pom(String groupId, String artifactId, String version, String dependencies) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(groupId, artifactId, version, dependencies);
    }

    private void writePom(String repository, String content) throws IOException {
        Files.createDirectories(ws.resolve(repository));
        Files.writeString(ws.resolve(repository).resolve("pom.xml"), content);
    }

    private static void commitPom(Path repo, String content, String message) throws IOException {
        Files.writeString(repo.resolve("pom.xml"), content);
        git(repo, "add", "pom.xml");
        git(repo, "commit", "--quiet", "-m", message);
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
