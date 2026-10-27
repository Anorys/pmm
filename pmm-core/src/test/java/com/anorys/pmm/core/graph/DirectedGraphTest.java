package com.anorys.pmm.core.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirectedGraphTest {

    /** reporting -> billing -> core -> auth, notifications -> auth */
    private static DirectedGraph<String> sample() {
        DirectedGraph<String> graph = new DirectedGraph<>();
        graph.addEdge("reporting", "billing");
        graph.addEdge("billing", "core");
        graph.addEdge("core", "auth");
        graph.addEdge("notifications", "auth");
        graph.addNode("standalone");
        return graph;
    }

    @Test
    void layersPutDependenciesFirst() {
        assertThat(sample().layers()).containsExactly(
                Set.of("auth", "standalone"),
                Set.of("core", "notifications"),
                Set.of("billing"),
                Set.of("reporting"));
    }

    @Test
    void transitiveNeighbours() {
        DirectedGraph<String> graph = sample();

        assertThat(graph.allPredecessors("core")).containsExactlyInAnyOrder("billing", "reporting");
        assertThat(graph.allSuccessors("reporting")).containsExactlyInAnyOrder("billing", "core", "auth");
        assertThat(graph.predecessors("auth")).containsExactlyInAnyOrder("core", "notifications");
        assertThat(graph.allPredecessors("standalone")).isEmpty();
    }

    @Test
    void noCyclesInADag() {
        assertThat(sample().cycles()).isEmpty();
    }

    @Test
    void detectsCyclesAndKeepsThemInOneLayer() {
        DirectedGraph<String> graph = sample();
        graph.addEdge("auth", "billing");   // auth -> billing -> core -> auth

        List<Set<String>> cycles = graph.cycles();

        assertThat(cycles).containsExactly(Set.of("auth", "billing", "core"));
        assertThat(graph.layers()).containsExactly(
                Set.of("billing", "core", "auth", "standalone"),
                Set.of("reporting", "notifications"));
    }

    @Test
    void ignoresSelfLoops() {
        DirectedGraph<String> graph = new DirectedGraph<>();
        graph.addEdge("a", "a");

        assertThat(graph.nodes()).containsExactly("a");
        assertThat(graph.cycles()).isEmpty();
        assertThat(graph.layers()).containsExactly(Set.of("a"));
    }

    @Test
    void handlesLongChainsWithoutStackOverflow() {
        DirectedGraph<Integer> graph = new DirectedGraph<>();
        for (int i = 0; i < 50_000; i++) {
            graph.addEdge(i, i + 1);
        }

        assertThat(graph.layers()).hasSize(50_001);
        assertThat(graph.cycles()).isEmpty();
    }
}
