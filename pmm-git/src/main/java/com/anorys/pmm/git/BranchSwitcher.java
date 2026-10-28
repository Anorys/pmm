package com.anorys.pmm.git;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.git.GitClient.Operation;
import com.anorys.pmm.git.GitClient.Ref;
import com.anorys.pmm.git.GitClient.Stash;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lists the branches of a repository and switches between them. Uncommitted changes are either
 * <em>parked</em>: stashed with the branch left, and restored when coming back to it, as if each branch
 * had its own working tree; or <em>carried</em>: stashed, and restored on the new branch. A remote
 * branch with no local copy gets a local branch that tracks it; a branch known nowhere is looked for
 * with a fetch first.
 */
public final class BranchSwitcher {

    /** The stash message of parked changes: {@code pmm-park:<branch>}. */
    static final String PARK = "pmm-park:";
    static final String CARRY = "pmm-carry:";

    public enum Outcome { SWITCHED, ALREADY, REFUSED, FAILED }

    /**
     * A branch to switch to.
     *
     * @param name     the local name, {@code feature/x}, also for a remote-only branch
     * @param local    there is a local branch; otherwise only {@code origin/<name>}
     * @param upstream the tracked branch, or {@code null}
     * @param track    {@code ahead 1, behind 2}, {@code gone}, or empty
     * @param parked   changes are parked with this branch, and come back when switching to it
     */
    public record Branch(String name, boolean local, boolean current, String upstream, String track, Instant lastCommit,
                         boolean parked) {
    }

    /**
     * @param stashed  what happened to the uncommitted changes ({@code parked with develop}...), or {@code null}
     * @param restored the parked changes of the new branch were restored
     * @param warning  something to look at (a stash that could not be restored), or {@code null}
     */
    public record Result(Outcome outcome, String repository, String from, String to, String stashed, boolean restored,
                         String warning, String message) {
    }

    private final PmmConfig config;
    private final ProcessRunner runner;

