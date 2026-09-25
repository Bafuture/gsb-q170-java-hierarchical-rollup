package com.example.gsb.rollup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Incremental aggregation over a tree-shaped hierarchy.
 *
 * <p>Each node caches the aggregates (sum, max, count) of its whole subtree. A leaf
 * value change only recomputes the nodes on the path from that leaf to the root, so a
 * single update costs {@code O(depth * avgBranching)} recomputations instead of the
 * {@code O(N)} of a full bottom-up recomputation. Recomputation stops early as soon as
 * an ancestor's aggregates no longer change. Structural edits (reparent, subtree
 * add/remove) recompute only the ancestors of the old and new attachment points.
 */
public class TreeAggregator {

    private static final class Node {
        final String id;
        Node parent;
        final Set<Node> children = new LinkedHashSet<>();
        boolean hasValue;
        double value;
        // Cached aggregates over this node's own value plus all descendants.
        double sum;
        double max = Double.NEGATIVE_INFINITY;
        long count;

        Node(String id, Node parent) {
            this.id = id;
            this.parent = parent;
        }
    }

    private final Map<String, Node> nodes = new HashMap<>();
    private final List<Node> roots = new ArrayList<>();

    private UpdateStats lastStats = new UpdateStats("init", 0, 0, 0, 0);
    private long cumulativeAffected;
    private long cumulativeFullCost;
    private int lastPathAffected;

    // ------------------------------------------------------------------ structure

    /** Registers a root node (no parent). */
    public void addNode(String id) {
        addNode(id, null);
    }

    /** Registers a node under the given parent, or as a root when {@code parentId} is null. */
    public void addNode(String id, String parentId) {
        Objects.requireNonNull(id, "id");
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("node already exists: " + id);
        }
        Node parent = parentId == null ? null : requireNode(parentId);
        lastPathAffected = 0;
        Node node = new Node(id, parent);
        nodes.put(id, node);
        if (parent == null) {
            roots.add(node);
        } else {
            parent.children.add(node);
            recomputePath(parent);
        }
        recordStats("addNode(" + id + ")");
    }

    /** Sets (or replaces) the metric value of a node, typically a leaf. */
    public void setValue(String id, double value) {
        Node node = requireNode(id);
        node.value = value;
        node.hasValue = true;
        recomputePath(node);
        recordStats("setValue(" + id + ")");
    }

    /** Removes the metric value of a node; the node itself stays in the tree. */
    public void clearValue(String id) {
        Node node = requireNode(id);
        node.hasValue = false;
        node.value = 0.0;
        recomputePath(node);
        recordStats("clearValue(" + id + ")");
    }

    /**
     * Moves the subtree rooted at {@code id} under {@code newParentId}
     * (or to a root when {@code newParentId} is null). Ancestors of both the old
     * and the new attachment point are recomputed incrementally.
     */
    public void moveSubtree(String id, String newParentId) {
        Node node = requireNode(id);
        Node newParent = newParentId == null ? null : requireNode(newParentId);
        if (newParent != null && isInSubtree(node, newParent)) {
            throw new IllegalArgumentException(
                    "cannot move " + id + " under its own descendant " + newParentId);
        }
        Node oldParent = node.parent;
        if (oldParent == newParent) {
            recordStats("moveSubtree(" + id + ")", 0);
            return;
        }
        int affected = 0;
        detach(node);
        if (oldParent != null) {
            affected += recomputePath(oldParent);
        }
        attach(node, newParent);
        if (newParent != null) {
            affected += recomputePath(newParent);
        }
        recordStats("moveSubtree(" + id + ")", affected);
    }

    /** Removes the subtree rooted at {@code id}; ancestors' aggregates are updated. */
    public void removeSubtree(String id) {
        Node node = requireNode(id);
        Node parent = node.parent;
        detach(node);
        int affected = parent == null ? 0 : recomputePath(parent);
        // Drop every node of the detached subtree from the index.
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            Node current = stack.pop();
            nodes.remove(current.id);
            for (Node child : current.children) {
                stack.push(child);
            }
        }
        recordStats("removeSubtree(" + id + ")", affected);
    }

    // ------------------------------------------------------------------- queries

    /** Aggregated value of the subtree rooted at {@code id}. */
    public double aggregate(String id, AggregationType type) {
        Node node = requireNode(id);
        return switch (type) {
            case SUM -> node.sum;
            case MAX -> node.max;
            case COUNT -> (double) node.count;
        };
    }

    public double sum(String id) {
        return aggregate(id, AggregationType.SUM);
    }

    public double max(String id) {
        return aggregate(id, AggregationType.MAX);
    }

    public long count(String id) {
        return (long) aggregate(id, AggregationType.COUNT);
    }

    /** Number of registered nodes. */
    public int nodeCount() {
        return nodes.size();
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    /** Cost statistics of the most recent mutating operation. */
    public UpdateStats lastUpdateStats() {
        return lastStats;
    }

    /**
     * Recomputes every aggregate from scratch and returns the number of nodes touched.
     * Exposed for correctness cross-checks and cost comparisons against incremental updates.
     */
    public int fullRecompute() {
        int touched = 0;
        for (Node root : roots) {
            touched += fullRecompute(root);
        }
        return touched;
    }

    private int fullRecompute(Node node) {
        int touched = 1;
        for (Node child : node.children) {
            touched += fullRecompute(child);
        }
        recompute(node);
        return touched;
    }

    // ------------------------------------------------------------------ internals

    private Node requireNode(String id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("unknown node: " + id);
        }
        return node;
    }

    private static boolean isInSubtree(Node root, Node candidate) {
        for (Node n = candidate; n != null; n = n.parent) {
            if (n == root) {
                return true;
            }
        }
        return false;
    }

    private void detach(Node node) {
        if (node.parent == null) {
            roots.remove(node);
        } else {
            node.parent.children.remove(node);
        }
        node.parent = null;
    }

    private void attach(Node node, Node newParent) {
        node.parent = newParent;
        if (newParent == null) {
            roots.add(node);
        } else {
            newParent.children.add(node);
        }
    }

    /**
     * Recomputes cached aggregates from {@code node} up to the root, stopping early
     * once an ancestor no longer changes. Returns the number of nodes recomputed.
     */
    private int recomputePath(Node node) {
        int affected = 0;
        for (Node current = node; current != null; current = current.parent) {
            affected++;
            if (!recompute(current)) {
                break;
            }
        }
        lastPathAffected = affected;
        return affected;
    }

    /** Recomputes one node's cached aggregates from its own value and its children. */
    private boolean recompute(Node node) {
        double sum = node.hasValue ? node.value : 0.0;
        double max = node.hasValue ? node.value : Double.NEGATIVE_INFINITY;
        long count = node.hasValue ? 1 : 0;
        for (Node child : node.children) {
            sum += child.sum;
            if (child.max > max) {
                max = child.max;
            }
            count += child.count;
        }
        boolean changed = node.sum != sum || node.max != max || node.count != count;
        node.sum = sum;
        node.max = max;
        node.count = count;
        return changed;
    }

    private void recordStats(String operation) {
        recordStats(operation, lastPathAffected);
    }

    private void recordStats(String operation, int affected) {
        cumulativeAffected += affected;
        cumulativeFullCost += nodes.size();
        lastStats = new UpdateStats(
                operation, affected, nodes.size(), cumulativeAffected, cumulativeFullCost);
    }
}
