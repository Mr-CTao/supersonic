package com.tencent.supersonic.forecast.api.model;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单个候选算法的回测和未来预测结果。
 *
 * @param algorithm 算法标识。
 * @param validationPredictions 回测区间预测值。
 * @param futureValues 未来值。
 * @param residuals 回测残差，定义为实际值减预测值。
 */
public record ForecastAlgorithmResult(ForecastAlgorithmType algorithm,
        List<BigDecimal> validationPredictions, List<BigDecimal> futureValues,
        List<BigDecimal> residuals) {
}
