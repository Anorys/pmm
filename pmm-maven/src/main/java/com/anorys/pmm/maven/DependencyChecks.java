package com.anorys.pmm.maven;

import com.anorys.pmm.config.CheckSetting;
import com.anorys.pmm.config.KnownChecks;
import com.anorys.pmm.config.PmmConfig;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.DependencyRef.Kind;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.maven.Versions.Lag;
import com.anorys.pmm.maven.nexus.ArtifactMetadata;
import com.anorys.pmm.maven.nexus.MetadataSource;
import com.anorys.pmm.maven.nexus.NexusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Checks the versions the repositories use from each other, and from the outside, against the POMs
 * and the released versions in Nexus:
 * <ul>
 *   <li>{@code deps.internal-snapshot}: a repository uses a SNAPSHOT of another one;</li>
 *   <li>{@code deps.release-missing}: it uses an internal version that is not released (a blocker);</li>
 *   <li>{@code deps.internal-outdated}: a newer internal release exists, beyond {@code allowedLag};</li>
 *   <li>{@code deps.undeclared-internal}: an internal group's artifact that no monitored repository produces;</li>
 *   <li>{@code deps.unresolvable}: an external version that the Nexus repository does not have;</li>
 *   <li>{@code deps.convergence}: repositories declare different versions of the same external artifact.</li>
 * </ul>
 * Without a {@link MetadataSource} (offline, or Nexus not configured) only the checks that need no
 * Nexus run.
 */
public final class DependencyChecks {

    /** How an internal usage compares with what Nexus holds. */
    public enum State {
        OK, UPDATE_AVAILABLE, OUTDATED, NOT_RELEASED, SNAPSHOT, UNKNOWN;

        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /**
     * One version of an internal artifact used by one repository.
     *
     * @param producer the repository that produces it, {@code null} when no monitored repository does
     * @param modules  the consumer's modules that reference it
     * @param latest   the latest release in Nexus, {@code null} when unknown
     */
    public record InternalUsage(String consumer, List<String> modules, ArtifactKey artifact, String producer,
                                String version, String latest, State state) {

        public InternalUsage {
            modules = List.copyOf(modules);
        }
    }

    /**
     * @param usages   every cross-repository usage of an internal artifact, by consumer then artifact
     * @param releases per producing repository, its latest known release and the repositories using it
     */
    public record Report(List<InternalUsage> usages, List<Finding> findings, List<ProducerRelease> releases) {

        public Report {
            usages = List.copyOf(usages);
            findings = List.copyOf(findings);
            releases = List.copyOf(releases);
        }
    }

    /**
     * A repository that others depend on, with the releases it may have made, newest first: the latest
     * in Nexus and the versions its consumers use (unverified when Nexus was not queried).
     */
    public record ProducerRelease(String repository, List<String> versions, List<String> consumers) {

        public ProducerRelease {
            versions = List.copyOf(versions);
            consumers = List.copyOf(consumers);
        }
    }

    /** Requests sent to Nexus at the same time; enough to hide latency without hammering it. */
    static final int PARALLEL_REQUESTS = 8;

    private record UsageKey(String consumer, ArtifactKey artifact, String version) {
    }

    private final PmmConfig config;
    private final Workspace workspace;
    private MetadataSource source;
    private final List<Finding> findings = new ArrayList<>();
    private final Map<ArtifactKey, Optional<ArtifactMetadata>> metadata = new HashMap<>();

    /**
     * @param source where released versions are read; {@code null} to skip every check that needs Nexus
     */
    public DependencyChecks(PmmConfig config, Workspace workspace, MetadataSource source) {
        this.config = config;
        this.workspace = workspace;
        this.source = source;
    }

    public Report evaluate() {
        Map<UsageKey, Set<String>> internal = new LinkedHashMap<>();
        Map<UsageKey, Set<String>> external = new LinkedHashMap<>();
        Map<ArtifactKey, Map<String, Set<String>>> declaredExternal = new TreeMap<>();
        collect(internal, external, declaredExternal);

        Set<ArtifactKey> lookups = new LinkedHashSet<>();
        internal.keySet().forEach(k -> lookups.add(k.artifact()));
        if (enabled(KnownChecks.DEPS_UNRESOLVABLE)) {
            external.keySet().stream().filter(k -> !Versions.isSnapshot(k.version())).forEach(k -> lookups.add(k.artifact()));
        }
        prefetch(lookups);

        List<InternalUsage> usages = new ArrayList<>();
        internal.forEach((key, modules) -> usages.add(evaluateInternal(key, List.copyOf(modules))));
        external.forEach(this::evaluateExternal);
        evaluateConvergence(declaredExternal);
        return new Report(usages, findings, releases(usages));
    }

