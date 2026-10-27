package com.anorys.pmm.cli.render;

import com.anorys.pmm.core.graph.DirectedGraph;
import com.anorys.pmm.core.workspace.ArtifactKey;
import com.anorys.pmm.core.workspace.DependencyRef;
import com.anorys.pmm.core.workspace.ModuleInfo;
import com.anorys.pmm.core.workspace.RepositoryInfo;
import com.anorys.pmm.core.workspace.Workspace;
import com.anorys.pmm.core.workspace.Workspace.RepositoryDependency;
import com.anorys.pmm.core.workspace.Workspace.Usage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders the workspace dependency graph as text, Mermaid or Graphviz DOT. */
public final class GraphRenderer {

    private GraphRenderer() {
    }

    /** Build order, dependencies with the versions used, and cycles. */
    public static String text(Workspace workspace) {
        DirectedGraph<String> graph = workspace.repositoryGraph();
        StringBuilder out = new StringBuilder();
        out.append(String.format("Workspace: %d repositories, %d modules%n%n",
                workspace.repositories().size(), workspace.modules().size()));

        out.append(String.format("Build order (repositories on the same line do not depend on each other)%n"));
        int width = workspace.repositories().stream().mapToInt(r -> r.name().length()).max().orElse(10);
        List<Set<String>> layers = graph.layers();
        for (int i = 0; i < layers.size(); i++) {
            String prefix = String.format("%3d. ", i + 1);
            for (String name : layers.get(i)) {
                RepositoryInfo repo = workspace.repository(name).orElseThrow();
                out.append(String.format("%s%-" + width + "s  %s%n", prefix, name, Objects.toString(repo.version(), "?")));
                prefix = "     ";
            }
        }

        out.append(String.format("%nDependencies between repositories%n"));
        if (workspace.repositoryDependencies().isEmpty()) {
            out.append(String.format("  none%n"));
        }
        for (RepositoryDependency dependency : workspace.repositoryDependencies()) {
            out.append(String.format("  %s -> %s%n", dependency.consumer(), dependency.producer()));
            for (Usage usage : dependency.usages()) {
                out.append(String.format("      %s%n", describe(usage)));
            }
        }

        List<Set<String>> cycles = graph.cycles();
        out.append(String.format("%nCycles: %s%n", cycles.isEmpty() ? "none" : cycles.size()));
        for (Set<String> cycle : cycles) {
            out.append(String.format("  %s%n", String.join(" <-> ", cycle)));
        }
        return out.toString();
    }

    /** What {@code repository} depends on and what depends on it, directly and transitively. */
    public static String focus(Workspace workspace, String repository) {
        DirectedGraph<String> graph = workspace.repositoryGraph();
        StringBuilder out = new StringBuilder();
        RepositoryInfo repo = workspace.repository(repository).orElseThrow();
        out.append(String.format("%s %s (%d modules)%n", repo.name(), Objects.toString(repo.version(), "?"), repo.modules().size()));
        out.append(String.format("%nDepends on:%n"));
        appendNeighbours(out, graph.successors(repository), graph.allSuccessors(repository));
        for (RepositoryDependency dependency : workspace.repositoryDependencies()) {
            if (dependency.consumer().equals(repository)) {
                dependency.usages().forEach(u -> out.append(String.format("      %s%n", describe(u))));
            }
        }
        out.append(String.format("%nNeeded by:%n"));
        appendNeighbours(out, graph.predecessors(repository), graph.allPredecessors(repository));
        return out.toString();
    }

    private static void appendNeighbours(StringBuilder out, Set<String> direct, Set<String> all) {
        if (all.isEmpty()) {
            out.append(String.format("  nothing in the workspace%n"));
            return;
        }
        out.append(String.format("  directly:   %s%n", String.join(", ", direct)));
        List<String> indirect = all.stream().filter(n -> !direct.contains(n)).toList();
        if (!indirect.isEmpty()) {
            out.append(String.format("  indirectly: %s%n", String.join(", ", indirect)));
        }
    }

