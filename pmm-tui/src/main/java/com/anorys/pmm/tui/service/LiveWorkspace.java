package com.anorys.pmm.tui.service;

import com.anorys.pmm.build.BuildCache;
import com.anorys.pmm.build.BuildChecks;
import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.build.BuildRunner;
import com.anorys.pmm.build.MavenCommand;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.process.ExecutableResolver;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.core.process.SystemProcessRunner;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.git.GitChecks;
import com.anorys.pmm.git.BranchSwitcher;
import com.anorys.pmm.git.BranchUpdater;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.git.StatusCollector;
import com.anorys.pmm.git.UnreleasedChanges;
import com.anorys.pmm.maven.DependencyChecks;
import com.anorys.pmm.maven.DependencyChecks.ProducerRelease;
import com.anorys.pmm.maven.DependencyChecks.Report;
import com.anorys.pmm.maven.WorkspaceScanner;
import com.anorys.pmm.maven.nexus.MetadataSource;
import com.anorys.pmm.maven.nexus.NexusClient;
import com.anorys.pmm.maven.nexus.NexusSettings;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The real workspace: git, Maven and Nexus, through the same classes as the commands. */
public final class LiveWorkspace implements WorkspaceService {

    private final PmmConfig config;
    private final ProcessRunner runner = new SystemProcessRunner();
    private volatile Workspace workspace;

    public LiveWorkspace(PmmConfig config) {
        this.config = config;
    }

    @Override
    public Loaded load() {
        WorkspaceScanner.ScanResult scan = WorkspaceScanner.scan(config);
        workspace = scan.workspace();
        BuildCache cache = BuildCache.load(BuildRunner.cacheFile(config));
        Map<String, Duration> durations = new LinkedHashMap<>();
        List<Repository> repositories = new ArrayList<>();
        for (RepositoryConfig repository : config.monitoredRepositories()) {
            repositories.add(new Repository(repository.name(), repository.path()));
            cache.get(repository.name()).ifPresent(e -> durations.put(repository.name(), Duration.ofMillis(e.durationMillis())));
        }
        return new Loaded(config.workspace().root(), repositories, workspace, durations, scan.warnings());
    }

    @Override
    public GitState git(List<String> repositories, boolean fetch) {
        List<RepositoryStatus> statuses = new StatusCollector(runner).collect(config, select(repositories), fetch);
        return new GitState(statuses, new GitChecks(config, Clock.systemDefaultZone()).evaluate(statuses));
    }

    @Override
    public Dependencies dependencies() {
        // The same steps as 'pmm deps': Nexus first, then git for the unreleased changes.
        List<String> notes = new ArrayList<>();
        MetadataSource source = null;
        if (config.maven().offline()) {
            notes.add("offline: Nexus not queried, release checks skipped");
        } else {
            NexusSettings.Resolution nexus = NexusSettings.resolve(config);
            notes.addAll(nexus.notes());
            if (nexus.endpoint().isPresent()) {
                notes.add("Nexus: " + nexus.endpoint().get());
                source = new NexusClient(nexus.endpoint().get());
            }
        }
        Report report = new DependencyChecks(config, workspace(), source).evaluate();
        List<Finding> findings = new ArrayList<>(report.findings());
        UnreleasedChanges unreleased = new UnreleasedChanges(config, runner);
        for (ProducerRelease release : report.releases()) {
            config.repository(release.repository())
                    .flatMap(repository -> unreleased.evaluate(repository, release.versions(), release.consumers()))
                    .ifPresent(findings::add);
        }
        return new Dependencies(report, findings, notes);
    }

    @Override
    public List<Blocker> build(List<String> repositories, boolean useCache, BuildListener listener) {
        BuildRunner builder = new BuildRunner(config, workspace(), runner,
                new MavenCommand(config, ExecutableResolver.fromEnvironment()), Clock.systemUTC());
        BuildChecks checks = new BuildChecks(config);
        List<BuildResult> results = builder.build(select(repositories),
                new BuildRunner.Options(config.build().effectiveParallelRepos(), useCache && config.build().cache(),
                        config.maven().offline()),
                new BuildRunner.Listener() {
                    @Override
                    public void started(String repository) {
                        listener.started(repository);
                    }

                    @Override
                    public void finished(BuildResult result) {
                        listener.finished(result, checks.evaluate(List.of(result)));
                    }
                });
        return BuildChecks.blockers(results);
    }

    @Override
    public Path buildLog(String repository) {
        return BuildRunner.logDirectory(config).resolve(repository + ".log");
    }

    @Override
    public List<BranchUpdater.Plan> planUpdate(String repository, boolean autostash) {
        return updater().plans(config.repository(repository).orElseThrow(), false, autostash);
    }

    @Override
    public BranchUpdater.Result update(BranchUpdater.Plan plan, UpdateStrategy strategy) {
        return updater().update(plan, strategy);
    }

    @Override
    public boolean pushable(BranchUpdater.Result result) {
        return updater().pushable(result);
    }

    @Override
    public PushPolicy pushPolicy() {
        return PushPolicy.valueOf(config.rebase().push().name());
    }

    @Override
    public String push(BranchUpdater.Result result) {
        return updater().push(result);
    }

    @Override
    public List<BranchSwitcher.Branch> branches(String repository) {
        return switcher().branches(config.repository(repository).orElseThrow());
    }

    @Override
    public BranchSwitcher.Result switchBranch(String repository, String branch, StashMode mode, boolean create) {
        return switcher().switchTo(config.repository(repository).orElseThrow(), branch, mode, true, create);
    }

    private BranchUpdater updater() {
        return new BranchUpdater(config, runner, Clock.systemDefaultZone());
    }

    private BranchSwitcher switcher() {
        return new BranchSwitcher(config, runner);
    }

    private Workspace workspace() {
        Workspace current = workspace;
        if (current == null) {
            current = WorkspaceScanner.scan(config).workspace();
            workspace = current;
        }
        return current;
    }

    private List<RepositoryConfig> select(List<String> names) {
        return names.stream().map(n -> config.repository(n).orElseThrow()).toList();
    }
}
