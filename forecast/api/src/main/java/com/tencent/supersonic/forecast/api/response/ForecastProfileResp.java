package com.tencent.supersonic.forecast.api.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 预测 Profile 详情。
 */
@Data
@Builder
public class ForecastProfileResp {
    private Long id;
    private String name;
    private Long sourceDatabaseId;
    private String sourceDatabaseName;
    private Long decisionDatabaseId;
    private String decisionDatabaseName;
    private String timeZone;
    private String syncCron;
    private String forecastCron;
    private String reconcileCron;
    private Integer historyDays;
    private boolean enabled;
    private Integer lockVersion;
    private Instant lastSyncAt;
    private Instant lastForecastAt;
    private String freshnessStatus;
    @Builder.Default
    private List<ForecastStreamResp> streams = new ArrayList<>();
    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;
}
