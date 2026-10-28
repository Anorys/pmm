package com.anorys.pmm.tui;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.BranchSwitcher;
import com.anorys.pmm.git.BranchSwitcher.Branch;
import com.anorys.pmm.git.BranchUpdater.Option;
import com.anorys.pmm.git.BranchUpdater.Outcome;
import com.anorys.pmm.git.BranchUpdater.Plan;
import com.anorys.pmm.git.BranchUpdater.Result;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.DashboardState.Dialog;
import com.anorys.pmm.tui.model.DashboardState.DialogLine;
import com.anorys.pmm.tui.model.DashboardState.Tab;
import com.anorys.pmm.tui.model.DashboardState.Toast;
import com.anorys.pmm.tui.model.Menu;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.model.RepoState.BuildPhase;
import com.anorys.pmm.tui.service.WorkspaceService;
import com.anorys.pmm.tui.service.WorkspaceService.BuildListener;
import com.anorys.pmm.tui.service.WorkspaceService.Dependencies;
import com.anorys.pmm.tui.service.WorkspaceService.GitState;
import com.anorys.pmm.tui.service.WorkspaceService.Loaded;
import com.anorys.pmm.tui.service.WorkspaceService.PushPolicy;
import com.anorys.pmm.tui.service.WorkspaceService.Repository;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import com.anorys.pmm.tui.view.DashboardView;
import com.anorys.pmm.tui.view.Format;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import org.jline.terminal.Terminal;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The interactive dashboard: the event loop, the keys, and the background tasks that feed the state.
 * The state is only touched on the render thread; background tasks hand their results back with
 * {@link TuiRunner#runLater}.
 */
public final class PmmDashboard {

    private static final Duration TICK = Duration.ofMillis(80);
    private static final Duration TOAST = Duration.ofSeconds(6);

    private final WorkspaceService service;
    private final DashboardState state;
    private final DashboardView view;
    // Platform threads: in the Windows native image, starting a process from a virtual thread crashes.
    private final ExecutorService background = Executors.newCachedThreadPool(
            Thread.ofPlatform().name("pmm-ui-", 1).daemon().factory());
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("pmm-ui-logs").daemon().factory());
    /** The logs of the builds running now, followed by {@link #pollLogs()}. */
    private final Map<String, Path> runningLogs = new ConcurrentHashMap<>();

    private final boolean synchronous;
    private TuiRunner runner;
    private boolean dirty = true;

    public PmmDashboard(WorkspaceService service, Path root, Theme theme) {
        this(service, new DashboardState(root), theme, false);
    }

    /** With {@code synchronous}, background work runs on the calling thread: for tests, with no terminal. */
    PmmDashboard(WorkspaceService service, DashboardState state, Theme theme, boolean synchronous) {
        this.service = service;
        this.state = state;
        this.view = new DashboardView(theme);
        this.synchronous = synchronous;
    }

    DashboardState state() {
        return state;
    }

    /** Runs until the user quits. */
    public void run() throws Exception {
        // The backend is named rather than discovered, so that the native image needs no service lookup.
        // The terminal is opened here, not by TamboUI's backend, which would settle for a dumb terminal.
        try (Terminal terminal = SystemTerminal.open();
             TuiRunner tui = TuiRunner.create(TuiConfig.builder().backend(new ConsoleBackend(terminal)).tickRate(TICK).build())) {
            runner = tui;
            tui.setWindowTitle("pmm");
            poller.scheduleWithFixedDelay(this::pollLogs, 400, 400, TimeUnit.MILLISECONDS);
            start();
            tui.run(this::handle, frame -> view.render(frame, state, Instant.now()));
        } finally {
            // Interrupting the builds stops their Maven processes (SystemProcessRunner kills the tree).
            poller.shutdownNow();
            background.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- startup and refreshes

    private void start() {
        task("reading the POMs", service::load, loaded -> {
            apply(loaded);
            task("reading git", () -> service.git(names(), false), git -> {
                apply(git);
                fetch();
                dependencies();
            });
        });
    }

    private void apply(Loaded loaded) {
        state.workspace(loaded.workspace());
        for (Repository repository : loaded.repositories()) {
            RepoState repo = new RepoState(repository.name(), repository.path());
            loaded.workspace().repository(repository.name()).ifPresent(info -> repo.project(info.version(), info.modules().size()));
            repo.expectedBuild(loaded.lastBuildDurations().get(repository.name()));
            state.repositories().add(repo);
        }
        if (!loaded.warnings().isEmpty()) {
            toast(Tone.WARN, loaded.warnings().get(0) + (loaded.warnings().size() > 1
                    ? " (+" + (loaded.warnings().size() - 1) + " more, see pmm graph)" : ""));
        }
    }

    private void apply(GitState git) {
        for (RepositoryStatus status : git.statuses()) {
            state.repository(status.name()).ifPresent(repo -> repo.git(status,
                    git.findings().stream().filter(f -> status.name().equals(f.repository())).toList()));
        }
    }

    private void fetch() {
        if (state.busy("fetching")) {
            return;
        }
        task("fetching", () -> service.git(names(), true), git -> {
            apply(git);
            state.lastFetch(Instant.now());
            long failed = git.statuses().stream().filter(s -> s.fetchError() != null).count();
            if (failed > 0) {
                toast(Tone.WARN, failed + " fetch" + (failed == 1 ? "" : "es") + " failed; showing the last fetched state");
            }
        });
    }

    private void dependencies() {
        if (state.busy("checking Nexus")) {
            return;
        }
        task("checking Nexus", service::dependencies, this::apply);
    }

    private void apply(Dependencies deps) {
        for (RepoState repo : state.repositories()) {
            List<InternalUsage> usages = deps.report().usages().stream().filter(u -> u.consumer().equals(repo.name())).toList();
            List<Finding> findings = deps.findings().stream().filter(f -> repo.name().equals(f.repository())).toList();
            repo.deps(usages, findings);
        }
        state.notes(deps.notes());
        state.depsCheckedAt(Instant.now());
    }

    // ---------------------------------------------------------------- builds

    private void build(List<RepoState> repositories, boolean useCache) {
        if (state.building()) {
            toast(Tone.INFO, "A build is already running");
            return;
        }
        if (repositories.isEmpty()) {
            return;
        }
        repositories.forEach(RepoState::queued);
        List<String> names = repositories.stream().map(RepoState::name).toList();
        String activity = "building " + (names.size() == 1 ? names.get(0) : names.size() + " repositories");
        Instant started = Instant.now();
        BuildListener listener = new BuildListener() {
            @Override
            public void started(String repository) {
                runningLogs.put(repository, service.buildLog(repository));
                ui(() -> state.repository(repository).ifPresent(r -> r.started(Instant.now())));
            }

            @Override
            public void finished(BuildResult result, List<Finding> findings) {
                Path log = runningLogs.remove(result.repository());
                LogTail.Progress progress = log == null ? null : LogTail.read(log);
                ui(() -> state.repository(result.repository()).ifPresent(r -> {
                    r.finished(result, findings);
                    if (progress != null) {
                        r.progress(null, 0, 0, progress.lines());
                    }
                }));
            }
        };
        task(activity, () -> service.build(names, useCache, listener), blockers -> {
            state.blockers(blockers);
            long failed = repositories.stream().filter(r -> r.build() != null && !r.build().status().ok()
                    && r.build().status() != BuildResult.Status.SKIPPED).count();
            String took = Format.duration(Duration.between(started, Instant.now()));
            if (failed == 0) {
                toast(Tone.OK, "Build finished in " + took + ": everything compiles");
            } else {
                toast(Tone.ERROR, "Build finished in " + took + ": " + failed + " of " + names.size()
                        + " failed or blocked" + (blockers.isEmpty() ? "" : "; see the Dependencies tab for what to release"));
            }
        }, () -> {
            runningLogs.clear();
            repositories.stream().filter(r -> r.buildPhase() != BuildPhase.DONE).forEach(RepoState::cancelled);
        });
    }

    private void pollLogs() {
        for (Map.Entry<String, Path> entry : runningLogs.entrySet()) {
            LogTail.Progress progress = LogTail.read(entry.getValue());
            if (progress != null) {
                ui(() -> state.repository(entry.getKey()).ifPresent(r -> {
                    if (r.buildPhase() == BuildPhase.RUNNING) {
                        r.progress(progress.module(), progress.index(), progress.count(), progress.lines());
                    }
                }));
            }
        }
    }

    // ---------------------------------------------------------------- update a branch

    /**
     * Opens the update menu of a repository: every strategy, with what it would do, from its upstream
     * or, with {@code onBase}, from its base branch with a rebase selected.
     */
    private void update(RepoState repo, boolean onBase) {
        if (repo.buildPhase() == BuildPhase.RUNNING || repo.buildPhase() == BuildPhase.QUEUED) {
            toast(Tone.WARN, repo.name() + " is building; update it once the build is done");
            return;
        }
        task("reading the branch of " + repo.name(), () -> service.planUpdate(repo.name(), false), plans ->
                openUpdate(repo.name(), plans, onBase ? plans.size() - 1 : 0, onBase ? UpdateStrategy.REBASE : null));
    }

    private void openUpdate(String repository, List<Plan> plans, int targetIndex, UpdateStrategy keep) {
        Plan plan = plans.get(Math.min(targetIndex, plans.size() - 1));
        if (!plan.allowed()) {
            List<DialogLine> lines = new ArrayList<>();
            plan.refusals().forEach(r -> lines.add(new DialogLine(Tone.ERROR, "✘ " + r)));
            state.dialog(new Dialog("Cannot update " + repository, lines, null, null));
            return;
        }
        List<DialogLine> header = new ArrayList<>();
        header.add(new DialogLine(null, plan.branch() + "  ←  " + plan.targetRef()
                + (plan.pushProtected() ? "    (push-protected: updated locally, never pushed)" : "")));
        List<String> facts = new ArrayList<>();
        facts.add(plan.behind() + " to bring in");
        facts.add(plural(plan.ahead(), "local commit"));
        if (plan.uncommitted() > 0) {
            facts.add(plan.uncommitted() + " uncommitted" + (plan.autostash() ? " (autostash on)" : ""));
        }
        header.add(new DialogLine(plan.behind() > 0 ? Tone.INFO : Tone.OK, String.join("  ·  ", facts)));
        if (plans.size() > 1) {
            StringBuilder from = new StringBuilder("from   ");
            for (int i = 0; i < plans.size(); i++) {
                from.append(i == targetIndex ? "● " : "○ ").append(plans.get(i).targetRef()).append("   ");
            }
            header.add(new DialogLine(null, from.toString().stripTrailing()));
        }
        List<Menu.Item> items = new ArrayList<>();
        for (Option option : plan.options()) {
            String note = !option.allowed() ? "not possible" : option.danger() ? "drops work"
                    : option.strategy() == plan.suggested() ? "suggested" : option.warning() != null ? "see below" : "";
            Tone tone = !option.allowed() ? Tone.IDLE : option.danger() ? Tone.ERROR : option.warning() != null ? Tone.WARN : Tone.OK;
            items.add(new Menu.Item(option.strategy().id(), option.strategy().label(), command(option.strategy(), plan.targetRef()),
                    tone, note, option.allowed()));
        }
        List<Menu.Hint> hints = new ArrayList<>(List.of(new Menu.Hint("↑↓", "choose"), new Menu.Hint("enter", "apply")));
        if (plans.size() > 1) {
            hints.add(new Menu.Hint("t", "from " + plans.get((targetIndex + 1) % plans.size()).targetRef()));
        }
        if (plan.uncommitted() > 0) {
            hints.add(new Menu.Hint("a", "autostash " + (plan.autostash() ? "off" : "on")));
        }
        hints.add(new Menu.Hint("esc", "cancel"));
        Menu[] menu = new Menu[1];
        menu[0] = new Menu("Update " + repository, header, items, item -> describe(plan, plan.option(UpdateStrategy.parse(item.id()))),
                hints, false, item -> confirmUpdate(plan, UpdateStrategy.parse(item.id())), c -> {
            UpdateStrategy selected = menu[0].selected() == null ? null : UpdateStrategy.parse(menu[0].selected().id());
            if (c == 't' && plans.size() > 1) {
                openUpdate(repository, plans, (targetIndex + 1) % plans.size(), selected);
                return true;
            }
            if (c == 'a' && plan.uncommitted() > 0) {
                state.menu(null);
                task("reading the branch of " + repository, () -> service.planUpdate(repository, !plan.autostash()),
                        replanned -> openUpdate(repository, replanned, targetIndex, selected));
                return true;
            }
            return false;
        });
        menu[0].select((keep != null && plan.option(keep).allowed() ? keep : plan.suggested()).id());
        state.menu(menu[0]);
    }

    private static List<DialogLine> describe(Plan plan, Option option) {
        List<DialogLine> lines = new ArrayList<>();
        if (!option.allowed()) {
            lines.add(new DialogLine(Tone.ERROR, "✘ " + option.refusal()));
            return lines;
        }
        lines.add(new DialogLine(null, "It " + option.effect() + "."));
        if (option.warning() != null) {
            for (String warning : option.warning().split("; (?=[a-z])")) {
                lines.add(new DialogLine(option.danger() ? Tone.ERROR : Tone.WARN, "▲ " + warning));
            }
        }
        if (option.strategy() != UpdateStrategy.FF) {
            lines.add(new DialogLine(Tone.IDLE, "The branch is saved under refs/pmm/backup/" + plan.branch() + "/ first."));
        }
        return lines;
    }

    private static String command(UpdateStrategy strategy, String target) {
        return switch (strategy) {
            case FF -> "git merge --ff-only " + target;
            case MERGE -> "git merge " + target;
            case REBASE -> "git rebase " + target;
            case RESET_SOFT -> "git reset --soft " + target;
            case RESET_MIXED -> "git reset --mixed " + target;
            case RESET_HARD -> "git reset --hard " + target;
        };
    }

    private void confirmUpdate(Plan plan, UpdateStrategy strategy) {
        state.menu(null);
        Option option = plan.option(strategy);
        if (!option.danger()) {
            runUpdate(plan, strategy);
            return;
        }
        List<DialogLine> lines = new ArrayList<>();
        lines.add(new DialogLine(Tone.ERROR, "It " + option.effect() + "."));
        lines.add(new DialogLine(null, "The commits are kept under refs/pmm/backup/" + plan.branch() + "/,"));
        lines.add(new DialogLine(null, "the uncommitted changes of tracked files under refs/pmm/wip/" + plan.branch() + "/."));
        state.dialog(new Dialog(strategy.label() + " " + plan.branch() + "?", lines, strategy.label(), () -> runUpdate(plan, strategy)));
    }

    private void runUpdate(Plan plan, UpdateStrategy strategy) {
        String repository = plan.repository().name();
        task("updating " + repository, () -> service.update(plan, strategy), result -> afterUpdate(repository, result));
    }

    private void afterUpdate(String repository, Result result) {
        refreshGit(repository);
        if (result.outcome() != Outcome.UPDATED) {
            toast(result.outcome() == Outcome.UP_TO_DATE ? Tone.OK : Tone.ERROR, repository + ": " + result.message());
            return;
        }
        toast(Tone.OK, repository + ": " + result.message()
                + (result.wipRef() != null ? " (changes saved in " + result.wipRef() + ")" : ""));
        if (!service.pushable(result)) {
            return;
        }
        String branch = result.plan().branch();
        PushPolicy policy = service.pushPolicy();
        if (policy == PushPolicy.ALWAYS) {
            push(repository, result);
        } else if (policy == PushPolicy.ASK) {
            List<DialogLine> lines = new ArrayList<>();
            if (result.strategy().rewrites()) {
                lines.add(new DialogLine(null, "The " + result.strategy().label() + " rewrote the history of " + branch + "."));
            }
            lines.add(new DialogLine(Tone.INFO, "git push --force-with-lease: refused if someone pushed in the meantime."));
            if (result.backupRef() != null) {
                lines.add(new DialogLine(null, "Undo with: git reset --hard " + result.backupRef()));
            }
            state.dialog(new Dialog("Push " + branch + "?", lines, "push", () -> push(repository, result)));
        }
    }

    private void push(String repository, Result result) {
        String branch = result.plan().branch();
        task("pushing " + branch, () -> service.push(result), reason -> {
            if (reason == null) {
                toast(Tone.OK, branch + " pushed");
            } else {
                toast(Tone.WARN, reason);
            }
            refreshGit(repository);
        });
    }

    /** Fast-forwards every repository that only lacks commits of its upstream, after one confirmation. */
    private void updateAll() {
        List<String> names = state.repositories().stream()
                .filter(r -> r.buildPhase() != BuildPhase.RUNNING && r.buildPhase() != BuildPhase.QUEUED)
                .map(RepoState::name).toList();
        task("reading the branches", () -> names.stream().map(n -> {
            try {
                return service.planUpdate(n, false).get(0);
            } catch (RuntimeException e) {
                return null;
            }
        }).filter(Objects::nonNull).toList(), this::confirmUpdateAll);
    }

    private void confirmUpdateAll(List<Plan> plans) {
        List<Plan> ready = plans.stream().filter(p -> p.allowed() && p.behind() > 0 && p.option(UpdateStrategy.FF).allowed()).toList();
        List<Plan> blocked = plans.stream().filter(p -> p.allowed() && p.behind() > 0 && !p.option(UpdateStrategy.FF).allowed()).toList();
        if (ready.isEmpty()) {
            toast(blocked.isEmpty() ? Tone.OK : Tone.WARN, blocked.isEmpty() ? "Every branch has the commits of its upstream"
                    : "Nothing to fast-forward; " + blocked.size() + " need another strategy: update them one by one (u)");
            return;
        }
        int width = plans.stream().mapToInt(p -> p.repository().name().length()).max().orElse(10) + 2;
        List<DialogLine> lines = new ArrayList<>();
        for (Plan plan : ready) {
            lines.add(new DialogLine(Tone.OK, "✔ " + pad(plan.repository().name(), width) + plan.branch() + "  +" + plan.behind()
                    + " from " + plan.targetRef()));
        }
        for (Plan plan : blocked) {
            lines.add(new DialogLine(Tone.WARN, "▲ " + pad(plan.repository().name(), width) + "skipped: "
                    + plan.option(UpdateStrategy.FF).refusal()));
        }
        state.dialog(new Dialog("Fast-forward " + (ready.size() == 1 ? "1 repository" : ready.size() + " repositories") + "?",
                lines, "fast-forward", () -> task("fast-forwarding " + ready.size() + " repositories", () -> ready.stream()
                .map(p -> service.update(p, UpdateStrategy.FF)).toList(), results -> {
            long failed = results.stream().filter(r -> r.outcome() != Outcome.UPDATED).count();
            int commits = results.stream().filter(r -> r.outcome() == Outcome.UPDATED).mapToInt(r -> r.plan().behind()).sum();
            toast(failed == 0 ? Tone.OK : Tone.WARN, (results.size() - failed) + " fast-forwarded, " + commits + " commits brought in"
                    + (failed == 0 ? "" : "; " + failed + " failed: " + results.stream().filter(r -> r.outcome() != Outcome.UPDATED)
                    .map(r -> r.plan().repository().name() + " (" + r.message() + ")").findFirst().orElse("")));
            task("reading git", () -> service.git(names(), false), this::apply);
        })));
    }

    // ---------------------------------------------------------------- switch branches

    private void switchBranch(RepoState repo) {
        task("listing the branches of " + repo.name(), () -> service.branches(repo.name()),
                branches -> openSwitch(repo, branches, false, ""));
    }

    private void openSwitch(RepoState repo, List<Branch> branches, boolean everywhere, String filter) {
        String current = branches.stream().filter(Branch::current).map(Branch::name).findFirst().orElse("(detached)");
        List<DialogLine> header = new ArrayList<>();
        int dirty = repo.git() == null || repo.git().tree() == null ? 0 : repo.git().tree().changed() + repo.git().tree().untracked();
        header.add(new DialogLine(null, "on " + current + (dirty > 0 ? "  ·  " + dirty + " uncommitted (you choose: park or carry)" : "")));
        header.add(new DialogLine(Tone.INFO, everywhere ? "● every repository that has the branch   ○ " + repo.name() + " only"
                : "○ every repository that has the branch   ● " + repo.name() + " only"));
        List<Menu.Item> items = new ArrayList<>();
        for (Branch branch : branches) {
            String detail = !branch.local() ? "remote only: a tracking branch is created"
                    : (branch.upstream() == null ? "no upstream" : branch.upstream())
                    + (branch.track().isEmpty() ? "" : " · " + branch.track());
            String note = branch.current() ? "current" : branch.parked() ? "parked changes" : branch.local() ? "" : "remote";
            Tone tone = branch.current() ? Tone.OK : branch.parked() ? Tone.WARN : branch.local() ? Tone.IDLE : Tone.INFO;
            items.add(new Menu.Item(branch.name(), branch.name(), detail, tone, note, everywhere || !branch.current()));
        }
        Menu[] menu = new Menu[1];
        menu[0] = new Menu("Switch " + (everywhere ? "every repository" : repo.name()), header, items, item -> {
            Branch branch = branches.stream().filter(b -> b.name().equals(item.id())).findFirst().orElseThrow();
            List<DialogLine> lines = new ArrayList<>();
            if (branch.current() && !everywhere) {
                lines.add(new DialogLine(Tone.IDLE, "This is the branch checked out."));
            } else {
                lines.add(new DialogLine(null, "Enter switches " + (everywhere ? "every repository that has it" : repo.name())
                        + " to " + branch.name() + (branch.local() ? "." : ", creating a local branch tracking " + branch.upstream() + ".")));
            }
            if (branch.parked()) {
                lines.add(new DialogLine(Tone.WARN, "Changes were parked with this branch; they come back when you switch to it."));
            }
            return lines;
        }, List.of(new Menu.Hint("type", "filter"), new Menu.Hint("↑↓", "choose"), new Menu.Hint("enter", "switch"),
                new Menu.Hint("tab", everywhere ? repo.name() + " only" : "every repository"), new Menu.Hint("esc", "cancel")),
                true, item -> {
            state.menu(null);
            if (everywhere) {
                switchEverywhere(item.id());
            } else {
                switchOne(repo.name(), item.id(), StashMode.ASK, false);
            }
        }, c -> {
            if (c == '\t') {
                openSwitch(repo, branches, !everywhere, menu[0].filter());
                return true;
            }
            if (c == '\n' && !menu[0].filter().isBlank() && menu[0].visible().isEmpty()) {
                String name = menu[0].filter().trim();
                state.menu(null);
                state.dialog(new Dialog("Create " + name + "?", List.of(
                        new DialogLine(null, "No branch " + name + " in " + repo.name() + "."),
                        new DialogLine(Tone.INFO, "It is created from the base branch and checked out.")),
                        "create", () -> switchOne(repo.name(), name, StashMode.ASK, true)));
                return true;
            }
            return false;
        });
        filter.chars().forEach(c -> menu[0].type((char) c));
        state.menu(menu[0]);
    }

    private void switchOne(String repository, String branch, StashMode mode, boolean create) {
        task("switching " + repository + " to " + branch, () -> service.switchBranch(repository, branch, mode, create), result -> {
            if (result.outcome() == BranchSwitcher.Outcome.REFUSED && mode == StashMode.ASK && result.message().contains("uncommitted")) {
                askStash(repository + " has " + result.message().split(";")[0] + " on " + result.from() + ".", result.from(), branch,
                        chosen -> switchOne(repository, branch, chosen, create));
                return;
            }
            afterSwitch(List.of(result));
        });
    }

    /** Asks what to do with uncommitted changes: park them with the branch left, or carry them along. */
    private void askStash(String why, String from, String to, Consumer<StashMode> then) {
        String left = from == null ? "this commit" : from;
        List<Menu.Item> items = List.of(
                new Menu.Item("park", "park them with " + left, "stashed now, restored when you come back to " + left, Tone.OK, "suggested", true),
                new Menu.Item("carry", "carry them to " + to, "stashed, then restored on " + to, Tone.INFO, "", true));
        state.menu(new Menu("Uncommitted changes", List.of(new DialogLine(Tone.WARN, why)), items, item -> List.of(
                new DialogLine(null, item.id().equals("park")
                        ? "git stash push -u, then git switch; the stash comes back on your return to " + left + "."
                        : "git stash push -u, git switch, git stash pop: on a conflict the stash is kept.")),
                List.of(new Menu.Hint("↑↓", "choose"), new Menu.Hint("enter", "switch"), new Menu.Hint("esc", "cancel")),
                false, item -> {
            state.menu(null);
            then.accept(item.id().equals("park") ? StashMode.PARK : StashMode.CARRY);
        }, null));
    }

    private void switchEverywhere(String branch) {
        boolean dirty = state.repositories().stream().anyMatch(r -> r.git() != null && r.git().tree() != null
                && r.git().tree().branch() != null && !r.git().tree().branch().equals(branch)
                && r.git().tree().changed() + r.git().tree().untracked() > 0);
        if (dirty) {
            askStash("Some repositories have uncommitted changes.", "their branch", branch, mode -> switchAll(branch, mode));
        } else {
            switchAll(branch, StashMode.NEVER);
        }
    }

    private void switchAll(String branch, StashMode mode) {
        List<String> names = names();
        task("switching to " + branch, () -> names.stream().map(n -> {
            try {
                return service.switchBranch(n, branch, mode, false);
            } catch (RuntimeException e) {
                return new BranchSwitcher.Result(BranchSwitcher.Outcome.FAILED, n, null, branch, null, false, null,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }).toList(), this::afterSwitch);
    }

    private void afterSwitch(List<BranchSwitcher.Result> results) {
        task("reading git", () -> service.git(names(), false), this::apply);
        if (results.size() == 1) {
            BranchSwitcher.Result result = results.get(0);
            boolean ok = result.outcome() == BranchSwitcher.Outcome.SWITCHED || result.outcome() == BranchSwitcher.Outcome.ALREADY;
            List<String> parts = new ArrayList<>(List.of(result.repository() + ": " + result.message()));
            if (result.stashed() != null) {
                parts.add(result.stashed());
            }
            if (result.restored()) {
                parts.add("the changes parked with " + result.to() + " are back");
            }
            if (result.warning() != null) {
                parts.add(result.warning());
            }
            toast(!ok ? Tone.ERROR : result.warning() != null ? Tone.WARN : Tone.OK, String.join("; ", parts));
            return;
        }
        long switched = results.stream().filter(r -> r.outcome() == BranchSwitcher.Outcome.SWITCHED).count();
        long already = results.stream().filter(r -> r.outcome() == BranchSwitcher.Outcome.ALREADY).count();
        long missing = results.stream().filter(r -> r.outcome() == BranchSwitcher.Outcome.REFUSED && r.message().startsWith("no branch ")).count();
        List<BranchSwitcher.Result> problems = results.stream().filter(r -> r.warning() != null
                || r.outcome() == BranchSwitcher.Outcome.FAILED
                || r.outcome() == BranchSwitcher.Outcome.REFUSED && !r.message().startsWith("no branch ")).toList();
        String summary = switched + " switched" + (already > 0 ? ", " + already + " already there" : "")
                + (missing > 0 ? ", " + missing + " without the branch" : "");
        toast(problems.isEmpty() ? Tone.OK : Tone.WARN, summary + (problems.isEmpty() ? "" : "; " + problems.get(0).repository()
                + ": " + (problems.get(0).warning() != null ? problems.get(0).warning() : problems.get(0).message())));
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private void refreshGit(String repository) {
        task("reading git", () -> service.git(List.of(repository), false), this::apply);
    }

    // ---------------------------------------------------------------- events

    boolean handle(Event event, TuiRunner tui) {
        if (event instanceof TickEvent) {
            state.tick(state.tick() + 1);
            Toast toast = state.toast();
            if (toast != null && Instant.now().isAfter(toast.until())) {
                state.toast(null);
                dirty = true;
            }
            boolean redraw = dirty || state.animating() || state.tick() % 12 == 0;
            dirty = false;
            return redraw;
        }
        if (event instanceof KeyEvent key) {
            key(key, tui);
            return true;
        }
        return false;
    }

    private void key(KeyEvent key, TuiRunner tui) {
        if (key.isCtrlC()) {
            tui.quit();
            return;
        }
        Menu menu = state.menu();
        if (menu != null) {
            menuKey(menu, key);
            return;
        }
        Dialog dialog = state.dialog();
        if (dialog != null) {
            if (dialog.onConfirm() != null && (key.isCharIgnoreCase('y') || key.isKey(KeyCode.ENTER))) {
                state.dialog(null);
                dialog.onConfirm().run();
            } else if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('n') || key.isChar('q')
                    || dialog.onConfirm() == null && key.isKey(KeyCode.ENTER)) {
                state.dialog(null);
            }
            return;
        }
        if (state.help()) {
            state.help(false);
            if (key.isChar('?') || key.isKey(KeyCode.ESCAPE)) {
                return;
            }
        }
        int size = state.repositories().size();
        if (key.isKey(KeyCode.UP) || key.isChar('k')) {
            state.select(state.selectedIndex() - 1);
        } else if (key.isKey(KeyCode.DOWN) || key.isChar('j')) {
            state.select(state.selectedIndex() + 1);
        } else if (key.isKey(KeyCode.HOME) || key.isChar('g')) {
            state.select(0);
        } else if (key.isKey(KeyCode.END) || key.isChar('G')) {
            state.select(size - 1);
        } else if (key.isKey(KeyCode.PAGE_UP)) {
            state.select(state.selectedIndex() - 10);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            state.select(state.selectedIndex() + 10);
        } else if (key.isKey(KeyCode.TAB)) {
            Tab[] tabs = Tab.values();
            int step = key.hasShift() ? tabs.length - 1 : 1;
            state.tab(tabs[(state.tab().ordinal() + step) % tabs.length]);
        } else if (key.isChar('1') || key.isChar('2') || key.isChar('3') || key.isChar('4')) {
            state.tab(Tab.values()[key.character() - '1']);
        } else if (key.isKey(KeyCode.ENTER) && state.tab() == Tab.WORKSPACE) {
            state.tab(Tab.BUILD);
        } else if (key.isChar('f')) {
            fetch();
        } else if (key.isChar('b')) {
            build(state.repositories(), true);
            state.tab(Tab.BUILD);
        } else if (key.isChar('B')) {
            state.selected().ifPresent(repo -> build(List.of(repo), false));
            state.tab(Tab.BUILD);
        } else if (key.isChar('u')) {
            state.selected().ifPresent(repo -> update(repo, false));
        } else if (key.isChar('r')) {
            state.selected().ifPresent(repo -> update(repo, true));
        } else if (key.isChar('U')) {
            updateAll();
        } else if (key.isChar('s')) {
            state.selected().ifPresent(this::switchBranch);
        } else if (key.isChar('d')) {
            dependencies();
        } else if (key.isChar('?')) {
            state.help(true);
        } else if (key.isChar('q') || key.isKey(KeyCode.ESCAPE)) {
            if (state.building()) {
                state.dialog(new Dialog("Quit?", List.of(
                        new DialogLine(Tone.WARN, "A build is running; quitting stops it.")), "quit", tui::quit));
            } else {
                tui.quit();
            }
        }
    }

    private void menuKey(Menu menu, KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE)) {
            state.menu(null);
        } else if (key.isKey(KeyCode.UP) || !menu.filterable() && key.isChar('k')) {
            menu.move(-1);
        } else if (key.isKey(KeyCode.DOWN) || !menu.filterable() && key.isChar('j')) {
            menu.move(1);
        } else if (key.isKey(KeyCode.PAGE_UP)) {
            menu.move(-10);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            menu.move(10);
        } else if (key.isKey(KeyCode.TAB)) {
            menu.key('\t');
        } else if (key.isKey(KeyCode.ENTER)) {
            if (!menu.choose()) {
                menu.key('\n');
            }
        } else if (menu.filterable() && key.isKey(KeyCode.BACKSPACE)) {
            menu.erase();
        } else if (key.isKey(KeyCode.CHAR) && !key.hasCtrl() && !key.hasAlt()) {
            if (menu.filterable()) {
                menu.type(key.character());
            } else if (!menu.key(key.character()) && key.isChar('q')) {
                state.menu(null);
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Runs {@code work} in the background under an activity label shown in the header, then
     * {@code apply} with its result on the render thread. Must be called on the render thread.
     */
    private <T> void task(String activity, Supplier<T> work, Consumer<T> apply) {
        task(activity, work, apply, () -> { });
    }

    /** As above, with {@code onError} run on the render thread when {@code work} fails. */
    private <T> void task(String activity, Supplier<T> work, Consumer<T> apply, Runnable onError) {
        state.activity().add(activity);
        dirty = true;
        if (synchronous) {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                state.activity().remove(activity);
                onError.run();
                toast(Tone.ERROR, activity + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                return;
            }
            state.activity().remove(activity);
            apply.accept(result);
            return;
        }
        background.submit(() -> {
            try {
                T result = work.get();
                ui(() -> {
                    state.activity().remove(activity);
                    apply.accept(result);
                });
            } catch (RuntimeException e) {
                ui(() -> {
                    state.activity().remove(activity);
                    onError.run();
                    toast(Tone.ERROR, activity + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                });
            }
        });
    }

    private void ui(Runnable action) {
        if (synchronous) {
            action.run();
            return;
        }
        TuiRunner tui = runner;
        if (tui != null && tui.isRunning()) {
            tui.runLater(() -> {
                action.run();
                dirty = true;
            });
        }
    }

    private void toast(Tone tone, String message) {
        state.toast(new Toast(tone, message, Instant.now().plus(TOAST)));
        dirty = true;
    }

    private List<String> names() {
        return state.repositories().stream().map(RepoState::name).toList();
    }
}
