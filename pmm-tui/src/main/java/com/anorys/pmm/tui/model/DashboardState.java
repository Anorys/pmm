package com.anorys.pmm.tui.model;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.tui.theme.Tone;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Everything the dashboard shows. Only read and changed on the render thread. */
public final class DashboardState {

    public enum Tab {
        WORKSPACE("Workspace"), BUILD("Build"), DEPENDENCIES("Dependencies"), GRAPH("Graph");

        private final String title;

        Tab(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** A one-line message above the footer, dismissed after a few seconds. */
    public record Toast(Tone tone, String message, Instant until) {
    }

    /**
     * A centered question; {@code onConfirm} runs when the user presses y or Enter.
     *
     * @param lines the body, already styled by the caller as plain strings with their tone
     */
    public record Dialog(String title, List<DialogLine> lines, String confirmLabel, Runnable onConfirm) {
    }

    public record DialogLine(Tone tone, String text) {
    }

    private final Path root;
    private final List<RepoState> repositories = new ArrayList<>();
    private Workspace workspace;
    private List<Blocker> blockers = List.of();
    private List<String> notes = List.of();

    private Tab tab = Tab.WORKSPACE;
    private int selected;
    private final List<String> activity = new ArrayList<>();
    private Instant lastFetch;
    private Instant depsCheckedAt;
    private Toast toast;
    private Dialog dialog;
    private Menu menu;
    private boolean help;
    private long tick;

    public DashboardState(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public List<RepoState> repositories() {
        return repositories;
    }

    public Optional<RepoState> repository(String name) {
        return repositories.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    public Workspace workspace() {
        return workspace;
    }

    public void workspace(Workspace workspace) {
        this.workspace = workspace;
    }

    public List<Blocker> blockers() {
        return blockers;
    }

    public void blockers(List<Blocker> blockers) {
        this.blockers = List.copyOf(blockers);
    }

    public List<String> notes() {
        return notes;
    }

    public void notes(List<String> notes) {
        this.notes = List.copyOf(notes);
    }

    public Tab tab() {
        return tab;
    }

    public void tab(Tab tab) {
        this.tab = tab;
    }

    public int selectedIndex() {
        return repositories.isEmpty() ? -1 : Math.min(selected, repositories.size() - 1);
    }

    public Optional<RepoState> selected() {
        int i = selectedIndex();
        return i < 0 ? Optional.empty() : Optional.of(repositories.get(i));
    }

    public void select(int index) {
        selected = Math.max(0, Math.min(index, repositories.size() - 1));
    }

    /** What runs in the background right now ("fetching", "checking Nexus"...), shown in the header. */
    public List<String> activity() {
        return activity;
    }

    public boolean busy(String what) {
        return activity.contains(what);
    }

    public Instant lastFetch() {
        return lastFetch;
    }

    public void lastFetch(Instant at) {
        this.lastFetch = at;
    }

    public Instant depsCheckedAt() {
        return depsCheckedAt;
    }

    public void depsCheckedAt(Instant at) {
        this.depsCheckedAt = at;
    }

    public Toast toast() {
        return toast;
    }

    public void toast(Toast toast) {
        this.toast = toast;
    }

    public Dialog dialog() {
        return dialog;
    }

    public void dialog(Dialog dialog) {
        this.dialog = dialog;
    }

    public Menu menu() {
        return menu;
    }

    public void menu(Menu menu) {
        this.menu = menu;
    }

    public boolean help() {
        return help;
    }

    public void help(boolean help) {
        this.help = help;
    }

    public long tick() {
        return tick;
    }

    public void tick(long tick) {
        this.tick = tick;
    }

    public boolean building() {
        return repositories.stream().anyMatch(r -> r.buildPhase() == RepoState.BuildPhase.RUNNING
                || r.buildPhase() == RepoState.BuildPhase.QUEUED);
    }

    /** Something moves on screen: a spinner, a running build. */
    public boolean animating() {
        return !activity.isEmpty() || building();
    }

    /** Share of the repositories with no warning or error, for the header gauge. */
    public double health() {
        long known = repositories.stream().filter(r -> r.tone() != Tone.IDLE).count();
        if (known == 0) {
            return 0;
        }
        long healthy = repositories.stream().filter(r -> r.tone() == Tone.OK || r.tone() == Tone.RUNNING).count();
        return (double) healthy / known;
    }
}
