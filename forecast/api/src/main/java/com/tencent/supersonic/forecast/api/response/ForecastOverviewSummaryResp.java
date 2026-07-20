package com.tencent.supersonic.forecast.api.response;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预测看板顶部汇总。
 */
@Data
@Builder
public class ForecastOverviewSummaryResp {
    private Long profileId;
    private ForecastMetric metric;
    private int horizon;
    private BigDecimal predictedTotal;
    private BigDecimal previousActualTotal;
    private ForecastDataStatus dataStatus;
    private ForecastAlgorithmType algorithm;
    private BigDecimal wape;
    private BigDecimal mae;
    private BigDecimal bias;
    private Instant lastSyncAt;
    private Instant lastForecastAt;
}
