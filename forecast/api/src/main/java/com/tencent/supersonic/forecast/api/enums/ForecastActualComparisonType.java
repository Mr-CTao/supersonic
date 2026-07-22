package com.tencent.supersonic.forecast.api.enums;

/**
 * 预测 KPI 中“实际量”的对比口径。
 *
 * <p>
 * 前端据此区分“前一等长周期实际”和“回测同期实际”，避免同一个数值字段在不同模式下 被错误解释。
 */
public enum ForecastActualComparisonType {

    /** 正式预测或今日预览：展示预测起点之前的等长实际周期。 */
    PREVIOUS_PERIOD,

    /** 历史回测：展示与预测日期完全重合的已知实际周期。 */
    FORECAST_PERIOD
}
