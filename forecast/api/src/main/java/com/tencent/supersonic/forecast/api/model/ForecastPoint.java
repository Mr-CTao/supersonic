package com.tencent.supersonic.forecast.api.model;

import java.math.BigDecimal;

/**
 * 单个未来日期的点预测和经验区间。
 *
 * @param offset 从预测原点起算的天偏移，从 1 开始。
 * @param point 点预测。
 * @param lower P10 残差修正下界；样本不足时为空。
 * @param upper P90 残差修正上界；样本不足时为空。
 */
public record ForecastPoint(int offset, BigDecimal point, BigDecimal lower, BigDecimal upper) {
}
