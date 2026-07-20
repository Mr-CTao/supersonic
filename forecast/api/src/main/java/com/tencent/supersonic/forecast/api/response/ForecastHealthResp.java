package com.tencent.supersonic.forecast.api.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 不暴露主机敏感信息的 Worker 和数据新鲜度健康信息。
 */
@Data
@Builder
public class ForecastHealthResp {
    private boolean workerHealthy;
    private int activeWorkers;
    private Instant latestHeartbeatAt;
    private Instant latestSyncAt;
    private Instant latestForecastAt;
    private String freshnessStatus;
}
