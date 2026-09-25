package com.example.gsb.rollup;

/**
 * 树的运行统计信息。
 *
 * @param totalNodes              当前树中节点总数
 * @param lastUpdateAffectedNodes 最近一次变更操作实际重算的节点数（增量代价）
 * @param fullRecomputeCost       若对当前树做一次全量重算需要访问的节点数（等于 totalNodes）
 */
public record RollupStats(long totalNodes, long lastUpdateAffectedNodes, long fullRecomputeCost) {

    /** 增量更新相对全量重算节省的节点访问比例，例如 0.95 表示少访问 95% 的节点。 */
    public double savingsRatio() {
        if (fullRecomputeCost == 0) {
            return 0.0;
        }
        return 1.0 - (double) lastUpdateAffectedNodes / fullRecomputeCost;
    }
}
