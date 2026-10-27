package com.anorys.pmm.git;

import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessSpec;
import com.anorys.pmm.core.process.SystemProcessRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Builds real git repositories for tests: a bare "origin" with a develop branch, and clones of it.
 * Commit dates are set explicitly so freshness thresholds can be tested with a fixed clock.
 */
final class GitFixture {

    static final SystemProcessRunner RUNNER = new SystemProcessRunner();

    private final Path root;
    private final Path origin;
    private final Path publisher;
    private int counter;

    GitFixture(Path root) {
        this.root = root;
        this.origin = root.resolve("origin.git");
        this.publisher = root.resolve("publisher");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        gitOk(root, "init", "--quiet", "--bare", "--initial-branch=develop", origin.toString());
        gitOk(root, "clone", "--quiet", origin.toString(), publisher.toString());
        commit(publisher, "initial", Instant.parse("2026-01-01T10:00:00Z"));
        gitOk(publisher, "push", "--quiet", "origin", "HEAD:develop");
    }

    Path origin() {
        return origin;
    }

    /** A new clone of origin, checked out on develop. */
    Path cloneAs(String name) {
        Path clone = root.resolve(name).normalize();
        gitOk(root, "clone", "--quiet", "--branch", "develop", origin.toString(), clone.toString());
        identify(clone);
        return clone;
    }

    /** Pushes {@code count} new commits to origin/develop, as a colleague would. */
    void advanceDevelop(int count, Instant date) {
        gitOk(publisher, "pull", "--quiet", "--ff-only", "origin", "develop");
        for (int i = 0; i < count; i++) {
            commit(publisher, "develop change", date);
        }
        gitOk(publisher, "push", "--quiet", "origin", "HEAD:develop");
    }

    /** Commits a new file (or the given content to {@code file}) in {@code repo}. */
    String commit(Path repo, String message, Instant date) {
        return commitFile(repo, "file-" + (++counter) + ".txt", message + " " + counter, message, date);
    }

    String commitFile(Path repo, String file, String content, String message, Instant date) {
        write(repo.resolve(file), content);
        gitOk(repo, "add", file);
        ProcessResult result = gitAt(repo, date, "commit", "--quiet", "-m", message);
        if (!result.succeeded()) {
            throw new AssertionError("git commit failed: " + result.output());
        }
        return gitOk(repo, "rev-parse", "HEAD").strip();
    }

    static void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ProcessResult git(Path dir, String... args) {
        return gitAt(dir, Instant.parse("2026-01-01T10:00:00Z"), args);
    }

    static ProcessResult gitAt(Path dir, Instant date, String... args) {
        return RUNNER.run(ProcessSpec.of("git", List.of(args))
                .in(dir)
                .env("GIT_AUTHOR_NAME", "Test").env("GIT_AUTHOR_EMAIL", "test@example.com")
                .env("GIT_COMMITTER_NAME", "Test").env("GIT_COMMITTER_EMAIL", "test@example.com")
                .env("GIT_AUTHOR_DATE", date.toString()).env("GIT_COMMITTER_DATE", date.toString())
                .env("GIT_CONFIG_NOSYSTEM", "1")
                .build());
    }

    /** pmm itself runs git without test variables (a rebase commits), so clones get a local identity. */
    static void identify(Path repo) {
        gitOk(repo, "config", "user.name", "Test");
        gitOk(repo, "config", "user.email", "test@example.com");
        gitOk(repo, "config", "commit.gpgsign", "false");
    }

    /** Like {@link #git} but fails the test when git fails. */
    static String gitOk(Path dir, String... args) {
        ProcessResult result = git(dir, args);
        if (!result.succeeded()) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + result.output());
        }
        return result.output();
    }
}
