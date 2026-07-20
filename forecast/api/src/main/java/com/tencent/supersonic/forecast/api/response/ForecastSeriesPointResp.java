package com.tencent.supersonic.forecast.api.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 看板折线图中的实际或预测日期点。
 *
 * @param date 业务日期。
 * @param actual 实际值；未来日期为空。
 * @param forecast 预测值；历史日期可为空。
 * @param lower 经验区间下界。
 * @param upper 经验区间上界。
 */
public record ForecastSeriesPointResp(LocalDate date, BigDecimal actual, BigDecimal forecast,
        BigDecimal lower, BigDecimal upper) {
}
