package com.anorys.pmm.tui.model;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.tui.theme.Tone;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** What the dashboard knows about one repository. Only read and changed on the render thread. */
public final class RepoState {

    public enum BuildPhase { NONE, QUEUED, RUNNING, DONE }

    private final String name;
    private final Path path;
    private String version;
    private int modules;

    private RepositoryStatus git;
    private List<Finding> gitFindings = List.of();

    private List<InternalUsage> usages = List.of();
    private List<Finding> depFindings = List.of();

    private BuildPhase buildPhase = BuildPhase.NONE;
    private Instant buildStarted;
    private BuildResult build;
    private List<Finding> buildFindings = List.of();
    /** Duration of the last successful build, to estimate the progress of the next one; may be null. */
    private Duration expectedBuild;
    /** "[INFO] Building core-api 3.1.5 [2/7]" seen in the running build's log: module 2 of 7. */
    private String currentModule;
    private int moduleIndex;
    private int moduleCount;
    private List<String> logTail = List.of();

    public RepoState(String name, Path path) {
        this.name = name;
        this.path = path;
    }

    public String name() {
        return name;
    }

    public Path path() {
        return path;
    }

    public String version() {
        return version;
    }

    public int modules() {
        return modules;
    }

    public void project(String version, int modules) {
        this.version = version;
        this.modules = modules;
    }

    public RepositoryStatus git() {
        return git;
    }

    public List<Finding> gitFindings() {
        return gitFindings;
    }

    public void git(RepositoryStatus status, List<Finding> findings) {
        this.git = status;
        this.gitFindings = List.copyOf(findings);
    }

    public List<InternalUsage> usages() {
        return usages;
    }

    public List<Finding> depFindings() {
        return depFindings;
    }

    public void deps(List<InternalUsage> usages, List<Finding> findings) {
        this.usages = List.copyOf(usages);
        this.depFindings = List.copyOf(findings);
    }

    public BuildPhase buildPhase() {
        return buildPhase;
    }

    public Instant buildStarted() {
        return buildStarted;
    }

    public BuildResult build() {
        return build;
    }

    public List<Finding> buildFindings() {
        return buildFindings;
    }

    public Duration expectedBuild() {
        return expectedBuild;
    }

    public void expectedBuild(Duration duration) {
        this.expectedBuild = duration;
    }

    public void queued() {
        buildPhase = BuildPhase.QUEUED;
        build = null;
        buildFindings = List.of();
        currentModule = null;
        moduleIndex = 0;
        moduleCount = 0;
        logTail = List.of();
    }

    /** The build stopped before this repository was built. */
    public void cancelled() {
        buildPhase = build == null ? BuildPhase.NONE : BuildPhase.DONE;
    }

    public void started(Instant at) {
        buildPhase = BuildPhase.RUNNING;
        buildStarted = at;
    }

    public void finished(BuildResult result, List<Finding> findings) {
        buildPhase = BuildPhase.DONE;
        build = result;
        buildFindings = List.copyOf(findings);
        if (result.status() == BuildResult.Status.SUCCESS) {
            expectedBuild = result.duration();
        }
    }

    public String currentModule() {
        return currentModule;
    }

    public int moduleIndex() {
        return moduleIndex;
    }

    public int moduleCount() {
        return moduleCount;
    }

    public List<String> logTail() {
        return logTail;
    }

    public void progress(String module, int index, int count, List<String> tail) {
        if (module != null) {
            currentModule = module;
            moduleIndex = index;
            moduleCount = count;
        }
        logTail = List.copyOf(tail);
    }

    public List<Finding> findings() {
        List<Finding> all = new ArrayList<>(gitFindings);
        all.addAll(depFindings);
        all.addAll(buildFindings);
        all.sort((a, b) -> b.severity().compareTo(a.severity()));
        return all;
    }

    /** The worst state of the repository, for its health dot. */
    public Tone tone() {
        if (buildPhase == BuildPhase.RUNNING) {
            return Tone.RUNNING;
        }
        if (build != null && build.status() == BuildResult.Status.BLOCKED) {
            return Tone.BLOCKED;
        }
        Severity worst = Finding.highest(findings());
        if (worst.compareTo(Severity.INFO) <= 0) {
            return git == null ? Tone.IDLE : Tone.OK;
        }
        return Tone.of(worst);
    }
}
