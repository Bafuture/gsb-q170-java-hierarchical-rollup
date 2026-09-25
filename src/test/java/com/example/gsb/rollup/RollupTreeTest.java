package com.example.gsb.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 测试树结构（数字为叶子指标值）：
 * <pre>
 * root
 * ├── deptA
 * │   ├── teamA1
 * │   │   ├── leaf1 = 10
 * │   │   └── leaf2 = 20
 * │   └── teamA2
 * │       └── leaf3 = 30
 * └── deptB
 *     └── leaf4 = 40
 * </pre>
 */
class RollupTreeTest {

    private RollupTree tree;

    @BeforeEach
    void setUp() {
        tree = new RollupTree();
        tree.addNode("root", null);
        tree.addNode("deptA", "root");
        tree.addNode("deptB", "root");
        tree.addNode("teamA1", "deptA");
        tree.addNode("teamA2", "deptA");
        tree.addNode("leaf1", "teamA1");
        tree.addNode("leaf2", "teamA1");
        tree.addNode("leaf3", "teamA2");
        tree.addNode("leaf4", "deptB");
        tree.setValue("leaf1", 10);
        tree.setValue("leaf2", 20);
        tree.setValue("leaf3", 30);
        tree.setValue("leaf4", 40);
    }

    @Test
    @DisplayName("注册层级后各层聚合值（求和/最大值/计数）正确")
    void aggregatesAtEveryLevel() {
        assertThat(tree.aggregate("leaf1")).isEqualTo(new Aggregate(10, 10, 1));
        assertThat(tree.aggregate("teamA1")).isEqualTo(new Aggregate(30, 20, 2));
        assertThat(tree.aggregate("teamA2")).isEqualTo(new Aggregate(30, 30, 1));
        assertThat(tree.aggregate("deptA")).isEqualTo(new Aggregate(60, 30, 3));
        assertThat(tree.aggregate("deptB")).isEqualTo(new Aggregate(40, 40, 1));
        assertThat(tree.aggregate("root")).isEqualTo(new Aggregate(100, 40, 4));
        assertThat(tree.totalAggregate()).isEqualTo(new Aggregate(100, 40, 4));
    }

    @Test
    @DisplayName("叶子更新只影响到根路径上的祖先，且三种聚合同步刷新")
    void leafUpdatePropagatesAlongPathOnly() {
        tree.setValue("leaf1", 15);

        assertThat(tree.aggregate("leaf1").sum()).isEqualTo(15);
        assertThat(tree.aggregate("teamA1")).isEqualTo(new Aggregate(35, 20, 2));
        assertThat(tree.aggregate("deptA")).isEqualTo(new Aggregate(65, 30, 3));
        assertThat(tree.aggregate("root")).isEqualTo(new Aggregate(105, 40, 4));
        // 路径之外的兄弟子树不受影响
        assertThat(tree.aggregate("teamA2")).isEqualTo(new Aggregate(30, 30, 1));
        assertThat(tree.aggregate("deptB")).isEqualTo(new Aggregate(40, 40, 1));
        // leaf1 -> teamA1 -> deptA -> root，恰好 4 个节点被重算
        assertThat(tree.stats().lastUpdateAffectedNodes()).isEqualTo(4);
    }

    @Test
    @DisplayName("叶子值下调时最大值沿路径正确回落")
    void maxDecreasesWhenLeafValueDrops() {
        tree.setValue("leaf4", 5);
        assertThat(tree.aggregate("deptB").max()).isEqualTo(5);
        assertThat(tree.aggregate("root").max()).isEqualTo(30);

        tree.setValue("leaf3", -100);
        assertThat(tree.aggregate("deptA").max()).isEqualTo(20);
        assertThat(tree.aggregate("root").max()).isEqualTo(20);
        assertThat(tree.aggregate("root").sum()).isEqualTo(-65);
    }

