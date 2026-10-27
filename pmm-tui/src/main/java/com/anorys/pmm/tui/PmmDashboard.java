package com.anorys.pmm.tui;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.DashboardState.Dialog;
import com.anorys.pmm.tui.model.DashboardState.DialogLine;
import com.anorys.pmm.tui.model.DashboardState.Tab;
import com.anorys.pmm.tui.model.DashboardState.Toast;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.model.RepoState.BuildPhase;
import com.anorys.pmm.tui.service.WorkspaceService;
import com.anorys.pmm.tui.service.WorkspaceService.BuildListener;
import com.anorys.pmm.tui.service.WorkspaceService.Dependencies;
import com.anorys.pmm.tui.service.WorkspaceService.GitState;
import com.anorys.pmm.tui.service.WorkspaceService.Loaded;
import com.anorys.pmm.tui.service.WorkspaceService.PushPolicy;
import com.anorys.pmm.tui.service.WorkspaceService.RebaseOutcome;
import com.anorys.pmm.tui.service.WorkspaceService.RebasePlan;
import com.anorys.pmm.tui.service.WorkspaceService.RebaseResult;
import com.anorys.pmm.tui.service.WorkspaceService.Repository;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.theme.Tone;
import com.anorys.pmm.tui.view.DashboardView;
import com.anorys.pmm.tui.view.Format;
import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private TuiRunner runner;
    private boolean dirty = true;

    public PmmDashboard(WorkspaceService service, Path root, Theme theme) {
        this.service = service;
        this.state = new DashboardState(root);
        this.view = new DashboardView(theme);
    }

    /** Runs until the user quits. */
    public void run() throws Exception {
        // The backend is named rather than discovered, so that the native image needs no service lookup.
        TuiConfig config = TuiConfig.builder().backend(new JLineBackend()).tickRate(TICK).build();
        try (TuiRunner tui = TuiRunner.create(config)) {
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

    // ---------------------------------------------------------------- rebase

    private void rebase(RepoState repo) {
        if (repo.buildPhase() == BuildPhase.RUNNING || repo.buildPhase() == BuildPhase.QUEUED) {
            toast(Tone.WARN, repo.name() + " is building; rebase it once the build is done");
            return;
        }
        task("planning the rebase of " + repo.name(), () -> service.planRebase(repo.name()), plan -> confirmRebase(plan));
    }

    private void confirmRebase(RebasePlan plan) {
        List<DialogLine> lines = new ArrayList<>();
        lines.add(new DialogLine(null, plan.branch() + "  →  " + plan.baseRef()));
        lines.add(new DialogLine(Tone.INFO, plan.ahead() + " commit" + (plan.ahead() == 1 ? "" : "s") + " of yours to replay, "
                + plan.behind() + " new on " + plan.baseRef()));
        if (!plan.allowed()) {
            plan.refusals().forEach(r -> lines.add(new DialogLine(Tone.ERROR, "✘ " + r)));
            state.dialog(new Dialog("Cannot rebase " + plan.repository(), lines, null, null));
            return;
        }
        if (plan.behind() == 0) {
            toast(Tone.OK, plan.branch() + " already contains " + plan.baseRef());
            return;
        }
        if (plan.predictedConflicts().isEmpty()) {
            lines.add(new DialogLine(Tone.OK, "✔ no conflict expected"));
        } else {
            lines.add(new DialogLine(Tone.WARN, "▲ conflicts expected in " + plan.predictedConflicts().size() + " file"
                    + (plan.predictedConflicts().size() == 1 ? "" : "s") + ":"));
            plan.predictedConflicts().stream().limit(4).forEach(f -> lines.add(new DialogLine(Tone.WARN, "   " + f)));
            lines.add(new DialogLine(null, "On conflict the rebase is aborted and the branch left as it was."));
        }
        lines.add(new DialogLine(null, "The branch is saved under refs/pmm/backup/ first."));
        state.dialog(new Dialog("Rebase " + plan.repository() + "?", lines, "rebase", () ->
                task("rebasing " + plan.repository(), () -> service.rebase(plan), result -> afterRebase(plan, result))));
    }

    private void afterRebase(RebasePlan plan, RebaseResult result) {
        refreshGit(plan.repository());
        if (result.outcome() != RebaseOutcome.REBASED) {
            toast(result.outcome() == RebaseOutcome.UP_TO_DATE ? Tone.OK : Tone.ERROR, plan.repository() + ": " + result.message());
            return;
        }
        toast(Tone.OK, plan.repository() + ": " + result.message());
        PushPolicy policy = service.pushPolicy();
        if (policy == PushPolicy.ALWAYS) {
            push(plan, result);
        } else if (policy == PushPolicy.ASK) {
            state.dialog(new Dialog("Push " + plan.branch() + "?", List.of(
                    new DialogLine(null, "The rebase rewrote the history of " + plan.branch() + "."),
                    new DialogLine(Tone.INFO, "git push --force-with-lease: refused if someone pushed in the meantime."),
                    new DialogLine(null, "Undo the rebase with: git reset --hard " + result.backupRef())),
                    "push", () -> push(plan, result)));
        }
    }

    private void push(RebasePlan plan, RebaseResult result) {
        task("pushing " + plan.branch(), () -> service.push(result), reason -> {
            if (reason == null) {
                toast(Tone.OK, plan.branch() + " pushed");
            } else {
                toast(Tone.WARN, reason);
            }
            refreshGit(plan.repository());
        });
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
        } else if (key.isChar('r')) {
            state.selected().ifPresent(this::rebase);
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
