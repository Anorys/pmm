package com.anorys.pmm.cli;

import com.anorys.pmm.cli.update.GitHubReleases;
import com.anorys.pmm.cli.update.Release;
import com.anorys.pmm.cli.update.ReleaseSource;
import com.anorys.pmm.cli.update.ReleaseVersion;
import com.anorys.pmm.cli.update.SelfUpdater;
import com.anorys.pmm.cli.update.UpdateCache;
import com.anorys.pmm.cli.update.UpdateException;
import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessSpec;
import com.anorys.pmm.core.process.SystemProcessRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "self-update", mixinStandardHelpOptions = true,
        description = {
                "Replaces this pmm executable with the latest release from GitHub (or the one given with",
                "--to), after checking its SHA-256. Only for the native executable, not the jar.",
                "Behind a proxy, set HTTPS_PROXY or pass -Dhttps.proxyHost=... -Dhttps.proxyPort=...;",
                "PMM_UPDATE_URL points to a mirror of the GitHub releases API."})
public class SelfUpdateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--check", description = "Only say whether a newer release exists.")
    boolean check;

    @Option(names = "--to", paramLabel = "<version>", description = "Install this version (also an older one).")
    String to;

    @Option(names = "--force", description = "Install even when this version is already the latest.")
    boolean force;

    /** Replaced in tests. */
    ReleaseSource source;
    Path executable;
    UpdateCache cache;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        String currentText = VersionProvider.version();
        Optional<ReleaseVersion> current = ReleaseVersion.parse(currentText);
        ReleaseSource releases = source != null ? source
                : GitHubReleases.fromEnvironment(System.getenv(), currentText, Duration.ofSeconds(20));

        Release release = lookUp(releases);
        if (to == null) {
            (cache != null ? cache : UpdateCache.forUser(System.getenv())).record(release, Instant.now());
        }
        boolean newer = current.map(version -> release.version().isNewerThan(version)).orElse(true);
        out.println("Installed: pmm " + currentText);
        out.println((to == null ? "Latest:    pmm " : "Requested: pmm ") + release.version()
                + (release.page() == null ? "" : "  " + release.page()));
        if (check) {
            out.println(newer ? "A newer release is available: run 'pmm self-update'." : "pmm is up to date.");
            out.flush();
            return ExitCodes.OK;
        }
        boolean same = current.map(version -> version.compareTo(release.version()) == 0).orElse(false);
        if ((to == null && !newer && !force) || (same && !force)) {
            out.println("pmm is up to date.");
            out.flush();
            return ExitCodes.OK;
        }

        Path exe = executable != null ? executable : SelfUpdater.currentExecutable().orElseThrow(() -> new UpdateException(
                "pmm is running on a Java runtime, not as the native executable, so it cannot replace itself; "
                        + "download the release from " + release.page()));
        String assetName = SelfUpdater.assetName(release.version(), System.getProperty("os.name", ""),
                System.getProperty("os.arch", "")).orElseThrow(() -> new UpdateException(
                "releases have no executable for " + System.getProperty("os.name") + " "
                        + System.getProperty("os.arch") + "; build pmm from source"));

        Release.Asset asset = release.asset(assetName).orElse(null);
        out.println("Downloading " + assetName + (asset != null && asset.size() > 0
                ? String.format(" (%.1f MB)", asset.size() / 1_048_576.0) : "") + "...");
        out.flush();
        Path downloaded = SelfUpdater.download(releases, release, assetName, exe);
        out.println("SHA-256 verified.");
        if (executable == null) {
            checkRuns(downloaded, release.version());
        }
        SelfUpdater.replace(exe, downloaded);
        out.println(spec.commandLine().getColorScheme().ansi().string(
                "@|green,bold pmm " + release.version() + " installed|@ in " + exe));
        out.flush();
        return ExitCodes.OK;
    }

    private Release lookUp(ReleaseSource releases) {
        try {
            if (to == null) {
                return releases.latest();
            }
            ReleaseVersion version = ReleaseVersion.parse(to).filter(ReleaseVersion::isRelease)
                    .orElseThrow(() -> new UpdateException("'" + to + "' is not a release version (X.Y.Z)"));
            return releases.release(version);
        } catch (IOException e) {
            throw new UpdateException("cannot look up the pmm releases: " + e.getMessage()
                    + "; behind a proxy, set HTTPS_PROXY", e);
        }
    }

    /** Runs the new executable once before it replaces this one, so that a broken download is never installed. */
    private static void checkRuns(Path downloaded, ReleaseVersion expected) {
        ProcessResult result = new SystemProcessRunner().run(ProcessSpec.of(downloaded.toString(), "--version")
                .timeout(Duration.ofSeconds(60)).build());
        if (!result.succeeded() || !result.output().contains("pmm " + expected)) {
            try {
                Files.deleteIfExists(downloaded);
            } catch (IOException e) {
                // Left behind; harmless.
            }
            throw new UpdateException("the downloaded pmm " + expected + " does not start ("
                    + (result.timedOut() ? "timed out" : "exit code " + result.exitCode()) + "): "
                    + result.output().strip() + "; nothing was changed");
        }
    }
}
