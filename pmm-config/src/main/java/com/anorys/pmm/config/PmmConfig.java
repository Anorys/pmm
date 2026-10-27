package com.anorys.pmm.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The effective configuration: {@code pmm.yaml}, merged with {@code pmm.local.yaml}, with every
 * default applied and every path resolved to an absolute path.
 *
 * @param file the main configuration file
 */
public record PmmConfig(Path file,
                        WorkspaceConfig workspace,
                        List<RepositoryConfig> repositories,
                        MavenConfig maven,
                        NexusConfig nexus,
                        List<String> internalGroups,
                        Map<String, CheckSetting> checks,
                        RebaseConfig rebase,
                        BuildConfig build) {

    public PmmConfig {
        repositories = List.copyOf(repositories);
        internalGroups = List.copyOf(internalGroups);
        checks = Map.copyOf(checks);
    }

    /** Repositories with {@code enabled: true}, in declaration order. */
    public List<RepositoryConfig> monitoredRepositories() {
        return repositories.stream().filter(RepositoryConfig::enabled).toList();
    }

    public Optional<RepositoryConfig> repository(String name) {
        return repositories.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /** The setting of a known check; every known check has an entry after loading. */
    public CheckSetting check(String id) {
        CheckSetting setting = checks.get(id);
        if (setting == null) {
            throw new IllegalArgumentException("Unknown check: " + id);
        }
        return setting;
    }

    /** Base branch of a repository: its own override, else the workspace default. */
    public String baseBranch(RepositoryConfig repository) {
        return repository.baseBranch() != null ? repository.baseBranch() : workspace.baseBranch();
    }

    public record WorkspaceConfig(Path root, String baseBranch, String remote) {
    }

    /**
     * @param modules           module artifactIds to monitor, or {@code *} for the whole reactor
     * @param baseBranch        per-repository override, {@code null} to use the workspace default
     * @param releaseTagPattern git tag of a release, with a {@code {version}} placeholder; {@code null} to auto-detect
     */
    public record RepositoryConfig(String name, Path path, String url, String baseBranch, List<String> modules,
                                   boolean enabled, String releaseTagPattern) {

        public RepositoryConfig {
            modules = List.copyOf(modules);
        }

        public boolean monitorsAllModules() {
            return modules.contains("*");
        }
    }

    /**
     * @param executable  {@code auto} (mvnd if installed, else mvn), {@code mvn}, {@code mvnd}, or a path
     * @param compileGoal goal used to check compilation: {@code compile} or {@code test-compile}
     */
    public record MavenConfig(String executable, Path settings, List<String> profiles, boolean offline,
                              String threads, List<String> extraArgs, String compileGoal) {

        public MavenConfig {
            profiles = List.copyOf(profiles);
            extraArgs = List.copyOf(extraArgs);
        }
    }

    /**
     * @param repositoryId server/mirror id in settings.xml, used for URL and credentials
     * @param url          explicit repository URL, overriding the one from settings.xml
     */
    public record NexusConfig(String repositoryId, String url) {
    }

    public record RebaseConfig(boolean requireCleanTree, boolean autostash, boolean backupRefs, PushPolicy push,
                               List<String> protectedBranches) {

        public RebaseConfig {
            protectedBranches = List.copyOf(protectedBranches);
        }
    }

    public enum PushPolicy {
        NEVER, ASK, ALWAYS
    }

    /**
     * @param parallelRepos  repositories built at the same time; {@code 0} means automatic
     * @param memoryPerBuild maximum heap of each Maven build, for example {@code 1g}
     */
    public record BuildConfig(int parallelRepos, String memoryPerBuild, int timeoutMinutes, boolean cache) {

        /** The automatic value is min(4, cores / 2), at least 1. */
        public int effectiveParallelRepos() {
            if (parallelRepos > 0) {
                return parallelRepos;
            }
            return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        }
    }
}
