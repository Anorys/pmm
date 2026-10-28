package com.anorys.pmm.cli;

import com.anorys.pmm.cli.update.FakeReleases;
import com.anorys.pmm.cli.update.Release;
import com.anorys.pmm.cli.update.SelfUpdater;
import com.anorys.pmm.cli.update.UpdateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelfUpdateCommandTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(FakeReleases source, Path exe, String... args) {
        CommandLine cli = Main.commandLine();
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        SelfUpdateCommand command = cli.getSubcommands().get("self-update").getCommand();
        command.source = source;
        command.executable = exe;
        command.cache = new UpdateCache(tmp.resolve("cache.properties"));
        String[] full = new String[args.length + 1];
        full[0] = "self-update";
        System.arraycopy(args, 0, full, 1, args.length);
        return cli.execute(full);
    }

    private FakeReleases releases(String version, byte[] content) throws Exception {
        String asset = SelfUpdater.assetName(com.anorys.pmm.cli.update.ReleaseVersion.parse(version).orElseThrow(),
                System.getProperty("os.name"), "amd64").orElseThrow();
        String sum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        return new FakeReleases().add(version, Map.of(asset, content,
                Release.CHECKSUMS, (sum + "  " + asset + "\n").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void checkOnlyReports() throws Exception {
        Path exe = Files.writeString(tmp.resolve("pmm"), "old");

        assertThat(run(releases("99.0.0", new byte[]{1}), exe, "--check")).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("Latest:    pmm 99.0.0", "A newer release is available");
        assertThat(exe).hasContent("old");
        assertThat(new UpdateCache(tmp.resolve("cache.properties")).read().latest()).hasToString("99.0.0");
    }

    @Test
    void installsTheLatestRelease() throws Exception {
        Path exe = Files.writeString(tmp.resolve("pmm"), "old");

        int code = run(releases("99.0.0", "new".getBytes(StandardCharsets.UTF_8)), exe);

        assertThat(code).as(err.toString()).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("SHA-256 verified", "pmm 99.0.0 installed");
        assertThat(exe).hasContent("new");
    }

    @Test
    void doesNothingWhenUpToDate() throws Exception {
        Path exe = Files.writeString(tmp.resolve("pmm"), "old");

        assertThat(run(releases("0.0.1", new byte[]{1}), exe)).isEqualTo(ExitCodes.OK);
        assertThat(out.toString()).contains("pmm is up to date");
        assertThat(exe).hasContent("old");
    }

    @Test
    void reportsALookupFailureAsAToolError() throws IOException {
        Path exe = Files.writeString(tmp.resolve("pmm"), "old");

        assertThat(run(new FakeReleases(), exe)).isEqualTo(ExitCodes.TOOL_ERROR);
        assertThat(err.toString()).contains("cannot look up the pmm releases");
    }
}
