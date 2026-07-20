package com.tencent.supersonic.forecast.api.response;

import com.tencent.supersonic.forecast.api.enums.ForecastMappingStatus;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 不可变映射版本详情。
 */
@Data
@Builder
public class ForecastMappingResp {
    private Long id;
    private Long streamId;
    private Integer version;
    private ForecastMappingStatus status;
    private ForecastMappingConfig config;
    private String configChecksum;
    private boolean valid;
    private String validationSummary;
    private String createdBy;
    private Instant createdAt;
    private Instant publishedAt;
}
