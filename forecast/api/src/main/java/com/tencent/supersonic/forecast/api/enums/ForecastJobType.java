package com.tencent.supersonic.forecast.api.enums;

/**
 * 预测 Worker 可执行的任务类型。
 */
public enum ForecastJobType {
    INITIAL_SYNC, INCREMENTAL_SYNC, RECONCILE, AGGREGATE, FORECAST, PUBLISH_SEMANTIC_MODEL
}
