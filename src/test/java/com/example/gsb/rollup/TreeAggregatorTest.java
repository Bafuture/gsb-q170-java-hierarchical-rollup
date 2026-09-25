package com.example.gsb.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TreeAggregatorTest {

    private TreeAggregator tree;

    /**
     * Builds this hierarchy (values on leaves):
     * <pre>
     *                 root
     *          ┌───────┴────────┐
     *          eu              asia
     *      ┌───┴───┐        ┌───┴───┐
     *     de(10)  fr(20)   cn(30)  jp(40)
     * </pre>
     */
    @BeforeEach
    void setUp() {
        tree = new TreeAggregator();
        tree.addNode("root");
        tree.addNode("eu", "root");
        tree.addNode("asia", "root");
        tree.addNode("de", "eu");
        tree.addNode("fr", "eu");
        tree.addNode("cn", "asia");
        tree.addNode("jp", "asia");
        tree.setValue("de", 10);
        tree.setValue("fr", 20);
        tree.setValue("cn", 30);
        tree.setValue("jp", 40);
    }

    // ------------------------------------------------------- multiple aggregations

    @Test
    void aggregatesSumMaxCountAtEveryLevel() {
        assertThat(tree.sum("root")).isCloseTo(100.0, within(1e-9));
        assertThat(tree.sum("eu")).isCloseTo(30.0, within(1e-9));
        assertThat(tree.sum("asia")).isCloseTo(70.0, within(1e-9));
        assertThat(tree.sum("de")).isCloseTo(10.0, within(1e-9));

        assertThat(tree.max("root")).isCloseTo(40.0, within(1e-9));
        assertThat(tree.max("eu")).isCloseTo(20.0, within(1e-9));
        assertThat(tree.max("asia")).isCloseTo(40.0, within(1e-9));

        assertThat(tree.count("root")).isEqualTo(4);
        assertThat(tree.count("eu")).isEqualTo(2);
        assertThat(tree.count("asia")).isEqualTo(2);
        assertThat(tree.count("de")).isEqualTo(1);

        assertThat(tree.aggregate("root", AggregationType.SUM)).isCloseTo(100.0, within(1e-9));
        assertThat(tree.aggregate("root", AggregationType.MAX)).isCloseTo(40.0, within(1e-9));
        assertThat(tree.aggregate("root", AggregationType.COUNT)).isEqualTo(4);
    }

    @Test
    void emptySubtreeAggregatesToIdentityValues() {
        tree.addNode("empty", "root");
        assertThat(tree.sum("empty")).isZero();
        assertThat(tree.count("empty")).isZero();
        assertThat(tree.max("empty")).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    // ------------------------------------------------------- incremental updates

    @Test
    void leafUpdatePropagatesOnlyAlongAncestorPath() {
        tree.setValue("de", 15);

        assertThat(tree.sum("root")).isCloseTo(105.0, within(1e-9));
        assertThat(tree.sum("eu")).isCloseTo(35.0, within(1e-9));
        assertThat(tree.sum("asia")).isCloseTo(70.0, within(1e-9)); // untouched branch
        assertThat(tree.max("eu")).isCloseTo(20.0, within(1e-9));
        assertThat(tree.count("root")).isEqualTo(4);

        // Path de -> eu -> root recomputed: 3 nodes, not all 7.
        assertThat(tree.lastUpdateStats().affectedNodes()).isEqualTo(3);
        assertThat(tree.lastUpdateStats().fullRecomputeCost()).isEqualTo(7);
    }

    @Test
    void maxDecreaseIsHandledByRecomputeFromChildren() {
        tree.setValue("jp", 5); // was the global max 40
        assertThat(tree.max("root")).isCloseTo(30.0, within(1e-9));
        assertThat(tree.max("asia")).isCloseTo(30.0, within(1e-9));
        assertThat(tree.sum("root")).isCloseTo(65.0, within(1e-9));
    }

    @Test
    void clearValueRemovesContribution() {
        tree.clearValue("cn");
        assertThat(tree.sum("root")).isCloseTo(70.0, within(1e-9));
        assertThat(tree.sum("asia")).isCloseTo(40.0, within(1e-9));
        assertThat(tree.count("root")).isEqualTo(3);
        assertThat(tree.max("asia")).isCloseTo(40.0, within(1e-9));
    }

    @Test
    void incrementalCacheAlwaysMatchesFullRecompute() {
        tree.setValue("de", 99);
        tree.setValue("jp", -5);
        tree.moveSubtree("fr", "asia");
        tree.removeSubtree("cn");
        tree.addNode("uk", "eu");
        tree.setValue("uk", 7);

        int touched = tree.fullRecompute();
        assertThat(touched).isEqualTo(tree.nodeCount());
        assertThat(tree.sum("root")).isCloseTo(99 + 20 - 5 + 7, within(1e-9));
        assertThat(tree.max("root")).isCloseTo(99.0, within(1e-9));
        assertThat(tree.count("root")).isEqualTo(4);
    }

    // ------------------------------------------------------- structural changes

    @Test
    void moveSubtreeUpdatesBothOldAndNewAncestors() {
        tree.moveSubtree("fr", "asia");

        assertThat(tree.sum("eu")).isCloseTo(10.0, within(1e-9));
        assertThat(tree.max("eu")).isCloseTo(10.0, within(1e-9));
        assertThat(tree.count("eu")).isEqualTo(1);

        assertThat(tree.sum("asia")).isCloseTo(90.0, within(1e-9));
        assertThat(tree.max("asia")).isCloseTo(40.0, within(1e-9));
        assertThat(tree.count("asia")).isEqualTo(3);

        assertThat(tree.sum("root")).isCloseTo(100.0, within(1e-9)); // total unchanged
        assertThat(tree.count("root")).isEqualTo(4);
    }

    @Test
    void moveWholeBranchKeepsItsInternalAggregates() {
        tree.moveSubtree("asia", "eu");

        assertThat(tree.sum("asia")).isCloseTo(70.0, within(1e-9));
        assertThat(tree.sum("eu")).isCloseTo(100.0, within(1e-9));
        assertThat(tree.sum("root")).isCloseTo(100.0, within(1e-9));
        assertThat(tree.max("eu")).isCloseTo(40.0, within(1e-9));
        assertThat(tree.count("eu")).isEqualTo(4);
    }

    @Test
    void moveToRootIsSupported() {
        tree.moveSubtree("de", null);
        assertThat(tree.sum("eu")).isCloseTo(20.0, within(1e-9));
        assertThat(tree.sum("root")).isCloseTo(90.0, within(1e-9));
        assertThat(tree.sum("de")).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void moveUnderOwnDescendantIsRejected() {
        assertThatThrownBy(() -> tree.moveSubtree("eu", "de"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descendant");
        assertThatThrownBy(() -> tree.moveSubtree("eu", "eu"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------- subtree add / remove

    @Test
    void removeSubtreeDecreasesAncestorAggregates() {
        tree.removeSubtree("asia");

        assertThat(tree.sum("root")).isCloseTo(30.0, within(1e-9));
        assertThat(tree.max("root")).isCloseTo(20.0, within(1e-9));
        assertThat(tree.count("root")).isEqualTo(2);
        assertThat(tree.nodeCount()).isEqualTo(4);
        assertThat(tree.contains("asia")).isFalse();
        assertThat(tree.contains("cn")).isFalse();
        assertThat(tree.contains("jp")).isFalse();
    }

    @Test
    void removeLeafOnlyAffectsAncestorPath() {
        tree.removeSubtree("de");
        assertThat(tree.sum("eu")).isCloseTo(20.0, within(1e-9));
        assertThat(tree.sum("root")).isCloseTo(90.0, within(1e-9));
        assertThat(tree.sum("asia")).isCloseTo(70.0, within(1e-9));
        assertThat(tree.lastUpdateStats().affectedNodes()).isEqualTo(2); // eu, root
    }

    @Test
    void addSubtreeIncreasesAncestorAggregates() {
        tree.addNode("us", "root");
        tree.addNode("ca", "us");
        tree.setValue("us", 5);
        tree.setValue("ca", 8);

        assertThat(tree.sum("root")).isCloseTo(113.0, within(1e-9));
        assertThat(tree.max("root")).isCloseTo(40.0, within(1e-9));
        assertThat(tree.count("root")).isEqualTo(6);
        assertThat(tree.sum("us")).isCloseTo(13.0, within(1e-9));
        assertThat(tree.nodeCount()).isEqualTo(9);
    }

    // ------------------------------------------------------- cost statistics

    @Test
    void deepTreeUpdateCostIsPathLengthNotTreeSize() {
        TreeAggregator deep = new TreeAggregator();
        int depth = 50;
        deep.addNode("n0");
        for (int i = 1; i <= depth; i++) {
            deep.addNode("n" + i, "n" + (i - 1));
        }
        deep.setValue("n" + depth, 42);

        var stats = deep.lastUpdateStats();
        assertThat(stats.affectedNodes()).isEqualTo(depth + 1); // leaf .. root
        assertThat(stats.fullRecomputeCost()).isEqualTo(depth + 1);
        // Now update the leaf again: still only the path, but tree has depth+1 nodes.
        deep.setValue("n" + depth, 43);
        stats = deep.lastUpdateStats();
        assertThat(stats.affectedNodes()).isEqualTo(depth + 1);
        assertThat(stats.fullRecomputeCost()).isEqualTo(depth + 1);
    }

    @Test
    void wideTreeUpdateCostIsMuchSmallerThanFullRecompute() {
        TreeAggregator wide = new TreeAggregator();
        wide.addNode("root");
        int leaves = 1000;
        for (int i = 0; i < leaves; i++) {
            wide.addNode("leaf" + i, "root");
            wide.setValue("leaf" + i, i);
        }
        wide.setValue("leaf0", 99999);

        var stats = wide.lastUpdateStats();
        assertThat(stats.affectedNodes()).isEqualTo(2); // leaf0 + root
        assertThat(stats.fullRecomputeCost()).isEqualTo(leaves + 1);
        assertThat(stats.costRatio()).isLessThan(0.01);
        assertThat(wide.sum("root")).isCloseTo(99999.0 + (999.0 * 1000 / 2), within(1e-6));
    }

    @Test
    void earlyStopLimitsAffectedNodesWhenAggregatesUnchanged() {
        // Update de from 10 to 20: eu sum changes, but max stays 20 (fr) -> still changed sum,
        // so use a no-op-ish update: set de to same value.
        tree.setValue("de", 10);
        assertThat(tree.lastUpdateStats().affectedNodes()).isEqualTo(1); // only de recomputed
    }

    @Test
    void cumulativeStatsTrackSavingsAcrossOperations() {
        var baseline = tree.lastUpdateStats();
        tree.setValue("de", 11);   // 3 affected vs 7 full
        tree.setValue("cn", 31);   // 3 affected vs 7 full
        tree.removeSubtree("jp");  // 2 affected (asia, root) vs 6 full

        var stats = tree.lastUpdateStats();
        assertThat(stats.cumulativeAffectedNodes() - baseline.cumulativeAffectedNodes())
                .isEqualTo(3 + 3 + 2);
        assertThat(stats.cumulativeFullRecomputeCost() - baseline.cumulativeFullRecomputeCost())
                .isEqualTo(7 + 7 + 6);
        assertThat(stats.cumulativeAffectedNodes())
                .isLessThan(stats.cumulativeFullRecomputeCost());
    }

    // ------------------------------------------------------- validation

    @Test
    void unknownOrDuplicateNodesAreRejected() {
        assertThatThrownBy(() -> tree.setValue("nope", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.aggregate("nope", AggregationType.SUM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.addNode("de", "root"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tree.addNode("x", "nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