    @Test
    @DisplayName("子树挂载到新父节点后，新旧两路祖先聚合值都正确")
    void moveSubtreeUpdatesBothAncestorChains() {
        // 把 teamA1 (sum=30, max=20, count=2) 从 deptA 挂到 deptB
        tree.move("teamA1", "deptB");

        assertThat(tree.aggregate("deptA")).isEqualTo(new Aggregate(30, 30, 1));
        assertThat(tree.aggregate("deptB")).isEqualTo(new Aggregate(70, 40, 3));
        assertThat(tree.aggregate("root")).isEqualTo(new Aggregate(100, 40, 4));
        // 被移动子树内部缓存保持不变
        assertThat(tree.aggregate("teamA1")).isEqualTo(new Aggregate(30, 20, 2));
        // 继续更新被移动子树里的叶子，应沿新路径传播
        tree.setValue("leaf1", 100);
        assertThat(tree.aggregate("deptB").sum()).isEqualTo(160);
        assertThat(tree.aggregate("deptA").sum()).isEqualTo(30);
        assertThat(tree.aggregate("root").sum()).isEqualTo(190);
    }

    @Test
    @DisplayName("禁止把子树挂到自身或子孙节点下")
    void moveRejectsCycles() {
        assertThatThrownBy(() -> tree.move("deptA", "teamA1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.move("deptA", "deptA"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.move("deptA", "leaf3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("删除子树后父链聚合值同步下调，节点被移除")
    void removeSubtreeDecreasesAncestors() {
        tree.removeSubtree("teamA1");

        assertThat(tree.aggregate("deptA")).isEqualTo(new Aggregate(30, 30, 1));
        assertThat(tree.aggregate("root")).isEqualTo(new Aggregate(70, 40, 2));
        assertThat(tree.contains("teamA1")).isFalse();
        assertThat(tree.contains("leaf1")).isFalse();
        assertThat(tree.contains("leaf2")).isFalse();
        assertThat(tree.nodeCount()).isEqualTo(6);
        assertThatThrownBy(() -> tree.aggregate("leaf1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("新增子树（新节点+叶子值）后祖先聚合值同步上调")
    void addSubtreeIncreasesAncestors() {
        tree.addNode("teamB1", "deptB");
        tree.addNode("leaf5", "teamB1");
        tree.setValue("leaf5", 50);

        assertThat(tree.aggregate("deptB")).isEqualTo(new Aggregate(90, 50, 2));
        assertThat(tree.aggregate("root")).isEqualTo(new Aggregate(150, 50, 5));
        assertThat(tree.nodeCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("统计信息：增量代价远小于全量重算代价")
    void statsCompareIncrementalVsFullRecompute() {
        // 构造一棵 3 叉、深 4 层的树：1 + 3 + 9 + 27 = 40 个节点
        RollupTree big = new RollupTree();
        big.addNode("n0", null);
        buildLevel(big, "n0", "", 1, 3);
        assertThat(big.nodeCount()).isEqualTo(40);

        // 更新最深处的叶子：只重算到根路径上的 4 个节点
        big.setValue("n3-0-0-0", 999);
        RollupStats stats = big.stats();
        assertThat(stats.totalNodes()).isEqualTo(40);
        assertThat(stats.fullRecomputeCost()).isEqualTo(40);
        assertThat(stats.lastUpdateAffectedNodes()).isEqualTo(4);
        assertThat(stats.savingsRatio()).isCloseTo(0.9, within(1e-9));

        // 增量结果与期望的全量结果一致
        assertThat(big.aggregate("n0").sum()).isEqualTo(999);
        assertThat(big.aggregate("n1-0").sum()).isEqualTo(999);
        assertThat(big.aggregate("n1-1").sum()).isEqualTo(0);
    }

    private static void buildLevel(RollupTree tree, String parentId, String parentPath,
                                   int depth, int maxDepth) {
        if (depth > maxDepth) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            String path = parentPath.isEmpty() ? String.valueOf(i) : parentPath + "-" + i;
            String id = "n" + depth + "-" + path;
            tree.addNode(id, parentId);
            if (depth == maxDepth) {
                tree.setValue(id, 0);
            } else {
                buildLevel(tree, id, path, depth + 1, maxDepth);
            }
        }
    }

    @Test
    @DisplayName("无指标值的节点聚合为空")
    void emptyAggregate() {
        RollupTree empty = new RollupTree();
        empty.addNode("r", null);
        Aggregate agg = empty.aggregate("r");
        assertThat(agg.isEmpty()).isTrue();
        assertThat(agg.sum()).isZero();
        assertThat(agg.count()).isZero();
        assertThat(agg.max()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    @DisplayName("重复注册节点或操作不存在的节点抛出异常")
    void invalidOperations() {
        assertThatThrownBy(() -> tree.addNode("leaf1", "root"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.addNode("x", "ghost"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.setValue("ghost", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.move("ghost", "root"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.removeSubtree("ghost"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
