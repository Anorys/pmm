package com.anorys.pmm.tui.demo;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.build.Failure;
import com.anorys.pmm.build.Failure.MissingArtifact;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.git.GitClient.AheadBehind;
import com.anorys.pmm.git.GitClient.Operation;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.git.WorkingTreeStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.anorys.pmm.maven.DependencyChecks.State;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.RepoState;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A made-up workspace of nine repositories in every state the dashboard can show, for {@code pmm ui --demo}
 * and for the screenshots of the documentation.
 */
public final class DemoWorkspace {

    private static final String GROUP = "com.anorys";

    private record Repo(String name, String version, String branch, int ahead, int behind, int days, int changes,
                        List<String> uses) {
    }

    private static final List<Repo> REPOS = List.of(
            new Repo("platform-parent", "12.0.3", "develop", 0, 0, 0, 0, List.of()),
            new Repo("platform-core", "3.1.5-SNAPSHOT", "feature/PMM-142-cache", 2, 3, 4, 4, List.of("platform-parent")),
            new Repo("platform-commons", "2.8.0-SNAPSHOT", "develop", 0, 0, 0, 0, List.of("platform-parent")),
            new Repo("catalog", "5.2.1-SNAPSHOT", "feature/PMM-150-search", 5, 0, 0, 0, List.of("platform-core", "platform-commons")),
            new Repo("billing", "4.0.0-SNAPSHOT", "develop", 0, 12, 9, 0, List.of("platform-core", "platform-commons")),
            new Repo("invoicing", "1.9.2-SNAPSHOT", "feature/PMM-161-pdf", 3, 25, 12, 1, List.of("billing", "platform-core")),
            new Repo("shipping", "2.3.0-SNAPSHOT", "develop", 0, 0, 0, 0, List.of("catalog", "platform-commons")),
            new Repo("reporting", "7.0.0-SNAPSHOT", "feature/PMM-158-exports", 1, 2, 1, 0, List.of("billing", "catalog")),
            new Repo("customer-portal", "3.4.0-SNAPSHOT", "develop", 0, 0, 0, 0, List.of("billing", "catalog", "shipping")));

    private DemoWorkspace() {
    }

    public static Path root() {
        return Path.of("C:\\work\\projects\\anorys");
    }

    public static Workspace workspace() {
        List<RepositoryInfo> repositories = new ArrayList<>();
        for (Repo repo : REPOS) {
            List<DependencyRef> refs = new ArrayList<>();
            for (String used : repo.uses()) {
                Repo producer = REPOS.stream().filter(r -> r.name().equals(used)).findFirst().orElseThrow();
                String version = producer.version().replace("-SNAPSHOT", "");
                refs.add(new DependencyRef(new ArtifactKey(GROUP, used + "-api"), version,
                        used.equals("platform-parent") ? DependencyRef.Kind.PARENT : DependencyRef.Kind.DEPENDENCY, null));
            }
            Path path = root().resolve(repo.name());
            ModuleInfo module = new ModuleInfo(repo.name(), new ArtifactKey(GROUP, repo.name() + "-api"), repo.version(), "jar",
                    path.resolve("pom.xml"), refs, true);
            repositories.add(new RepositoryInfo(repo.name(), path, repo.version(), List.of(module)));
        }
        return new Workspace(repositories);
    }

    public static List<String> names() {
        return REPOS.stream().map(Repo::name).toList();
    }

    public static RepositoryStatus status(String name, Instant now) {
        Repo repo = repo(name);
        WorkingTreeStatus tree = new WorkingTreeStatus(repo.branch(), "origin/" + repo.branch(), 0, 0,
                repo.changes(), 0, 0, Operation.NONE);
        Instant since = repo.behind() > 0 ? now.minus(Duration.ofDays(repo.days())).minus(Duration.ofHours(3)) : null;
        return new RepositoryStatus(repo.name(), root().resolve(repo.name()), "origin/develop", tree,
                new AheadBehind(repo.ahead(), repo.behind()), since, null, null);
    }

    /** The same repository on {@code branch}, {@code ahead} and {@code behind} origin/develop. */
    public static RepositoryStatus withBranch(RepositoryStatus status, String branch, int ahead, int behind, int changes) {
        WorkingTreeStatus tree = new WorkingTreeStatus(branch, "origin/" + branch, 0, 0, changes, 0, 0, Operation.NONE);
        return new RepositoryStatus(status.name(), status.path(), status.baseRef(), tree, new AheadBehind(ahead, behind),
                behind > 0 ? status.behindSince() : null, null, null);
    }

