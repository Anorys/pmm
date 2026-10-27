package com.anorys.pmm.build;

import com.anorys.pmm.build.BuildResult.Status;
import com.anorys.pmm.build.Failure.Kind;
import com.anorys.pmm.build.MavenCommand.Executable;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.config.PmmConfig.RepositoryConfig;
import com.anorys.pmm.core.process.ProcessResult;
import com.anorys.pmm.core.process.ProcessRunner;
import com.anorys.pmm.core.process.ProcessSpec;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.git.GitClient;
import com.anorys.pmm.git.GitException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * Builds repositories with Maven, several at a time. Repositories depend on each other through
 * released versions downloaded from Nexus, not through sources, so they can all build in parallel;
 * they are started upstream first so that the results read in dependency order.
 */
public final class BuildRunner {

    /** Progress callbacks, called from the build threads. */
    public interface Listener {
        default void started(String repository) {
        }

        default void finished(BuildResult result) {
        }
    }

    /**
     * @param parallel repositories built at the same time
     * @param useCache skip repositories whose fingerprint matches their last successful build
     */
    public record Options(int parallel, boolean useCache, boolean offline) {
    }

    private final PmmConfig config;
    private final Workspace workspace;
    private final ProcessRunner runner;
    private final MavenCommand maven;
    private final Clock clock;

    public BuildRunner(PmmConfig config, Workspace workspace, ProcessRunner runner, MavenCommand maven, Clock clock) {
        this.config = config;
        this.workspace = workspace;
        this.runner = runner;
        this.maven = maven;
        this.clock = clock;
    }

    public static Path logDirectory(PmmConfig config) {
        return config.workspace().root().resolve(".pmm").resolve("logs");
    }

    public static Path cacheFile(PmmConfig config) {
        return config.workspace().root().resolve(".pmm").resolve("build-cache.json");
    }

    /** Results in the order of {@code repositories}. */
    public List<BuildResult> build(List<RepositoryConfig> repositories, Options options, Listener listener) {
        BuildCache cache = BuildCache.load(cacheFile(config));
        List<RepositoryConfig> ordered = upstreamFirst(repositories);
        List<Future<BuildResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, options.parallel()),
                Thread.ofPlatform().name("pmm-build-", 1).daemon().factory())) {
            for (RepositoryConfig repository : ordered) {
                futures.add(executor.submit(() -> {
                    listener.started(repository.name());
                    BuildResult result = buildOne(repository, options, cache);
                    listener.finished(result);
                    return result;
                }));
            }
            List<BuildResult> results = new ArrayList<>();
            for (Future<BuildResult> future : futures) {
                results.add(future.get());
            }
            if (options.useCache()) {
                cache.save();
            }
            return results.stream()
                    .sorted(Comparator.comparingInt(r -> indexOf(repositories, r.repository())))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the build cache " + cacheFile(config), e);
        }
    }

    private static int indexOf(List<RepositoryConfig> repositories, String name) {
        return IntStream.range(0, repositories.size())
                .filter(i -> repositories.get(i).name().equals(name)).findFirst().orElse(Integer.MAX_VALUE);
    }

    /** Layer by layer of the repository graph; repositories outside the graph keep their order, last. */
    private List<RepositoryConfig> upstreamFirst(List<RepositoryConfig> repositories) {
        List<Set<String>> layers = workspace.repositoryGraph().layers();
        Map<String, Integer> layerOf = new java.util.HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            for (String name : layers.get(i)) {
                layerOf.put(name, i);
            }
        }
        return repositories.stream()
                .sorted(Comparator.comparingInt(r -> layerOf.getOrDefault(r.name(), Integer.MAX_VALUE)))
                .toList();
    }

    BuildResult buildOne(RepositoryConfig repository, Options options, BuildCache cache) {
        String name = repository.name();
        if (!Files.isRegularFile(repository.path().resolve("pom.xml"))) {
            return new BuildResult(name, Status.SKIPPED, Duration.ZERO, null, null,
                    Files.isDirectory(repository.path()) ? "no pom.xml" : "not cloned");
        }
        Executable executable = maven.executable(repository);
        Path log = logDirectory(config).resolve(name + ".log");
        ProcessSpec spec = maven.spec(repository, executable, options.offline(), log);

        String fingerprint = options.useCache() && cacheable(name) ? fingerprint(repository, spec) : null;
        if (fingerprint != null) {
            var entry = cache.get(name);
            if (entry.isPresent() && entry.get().fingerprint().equals(fingerprint)) {
                return new BuildResult(name, Status.CACHED, Duration.ofMillis(entry.get().durationMillis()),
                        Files.exists(log) ? log : null, null, null);
            }
        }

        try {
            Files.createDirectories(log.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ProcessResult result = runner.run(spec);
        if (result.succeeded()) {
            if (fingerprint != null) {
                cache.put(name, new BuildCache.Entry(fingerprint, clock.instant(), result.duration().toMillis()));
            }
            return new BuildResult(name, Status.SUCCESS, result.duration(), log, null, null);
        }
        cache.remove(name);
        Failure failure = new FailureAnalyzer(workspace).analyze(result.lines(), result.timedOut());
        Status status = failure.kind() == Kind.MISSING_ARTIFACT ? Status.BLOCKED : Status.FAILED;
        return new BuildResult(name, status, result.duration(), log, failure, null);
    }

    /**
     * A repository that uses SNAPSHOTs from outside can get a different result with unchanged sources,
     * so it is always built.
     */
    private boolean cacheable(String repository) {
        return workspace.repository(repository)
                .map(r -> r.modules().stream().flatMap(m -> m.references().stream())
                        .map(DependencyRef::version)
                        .noneMatch(v -> v != null && v.endsWith("-SNAPSHOT")
                                && !r.modules().stream().anyMatch(m -> v.equals(m.version()))))
                .orElse(false);
    }

    private String fingerprint(RepositoryConfig repository, ProcessSpec spec) {
        GitClient git = new GitClient(runner, repository.path());
        try {
            return git.isRepository() ? Fingerprint.of(git, spec.arguments()) : null;
        } catch (GitException e) {
            return null;
        }
    }
}
