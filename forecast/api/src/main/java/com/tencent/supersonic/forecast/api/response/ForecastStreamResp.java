package com.tencent.supersonic.forecast.api.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Profile 下单个出入库数据流详情。
 */
@Data
@Builder
public class ForecastStreamResp {
    private Long id;
    private Long profileId;
    private String name;
    private boolean enabled;
    private Long activeMappingId;
    private Integer activeMappingVersion;
    private ForecastActivationSummaryResp latestActivation;
    private Integer lockVersion;
    private Instant lastSyncAt;
    private Instant createdAt;
    private Instant updatedAt;
}
