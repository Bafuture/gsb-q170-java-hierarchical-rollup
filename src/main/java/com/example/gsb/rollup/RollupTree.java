package com.example.gsb.rollup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 树形层级指标的增量聚合组件。
 *
 * <p>每个节点缓存其子树的聚合值（sum / max / count）。叶子指标变化、子树挂载、
 * 子树增删时，只沿“变更点到根”的路径自底向上重算祖先，避免全量重算。</p>
 *
 * <h3>更新代价</h3>
 * <ul>
 *   <li>叶子值更新：O(h · k)，h 为叶子到根的路径长度，k 为路径上节点的最大子节点数。
 *       每个祖先的聚合值由其直接子节点的缓存值重算，整棵树其余部分不被访问。</li>
 *   <li>子树挂载 / 删除：O(h · k + s)，h 为新旧父节点到根的路径长度，s 为被删除
 *       子树的大小（仅删除时需要摘除登记）。子树内部缓存原样保留，无需重算。</li>
 *   <li>对比全量重算：O(N)，N 为全树节点数。{@link #stats()} 给出最近一次变更的
 *       实际增量代价与全量代价的对比。</li>
 * </ul>
 *
 * <p>约定：节点可以携带自身的指标值（通常为叶子）；节点的聚合值 = 自身值（若有）
 * 与所有子节点聚合值的合并。count 统计子树内携带值的节点个数。</p>
 */
public final class RollupTree {

    private static final class Node {
        final String id;
        Node parent;
        final Map<String, Node> children = new LinkedHashMap<>();
        boolean hasValue;
        double value;
        double sum;
        double max = Double.NEGATIVE_INFINITY;
        long count;

        Node(String id) {
            this.id = id;
        }
    }

    private final Map<String, Node> nodes = new HashMap<>();
    private final List<Node> roots = new ArrayList<>();
    private long lastAffected;

    /** 注册一个节点。{@code parentId} 为 {@code null} 时作为根节点。 */
    public void addNode(String id, String parentId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("节点 id 不能为空");
        }
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("节点已存在: " + id);
        }
        Node parent = parentId == null ? null : require(parentId);
        Node node = new Node(id);
        node.parent = parent;
        nodes.put(id, node);
        if (parent == null) {
            roots.add(node);
        } else {
            parent.children.put(id, node);
        }
        lastAffected = 0;
        propagateFrom(node);
    }

    /** 设置（或更新）节点的指标值，只沿到根的路径重算祖先。 */
    public void setValue(String id, double value) {
        Node node = require(id);
        lastAffected = 0;
        node.hasValue = true;
        node.value = value;
        propagateFrom(node);
    }

    /** 移除节点自身携带的指标值（不删除节点及其子树）。 */
    public void clearValue(String id) {
        Node node = require(id);
        lastAffected = 0;
        node.hasValue = false;
        node.value = 0.0;
        propagateFrom(node);
    }

    /** 查询任意节点的子树聚合值。 */
    public Aggregate aggregate(String id) {
        Node node = require(id);
        return new Aggregate(node.sum, node.max, node.count);
    }

    /**
     * 把 {@code id} 为根的子树整体挂到 {@code newParentId} 下
     * （{@code null} 表示提升为根）。旧父链与新父链上的祖先都会被重算。
     */
    public void move(String id, String newParentId) {
        Node node = require(id);
        Node newParent = newParentId == null ? null : require(newParentId);
        for (Node cursor = newParent; cursor != null; cursor = cursor.parent) {
            if (cursor == node) {
                throw new IllegalArgumentException("不能把子树挂到自身或其子孙节点下: " + id);
            }
        }
        Node oldParent = node.parent;
        if (oldParent == newParent) {
            return;
        }
        lastAffected = 0;
        detach(node);
        node.parent = newParent;
        if (newParent == null) {
            roots.add(node);
        } else {
            newParent.children.put(id, node);
        }
        if (oldParent != null) {
            propagateFrom(oldParent);
        }
        if (newParent != null) {
            propagateFrom(newParent);
        }
    }

    /** 删除以 {@code id} 为根的整棵子树，父链祖先的聚合值同步下调。 */
    public void removeSubtree(String id) {
        Node node = require(id);
        lastAffected = 0;
        Node oldParent = node.parent;
        detach(node);
        node.parent = null;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            Node current = stack.pop();
            nodes.remove(current.id);
            for (Node child : current.children.values()) {
                stack.push(child);
            }
        }
        if (oldParent != null) {
            propagateFrom(oldParent);
        }
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    public long nodeCount() {
        return nodes.size();
    }

    /** 全部根节点（森林）聚合后的整树结果。 */
    public Aggregate totalAggregate() {
        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        long count = 0;
        for (Node root : roots) {
            sum += root.sum;
            max = Math.max(max, root.max);
            count += root.count;
        }
        return new Aggregate(sum, max, count);
    }

    /** 统计：节点总数、最近一次变更的增量代价、全量重算代价及节省比例。 */
    public RollupStats stats() {
        return new RollupStats(nodes.size(), lastAffected, nodes.size());
    }

    private void detach(Node node) {
        if (node.parent == null) {
            roots.remove(node);
        } else {
            node.parent.children.remove(node.id);
        }
    }

    private void propagateFrom(Node node) {
        Node cursor = node;
        while (cursor != null) {
            recompute(cursor);
            lastAffected++;
            cursor = cursor.parent;
        }
    }

    private void recompute(Node node) {
        double sum = node.hasValue ? node.value : 0.0;
        double max = node.hasValue ? node.value : Double.NEGATIVE_INFINITY;
        long count = node.hasValue ? 1 : 0;
        for (Node child : node.children.values()) {
            sum += child.sum;
            max = Math.max(max, child.max);
            count += child.count;
        }
        node.sum = sum;
        node.max = max;
        node.count = count;
    }

    private Node require(String id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在: " + id);
        }
        return node;
    }
}
