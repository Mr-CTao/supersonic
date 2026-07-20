package com.tencent.supersonic.forecast.api.request;

import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 幂等创建预测任务的请求。
 */
@Data
public class ForecastJobReq {
    @NotNull
    @Positive
    private Long profileId;
    @Positive
    private Long streamId;
    @Positive
    private Long mappingId;
    @NotNull
    private ForecastJobType type;
    @Min(90)
    @Max(730)
    private Integer historyDays;
}
