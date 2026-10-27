package com.anorys.pmm.tui.service;

import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.workspace.Workspace;
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

    /** What a rebase would do, for the confirmation dialog. */
    record RebasePlan(String repository, String branch, String baseRef, int ahead, int behind,
                      List<String> predictedConflicts, List<String> refusals, Object handle) {

        public boolean allowed() {
            return refusals.isEmpty();
        }
    }

    enum RebaseOutcome { REBASED, UP_TO_DATE, CONFLICTS, REFUSED }

    record RebaseResult(RebaseOutcome outcome, String message, String backupRef, Object handle) {
    }

    enum PushPolicy { NEVER, ASK, ALWAYS }

    Loaded load();

    GitState git(List<String> repositories, boolean fetch);

    Dependencies dependencies();

    /** Builds the repositories, several at a time; returns the blockers found. */
    List<com.anorys.pmm.build.BuildChecks.Blocker> build(List<String> repositories, boolean useCache, BuildListener listener);

    /** Where the output of a repository's build goes, to follow it while it runs. */
    Path buildLog(String repository);

    RebasePlan planRebase(String repository);

    RebaseResult rebase(RebasePlan plan);

    PushPolicy pushPolicy();

    /** Pushes a rebased branch with a lease; returns why it was not pushed, or null. */
    String push(RebaseResult result);
}
