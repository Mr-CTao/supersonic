package com.tencent.supersonic.forecast.api.response;

import com.tencent.supersonic.forecast.api.enums.ForecastActualComparisonType;
import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastAnchorMode;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 预测看板一次渲染所需的完整只读快照。
 *
 * <p>
 * 日期上下文、KPI、趋势点与仓库拆分由同一次查询或计算产生，避免前端并行请求在任务发布切换时 读取到不同版本。该响应不包含任何写操作句柄，历史回测也不会进入正式发布视图。
 */
@Data
@Builder
public class ForecastOverviewSnapshotResp {

    private Long profileId;
    private ForecastMetric metric;
    /** 为空表示“全部方向”，否则表示本快照只统计指定方向。 */
    private ForecastDirection direction;
    private int horizon;
    private ForecastAnchorMode anchorMode;
    private ForecastActualComparisonType actualComparisonType;
    private LocalDate dataStartDate;
    private LocalDate latestActualDate;
    private LocalDate trainingStartDate;
    private LocalDate trainingEndDate;
    private LocalDate forecastStartDate;
    private LocalDate forecastEndDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;
    private Long businessDataLagDays;
    private BigDecimal predictedTotal;
    private BigDecimal actualTotal;
    private ForecastDataStatus dataStatus;
    private ForecastAlgorithmType algorithm;
    private BigDecimal wape;
    private BigDecimal mae;
    private BigDecimal bias;
    private Instant lastSyncAt;
    private Instant lastForecastAt;
    private List<ForecastSeriesPointResp> series;
    private List<ForecastBreakdownResp> breakdown;
}
