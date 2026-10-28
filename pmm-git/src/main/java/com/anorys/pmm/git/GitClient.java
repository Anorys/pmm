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
    /** A rebase runs hooks and may replay many commits. */
    static final Duration REBASE_TIMEOUT = Duration.ofMinutes(10);

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

    /** The tree id of {@code HEAD}: identical content gives an identical id. */
    public String headTree() {
        return lastLine(check(run(LOCAL_TIMEOUT, "rev-parse", "HEAD^{tree}"), "rev-parse HEAD^{tree}"));
    }

    /** The uncommitted changes to tracked files (staged or not), as a binary patch. */
    public String diffHead() {
        return check(run(LOCAL_TIMEOUT, "diff", "HEAD", "--binary", "--no-color", "--no-ext-diff"), "diff HEAD").output();
    }

    /** Untracked files that are not ignored, relative to the repository root. */
    public List<String> untrackedFiles() {
        return check(run(LOCAL_TIMEOUT, "ls-files", "--others", "--exclude-standard"), "ls-files --others").lines()
                .stream().map(String::trim).filter(l -> !l.isEmpty()).sorted().toList();
    }

    /** Commit id of {@code ref}; throws when it does not exist. */
    public String revParse(String ref) {
        return lastLine(check(run(LOCAL_TIMEOUT, "rev-parse", "--verify", ref + "^{commit}"), "rev-parse " + ref));
    }

    /**
     * Predicts the conflicts of bringing {@code head} onto {@code base} with {@code git merge-tree}
     * (git 2.38 or later), without touching the working tree or the index. The result of a rebase
     * has the same content as this merge, so the files listed are the ones a rebase would stop on
     * (a rebase can also stop on a file that one commit changes and a later one changes back).
     *
     * @return the conflicting files (empty when the rebase applies cleanly), or empty when git is too old
     */
    public Optional<List<String>> predictConflicts(String base, String head) {
        ProcessResult result = run(LOCAL_TIMEOUT, "merge-tree", "--write-tree", "--name-only", "--no-messages", base, head);
        if (result.timedOut() || result.exitCode() > 1) {
            return Optional.empty();
        }
        List<String> lines = result.lines().stream().map(String::trim).toList();
        int start = 0;
        while (start < lines.size() && !lines.get(start).matches("[0-9a-f]{40,64}")) {
            start++;    // skip banners printed before the tree id
        }
        if (start == lines.size()) {
            return Optional.empty();
        }
        List<String> files = lines.subList(start + 1, lines.size()).stream()
                .takeWhile(l -> !l.isEmpty()).distinct().toList();
        return Optional.of(result.exitCode() == 0 ? List.of() : files);
    }

    /** Creates or moves {@code ref} to {@code commit}. */
    public void updateRef(String ref, String commit) {
        check(run(LOCAL_TIMEOUT, "update-ref", ref, commit), "update-ref " + ref);
    }

    /** Fast-forwards the current branch to {@code ref}; fails if that is not a fast-forward. */
    public void fastForward(String ref) {
        check(run(LOCAL_TIMEOUT, "merge", "--ff-only", "--quiet", ref), "merge --ff-only " + ref);
    }

    /** {@code git merge --ff-only}, optionally stashing uncommitted changes around it. */
    public ProcessResult mergeFastForward(String ref, boolean autostash) {
        return run(REBASE_TIMEOUT, "merge", "--ff-only", "--quiet", autostash ? "--autostash" : "--no-autostash", ref);
    }

    /** {@code git merge --no-edit}: a merge commit when both sides have commits; the result says whether it stopped. */
    public ProcessResult merge(String ref, boolean autostash) {
        return run(REBASE_TIMEOUT, "merge", "--no-edit", "--quiet", autostash ? "--autostash" : "--no-autostash", ref);
    }

    public void mergeAbort() {
        check(run(LOCAL_TIMEOUT, "merge", "--abort"), "merge --abort");
    }

    /** {@code git reset --soft|--mixed|--hard <ref>}. */
    public void reset(String mode, String ref) {
        check(run(LOCAL_TIMEOUT, "reset", "--quiet", "--" + mode, ref), "reset --" + mode + " " + ref);
    }

    /** The best common ancestor of two commits, if they have one. */
    public Optional<String> mergeBase(String a, String b) {
        ProcessResult result = run(LOCAL_TIMEOUT, "merge-base", a, b);
        return result.succeeded() ? Optional.of(lastLine(result)) : Optional.empty();
    }

    /** {@code git rev-list --count <range>}. */
    public int count(String range) {
        return Integer.parseInt(lastLine(check(run(LOCAL_TIMEOUT, "rev-list", "--count", range), "rev-list --count " + range)));
    }

    /**
     * Saves the uncommitted changes to tracked files as a stash commit without touching the working tree
     * ({@code git stash create}); empty when there is nothing to save.
     */
    public Optional<String> stashCreate() {
        String commit = lastLine(check(run(LOCAL_TIMEOUT, "stash", "create"), "stash create"));
        return commit.isEmpty() ? Optional.empty() : Optional.of(commit);
    }

    /** {@code git stash push --include-untracked -m <message>}. */
    public void stashPush(String message) {
        check(run(LOCAL_TIMEOUT, "stash", "push", "--include-untracked", "--quiet", "-m", message), "stash push");
    }

    /** The stash entries, newest first. */
    public List<Stash> stashes() {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "stash", "list", "--format=%gd%x09%gs"), "stash list");
        List<Stash> stashes = new ArrayList<>();
        for (String line : result.lines()) {
            int tab = line.indexOf('\t');
            if (tab > 0 && line.startsWith("stash@{")) {
                stashes.add(new Stash(line.substring(0, tab).trim(), line.substring(tab + 1).trim()));
            }
        }
        return stashes;
    }

    /** {@code git stash pop <stash>}; on a conflict git keeps the stash, and the result says it failed. */
    public ProcessResult stashPop(String stash) {
        return run(LOCAL_TIMEOUT, "stash", "pop", "--quiet", stash);
    }

    /** {@code git switch <branch>}. */
    public ProcessResult switchTo(String branch) {
        return run(LOCAL_TIMEOUT, "switch", "--quiet", branch);
    }

    /** {@code git switch -c <branch> [--track] <start>}: a new local branch, tracking {@code start} when it is remote. */
    public ProcessResult switchCreate(String branch, String start, boolean track) {
        List<String> args = new ArrayList<>(List.of("switch", "--quiet", "-c", branch));
        args.add(track ? "--track" : "--no-track");
        args.add(start);
        return run(LOCAL_TIMEOUT, args.toArray(String[]::new));
    }

    /**
     * The local branches and the branches of {@code remote}, with their upstream and last commit date.
     * The remote's {@code HEAD} alias is left out.
     */
    public List<Ref> branches(String remote) {
        ProcessResult result = check(run(LOCAL_TIMEOUT, "for-each-ref",
                "--format=%(refname)%09%(upstream:short)%09%(upstream:track,nobracket)%09%(committerdate:unix)%09%(HEAD)",
                "refs/heads", "refs/remotes/" + remote), "for-each-ref");
        List<Ref> refs = new ArrayList<>();
        for (String line : result.lines()) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 5 || parts[0].endsWith("/HEAD")) {
                continue;
            }
            Instant date = parts[3].isBlank() ? null : Instant.ofEpochSecond(Long.parseLong(parts[3].trim()));
            refs.add(new Ref(parts[0].trim(), parts[1].trim().isEmpty() ? null : parts[1].trim(), parts[2].trim(), date,
                    parts[4].trim().equals("*")));
        }
        return refs;
    }

    /** Runs {@code git rebase}; the result says whether it stopped. */
    public ProcessResult rebase(String onto, boolean autostash) {
        List<String> args = new ArrayList<>(List.of("rebase"));
        args.add(autostash ? "--autostash" : "--no-autostash");
        args.add(onto);
        return run(REBASE_TIMEOUT, args.toArray(String[]::new));
    }

    public void rebaseAbort() {
        check(run(LOCAL_TIMEOUT, "rebase", "--abort"), "rebase --abort");
    }

    /** Files with unresolved conflicts in the working tree. */
    public List<String> conflictedFiles() {
        return check(run(LOCAL_TIMEOUT, "diff", "--name-only", "--diff-filter=U"), "diff --diff-filter=U").lines()
                .stream().map(String::trim).filter(l -> !l.isEmpty()).toList();
    }

    /**
     * {@code git push --force-with-lease=<branch>:<expected> <remote> <branch>}: refused when the remote
     * branch moved since {@code expected}, so nobody's work is overwritten.
     */
    public void pushWithLease(String remote, String branch, String expected) {
        check(run(FETCH_TIMEOUT, "push", "--quiet", "--force-with-lease=refs/heads/" + branch + ":" + expected,
                remote, "refs/heads/" + branch + ":refs/heads/" + branch), "push " + remote + " " + branch);
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
                .env("GIT_EDITOR", "true")             // a rebase must never wait for an editor
                .env("GIT_SEQUENCE_EDITOR", "true")
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

    /** A stash entry: {@code stash@{0}} and its subject, {@code On develop: <message>}. */
    public record Stash(String ref, String subject) {
    }

    /**
     * A branch from {@code for-each-ref}.
     *
     * @param name     the full ref name, {@code refs/heads/develop} or {@code refs/remotes/origin/develop}
     * @param upstream the tracked branch of a local branch ({@code origin/develop}), or {@code null}
     * @param track    {@code ahead 1, behind 2}, {@code gone}, or empty when in sync
     * @param current  the branch checked out
     */
    public record Ref(String name, String upstream, String track, Instant lastCommit, boolean current) {
    }

    public enum Operation {
        NONE, REBASE, MERGE, CHERRY_PICK
    }
}
