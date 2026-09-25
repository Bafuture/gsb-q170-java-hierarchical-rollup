package com.example.gsb.rollup;

/**
 * Cost statistics of the most recent mutating operation, plus cumulative totals.
 *
 * <p>Every mutation reports how many nodes had their cached aggregates recomputed
 * ({@link #affectedNodes()}) versus how many a full bottom-up recomputation of the
 * whole tree would have touched ({@link #fullRecomputeCost()}, i.e. every node).
 */
public record UpdateStats(
        String operation,
        int affectedNodes,
        int fullRecomputeCost,
        long cumulativeAffectedNodes,
        long cumulativeFullRecomputeCost) {

    /** Ratio of incremental cost to full-recompute cost, in [0, 1]. Lower is better. */
    public double costRatio() {
        return fullRecomputeCost == 0 ? 0.0 : (double) affectedNodes / fullRecomputeCost;
    }
}