    /** Groups references by (consumer repository, artifact, version), skipping references inside a repository. */
    private void collect(Map<UsageKey, Set<String>> internal, Map<UsageKey, Set<String>> external,
                         Map<ArtifactKey, Map<String, Set<String>>> declaredExternal) {
        for (RepositoryInfo repository : workspace.repositories()) {
            for (ModuleInfo module : repository.modules()) {
                if (!module.monitored()) {
                    continue;
                }
                for (DependencyRef reference : module.references()) {
                    String version = reference.version();
                    if (version == null || reference.hasUnresolvedVersion() || Versions.isRange(version)) {
                        continue;    // reported by the scanner, or not checkable
                    }
                    Optional<ModuleInfo> producer = workspace.producer(reference.artifact());
                    if (producer.isPresent() && producer.get().repository().equals(repository.name())) {
                        continue;
                    }
                    UsageKey key = new UsageKey(repository.name(), reference.artifact(), version);
                    if (producer.isPresent() || isInternalGroup(reference.artifact())) {
                        internal.computeIfAbsent(key, k -> new TreeSet<>()).add(module.artifactId());
                    } else {
                        external.computeIfAbsent(key, k -> new TreeSet<>()).add(module.artifactId());
                        if (reference.kind() == Kind.DEPENDENCY || reference.kind() == Kind.IMPORT) {
                            declaredExternal.computeIfAbsent(reference.artifact(), k -> new TreeMap<>(Versions::compare))
                                    .computeIfAbsent(version, k -> new TreeSet<>()).add(repository.name());
                        }
                    }
                }
            }
        }
    }

    private boolean isInternalGroup(ArtifactKey artifact) {
        return config.internalGroups().stream().anyMatch(artifact::groupMatches);
    }

