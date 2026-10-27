package com.anorys.pmm.tui.service;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.check.Finding;
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
    public RebasePlan planRebase(String repository) {
        pause(500);
        RepositoryStatus status = statuses.computeIfAbsent(repository, n -> DemoWorkspace.status(n, Instant.now()));
        List<String> refusals = new ArrayList<>();
        String branch = status.tree().branch();
        if (branch.equals("develop")) {
            refusals.add("develop is a protected branch (rebase.protectedBranches)");
        }
        if (!status.tree().clean()) {
            refusals.add(status.tree().changed() + " uncommitted changes; commit or stash them first");
        }
        List<String> conflicts = repository.equals("invoicing") ? List.of("invoicing-service/src/main/java/com/anorys/invoicing/PdfRenderer.java") : List.of();
        return new RebasePlan(repository, branch, status.baseRef(), status.ahead(), status.behind(), conflicts, refusals, null);
    }

    @Override
    public RebaseResult rebase(RebasePlan plan) {
        pause(1200);
        if (!plan.predictedConflicts().isEmpty()) {
            return new RebaseResult(RebaseOutcome.CONFLICTS, "conflicts; the rebase was aborted and " + plan.branch() + " is unchanged",
                    "refs/pmm/backup/" + plan.branch() + "/demo", null);
        }
        statuses.computeIfPresent(plan.repository(), (n, s) -> DemoWorkspace.rebased(s));
        return new RebaseResult(RebaseOutcome.REBASED, plan.ahead() + " commits of " + plan.branch() + " replayed onto " + plan.baseRef(),
                "refs/pmm/backup/" + plan.branch() + "/demo", null);
    }

    @Override
    public PushPolicy pushPolicy() {
        return PushPolicy.ASK;
    }

    @Override
    public String push(RebaseResult result) {
        pause(900);
        return null;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
