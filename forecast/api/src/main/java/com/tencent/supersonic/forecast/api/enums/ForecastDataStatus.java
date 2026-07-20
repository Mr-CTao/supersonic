package com.tencent.supersonic.forecast.api.enums;

/**
 * 预测结果的数据质量和新鲜度状态。
 */
public enum ForecastDataStatus {
    READY, LOW_CONFIDENCE, INSUFFICIENT_DATA, STALE, FAILED
}
