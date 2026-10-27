package com.anorys.pmm.git;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.git.GitClient.Operation;

import java.nio.file.Files;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rebases the current branch of a repository onto its base branch, safely:
 * <ul>
 *   <li>never on a protected branch ({@code rebase.protectedBranches}), a detached HEAD, or while a
 *       rebase or merge is in progress;</li>
 *   <li>only on a clean working tree, unless {@code --autostash} (or {@code rebase.autostash});</li>
 *   <li>after saving the branch under {@code refs/pmm/backup/<branch>/<timestamp>}, so it can be undone;</li>
 *   <li>on conflicts the rebase is aborted (the branch is back where it was) unless asked to keep it;</li>
 *   <li>the push uses {@code --force-with-lease} against the remote state read before the rebase.</li>
 * </ul>
 */
public final class Rebaser {

    public enum Outcome { REBASED, UP_TO_DATE, CONFLICTS, REFUSED }

    /**
     * What a rebase would do, computed before touching anything.
     *
     * @param predictedConflicts files expected to conflict; empty when none, {@code null} when unknown
     * @param refusals           reasons the rebase must not run; empty when it may
     * @param upstream           the tracked remote branch ({@code origin/feature/x}), or {@code null}
     * @param upstreamCommit     where the upstream pointed before the rebase, for the lease of the push
     */
    public record Plan(RepositoryConfig repository, String branch, String baseRef, int ahead, int behind,
                       List<String> predictedConflicts, List<String> refusals, String upstream, String upstreamCommit,
                       String fetchError) {

        public boolean allowed() {
            return refusals.isEmpty();
        }
    }

    /**
     * @param backupRef where the branch was saved before the rebase, or {@code null}
     * @param before    the commit the branch pointed to before the rebase
     * @param conflicts the files that stopped the rebase
     * @param kept      the rebase was left in progress for a manual resolution
     */
    public record Result(Outcome outcome, Plan plan, String backupRef, String before, List<String> conflicts,
                         boolean kept, String message) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final PmmConfig config;
    private final ProcessRunner runner;
    private final Clock clock;

    public Rebaser(PmmConfig config, ProcessRunner runner, Clock clock) {
        this.config = config;
        this.runner = runner;
        this.clock = clock;
    }

    public Plan plan(RepositoryConfig repository, boolean fetch, boolean autostash) {
        String remote = config.workspace().remote();
        String baseRef = remote + "/" + config.baseBranch(repository);
        List<String> refusals = new ArrayList<>();
        if (!Files.isDirectory(repository.path())) {
            refusals.add("not cloned at " + repository.path());
            return new Plan(repository, null, baseRef, 0, 0, null, refusals, null, null, null);
        }
        GitClient git = new GitClient(runner, repository.path());
        if (!git.isRepository()) {
            refusals.add(repository.path() + " is not a git repository");
            return new Plan(repository, null, baseRef, 0, 0, null, refusals, null, null, null);
        }
        String fetchError = null;
        if (fetch) {
            try {
                git.fetch(remote);
            } catch (GitException e) {
                fetchError = e.getMessage();
            }
        }
        WorkingTreeStatus tree = git.status();
        String branch = tree.branch();
        if (branch == null) {
            refusals.add("HEAD is detached; check out a branch first");
        } else if (isProtected(branch)) {
            refusals.add(branch + " is protected (rebase.protectedBranches); it is never rebased");
        }
        if (tree.operation() != Operation.NONE) {
            refusals.add("a " + tree.operation().name().toLowerCase(Locale.ROOT).replace('_', '-')
                    + " is in progress; finish or abort it first");
        } else if (!tree.clean() && !(autostash || config.rebase().autostash()) && config.rebase().requireCleanTree()) {
            refusals.add("uncommitted changes; commit or stash them, or use --autostash");
        }
        if (!git.exists(baseRef)) {
            refusals.add("base branch " + baseRef + " does not exist");
            return new Plan(repository, branch, baseRef, 0, 0, null, refusals, tree.upstream(), null, fetchError);
        }
        GitClient.AheadBehind vsBase = git.aheadBehind(baseRef, "HEAD");
        List<String> conflicts = vsBase.behind() > 0 && vsBase.ahead() > 0
                ? git.predictConflicts(baseRef, "HEAD").orElse(null) : List.of();
        String upstreamCommit = tree.upstream() != null && git.exists(tree.upstream()) ? git.revParse(tree.upstream()) : null;
        return new Plan(repository, branch, baseRef, vsBase.ahead(), vsBase.behind(), conflicts, refusals,
                tree.upstream(), upstreamCommit, fetchError);
    }

