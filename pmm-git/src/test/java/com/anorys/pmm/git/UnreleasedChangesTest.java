package com.anorys.pmm.git;

import com.anorys.pmm.config.ConfigLoader;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.anorys.pmm.git.GitFixture.gitOk;
import static org.assertj.core.api.Assertions.assertThat;

/** origin/develop of "core": 3.1.4-SNAPSHOT, then a maven-release-plugin release of 3.1.4, then 3.1.5-SNAPSHOT. */
class UnreleasedChangesTest {

    private static final Instant DATE = Instant.parse("2026-02-01T10:00:00Z");

    @TempDir
    Path ws;

    private GitFixture fixture;
    private Path publisher;
    private String releaseCommit;

    @BeforeEach
    void setUp() {
        fixture = new GitFixture(ws.resolve("remotes"));
        publisher = ws.resolve("remotes/publisher");
        fixture.commitFile(publisher, "pom.xml", pom("3.1.4-SNAPSHOT"), "start 3.1.4", DATE);
        releaseCommit = fixture.commitFile(publisher, "pom.xml", pom("3.1.4"),
                "[maven-release-plugin] prepare release core-3.1.4", DATE);
        fixture.commitFile(publisher, "pom.xml", pom("3.1.5-SNAPSHOT"),
                "[maven-release-plugin] prepare for next development iteration", DATE);
        gitOk(publisher, "push", "--quiet", "origin", "HEAD:develop");
    }

    @Test
    void releaseCommitsAloneAreNotUnreleasedChanges() throws IOException {
        fixture.cloneAs("../core");

        assertThat(evaluate("")).isEmpty();
    }

    @Test
    void countsCommitsSinceTheReleaseFoundInThePom() throws IOException {
        fixture.advanceDevelop(2, DATE);
        fixture.cloneAs("../core");

        Finding finding = evaluate("").orElseThrow();

        assertThat(finding.checkId()).isEqualTo(KnownChecks.DEPS_UNRELEASED_UPSTREAM);
        assertThat(finding.severity()).isEqualTo(Severity.INFO);
        assertThat(finding.repository()).isEqualTo("core");
        assertThat(finding.message()).isEqualTo("2 commits on origin/develop since release 3.1.4, used by billing, reporting");
        assertThat(finding.suggestion()).isEqualTo("release core if they need these changes");
    }

    @Test
    void usesTheConfiguredTagPattern() throws IOException {
        // The tag is on an older commit than the pom change, so the count shows which one was used.
        String first = gitOk(publisher, "rev-list", "--max-parents=0", "HEAD").strip();
        gitOk(publisher, "tag", "release/3.1.4", first);
        gitOk(publisher, "push", "--quiet", "origin", "--tags");
        fixture.cloneAs("../core");

        Finding finding = evaluate("    releaseTagPattern: \"release/{version}\"\n").orElseThrow();

        assertThat(finding.message()).startsWith("1 commit on origin/develop since release 3.1.4");
    }

    @Test
    void findsTheReleaseCommitFromATagEndingWithTheVersion() throws IOException {
        fixture.cloneAs("../core");
        GitClient git = new GitClient(GitFixture.RUNNER, ws.resolve("core"));
        gitOk(ws.resolve("core"), "tag", "core-3.1.4", releaseCommit);

        PmmConfig config = config("");
        assertThat(UnreleasedChanges.releaseCommit(git, config.repositories().get(0), "3.1.4", "origin/develop"))
                .contains(releaseCommit);
    }

    @Test
    void picksTheTagThatReallyEndsWithTheVersion() {
        List<String> tags = List.of("v13.1.4", "core-3.1.4", "x.3.1.4", "3.1.40", "api-core-3.1.4");

        assertThat(UnreleasedChanges.releaseTag(tags, "3.1.4")).contains("core-3.1.4");
        assertThat(UnreleasedChanges.releaseTag(List.of("3.1.4", "v3.1.4"), "3.1.4")).contains("3.1.4");
        assertThat(UnreleasedChanges.releaseTag(List.of("v13.1.4"), "3.1.4")).isEmpty();
    }

    @Test
    void unknownReleaseOrDisabledCheckGivesNothing() throws IOException {
        fixture.advanceDevelop(1, DATE);
        fixture.cloneAs("../core");
        PmmConfig config = config("");

        assertThat(new UnreleasedChanges(config, GitFixture.RUNNER)
                .evaluate(config.repositories().get(0), List.of("9.9.9"), List.of("billing"))).isEmpty();
        assertThat(new UnreleasedChanges(config, GitFixture.RUNNER)
                .evaluate(config.repositories().get(0), List.of("9.9.9", "3.1.4"), List.of("billing"))).isPresent();

        Files.writeString(ws.resolve("pmm.local.yaml"), "checks:\n  deps.unreleased-upstream: off\n");
        assertThat(evaluate("")).isEmpty();
    }

    private Optional<Finding> evaluate(String repoOptions) throws IOException {
        PmmConfig config = config(repoOptions);
        return new UnreleasedChanges(config, GitFixture.RUNNER)
                .evaluate(config.repositories().get(0), List.of("3.1.4"), List.of("billing", "reporting"));
    }

    private PmmConfig config(String repoOptions) throws IOException {
        Files.writeString(ws.resolve("pmm.yaml"), "repositories:\n  - name: core\n" + repoOptions);
        return ConfigLoader.load(ws.resolve("pmm.yaml"));
    }

    private static String pom(String version) {
        return """
                <project>
                  <groupId>com.anorys.core</groupId>
                  <artifactId>core</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version);
    }
}