    /** Fetches all metadata up front, in parallel; the first failure stops every Nexus check. */
    private void prefetch(Set<ArtifactKey> artifacts) {
        if (source == null || artifacts.isEmpty()) {
            return;
        }
        Semaphore permits = new Semaphore(PARALLEL_REQUESTS);
        Map<ArtifactKey, Future<Optional<ArtifactMetadata>>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ArtifactKey artifact : artifacts) {
                futures.put(artifact, executor.submit(() -> {
                    permits.acquire();
                    try {
                        return source.fetch(artifact);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Map.Entry<ArtifactKey, Future<Optional<ArtifactMetadata>>> entry : futures.entrySet()) {
                metadata.put(entry.getKey(), entry.getValue().get());
            }
        } catch (ExecutionException e) {
            String message = e.getCause() instanceof NexusException ? e.getCause().getMessage() : e.getCause().toString();
            add(KnownChecks.DEPS_NEXUS_UNAVAILABLE, null, "cannot query Nexus: " + message
                    + "; release checks skipped", "check the network, nexus.url and the credentials");
            source = null;
            metadata.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            source = null;
            metadata.clear();
        }
    }

    /** Empty when Nexus is not queried; otherwise empty metadata when Nexus does not know the artifact. */
    private Optional<Optional<ArtifactMetadata>> metadata(ArtifactKey artifact) {
        return source == null ? Optional.empty() : Optional.ofNullable(metadata.get(artifact));
    }

    private InternalUsage evaluateInternal(UsageKey key, List<String> modules) {
        ModuleInfo producerModule = workspace.producer(key.artifact()).orElse(null);
        String producer = producerModule == null ? null : producerModule.repository();
        Optional<Optional<ArtifactMetadata>> known = metadata(key.artifact());
        String latest = known.flatMap(m -> m.flatMap(ArtifactMetadata::latestRelease)).orElse(null);
        String what = describe(key, modules, producer);

        if (producer == null) {
            add(KnownChecks.DEPS_UNDECLARED_INTERNAL, key.consumer(), what + ", but no monitored repository produces it",
                    "add the repository that produces it to pmm.yaml, or narrow internalGroups");
        }
        if (Versions.isSnapshot(key.version())) {
            add(KnownChecks.DEPS_INTERNAL_SNAPSHOT, key.consumer(), what + ": a SNAPSHOT",
                    "depend on a released version" + (latest == null ? "" : " (latest: " + latest + ")"));
            return new InternalUsage(key.consumer(), modules, key.artifact(), producer, key.version(), latest, State.SNAPSHOT);
        }
        if (known.isEmpty()) {
            return new InternalUsage(key.consumer(), modules, key.artifact(), producer, key.version(), null, State.UNKNOWN);
        }
        boolean released = known.get().map(m -> m.contains(key.version())).orElse(false);
        if (!released) {
            add(KnownChecks.DEPS_RELEASE_MISSING, key.consumer(), what + ", which is not released in Nexus",
                    releaseSuggestion(key, producerModule, latest));
            return new InternalUsage(key.consumer(), modules, key.artifact(), producer, key.version(), latest,
                    State.NOT_RELEASED);
        }
        Lag lag = latest == null ? Lag.NONE : Versions.lag(key.version(), latest);
        if (lag == Lag.NONE) {
            return new InternalUsage(key.consumer(), modules, key.artifact(), producer, key.version(), latest, State.OK);
        }
        CheckSetting outdated = config.check(KnownChecks.DEPS_INTERNAL_OUTDATED);
        Lag allowed = Lag.parse(outdated.option("allowedLag").orElse("patch"));
        if (lag.compareTo(allowed) <= 0) {
            return new InternalUsage(key.consumer(), modules, key.artifact(), producer, key.version(), latest,
                    State.UPDATE_AVAILABLE);
        }
        add(KnownChecks.DEPS_INTERNAL_OUTDATED, key.consumer(), what + "; latest release is " + latest
                + " (" + lag.name().toLowerCase(Locale.ROOT) + " update)", "upgrade to " + latest);
        return new InternalUsage(key.consumer(), modules, key.artifact(), producer, key.version(), latest, State.OUTDATED);
    }

    private static String describe(UsageKey key, List<String> modules, String producer) {
        return String.join(", ", modules) + (modules.size() == 1 ? " uses " : " use ")
                + key.artifact().artifactId() + " " + key.version()
                + (producer == null ? " (" + key.artifact().groupId() + ")" : " from " + producer);
    }

    /** "release platform-core 3.1.5 (it is at 3.1.5-SNAPSHOT)" names who must act to unblock. */
    private static String releaseSuggestion(UsageKey key, ModuleInfo producerModule, String latest) {
        String alternative = latest == null ? "" : ", or use the latest release " + latest;
        if (producerModule == null) {
            return "check the version" + alternative;
        }
        String current = producerModule.version();
        String state = (key.version() + "-SNAPSHOT").equals(current) ? " (it is at " + current + ")" : "";
        return "release " + producerModule.repository() + " " + key.version() + state + alternative;
    }

    private void evaluateExternal(UsageKey key, Set<String> modules) {
        if (Versions.isSnapshot(key.version())) {
            return;
        }
        Optional<ArtifactMetadata> known = metadata(key.artifact()).flatMap(m -> m);
        // An artifact Nexus does not know at all is usually served by another repository: not reported.
        if (known.isPresent() && !known.get().contains(key.version())) {
            String latest = known.get().latestRelease().orElse(null);
            add(KnownChecks.DEPS_UNRESOLVABLE, key.consumer(), String.join(", ", modules)
                            + (modules.size() == 1 ? " uses " : " use ") + key.artifact() + " " + key.version()
                            + ", which Nexus does not have",
                    "check the version" + (latest == null ? "" : " (latest release: " + latest + ")"));
        }
    }

    private void evaluateConvergence(Map<ArtifactKey, Map<String, Set<String>>> declared) {
        declared.forEach((artifact, byVersion) -> {
            if (byVersion.size() < 2) {
                return;
            }
            List<String> versions = new ArrayList<>(byVersion.keySet());
            String highest = versions.get(versions.size() - 1);
            String detail = versions.reversed().stream()
                    .map(v -> v + " in " + String.join(", ", byVersion.get(v)))
                    .collect(Collectors.joining("; "));
            add(KnownChecks.DEPS_CONVERGENCE, null, artifact + ": " + detail, "align on " + highest);
        });
    }

    /** For each producer: the releases known, newest first. */
    private List<ProducerRelease> releases(List<InternalUsage> usages) {
        Map<String, Set<String>> versions = new LinkedHashMap<>();
        Map<String, Set<String>> consumers = new LinkedHashMap<>();
        for (InternalUsage usage : usages) {
            if (usage.producer() == null) {
                continue;
            }
            consumers.computeIfAbsent(usage.producer(), k -> new TreeSet<>()).add(usage.consumer());
            boolean usedIsReleased = usage.state() != State.NOT_RELEASED && usage.state() != State.SNAPSHOT;
            for (String candidate : new String[]{usage.latest(), usedIsReleased ? usage.version() : null}) {
                if (candidate != null) {
                    versions.computeIfAbsent(usage.producer(), k -> new TreeSet<>((a, b) -> Versions.compare(b, a)))
                            .add(candidate);
                }
            }
        }
        return workspace.repositories().stream()
                .filter(r -> versions.containsKey(r.name()))
                .map(r -> new ProducerRelease(r.name(), List.copyOf(versions.get(r.name())),
                        List.copyOf(consumers.get(r.name()))))
                .toList();
    }

    private boolean enabled(String checkId) {
        return config.check(checkId).enabled();
    }

    private void add(String checkId, String repository, String message, String suggestion) {
        CheckSetting setting = config.check(checkId);
        if (setting.severity() != Severity.OFF) {
            findings.add(new Finding(checkId, setting.severity(), repository, message, suggestion));
        }
    }
}
