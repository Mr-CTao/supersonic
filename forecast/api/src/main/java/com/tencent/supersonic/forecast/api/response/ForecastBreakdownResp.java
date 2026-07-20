package com.tencent.supersonic.forecast.api.response;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 按仓库和方向拆分的预测结果。
 */
@Data
@Builder
public class ForecastBreakdownResp {
    private String warehouseCode;
    private ForecastDirection direction;
    private BigDecimal predictedTotal;
    private ForecastDataStatus dataStatus;
    private ForecastAlgorithmType algorithm;
    private BigDecimal wape;
    private BigDecimal mae;
    private BigDecimal bias;
}