    public Result rebase(Plan plan, boolean autostash, boolean keepConflicts) {
        if (!plan.allowed()) {
            return new Result(Outcome.REFUSED, plan, null, null, List.of(), false, String.join("; ", plan.refusals()));
        }
        if (plan.behind() == 0) {
            return new Result(Outcome.UP_TO_DATE, plan, null, null, List.of(), false,
                    plan.branch() + " already contains " + plan.baseRef());
        }
        GitClient git = new GitClient(runner, plan.repository().path());
        String before = git.revParse("HEAD");
        String backupRef = null;
        if (config.rebase().backupRefs()) {
            backupRef = "refs/pmm/backup/" + plan.branch() + "/" + STAMP.format(clock.instant().atZone(clock.getZone()));
            git.updateRef(backupRef, before);
        }
        ProcessResult result = git.rebase(plan.baseRef(), autostash || config.rebase().autostash());
        if (result.succeeded()) {
            return new Result(Outcome.REBASED, plan, backupRef, before, List.of(), false,
                    plan.ahead() + " commit" + (plan.ahead() == 1 ? "" : "s") + " of " + plan.branch()
                            + " replayed onto " + plan.baseRef());
        }
        List<String> conflicts = git.conflictedFiles();
        if (keepConflicts && !conflicts.isEmpty()) {
            return new Result(Outcome.CONFLICTS, plan, backupRef, before, conflicts, true,
                    "rebase stopped on conflicts; resolve them, then 'git rebase --continue' (or 'git rebase --abort')");
        }
        if (git.operationInProgress() == Operation.REBASE) {
            git.rebaseAbort();
        }
        String reason = conflicts.isEmpty() ? lastLines(result) : "conflicts; the rebase was aborted and "
                + plan.branch() + " is unchanged";
        return new Result(Outcome.CONFLICTS, plan, backupRef, before, conflicts, false, reason);
    }

    /**
     * Pushes a rebased branch to its upstream with a lease on the remote state read before the
     * rebase. Returns why it was not pushed, or {@code null} once pushed.
     */
    public String push(Result result) {
        Plan plan = result.plan();
        if (result.outcome() != Outcome.REBASED) {
            return "nothing to push";
        }
        String remote = config.workspace().remote();
        if (plan.upstream() == null || !plan.upstream().equals(remote + "/" + plan.branch()) || plan.upstreamCommit() == null) {
            return plan.branch() + " has no upstream " + remote + "/" + plan.branch() + "; push it with: git push -u "
                    + remote + " " + plan.branch();
        }
        new GitClient(runner, plan.repository().path()).pushWithLease(remote, plan.branch(), plan.upstreamCommit());
        return null;
    }

    public boolean isProtected(String branch) {
        return config.rebase().protectedBranches().stream().anyMatch(p -> glob(p).matcher(branch).matches());
    }

    /** {@code *} matches anything but a slash, {@code **} anything. */
    static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                boolean twice = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                regex.append(twice ? ".*" : "[^/]*");
                i += twice ? 1 : 0;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static String lastLines(ProcessResult result) {
        List<String> lines = result.lines().stream().filter(l -> !l.isBlank()).toList();
        return "git rebase failed: " + String.join(" ", lines.subList(Math.max(0, lines.size() - 2), lines.size())).trim();
    }
}
