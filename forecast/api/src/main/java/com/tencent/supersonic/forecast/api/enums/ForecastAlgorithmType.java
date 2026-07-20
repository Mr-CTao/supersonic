package com.tencent.supersonic.forecast.api.enums;

/**
 * 内置 Java 统计预测算法标识。
 */
public enum ForecastAlgorithmType {
    SEASONAL_NAIVE_7,
    MOVING_AVERAGE_7,
    MOVING_AVERAGE_14,
    MOVING_AVERAGE_28,
    SIMPLE_EXPONENTIAL_SMOOTHING_02,
    SIMPLE_EXPONENTIAL_SMOOTHING_04,
    SIMPLE_EXPONENTIAL_SMOOTHING_06,
    SIMPLE_EXPONENTIAL_SMOOTHING_08
}
