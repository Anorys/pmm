package com.anorys.pmm.tui.service;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.config.PmmConfig.StashMode;
import com.anorys.pmm.config.PmmConfig.UpdateStrategy;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.BranchSwitcher;
import com.anorys.pmm.git.BranchUpdater;
import com.anorys.pmm.git.BranchUpdater.Target;
import com.anorys.pmm.git.WorkingTreeStatus;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.maven.DependencyChecks.Report;
import com.anorys.pmm.tui.demo.DemoWorkspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The demo workspace, with git, Nexus and Maven simulated: builds take a few seconds and write a
 * plausible Maven log, so that every screen of {@code pmm ui --demo} moves as with a real workspace.
 */
public final class DemoService implements WorkspaceService {

    /** Demo builds run this many times faster than the durations they pretend to have. */
    private static final int SPEED = 12;

    private final Path logs;
    private final Map<String, RepositoryStatus> statuses = new ConcurrentHashMap<>();
    /** Commits of the upstream not pulled yet, per repository; platform-core starts with one. */
    private final Map<String, Integer> upstreamBehind = new ConcurrentHashMap<>();
    /** Changes parked with each branch, per repository. */
    private final Map<String, Map<String, Integer>> parked = new ConcurrentHashMap<>();
    /** Branches checked out during the demo, to list them afterwards. */
    private final Map<String, List<String>> knownBranches = new ConcurrentHashMap<>();

