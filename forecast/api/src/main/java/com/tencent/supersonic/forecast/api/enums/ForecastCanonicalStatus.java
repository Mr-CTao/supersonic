package com.tencent.supersonic.forecast.api.enums;

/**
 * 源任务状态映射后的标准状态。
 *
 * <p>
 * 只有 COMPLETED 且未删除的事件进入预测日汇总，其他状态用于修正历史聚合。
 * </p>
 */
public enum ForecastCanonicalStatus {
    PENDING, COMPLETED, CANCELLED, DELETED, UNKNOWN
}
