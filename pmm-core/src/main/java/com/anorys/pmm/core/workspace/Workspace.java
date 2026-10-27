package com.anorys.pmm.core.workspace;

import com.anorys.pmm.core.graph.DirectedGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every scanned repository and module, and the dependencies between them. Only references to
 * artifacts produced inside the workspace create edges; third-party dependencies are ignored here.
 */
public final class Workspace {

    /** One module of {@code consumer} referencing an artifact produced by another repository. */
    public record Usage(ModuleInfo consumer, DependencyRef reference, ModuleInfo producer) {
    }

    /** All the ways repository {@code consumer} depends on repository {@code producer}. */
    public record RepositoryDependency(String consumer, String producer, List<Usage> usages) {

        public RepositoryDependency {
            usages = List.copyOf(usages);
        }
    }

    private final List<RepositoryInfo> repositories;
    private final Map<ArtifactKey, ModuleInfo> producers = new HashMap<>();
    private final List<RepositoryDependency> repositoryDependencies;

    public Workspace(List<RepositoryInfo> repositories) {
        this.repositories = List.copyOf(repositories);
        for (RepositoryInfo repository : repositories) {
            for (ModuleInfo module : repository.modules()) {
                producers.putIfAbsent(module.artifact(), module);
            }
        }
        this.repositoryDependencies = computeRepositoryDependencies();
    }

    public List<RepositoryInfo> repositories() {
        return repositories;
    }

    public Optional<RepositoryInfo> repository(String name) {
        return repositories.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    public List<ModuleInfo> modules() {
        return repositories.stream().flatMap(r -> r.modules().stream()).toList();
    }

    /** The module that produces {@code artifact}, if it is part of the workspace. */
    public Optional<ModuleInfo> producer(ArtifactKey artifact) {
        return Optional.ofNullable(producers.get(artifact));
    }

    /** Cross-repository dependencies, in repository declaration order. */
    public List<RepositoryDependency> repositoryDependencies() {
        return repositoryDependencies;
    }

    /** Edge {@code a -> b}: repository a uses an artifact produced by repository b. */
    public DirectedGraph<String> repositoryGraph() {
        DirectedGraph<String> graph = new DirectedGraph<>();
        repositories.forEach(r -> graph.addNode(r.name()));
        repositoryDependencies.forEach(d -> graph.addEdge(d.consumer(), d.producer()));
        return graph;
    }

    /** Edge {@code a -> b}: module a references module b, inside or across repositories. */
    public DirectedGraph<ArtifactKey> moduleGraph() {
        DirectedGraph<ArtifactKey> graph = new DirectedGraph<>();
        for (ModuleInfo module : modules()) {
            graph.addNode(module.artifact());
            for (DependencyRef reference : module.references()) {
                if (producers.containsKey(reference.artifact())) {
                    graph.addEdge(module.artifact(), reference.artifact());
                }
            }
        }
        return graph;
    }

    private List<RepositoryDependency> computeRepositoryDependencies() {
        Map<String, Map<String, List<Usage>>> byConsumer = new LinkedHashMap<>();
        for (RepositoryInfo repository : repositories) {
            Map<String, List<Usage>> byProducer = new LinkedHashMap<>();
            for (ModuleInfo module : repository.modules()) {
                for (DependencyRef reference : module.references()) {
                    ModuleInfo producer = producers.get(reference.artifact());
                    if (producer != null && !producer.repository().equals(repository.name())) {
                        byProducer.computeIfAbsent(producer.repository(), k -> new ArrayList<>())
                                .add(new Usage(module, reference, producer));
                    }
                }
            }
            byConsumer.put(repository.name(), byProducer);
        }
        List<RepositoryDependency> result = new ArrayList<>();
        byConsumer.forEach((consumer, byProducer) -> byProducer.forEach(
                (producer, usages) -> result.add(new RepositoryDependency(consumer, producer, usages))));
        return List.copyOf(result);
    }
}