    public DemoService() {
        try {
            logs = Files.createTempDirectory("pmm-demo-");
            logs.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Loaded load() {
        pause(400);
        List<Repository> repositories = DemoWorkspace.names().stream()
                .map(n -> new Repository(n, DemoWorkspace.root().resolve(n))).toList();
        Map<String, Duration> durations = new java.util.LinkedHashMap<>();
        for (String name : DemoWorkspace.names()) {
            durations.put(name, DemoWorkspace.buildResult(name).duration());
        }
        return new Loaded(DemoWorkspace.root(), repositories, DemoWorkspace.workspace(), durations, List.of());
    }

    @Override
    public GitState git(List<String> repositories, boolean fetch) {
        pause(fetch ? 1800 : 300);
        List<RepositoryStatus> result = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (String name : repositories) {
            result.add(statuses.computeIfAbsent(name, n -> DemoWorkspace.status(n, Instant.now())));
            if (statuses.get(name).behind() > 0) {
                findings.addAll(DemoWorkspace.gitFindings(name));
            } else {
                findings.addAll(DemoWorkspace.gitFindings(name).stream().filter(f -> !f.checkId().equals("git.behind")).toList());
            }
        }
        return new GitState(result, findings);
    }

    @Override
    public Dependencies dependencies() {
        pause(1500);
        List<InternalUsage> usages = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (String name : DemoWorkspace.names()) {
            usages.addAll(DemoWorkspace.usages(name));
            findings.addAll(DemoWorkspace.depFindings(name));
        }
        return new Dependencies(new Report(usages, findings, List.of()), findings,
                List.of("demo: Nexus is simulated"));
    }

    @Override
    public List<Blocker> build(List<String> repositories, boolean useCache, BuildListener listener) {
        ExecutorService executor = Executors.newFixedThreadPool(3, Thread.ofPlatform().name("pmm-demo-build-", 1).daemon().factory());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String name : repositories) {
                futures.add(executor.submit(() -> buildOne(name, useCache, listener)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
        return repositories.contains("invoicing") ? DemoWorkspace.blockers() : List.of();
    }

    private void buildOne(String name, boolean useCache, BuildListener listener) {
        listener.started(name);
        BuildResult expected = DemoWorkspace.buildResult(name);
        if (useCache && expected.status() == BuildResult.Status.CACHED) {
            pause(300);
            listener.finished(expected, List.of());
            return;
        }
        int modules = DemoWorkspace.modules(name);
        long perModule = Math.max(250, expected.duration().toMillis() / SPEED / modules);
        int stopAt = expected.status() == BuildResult.Status.BLOCKED ? 1 : expected.status() == BuildResult.Status.FAILED ? modules - 1 : modules;
        long start = System.nanoTime();
        try (Writer log = Files.newBufferedWriter(buildLog(name), StandardCharsets.UTF_8)) {
            line(log, "[INFO] Scanning for projects...");
            for (int i = 1; i <= stopAt; i++) {
                String module = name + (i == 1 ? "-api" : i == 2 ? "-model" : i == 3 ? "-service" : "-module" + i);
                line(log, "[INFO] ");
                line(log, "[INFO] ----------------------< com.anorys:" + module + " >----------------------");
                line(log, "[INFO] Building " + module + " 1.0.0-SNAPSHOT                    [" + i + "/" + modules + "]");
                line(log, "[INFO] --------------------------------[ jar ]---------------------------------");
                pause(perModule / 3);
                line(log, "[INFO] --- resources:3.3.1:resources (default-resources) @ " + module + " ---");
                line(log, "[INFO] --- compiler:3.13.0:compile (default-compile) @ " + module + " ---");
                line(log, "[INFO] Compiling " + (40 + i * 17) + " source files with javac [debug release 21] to target/classes");
                if (i == 2) {
                    line(log, "[WARNING] Mapper.java:[57,18] unchecked conversion");
                }
                pause(perModule / 3);
                line(log, "[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ " + module + " ---");
                pause(perModule / 3);
            }
            if (expected.status() == BuildResult.Status.FAILED) {
                line(log, "[ERROR] COMPILATION ERROR : ");
                line(log, "[ERROR] " + expected.failure().detail());
                line(log, "[INFO] BUILD FAILURE");
            } else if (expected.status() == BuildResult.Status.BLOCKED) {
                line(log, "[ERROR] Failed to execute goal on project " + name + "-service: Could not resolve dependencies:");
                line(log, "[ERROR] com.anorys:billing-api:jar:4.0.0 was not found in https://nexus.anorys.example");
                line(log, "[INFO] BUILD FAILURE");
            } else {
                line(log, "[INFO] ------------------------------------------------------------------------");
                line(log, "[INFO] BUILD SUCCESS");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start).multipliedBy(SPEED);
        BuildResult.Status status = expected.status() == BuildResult.Status.CACHED ? BuildResult.Status.SUCCESS : expected.status();
        BuildResult result = new BuildResult(name, status, took, buildLog(name), expected.failure(), null);
        listener.finished(result, DemoWorkspace.buildFindings(result));
    }

    private static void line(Writer log, String line) throws IOException {
        log.write(line);
        log.write(System.lineSeparator());
        log.flush();
    }

    @Override
    public Path buildLog(String repository) {
        return logs.resolve(repository + ".log");
    }

    @Override
    public List<BranchUpdater.Plan> planUpdate(String repository, boolean autostash) {
        pause(500);
        RepositoryStatus status = status(repository);
        WorkingTreeStatus tree = status.tree();
        String branch = tree.branch();
        boolean develop = branch.equals("develop");
        RepositoryConfig config = new RepositoryConfig(repository, status.path(), null, null, List.of("*"), true, null);
        List<String> conflicts = repository.equals("invoicing")
                ? List.of("invoicing-service/src/main/java/com/anorys/invoicing/PdfRenderer.java") : List.of();
        BranchUpdater.Plan onBase = plan(config, tree, Target.BASE, status.baseRef(), status.ahead(), status.behind(),
                develop ? 0 : status.ahead(), conflicts, develop, autostash);
        if (develop) {
            return List.of(onBase);
        }
        int behindUpstream = upstreamBehind.getOrDefault(repository, repository.equals("platform-core") ? 1 : 0);
        return List.of(plan(config, tree, Target.UPSTREAM, tree.upstream(), 0, behindUpstream, 0, List.of(), false, autostash), onBase);
    }

    private static BranchUpdater.Plan plan(RepositoryConfig config, WorkingTreeStatus tree, Target target, String targetRef,
                                           int ahead, int behind, int published, List<String> conflicts, boolean pushProtected,
                                           boolean autostash) {
        List<BranchUpdater.Option> options = BranchUpdater.options(tree.branch(), targetRef, tree.upstream(), ahead, behind,
                published, conflicts, tree, pushProtected, autostash, true);
        UpdateStrategy suggested = BranchUpdater.suggest(options, pushProtected ? UpdateStrategy.FF : UpdateStrategy.REBASE);
        return new BranchUpdater.Plan(config, tree.branch(), target, targetRef, ahead, behind, published, conflicts, tree,
                List.of(), suggested, options, autostash, pushProtected, tree.upstream(), "demo", null);
    }

    @Override
    public BranchUpdater.Result update(BranchUpdater.Plan plan, UpdateStrategy strategy) {
        pause(1200);
        String name = plan.repository().name();
        String backup = strategy == UpdateStrategy.FF ? null : "refs/pmm/backup/" + plan.branch() + "/demo";
        BranchUpdater.Option option = plan.option(strategy);
        if (!option.allowed()) {
            return new BranchUpdater.Result(BranchUpdater.Outcome.REFUSED, plan, strategy, null, null, List.of(), option.refusal());
        }
        if (!plan.predictedConflicts().isEmpty() && (strategy == UpdateStrategy.MERGE || strategy == UpdateStrategy.REBASE)) {
            return new BranchUpdater.Result(BranchUpdater.Outcome.CONFLICTS, plan, strategy, backup, null, plan.predictedConflicts(),
                    "conflicts; the " + strategy.id() + " was aborted and " + plan.branch() + " is unchanged");
        }
        RepositoryStatus status = status(name);
        if (plan.target() == Target.UPSTREAM) {
            upstreamBehind.put(name, 0);
            return new BranchUpdater.Result(BranchUpdater.Outcome.UPDATED, plan, strategy, backup, null, List.of(),
                    plan.branch() + " brought up to date with " + plan.targetRef());
        }
        int ahead = status.ahead();
        int changes = status.tree().changed();
        switch (strategy) {
            case MERGE -> ahead = ahead == 0 ? 0 : ahead + 1;
            case RESET_SOFT, RESET_MIXED -> {
                changes += ahead;
                ahead = 0;
            }
            case RESET_HARD -> {
                ahead = 0;
                changes = 0;
            }
            default -> {
            }
        }
        statuses.put(name, DemoWorkspace.withBranch(status, status.tree().branch(), ahead, 0, changes));
        String message = switch (strategy) {
            case FF -> plan.branch() + " fast-forwarded by " + plan.behind() + " commits of " + plan.targetRef();
            case MERGE -> plan.behind() + " commits of " + plan.targetRef() + " merged into " + plan.branch();
            case REBASE -> plan.ahead() + " commits of " + plan.branch() + " replayed onto " + plan.targetRef();
            default -> plan.branch() + " reset (" + strategy.id().substring(6) + ") to " + plan.targetRef();
        };
        String wip = strategy == UpdateStrategy.RESET_HARD && status.tree().changed() > 0 ? "refs/pmm/wip/" + plan.branch() + "/demo" : null;
        return new BranchUpdater.Result(BranchUpdater.Outcome.UPDATED, plan, strategy, backup, wip, List.of(), message);
    }

    @Override
    public boolean pushable(BranchUpdater.Result result) {
        return result.outcome() == BranchUpdater.Outcome.UPDATED && !result.plan().pushProtected()
                && result.plan().target() == Target.BASE;
    }

    @Override
    public PushPolicy pushPolicy() {
        return PushPolicy.ASK;
    }

    @Override
    public String push(BranchUpdater.Result result) {
        pause(900);
        return null;
    }

    @Override
    public List<BranchSwitcher.Branch> branches(String repository) {
        pause(250);
        String current = status(repository).tree().branch();
        Set<String> parkedHere = parked.getOrDefault(repository, Map.of()).keySet();
        Instant now = Instant.now();
        List<BranchSwitcher.Branch> branches = new ArrayList<>();
        branches.add(new BranchSwitcher.Branch(current, true, true, "origin/" + current, "", now, false));
        List<String> locals = new ArrayList<>(List.of("develop", "feature/PMM-130-logging", "release/3.1"));
        knownBranches.getOrDefault(repository, List.of()).forEach(b -> {
            if (!locals.contains(b)) {
                locals.add(b);
            }
        });
        int day = 1;
        for (String local : locals) {
            if (!local.equals(current)) {
                branches.add(new BranchSwitcher.Branch(local, true, false, "origin/" + local,
                        local.startsWith("feature") ? "ahead 2" : "", now.minus(Duration.ofDays(day++)), parkedHere.contains(local)));
            }
        }
        for (String remote : List.of("feature/PMM-170-api-v2", "feature/PMM-171-audit", "bugfix/PMM-165-rounding")) {
            if (!locals.contains(remote) && !remote.equals(current)) {
                branches.add(new BranchSwitcher.Branch(remote, false, false, "origin/" + remote, "",
                        now.minus(Duration.ofDays(day++)), false));
            }
        }
        return branches;
    }

    @Override
    public BranchSwitcher.Result switchBranch(String repository, String branch, StashMode mode, boolean create) {
        pause(600);
        RepositoryStatus status = status(repository);
        String from = status.tree().branch();
        if (branch.equals(from)) {
            return new BranchSwitcher.Result(BranchSwitcher.Outcome.ALREADY, repository, from, branch, null, false, null, "already on " + branch);
        }
        int changes = status.tree().changed();
        Map<String, Integer> parkedHere = parked.computeIfAbsent(repository, r -> new ConcurrentHashMap<>());
        String stashed = null;
        if (changes > 0) {
            if (mode == StashMode.ASK || mode == StashMode.NEVER) {
                return new BranchSwitcher.Result(BranchSwitcher.Outcome.REFUSED, repository, from, branch, null, false, null,
                        changes + " uncommitted changes; park them (stashed, back when you return to " + from + ") or carry them to " + branch);
            }
            if (mode == StashMode.PARK) {
                parkedHere.put(from, changes);
                stashed = changes + " changes parked with " + from;
                changes = 0;
            } else {
                stashed = changes + " changes carried over";
            }
        }
        Integer restored = parkedHere.remove(branch);
        if (restored != null) {
            changes += restored;
        }
        knownBranches.computeIfAbsent(repository, r -> new ArrayList<>()).add(from);
        boolean develop = branch.equals("develop");
        statuses.put(repository, DemoWorkspace.withBranch(status, branch, develop ? 0 : 1, develop ? 0 : 2, changes));
        return new BranchSwitcher.Result(BranchSwitcher.Outcome.SWITCHED, repository, from, branch, stashed, restored != null, null,
                "switched to " + branch);
    }

    private RepositoryStatus status(String repository) {
        return statuses.computeIfAbsent(repository, n -> DemoWorkspace.status(n, Instant.now()));
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