    public static List<Finding> gitFindings(String name) {
        return gitFindings(repo(name));
    }

    public static int modules(String name) {
        return new int[]{1, 7, 4, 6, 9, 5, 3, 8, 6}[names().indexOf(name)];
    }

    private static Repo repo(String name) {
        return REPOS.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    /** The state with git, dependencies and a finished build, as after a few minutes of use. */
    public static DashboardState state(Instant now) {
        DashboardState state = new DashboardState(root());
        Workspace workspace = workspace();
        state.workspace(workspace);
        for (Repo repo : REPOS) {
            RepoState r = new RepoState(repo.name(), root().resolve(repo.name()));
            r.project(repo.version(), modules(repo.name()));
            r.git(status(repo.name(), now), gitFindings(repo));
            state.repositories().add(r);
        }
        deps(state);
        builds(state, now);
        state.lastFetch(now.minus(Duration.ofMinutes(2)));
        state.depsCheckedAt(now.minus(Duration.ofMinutes(2)));
        state.notes(List.of("Nexus: https://nexus.anorys.example/repository/releases"));
        state.tick(7);
        return state;
    }

    private static List<Finding> gitFindings(Repo repo) {
        List<Finding> findings = new ArrayList<>();
        if (repo.behind() >= 10) {
            findings.add(new Finding("git.behind", Severity.WARN, repo.name(),
                    repo.behind() + " commits behind origin/develop (oldest " + repo.days() + " days old)",
                    repo.ahead() == 0 ? "pmm sync --fast-forward" : "pmm rebase " + repo.name()));
        } else if (repo.behind() > 0) {
            findings.add(new Finding("git.behind", Severity.INFO, repo.name(),
                    repo.behind() + " commits behind origin/develop", "pmm rebase " + repo.name()));
        }
        if (repo.changes() > 0) {
            findings.add(new Finding("git.dirty", Severity.INFO, repo.name(), repo.changes() + " uncommitted changes", null));
        }
        return findings;
    }

    private static void deps(DashboardState state) {
        for (RepoState repo : state.repositories()) {
            repo.deps(usages(repo.name()), depFindings(repo.name()));
        }
    }

    public static List<Finding> depFindings(String name) {
        return switch (name) {
            case "invoicing" -> List.of(new Finding("deps.release-missing", Severity.ERROR, "invoicing",
                    "uses billing-api 4.0.0, which is not in Nexus", "release billing 4.0.0 (it is at 4.0.0-SNAPSHOT)"));
            case "reporting" -> List.of(new Finding("deps.internal-outdated", Severity.WARN, "reporting",
                    "uses catalog-api 5.0.2, latest release is 5.2.0", "bump catalog-api to 5.2.0"));
            case "platform-core" -> List.of(new Finding("deps.unreleased-upstream", Severity.INFO, "platform-core",
                    "14 commits on develop since release 3.1.4, used by 4 repositories", "release platform-core"));
            default -> List.of();
        };
    }

    public static List<InternalUsage> usages(String name) {
        List<InternalUsage> usages = new ArrayList<>();
        for (String used : repo(name).uses()) {
            String version = repo(used).version().replace("-SNAPSHOT", "");
            State s = State.OK;
            String latest = version;
            if (name.equals("invoicing") && used.equals("billing")) {
                s = State.NOT_RELEASED;
                latest = "3.9.4";
            } else if (name.equals("reporting") && used.equals("catalog")) {
                s = State.OUTDATED;
                version = "5.0.2";
                latest = "5.2.0";
            } else if (name.equals("shipping") && used.equals("catalog")) {
                s = State.UPDATE_AVAILABLE;
                version = "5.1.9";
                latest = "5.2.0";
            }
            usages.add(new InternalUsage(name, List.of(name + "-api"), new ArtifactKey(GROUP, used + "-api"), used, version, latest, s));
        }
        return usages;
    }

    private static void builds(DashboardState state, Instant now) {
        for (RepoState repo : state.repositories()) {
            BuildResult result = buildResult(repo.name());
            repo.queued();
            repo.started(now.minus(result.duration()));
            repo.finished(result, buildFindings(result));
            repo.expectedBuild(result.duration());
        }
        state.blockers(blockers());
    }

    public static List<Blocker> blockers() {
        return List.of(new Blocker("billing", Set.of("invoicing"), Set.of("billing-api 4.0.0")));
    }

    public static BuildResult buildResult(String name) {
        return switch (name) {
            case "platform-parent" -> new BuildResult(name, BuildResult.Status.CACHED, Duration.ofSeconds(8), null, null, null);
            case "platform-core" -> new BuildResult(name, BuildResult.Status.SUCCESS, Duration.ofSeconds(72), null, null, null);
            case "platform-commons" -> new BuildResult(name, BuildResult.Status.CACHED, Duration.ofSeconds(41), null, null, null);
            case "catalog" -> new BuildResult(name, BuildResult.Status.SUCCESS, Duration.ofSeconds(154), null, null, null);
            case "billing" -> new BuildResult(name, BuildResult.Status.SUCCESS, Duration.ofSeconds(201), null, null, null);
            case "invoicing" -> new BuildResult(name, BuildResult.Status.BLOCKED, Duration.ofSeconds(23), null,
                    new Failure(Failure.Kind.MISSING_ARTIFACT, "invoicing-service",
                            List.of(new MissingArtifact(new ArtifactKey(GROUP, "billing-api"), "4.0.0", "billing")), null), null);
            case "shipping" -> new BuildResult(name, BuildResult.Status.CACHED, Duration.ofSeconds(66), null, null, null);
            case "reporting" -> new BuildResult(name, BuildResult.Status.FAILED, Duration.ofSeconds(98), null,
                    new Failure(Failure.Kind.COMPILATION, "reporting-exports", List.of(),
                            "ExportService.java:[84,23] cannot find symbol: method toCsv(Report)"), null);
            default -> new BuildResult(name, BuildResult.Status.SUCCESS, Duration.ofSeconds(187), null, null, null);
        };
    }

    public static List<Finding> buildFindings(BuildResult result) {
        String name = result.repository();
        return switch (result.status()) {
            case BLOCKED -> List.of(new Finding("build.blocked", Severity.ERROR, name,
                    "build blocked: billing-api 4.0.0 is not in Nexus", "release billing 4.0.0"));
            case FAILED -> List.of(new Finding("build.failed", Severity.ERROR, name,
                    "does not compile: " + result.failure().detail(), "see .pmm/logs/" + name + ".log"));
            default -> List.of();
        };
    }

    /** The same workspace while a build runs: some done, some running, some queued. */
    public static DashboardState building(Instant now) {
        DashboardState state = state(now);
        String[] running = {"billing", "catalog", "customer-portal"};
        String[] queued = {"invoicing", "reporting", "shipping"};
        int[][] progress = {{6, 9}, {2, 6}, {4, 6}};
        long[] elapsed = {118, 41, 150};
        for (int i = 0; i < running.length; i++) {
            RepoState repo = state.repository(running[i]).orElseThrow();
            Duration expected = repo.expectedBuild();
            repo.queued();
            repo.expectedBuild(expected);
            repo.started(now.minus(Duration.ofSeconds(elapsed[i])));
            repo.progress(running[i] + "-" + new String[]{"service", "search", "web"}[i], progress[i][0], progress[i][1], List.of());
        }
        for (String name : queued) {
            RepoState repo = state.repository(name).orElseThrow();
            Duration expected = repo.expectedBuild();
            repo.queued();
            repo.expectedBuild(expected);
        }
        state.repository("billing").orElseThrow().progress(null, 0, 0, List.of(
                "[INFO] --- compiler:3.13.0:compile (default-compile) @ billing-model ---",
                "[INFO] Recompiling the module because of changed source code.",
                "[INFO] Compiling 214 source files with javac [debug release 21] to target/classes",
                "[WARNING] InvoiceMapper.java:[57,18] unchecked conversion",
                "[INFO] --- resources:3.3.1:testResources (default-testResources) @ billing-model ---",
                "[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ billing-model ---",
                "[INFO] Compiling 96 source files with javac [debug release 21] to target/test-classes",
                "[INFO] ",
                "[INFO] ----------------------< com.anorys:billing-service >----------------------",
                "[INFO] Building billing-service 4.0.0-SNAPSHOT                             [6/9]",
                "[INFO]   from billing-service/pom.xml",
                "[INFO] --------------------------------[ jar ]---------------------------------",
                "[INFO] --- resources:3.3.1:resources (default-resources) @ billing-service ---",
                "[INFO] --- compiler:3.13.0:compile (default-compile) @ billing-service ---"));
        state.blockers(List.of());
        state.activity().add("building 3 of 9");
        state.tick(23);
        return state;
    }
}
