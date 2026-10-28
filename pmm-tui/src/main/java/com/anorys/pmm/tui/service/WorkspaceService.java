package com.anorys.pmm.tui.service;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.git.BranchSwitcher;
import com.anorys.pmm.git.BranchUpdater;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.Report;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What the dashboard can do with the workspace. Every method blocks, and is called from a background
 * thread; {@link LiveWorkspace} runs git and Maven, {@link DemoService} pretends to.
 */
public interface WorkspaceService {

    /** The repositories to show, in the order of the configuration. */
    record Repository(String name, Path path) {
    }

    record Loaded(Path root, List<Repository> repositories, Workspace workspace,
                  Map<String, Duration> lastBuildDurations, List<String> warnings) {
    }

    record GitState(List<RepositoryStatus> statuses, List<Finding> findings) {
    }

    record Dependencies(Report report, List<Finding> findings, List<String> notes) {
    }

    interface BuildListener {
        void started(String repository);

        void finished(BuildResult result, List<Finding> findings);
    }

    enum PushPolicy { NEVER, ASK, ALWAYS }

    Loaded load();

    GitState git(List<String> repositories, boolean fetch);

    Dependencies dependencies();

    /** Builds the repositories, several at a time; returns the blockers found. */
    List<com.anorys.pmm.build.BuildChecks.Blocker> build(List<String> repositories, boolean useCache, BuildListener listener);

    /** Where the output of a repository's build goes, to follow it while it runs. */
    Path buildLog(String repository);

    /**
     * What updating the current branch would do, for each target that makes sense: its upstream, then
     * its base branch (one plan when they are the same branch).
     */
    List<BranchUpdater.Plan> planUpdate(String repository, boolean autostash);

    BranchUpdater.Result update(BranchUpdater.Plan plan, UpdateStrategy strategy);

    /** The updated branch can be pushed to its upstream (it differs, and is not push-protected). */
    boolean pushable(BranchUpdater.Result result);

    PushPolicy pushPolicy();

    /** Pushes an updated branch with a lease; returns why it was not pushed, or null. */
    String push(BranchUpdater.Result result);

    /** The branches to switch to, the current one first. */
    List<BranchSwitcher.Branch> branches(String repository);

    /**
     * Switches to a branch, restoring the changes parked with it; {@link StashMode#ASK} refuses when
     * there are uncommitted changes. With {@code create}, a missing branch is created from the base branch.
     */
    BranchSwitcher.Result switchBranch(String repository, String branch, StashMode mode, boolean create);
}
