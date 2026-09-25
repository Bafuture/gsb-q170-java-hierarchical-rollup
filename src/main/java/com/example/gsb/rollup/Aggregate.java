package com.example.gsb.rollup;

/**
 * 某个节点子树的聚合结果快照。
 *
 * @param sum   子树内所有指标值之和（无值时为 0）
 * @param max   子树内所有指标值的最大值（无值时为 {@link Double#NEGATIVE_INFINITY}）
 * @param count 子树内携带指标值的节点个数
 */
public record Aggregate(double sum, double max, long count) {

    public static final Aggregate EMPTY = new Aggregate(0.0, Double.NEGATIVE_INFINITY, 0);

    public boolean isEmpty() {
        return count == 0;
    }
}
