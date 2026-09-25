package com.example.gsb.rollup;

/** Supported aggregation kinds over the metric values in a subtree. */
public enum AggregationType {
    /** Sum of all metric values in the subtree. Empty subtree aggregates to 0. */
    SUM,
    /** Maximum metric value in the subtree. Empty subtree aggregates to {@link Double#NEGATIVE_INFINITY}. */
    MAX,
    /** Number of nodes carrying a metric value in the subtree. Empty subtree aggregates to 0. */
    COUNT
}