    public BranchSwitcher(PmmConfig config, ProcessRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    /** Current branch first, then local branches, then remote-only ones, each by most recent commit. */
    public List<Branch> branches(RepositoryConfig repository) {
        GitClient git = git(repository);
        String remote = config.workspace().remote();
        String remotePrefix = "refs/remotes/" + remote + "/";
        Set<String> parked = git.stashes().stream().map(s -> parkedBranch(s.subject())).flatMap(Optional::stream)
                .collect(Collectors.toSet());
        List<Ref> refs = git.branches(remote);
        Set<String> locals = refs.stream().filter(r -> r.name().startsWith("refs/heads/"))
                .map(r -> r.name().substring("refs/heads/".length())).collect(Collectors.toSet());
        List<Branch> branches = new ArrayList<>();
        for (Ref ref : refs) {
            if (ref.name().startsWith("refs/heads/")) {
                String name = ref.name().substring("refs/heads/".length());
                branches.add(new Branch(name, true, ref.current(), ref.upstream(), ref.track(), ref.lastCommit(), parked.contains(name)));
            } else if (ref.name().startsWith(remotePrefix)) {
                String name = ref.name().substring(remotePrefix.length());
                if (!locals.contains(name)) {
                    branches.add(new Branch(name, false, false, remote + "/" + name, "", ref.lastCommit(), false));
                }
            }
        }
        Comparator<Branch> order = Comparator.comparing((Branch b) -> !b.current())
                .thenComparing(b -> !b.local())
                .thenComparing(Branch::lastCommit, Comparator.nullsLast(Comparator.reverseOrder()));
        return branches.stream().sorted(order).toList();
    }

    /**
     * Switches to {@code branch}: a local branch, or a remote branch that gets a local tracking copy, or
     * with {@code create} a new branch started from the base branch.
     *
     * @param mode    what to do with uncommitted changes; {@link StashMode#ASK} refuses when there are any
     * @param restore restore the changes parked with {@code branch}, if any
     */
    public Result switchTo(RepositoryConfig repository, String branch, StashMode mode, boolean restore, boolean create) {
        String name = repository.name();
        if (!Files.isDirectory(repository.path())) {
            return new Result(Outcome.REFUSED, name, null, branch, null, false, null, "not cloned at " + repository.path());
        }
        GitClient git = git(repository);
        if (!git.isRepository()) {
            return new Result(Outcome.REFUSED, name, null, branch, null, false, null, repository.path() + " is not a git repository");
        }
        WorkingTreeStatus tree = git.status();
        String from = tree.branch();
        if (branch.equals(from)) {
            return new Result(Outcome.ALREADY, name, from, branch, null, false, null, "already on " + branch);
        }
        if (tree.operation() != Operation.NONE) {
            return new Result(Outcome.REFUSED, name, from, branch, null, false, null, "a "
                    + tree.operation().name().toLowerCase(Locale.ROOT).replace('_', '-') + " is in progress; finish or abort it first");
        }
        String remoteRef = config.workspace().remote() + "/" + branch;
        boolean local = git.exists("refs/heads/" + branch);
        boolean remote = !local && git.exists("refs/remotes/" + remoteRef);
        if (!local && !remote) {
            // Maybe pushed since the last fetch.
            try {
                git.fetch(config.workspace().remote());
                remote = git.exists("refs/remotes/" + remoteRef);
            } catch (GitException e) {
                // offline: the branch is unknown, as said below
            }
        }
        String base = config.workspace().remote() + "/" + config.baseBranch(repository);
        if (!local && !remote && !create) {
            return new Result(Outcome.REFUSED, name, from, branch, null, false, null,
                    "no branch " + branch + " here, nor " + remoteRef);
        }
        if (!local && !remote && !git.exists(base)) {
            return new Result(Outcome.REFUSED, name, from, branch, null, false, null, "cannot create " + branch + ": " + base + " does not exist");
        }
        int dirty = tree.changed() + tree.untracked() + tree.conflicted();
        String stashed = null;
        if (dirty > 0) {
            if (mode == StashMode.NEVER || mode == StashMode.ASK) {
                return new Result(Outcome.REFUSED, name, from, branch, null, false, null,
                        BranchUpdater.plural(dirty, "uncommitted change") + "; park them (stashed, back when you return to "
                                + (from == null ? "this commit" : from) + ") or carry them to " + branch);
            }
            String label = from == null ? "detached" : from;
            git.stashPush(mode == StashMode.PARK ? PARK + label : CARRY + label + "->" + branch);
            stashed = mode == StashMode.PARK ? BranchUpdater.plural(dirty, "change") + " parked with " + label
                    : BranchUpdater.plural(dirty, "change") + " carried over";
        }
        ProcessResult switched = local ? git.switchTo(branch)
                : remote ? git.switchCreate(branch, remoteRef, true) : git.switchCreate(branch, base, false);
        if (!switched.succeeded()) {
            String warning = stashed == null ? null : restoreTop(git, "your changes");
            return new Result(Outcome.FAILED, name, from, branch, null, false, warning, "git switch failed: " + lastLine(switched));
        }
        String warning = null;
        if (mode == StashMode.CARRY && stashed != null) {
            warning = restoreTop(git, "the carried changes");
        }
        boolean restored = false;
        if (restore && warning == null) {
            Optional<Stash> parked = git.stashes().stream().filter(s -> parkedBranch(s.subject()).filter(branch::equals).isPresent()).findFirst();
            if (parked.isPresent()) {
                ProcessResult pop = git.stashPop(parked.get().ref());
                restored = pop.succeeded();
                if (!restored) {
                    warning = "the changes parked with " + branch + " could not be restored (" + lastLine(pop) + "); they are in "
                            + parked.get().ref() + ": 'git stash pop " + parked.get().ref() + "'";
                }
            }
        }
        String how = local ? "" : remote ? " (new local branch tracking " + remoteRef + ")" : " (new branch from " + base + ")";
        return new Result(Outcome.SWITCHED, name, from, branch, stashed, restored, warning, "switched to " + branch + how);
    }

    private static String restoreTop(GitClient git, String what) {
        ProcessResult pop = git.stashPop("stash@{0}");
        return pop.succeeded() ? null : what + " could not be restored (" + lastLine(pop) + "); they are in stash@{0}: 'git stash pop'";
    }

    /** The branch a stash was parked with: its subject reads {@code On develop: pmm-park:develop}. */
    static Optional<String> parkedBranch(String subject) {
        int at = subject.indexOf(PARK);
        return at < 0 ? Optional.empty() : Optional.of(subject.substring(at + PARK.length()).trim());
    }

    private GitClient git(RepositoryConfig repository) {
        return new GitClient(runner, repository.path());
    }

    private static String lastLine(ProcessResult result) {
        List<String> lines = result.lines().stream().filter(l -> !l.isBlank()).toList();
        return lines.isEmpty() ? "exit code " + result.exitCode() : lines.get(lines.size() - 1).trim();
    }
}