    /** "billing-service uses core-api 3.1.4 (workspace: 3.2.0-SNAPSHOT)" */
    static String describe(Usage usage) {
        DependencyRef ref = usage.reference();
        StringBuilder line = new StringBuilder(usage.consumer().artifactId()).append(' ');
        line.append(switch (ref.kind()) {
            case PARENT -> "has parent";
            case IMPORT -> "imports BOM";
            case PLUGIN -> "uses plugin";
            case DEPENDENCY -> "compile".equals(ref.scope()) ? "uses" : "uses (" + ref.scope() + ")";
        });
        line.append(' ').append(ref.artifact().artifactId()).append(' ').append(ref.version() == null ? "(version not set)" : ref.version());
        String produced = usage.producer().version();
        if (ref.version() != null && produced != null && !produced.equals(ref.version())) {
            line.append(" (workspace: ").append(produced).append(')');
        }
        return line.toString();
    }

    public static String mermaid(Workspace workspace, boolean modules) {
        StringBuilder out = new StringBuilder("graph LR\n");
        if (!modules) {
            for (RepositoryInfo repo : workspace.repositories()) {
                out.append(String.format("  %s[\"%s<br/>%s\"]%n", id(repo.name()), repo.name(), Objects.toString(repo.version(), "?")));
            }
            for (RepositoryDependency dependency : workspace.repositoryDependencies()) {
                out.append(String.format("  %s --> %s%n", id(dependency.consumer()), id(dependency.producer())));
            }
            appendMermaidCycles(out, workspace.repositoryGraph().cycles().stream()
                    .flatMap(Set::stream).map(GraphRenderer::id).toList());
            return out.toString();
        }
        for (RepositoryInfo repo : workspace.repositories()) {
            out.append(String.format("  subgraph %s[\"%s\"]%n", id("repo " + repo.name()), repo.name()));
            for (ModuleInfo module : repo.modules()) {
                out.append(String.format("    %s[\"%s\"]%n", id(module.artifact().toString()), module.artifactId()));
            }
            out.append("  end\n");
        }
        DirectedGraph<ArtifactKey> graph = workspace.moduleGraph();
        for (ArtifactKey from : graph.nodes()) {
            for (ArtifactKey to : graph.successors(from)) {
                out.append(String.format("  %s --> %s%n", id(from.toString()), id(to.toString())));
            }
        }
        appendMermaidCycles(out, graph.cycles().stream().flatMap(Set::stream)
                .map(k -> id(k.toString())).toList());
        return out.toString();
    }

    private static void appendMermaidCycles(StringBuilder out, List<String> ids) {
        if (!ids.isEmpty()) {
            out.append("  classDef cycle stroke:#b3261e,stroke-width:2px\n");
            out.append("  class ").append(String.join(",", ids)).append(" cycle\n");
        }
    }

    public static String dot(Workspace workspace, boolean modules) {
        StringBuilder out = new StringBuilder("digraph workspace {\n  rankdir=LR;\n  node [shape=box, fontname=\"Helvetica\"];\n");
        if (!modules) {
            Set<String> inCycle = workspace.repositoryGraph().cycles().stream().flatMap(Set::stream).collect(Collectors.toSet());
            for (RepositoryInfo repo : workspace.repositories()) {
                out.append(String.format("  %s [label=\"%s\\n%s\"%s];%n", quote(repo.name()), repo.name(),
                        Objects.toString(repo.version(), "?"), inCycle.contains(repo.name()) ? ", color=red" : ""));
            }
            for (RepositoryDependency dependency : workspace.repositoryDependencies()) {
                out.append(String.format("  %s -> %s [label=\"%d\"];%n", quote(dependency.consumer()),
                        quote(dependency.producer()), dependency.usages().size()));
            }
            return out.append("}\n").toString();
        }
        int cluster = 0;
        for (RepositoryInfo repo : workspace.repositories()) {
            out.append(String.format("  subgraph cluster_%d {%n    label=%s;%n", cluster++, quote(repo.name())));
            for (ModuleInfo module : repo.modules()) {
                out.append(String.format("    %s [label=%s];%n", quote(module.artifact().toString()), quote(module.artifactId())));
            }
            out.append("  }\n");
        }
        DirectedGraph<ArtifactKey> graph = workspace.moduleGraph();
        for (ArtifactKey from : graph.nodes()) {
            for (ArtifactKey to : graph.successors(from)) {
                out.append(String.format("  %s -> %s;%n", quote(from.toString()), quote(to.toString())));
            }
        }
        return out.append("}\n").toString();
    }

    /** Mermaid node ids may only contain letters, digits and underscores. */
    static String id(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
