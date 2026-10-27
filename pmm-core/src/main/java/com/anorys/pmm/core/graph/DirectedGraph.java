package com.anorys.pmm.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small directed graph. In pmm an edge {@code a -> b} means "a depends on b", so the nodes
 * without outgoing edges are the ones to build first. Iteration order follows insertion order,
 * which keeps every output deterministic.
 */
public final class DirectedGraph<T> {

    private final Map<T, Set<T>> successors = new LinkedHashMap<>();
    private final Map<T, Set<T>> predecessors = new LinkedHashMap<>();

    public void addNode(T node) {
        successors.computeIfAbsent(node, k -> new LinkedHashSet<>());
        predecessors.computeIfAbsent(node, k -> new LinkedHashSet<>());
    }

    /** Adds {@code from -> to}; self-loops are ignored. */
    public void addEdge(T from, T to) {
        addNode(from);
        addNode(to);
        if (!from.equals(to)) {
            successors.get(from).add(to);
            predecessors.get(to).add(from);
        }
    }

    public Set<T> nodes() {
        return Collections.unmodifiableSet(successors.keySet());
    }

    /** What {@code node} depends on directly. */
    public Set<T> successors(T node) {
        return Collections.unmodifiableSet(successors.getOrDefault(node, Set.of()));
    }

    /** What depends on {@code node} directly. */
    public Set<T> predecessors(T node) {
        return Collections.unmodifiableSet(predecessors.getOrDefault(node, Set.of()));
    }

    public boolean hasEdge(T from, T to) {
        return successors.getOrDefault(from, Set.of()).contains(to);
    }

    /** Everything that depends on {@code node}, directly or transitively (excluding {@code node}). */
    public Set<T> allPredecessors(T node) {
        return reach(node, predecessors);
    }

    /** Everything {@code node} depends on, directly or transitively (excluding {@code node}). */
    public Set<T> allSuccessors(T node) {
        return reach(node, successors);
    }

    private Set<T> reach(T start, Map<T, Set<T>> edges) {
        Set<T> seen = new LinkedHashSet<>();
        Deque<T> todo = new ArrayDeque<>(edges.getOrDefault(start, Set.of()));
        while (!todo.isEmpty()) {
            T next = todo.poll();
            if (!next.equals(start) && seen.add(next)) {
                todo.addAll(edges.getOrDefault(next, Set.of()));
            }
        }
        return seen;
    }

    /** Groups of nodes that depend on each other (Tarjan's algorithm); only groups of two or more. */
    public List<Set<T>> cycles() {
        return stronglyConnectedComponents().stream().filter(c -> c.size() > 1).toList();
    }

    /**
     * Build layers: layer 0 holds the nodes that depend on nothing, layer n the nodes whose
     * dependencies are all in earlier layers. The members of a cycle share one layer.
     */
    public List<Set<T>> layers() {
        List<Set<T>> components = stronglyConnectedComponents();
        Map<T, Integer> componentOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (T node : components.get(i)) {
                componentOf.put(node, i);
            }
        }
        // Tarjan emits a component only after every component it depends on, so one pass is enough.
        int[] layerOf = new int[components.size()];
        int maxLayer = -1;
        for (int i = 0; i < components.size(); i++) {
            int layer = 0;
            for (T node : components.get(i)) {
                for (T dependency : successors.get(node)) {
                    int other = componentOf.get(dependency);
                    if (other != i) {
                        layer = Math.max(layer, layerOf[other] + 1);
                    }
                }
            }
            layerOf[i] = layer;
            maxLayer = Math.max(maxLayer, layer);
        }
        List<Set<T>> layers = new ArrayList<>();
        for (int l = 0; l <= maxLayer; l++) {
            layers.add(new LinkedHashSet<>());
        }
        for (T node : successors.keySet()) {
            layers.get(layerOf[componentOf.get(node)]).add(node);
        }
        return layers;
    }

    private List<Set<T>> stronglyConnectedComponents() {
        Tarjan tarjan = new Tarjan();
        for (T node : successors.keySet()) {
            if (!tarjan.index.containsKey(node)) {
                tarjan.visit(node);
            }
        }
        return tarjan.components;
    }

    /** Iterative Tarjan, so deep graphs cannot overflow the stack. */
    private final class Tarjan {
        final Map<T, Integer> index = new HashMap<>();
        final Map<T, Integer> lowLink = new HashMap<>();
        final Deque<T> stack = new ArrayDeque<>();
        final Set<T> onStack = new HashSet<>();
        final List<Set<T>> components = new ArrayList<>();
        int counter;

        void visit(T root) {
            Deque<Frame> frames = new ArrayDeque<>();
            frames.push(open(root));
            while (!frames.isEmpty()) {
                Frame frame = frames.peek();
                if (frame.remaining.hasNext()) {
                    T next = frame.remaining.next();
                    if (!index.containsKey(next)) {
                        frames.push(open(next));
                    } else if (onStack.contains(next)) {
                        lowLink.merge(frame.node, index.get(next), Math::min);
                    }
                    continue;
                }
                frames.pop();
                if (!frames.isEmpty()) {
                    lowLink.merge(frames.peek().node, lowLink.get(frame.node), Math::min);
                }
                if (lowLink.get(frame.node).equals(index.get(frame.node))) {
                    Set<T> component = new LinkedHashSet<>();
                    T member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(frame.node));
                    components.add(component);
                }
            }
        }

        private Frame open(T node) {
            index.put(node, counter);
            lowLink.put(node, counter);
            counter++;
            stack.push(node);
            onStack.add(node);
            return new Frame(node, successors.get(node).iterator());
        }

        private final class Frame {
            final T node;
            final Iterator<T> remaining;

            Frame(T node, Iterator<T> remaining) {
                this.node = node;
                this.remaining = remaining;
            }
        }
    }
}
