package com.anorys.pmm.git;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.git.GitClient.Operation;

import java.nio.file.Files;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Brings the commits of another branch into the current one, with the strategy the user picks: a
 * fast-forward, a merge, a rebase, or a reset (soft, mixed or hard). The target is the branch's
 * upstream ({@code origin/develop} for develop, {@code origin/feature/x} for feature/x) or its base
 * branch.
 * <p>
 * {@link #plan} says, before touching anything, what each strategy would do and which ones are refused:
 * <ul>
 *   <li>nothing runs on an update-protected branch, a detached HEAD, or during a rebase or merge;</li>
 *   <li>on a push-protected branch, no strategy may rewrite a commit that is already on its upstream
 *       (updating the local develop from origin/develop is fine; rewriting origin's history is not);</li>
 *   <li>uncommitted changes are stashed around a fast-forward, merge or rebase only with autostash.</li>
 * </ul>
 * {@link #update} saves the branch under {@code refs/pmm/backup/<branch>/<timestamp>} first (and the
 * uncommitted changes under {@code refs/pmm/wip/...} before a hard reset), and aborts a merge or rebase
 * that stops on conflicts, so the branch is left as it was.
 */
public final class BranchUpdater {

    public enum Target { UPSTREAM, BASE }

    public enum Outcome { UPDATED, UP_TO_DATE, CONFLICTS, REFUSED, FAILED }

    /**
     * What one strategy would do.
     *
     * @param refusal why it must not run, or {@code null} when it may
     * @param effect  what it does, in one sentence
     * @param warning what to know before choosing it (conflicts, a force push), or {@code null}
     * @param danger  it throws work away (kept in the backup refs)
     */
    public record Option(UpdateStrategy strategy, String refusal, String effect, String warning, boolean danger) {

        public boolean allowed() {
            return refusal == null;
        }
    }

    /**
     * @param ahead              local commits not on the target
     * @param behind             commits of the target not on the branch
     * @param published          local commits that are already on the upstream, which a rebase or reset rewrites
     * @param predictedConflicts files a merge or rebase would conflict on; {@code null} when unknown
     * @param refusals           reasons nothing may run; empty when the options say
     * @param suggested          the strategy to offer first: the configured one, else the first allowed
     */
    public record Plan(RepositoryConfig repository, String branch, Target target, String targetRef,
                       int ahead, int behind, int published, List<String> predictedConflicts, WorkingTreeStatus tree,
                       List<String> refusals, UpdateStrategy suggested, List<Option> options, boolean autostash,
                       boolean pushProtected, String upstream, String upstreamCommit, String fetchError) {

        public boolean allowed() {
            return refusals.isEmpty();
        }

        public boolean upToDate() {
            return allowed() && ahead == 0 && behind == 0;
        }

        public Option option(UpdateStrategy strategy) {
            return options.stream().filter(o -> o.strategy() == strategy).findFirst().orElseThrow();
        }

        public int uncommitted() {
            return tree == null ? 0 : tree.changed() + tree.untracked();
        }
    }

    /**
     * @param backupRef   where the branch was saved first, or {@code null}
     * @param wipRef      where the uncommitted changes were saved before a hard reset, or {@code null}
     */
    public record Result(Outcome outcome, Plan plan, UpdateStrategy strategy, String backupRef, String wipRef,
                         List<String> conflicts, String message) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final PmmConfig config;
    private final ProcessRunner runner;
    private final Clock clock;
    private final BranchProtection protection;

    public BranchUpdater(PmmConfig config, ProcessRunner runner, Clock clock) {
        this.config = config;
        this.runner = runner;
        this.clock = clock;
        this.protection = new BranchProtection(config);
    }

    public BranchProtection protection() {
        return protection;
    }

    /**
     * The plans for the targets that make sense: the upstream, then the base branch when it is another
     * branch. A branch without upstream only gets the base branch.
     */
    public List<Plan> plans(RepositoryConfig repository, boolean fetch, boolean autostash) {
        Plan upstream = plan(repository, Target.UPSTREAM, fetch, autostash);
        if (upstream.tree() == null) {
            return List.of(upstream);
        }
        String baseRef = baseRef(repository);
        if (upstream.tree().upstream() == null) {
            return List.of(plan(repository, Target.BASE, false, autostash));
        }
        if (baseRef.equals(upstream.tree().upstream())) {
            return List.of(upstream);
        }
        return List.of(upstream, plan(repository, Target.BASE, false, autostash));
    }

    public Plan plan(RepositoryConfig repository, Target target, boolean fetch, boolean autostash) {
        String remote = config.workspace().remote();
        List<String> refusals = new ArrayList<>();
        if (!Files.isDirectory(repository.path())) {
            refusals.add("not cloned at " + repository.path());
            return refused(repository, target, refusals, null, null);
        }
        GitClient git = new GitClient(runner, repository.path());
        if (!git.isRepository()) {
            refusals.add(repository.path() + " is not a git repository");
            return refused(repository, target, refusals, null, null);
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
        String targetRef = target == Target.BASE ? baseRef(repository) : tree.upstream();
        if (branch == null) {
            refusals.add("HEAD is detached; check out a branch first");
        } else if (protection.updateProtected(branch)) {
            refusals.add(branch + " is update-protected (branches.updateProtected); pmm never changes it locally");
        }
        if (tree.operation() != Operation.NONE) {
            refusals.add("a " + tree.operation().name().toLowerCase(Locale.ROOT).replace('_', '-')
                    + " is in progress; finish or abort it first");
        }
        if (targetRef == null) {
            refusals.add((branch == null ? "HEAD" : branch) + " has no upstream branch; push it with 'git push -u', "
                    + "or update it from its base branch");
        } else if (!git.exists(targetRef)) {
            refusals.add(targetRef + " does not exist" + (target == Target.UPSTREAM ? " (deleted on the remote?)" : ""));
        }
        if (!refusals.isEmpty()) {
            return new Plan(repository, branch, target, targetRef, 0, 0, 0, null, tree, refusals, null, List.of(),
                    autostash, protection.pushProtected(branch), tree.upstream(), null, fetchError);
        }
        GitClient.AheadBehind counts = git.aheadBehind(targetRef, "HEAD");
        int published = publishedCommitsRewritten(git, targetRef, tree.upstream());
        List<String> conflicts = counts.behind() > 0 && counts.ahead() > 0
                ? git.predictConflicts(targetRef, "HEAD").orElse(null) : List.of();
        String upstreamCommit = tree.upstream() != null && git.exists(tree.upstream()) ? git.revParse(tree.upstream()) : null;
        boolean pushProtected = protection.pushProtected(branch);
        boolean stash = autostash || config.rebase().autostash();
        List<Option> options = options(branch, targetRef, upstreamName(tree), counts.ahead(), counts.behind(), published,
                conflicts, tree, pushProtected, stash, config.rebase().requireCleanTree());
        UpdateStrategy suggested = suggest(options, protection.defaultStrategy(branch));
        return new Plan(repository, branch, target, targetRef, counts.ahead(), counts.behind(), published, conflicts, tree,
                refusals, suggested, options, stash, pushProtected, tree.upstream(), upstreamCommit, fetchError);
    }

    /**
     * What each strategy would do for a branch {@code ahead} and {@code behind} its target; public for the
     * demo of the dashboard, which has no repository.
     */
    public static List<Option> options(String branch, String target, String upstream, int ahead, int behind, int published,
                                       List<String> conflicts, WorkingTreeStatus tree, boolean pushProtected,
                                       boolean autostash, boolean requireCleanTree) {
        int dirty = tree.changed() + tree.untracked();
        String uncommitted = dirty == 0 ? null : !autostash && requireCleanTree
                ? plural(dirty, "uncommitted change") + "; commit or stash them, or turn autostash on" : null;
        String stashNote = dirty > 0 && autostash ? "uncommitted changes are stashed first and restored after" : null;
        String conflictNote = conflicts == null ? "conflicts not predictable (git 2.38+ needed)"
                : conflicts.isEmpty() ? null : "conflicts expected in " + String.join(", ", conflicts)
                + "; on conflict it is aborted and " + branch + " left as it was";
        String rewritten = published == 0 ? null : plural(published, "commit") + " already on " + upstream;
        String rewriteRefusal = rewritten != null && pushProtected
                ? branch + " is push-protected and " + rewritten + " would be rewritten" : null;
        String rewriteWarning = rewritten != null && !pushProtected ? "rewrites " + rewritten + ": needs a force push" : null;
        String nothing = behind == 0 ? "nothing to bring in: " + branch + " already contains " + target : null;

        List<Option> options = new ArrayList<>();
        options.add(new Option(UpdateStrategy.FF,
                first(nothing, ahead > 0 ? "not a fast-forward: " + plural(ahead, "local commit") + " not on " + target : null,
                        uncommitted),
                "moves " + branch + " forward by " + plural(behind, "commit") + " of " + target,
                stashNote, false));
        options.add(new Option(UpdateStrategy.MERGE, first(nothing, uncommitted),
                ahead == 0 ? "fast-forwards " + branch + " to " + target + " (no merge commit needed)"
                        : "creates a merge commit bringing " + plural(behind, "commit") + " of " + target,
                join(conflictNote(ahead, conflictNote), stashNote), false));
        options.add(new Option(UpdateStrategy.REBASE, first(nothing, rewriteRefusal, uncommitted),
                ahead == 0 ? "fast-forwards " + branch + " to " + target + " (no commit of its own to replay)"
                        : "replays " + plural(ahead, "local commit") + " on top of " + target,
                join(conflictNote(ahead, conflictNote), rewriteWarning, stashNote), false));
        String keepRefusal = behind > 0 ? "would stage the reverse of the " + plural(behind, "new commit") + " of " + target
                + "; use a fast-forward, merge or rebase" : ahead == 0 ? "nothing to undo: " + branch + " is at " + target : null;
        String dropped = ahead == 0 ? "no local commit" : plural(ahead, "local commit");
        options.add(new Option(UpdateStrategy.RESET_SOFT, first(keepRefusal, rewriteRefusal),
                "points " + branch + " at " + target + "; the changes of its " + dropped + " stay staged",
                rewriteWarning, false));
        options.add(new Option(UpdateStrategy.RESET_MIXED, first(keepRefusal, rewriteRefusal),
                "points " + branch + " at " + target + "; the changes of its " + dropped + " stay in the working tree, unstaged",
                rewriteWarning, false));
        options.add(new Option(UpdateStrategy.RESET_HARD,
                first(ahead == 0 && behind == 0 && dirty == 0 ? "nothing to do: " + branch + " is at " + target : null, rewriteRefusal),
                "makes " + branch + " exactly " + target + (ahead > 0 ? ", dropping " + plural(ahead, "local commit") : "")
                        + (tree.changed() > 0 ? ", discarding " + plural(tree.changed(), "uncommitted change") : ""),
                join(rewriteWarning, ahead > 0 || tree.changed() > 0 ? "everything dropped is kept in refs/pmm/" : null),
                ahead > 0 || tree.changed() > 0));
        return options;
    }

    /** The configured strategy when allowed, else the first allowed of a fast-forward, a rebase and a merge. */
    public static UpdateStrategy suggest(List<Option> options, UpdateStrategy configured) {
        return options.stream().filter(o -> o.strategy() == configured && o.allowed()).findFirst()
                .or(() -> Stream.of(UpdateStrategy.FF, UpdateStrategy.REBASE, UpdateStrategy.MERGE)
                        .flatMap(s -> options.stream().filter(o -> o.strategy() == s))
                        .filter(Option::allowed).findFirst())
                .map(Option::strategy).orElse(configured);
    }

    public Result update(Plan plan, UpdateStrategy strategy) {
        if (!plan.allowed()) {
            return new Result(Outcome.REFUSED, plan, strategy, null, null, List.of(), String.join("; ", plan.refusals()));
        }
        Option option = plan.option(strategy);
        if (!option.allowed()) {
            boolean nothing = plan.behind() == 0 && !strategy.reset() || plan.upToDate();
            return new Result(nothing ? Outcome.UP_TO_DATE : Outcome.REFUSED, plan, strategy, null, null, List.of(), option.refusal());
        }
        GitClient git = new GitClient(runner, plan.repository().path());
        String before = git.revParse("HEAD");
        String stamp = STAMP.format(clock.instant().atZone(clock.getZone()));
        String backupRef = null;
        if (strategy != UpdateStrategy.FF && config.rebase().backupRefs()) {
            backupRef = "refs/pmm/backup/" + plan.branch() + "/" + stamp;
            git.updateRef(backupRef, before);
        }
        boolean stash = plan.autostash() && plan.uncommitted() > 0;
        String target = plan.targetRef();
        switch (strategy) {
            case FF -> {
                ProcessResult result = git.mergeFastForward(target, stash);
                return result.succeeded()
                        ? done(plan, strategy, backupRef, null, plan.branch() + " fast-forwarded by " + plural(plan.behind(), "commit")
                        + " of " + target)
                        : new Result(Outcome.FAILED, plan, strategy, backupRef, null, List.of(), lastLines("git merge --ff-only", result));
            }
            case MERGE -> {
                ProcessResult result = git.merge(target, stash);
                if (result.succeeded()) {
                    return done(plan, strategy, backupRef, null, plan.ahead() == 0
                            ? plan.branch() + " fast-forwarded to " + target
                            : plural(plan.behind(), "commit") + " of " + target + " merged into " + plan.branch());
                }
                List<String> conflicts = git.conflictedFiles();
                if (git.operationInProgress() == Operation.MERGE) {
                    git.mergeAbort();
                }
                return conflicts.isEmpty()
                        ? new Result(Outcome.FAILED, plan, strategy, backupRef, null, List.of(), lastLines("git merge", result))
                        : new Result(Outcome.CONFLICTS, plan, strategy, backupRef, null, conflicts,
                        "conflicts; the merge was aborted and " + plan.branch() + " is unchanged");
            }
            case REBASE -> {
                ProcessResult result = git.rebase(target, stash);
                if (result.succeeded()) {
                    return done(plan, strategy, backupRef, null, plan.ahead() == 0
                            ? plan.branch() + " fast-forwarded to " + target
                            : plural(plan.ahead(), "commit") + " of " + plan.branch() + " replayed onto " + target);
                }
                List<String> conflicts = git.conflictedFiles();
                if (git.operationInProgress() == Operation.REBASE) {
                    git.rebaseAbort();
                }
                return conflicts.isEmpty()
                        ? new Result(Outcome.FAILED, plan, strategy, backupRef, null, List.of(), lastLines("git rebase", result))
                        : new Result(Outcome.CONFLICTS, plan, strategy, backupRef, null, conflicts,
                        "conflicts; the rebase was aborted and " + plan.branch() + " is unchanged");
            }
            default -> {
                String wipRef = null;
                if (strategy == UpdateStrategy.RESET_HARD && plan.tree().changed() > 0) {
                    String wip = git.stashCreate().orElse(null);
                    if (wip != null) {
                        wipRef = "refs/pmm/wip/" + plan.branch() + "/" + stamp;
                        git.updateRef(wipRef, wip);
                    }
                }
                String mode = strategy.id().substring("reset-".length());
                try {
                    git.reset(mode, target);
                } catch (GitException e) {
                    return new Result(Outcome.FAILED, plan, strategy, backupRef, wipRef, List.of(), e.getMessage());
                }
                return done(plan, strategy, backupRef, wipRef, plan.branch() + " reset (" + mode + ") to " + target
                        + (plan.ahead() > 0 ? "; " + plural(plan.ahead(), "local commit") + " dropped from the branch" : ""));
            }
        }
    }

    /** True when the updated branch differs from its upstream and pmm may push it (with a lease). */
    public boolean pushable(Result result) {
        Plan plan = result.plan();
        if (result.outcome() != Outcome.UPDATED || plan.pushProtected() || plan.upstreamCommit() == null
                || !(config.workspace().remote() + "/" + plan.branch()).equals(plan.upstream())) {
            return false;
        }
        return !new GitClient(runner, plan.repository().path()).revParse("HEAD").equals(plan.upstreamCommit());
    }

    /**
     * Pushes an updated branch to its upstream with a lease on the remote state read before the update.
     * Returns why it was not pushed, or {@code null} once pushed.
     */
    public String push(Result result) {
        Plan plan = result.plan();
        if (plan.pushProtected()) {
            return plan.branch() + " is push-protected (branches.pushProtected); pmm never pushes it";
        }
        if (!pushable(result)) {
            return "nothing to push";
        }
        new GitClient(runner, plan.repository().path()).pushWithLease(config.workspace().remote(), plan.branch(), plan.upstreamCommit());
        return null;
    }

    /**
     * How many commits of HEAD that are already on {@code upstream} a rebase or reset onto {@code target}
     * would rewrite or drop: those between the target and the last published commit.
     */
    static int publishedCommitsRewritten(GitClient git, String target, String upstream) {
        if (upstream == null || !git.exists(upstream)) {
            return 0;
        }
        return git.mergeBase("HEAD", upstream).map(base -> git.count(target + ".." + base)).orElse(0);
    }

    private String baseRef(RepositoryConfig repository) {
        return config.workspace().remote() + "/" + config.baseBranch(repository);
    }

    private static String upstreamName(WorkingTreeStatus tree) {
        return tree.upstream() == null ? "the remote" : tree.upstream();
    }

    private Result done(Plan plan, UpdateStrategy strategy, String backupRef, String wipRef, String message) {
        return new Result(Outcome.UPDATED, plan, strategy, backupRef, wipRef, List.of(), message);
    }

    private Plan refused(RepositoryConfig repository, Target target, List<String> refusals, String branch, WorkingTreeStatus tree) {
        return new Plan(repository, branch, target, null, 0, 0, 0, null, tree, refusals, null, List.of(), false, false,
                null, null, null);
    }

    private static String conflictNote(int ahead, String note) {
        return ahead == 0 ? null : note;
    }

    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static String first(String... reasons) {
        for (String reason : reasons) {
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String join(String... notes) {
        List<String> present = new ArrayList<>();
        for (String note : notes) {
            if (note != null) {
                present.add(note);
            }
        }
        return present.isEmpty() ? null : String.join("; ", present);
    }

    private static String lastLines(String what, ProcessResult result) {
        List<String> lines = result.lines().stream().filter(l -> !l.isBlank()).toList();
        return what + " failed: " + String.join(" ", lines.subList(Math.max(0, lines.size() - 2), lines.size())).trim();
    }
}
