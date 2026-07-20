package com.tencent.supersonic.forecast.api.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 按日期连续补零后的单条训练序列。
 *
 * @param key profile、仓库、方向和指标组成的稳定键。
 * @param values 按时间升序排列的非负日值。
 */
public record ForecastSeries(String key, List<BigDecimal> values) {
}
