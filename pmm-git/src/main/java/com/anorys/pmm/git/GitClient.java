package com.anorys.pmm.git;

import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.core.process.ProcessSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Git commands for one repository, run through the {@code git} executable so that the user's
 * credentials, hooks and configuration apply. Only plumbing or {@code --porcelain} output is
 * parsed, which does not depend on the user's language.
 */
public final class GitClient {

    /** Long enough for a fetch over a slow VPN; a hung credential prompt is prevented separately. */
    static final Duration FETCH_TIMEOUT = Duration.ofMinutes(3);
    static final Duration LOCAL_TIMEOUT = Duration.ofMinutes(1);

    private final ProcessRunner runner;
    private final Path repository;

    public GitClient(ProcessRunner runner, Path repository) {
        this.runner = runner;
        this.repository = repository;
    }

    public Path repository() {
        return repository;
    }

    public boolean isRepository() {
        return Files.exists(repository.resolve(".git"))
                && run(LOCAL_TIMEOUT, "rev-parse", "--is-inside-work-tree").succeeded();
    }

    /** {@code git fetch --prune <remote>}; throws with git's message on failure. */
    public void fetch(String remote) {
        check(run(FETCH_TIMEOUT, "fetch", "--prune", "--quiet", remote), "fetch " + remote);
    }

    public WorkingTreeStatus status() {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "status", "--porcelain=v2", "--branch"), "status");
        return WorkingTreeStatus.parse(result.lines(), operationInProgress());
    }

    /** True when {@code ref} names an existing commit. */
    public boolean exists(String ref) {
        return run(LOCAL_TIMEOUT, "rev-parse", "--verify", "--quiet", ref + "^{commit}").succeeded();
    }

    /** Commits of {@code base} missing from {@code head}, and of {@code head} missing from {@code base}. */
    public AheadBehind aheadBehind(String base, String head) {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "rev-list", "--left-right", "--count", base + "..." + head),
                "rev-list " + base + "..." + head);
        String[] counts = result.output().trim().split("\\s+");
        return new AheadBehind(Integer.parseInt(counts[1]), Integer.parseInt(counts[0]));
    }

    /** Commit date of the oldest commit of {@code base} that {@code head} does not contain yet. */
    public Optional<Instant> oldestMissingCommit(String base, String head) {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "log", "--reverse", "--format=%cI", head + ".." + base),
                "log " + head + ".." + base);
        return result.lines().stream().filter(l -> !l.isBlank()).findFirst()
                .map(line -> OffsetDateTime.parse(line.trim()).toInstant());
    }

    /** All tag names. */
    public List<String> tags() {
        return check(run(LOCAL_TIMEOUT, "tag", "--list"), "tag --list").lines().stream()
                .map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    /** The commit {@code ref} points to (peeling tags), if it exists. */
    public Optional<String> commitOf(String ref) {
        ProcessResult result = run(LOCAL_TIMEOUT, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
        return result.succeeded() ? Optional.of(lastLine(result)) : Optional.empty();
    }

    /** True when {@code ancestor} is reachable from {@code ref}. */
    public boolean isAncestor(String ancestor, String ref) {
        return run(LOCAL_TIMEOUT, "merge-base", "--is-ancestor", ancestor, ref).succeeded();
    }

    /** The oldest commit of {@code ref} whose diff of {@code path} adds or removes a line matching {@code regex}. */
    public Optional<String> oldestCommitChanging(String ref, String path, String regex) {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "log", "--reverse", "--format=%H", "-G" + regex, ref, "--", path),
                "log -G " + regex);
        return result.lines().stream().map(String::trim).filter(l -> l.matches("[0-9a-f]{40,64}")).findFirst();
    }

    /** Non-merge commits of {@code range} whose message does not contain {@code excludedMessage}. */
    public int countCommits(String range, String excludedMessage) {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "rev-list", "--count", "--no-merges", "--fixed-strings",
                "--invert-grep", "--grep=" + excludedMessage, range), "rev-list --count " + range);
        return Integer.parseInt(lastLine(result));
    }

    /** The operation git is in the middle of, read from the git directory. */
    public Operation operationInProgress() {
        ProcessResult result = run(LOCAL_TIMEOUT, "rev-parse", "--absolute-git-dir");
        if (!result.succeeded()) {
            return Operation.NONE;
        }
        Path gitDir = Path.of(lastLine(result));
        if (Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply"))) {
            return Operation.REBASE;
        }
        if (Files.exists(gitDir.resolve("MERGE_HEAD"))) {
            return Operation.MERGE;
        }
        if (Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))) {
            return Operation.CHERRY_PICK;
        }
        return Operation.NONE;
    }

    private ProcessResult run(Duration timeout, String... args) {
        List<String> command = new ArrayList<>(List.of("-c", "core.quotepath=off"));
        command.addAll(List.of(args));
        return runner.run(ProcessSpec.of("git", command)
                .in(repository)
                .env("GIT_TERMINAL_PROMPT", "0")      // fail instead of waiting for a password
                .env("GCM_INTERACTIVE", "never")       // same for Git Credential Manager on Windows
                .timeout(timeout)
                .build());
    }

    private static ProcessResult check(ProcessResult result, String what) {
        if (result.timedOut()) {
            throw new GitException("git " + what + " timed out");
        }
        if (!result.succeeded()) {
            String output = result.output().strip();
            throw new GitException("git " + what + " failed" + (output.isEmpty() ? "" : ": " + lastLine(result)));
        }
        return result;
    }

    /** The last non-blank line; child JVM or git banners may precede the useful one. */
    private static String lastLine(ProcessResult result) {
        List<String> lines = result.lines().stream().filter(l -> !l.isBlank()).toList();
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1).trim();
    }

    public record AheadBehind(int ahead, int behind) {
    }

    public enum Operation {
        NONE, REBASE, MERGE, CHERRY_PICK
    }
}
