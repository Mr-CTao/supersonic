package com.tencent.supersonic.forecast.api.enums;

/**
 * 可恢复预测任务的运行状态。
 */
public enum ForecastJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLING, CANCELLED
}
