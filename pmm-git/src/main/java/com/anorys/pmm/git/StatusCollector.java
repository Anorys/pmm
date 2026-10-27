package com.anorys.pmm.git;

import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.process.ProcessException;
import com.anorys.pmm.core.process.ProcessRunner;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Collects the git status of several repositories at once. Fetches run in parallel, since they
 * spend their time waiting on the network.
 */
public final class StatusCollector {

    private final ProcessRunner runner;

    public StatusCollector(ProcessRunner runner) {
        this.runner = runner;
    }

    /** Statuses in the order of {@code repositories}. */
    public List<RepositoryStatus> collect(PmmConfig config, List<RepositoryConfig> repositories, boolean fetch) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RepositoryStatus>> futures = new ArrayList<>();
            for (RepositoryConfig repository : repositories) {
                futures.add(executor.submit(() -> collect(config, repository, fetch)));
            }
            List<RepositoryStatus> result = new ArrayList<>();
            for (Future<RepositoryStatus> future : futures) {
                result.add(future.get());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessException("Interrupted while reading git status", e);
        } catch (ExecutionException e) {
            throw new ProcessException("Cannot read git status: " + e.getCause().getMessage(), e.getCause());
        }
    }

    RepositoryStatus collect(PmmConfig config, RepositoryConfig repository, boolean fetch) {
        String remote = config.workspace().remote();
        String baseRef = remote + "/" + config.baseBranch(repository);
        if (!Files.isDirectory(repository.path())) {
            return RepositoryStatus.failed(repository.name(), repository.path(), baseRef, "not cloned at " + repository.path());
        }
        GitClient git = new GitClient(runner, repository.path());
        if (!git.isRepository()) {
            return RepositoryStatus.failed(repository.name(), repository.path(), baseRef, repository.path() + " is not a git repository");
        }
        String fetchError = null;
        if (fetch) {
            try {
                git.fetch(remote);
            } catch (GitException e) {
                fetchError = e.getMessage();
            }
        }
        try {
            WorkingTreeStatus tree = git.status();
            if (!git.exists(baseRef)) {
                return new RepositoryStatus(repository.name(), repository.path(), baseRef, tree, null, null, fetchError, null);
            }
            GitClient.AheadBehind vsBase = git.aheadBehind(baseRef, "HEAD");
            Instant behindSince = vsBase.behind() > 0 ? git.oldestMissingCommit(baseRef, "HEAD").orElse(null) : null;
            return new RepositoryStatus(repository.name(), repository.path(), baseRef, tree, vsBase, behindSince, fetchError, null);
        } catch (GitException e) {
            return RepositoryStatus.failed(repository.name(), repository.path(), baseRef, e.getMessage());
        }
    }
}
