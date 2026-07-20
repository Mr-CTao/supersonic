package com.tencent.supersonic.forecast.api.model;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自动选模后的可持久化结果。
 *
 * @param algorithm 入选算法；数据不足时为空。
 * @param dataStatus 数据质量状态。
 * @param points 未来预测点。
 * @param wape 加权绝对百分比误差。
 * @param mae 平均绝对误差。
 * @param bias 归一化偏差。
 * @param trainingSize 训练日数。
 * @param validationSize 回测日数。
 */
public record ForecastModelSelection(ForecastAlgorithmType algorithm,
        ForecastDataStatus dataStatus, List<ForecastPoint> points, BigDecimal wape,
        BigDecimal mae, BigDecimal bias, int trainingSize, int validationSize) {
}
