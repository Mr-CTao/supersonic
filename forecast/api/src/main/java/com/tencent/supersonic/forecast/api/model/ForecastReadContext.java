package com.tencent.supersonic.forecast.api.model;

import java.time.Instant;

/**
 * Connector 单页读取所需的有界上下文。
 *
 * @param historyStart 业务时间窗口起点。
 * @param historyEnd 业务时间窗口终点。
 * @param lowerExclusive 已提交的下界水位，不包含该位置。
 * @param upperInclusive 任务开始时捕获的固定上界。
 * @param pageSize 单页最大行数。
 */
public record ForecastReadContext(Instant historyStart, Instant historyEnd,
        ForecastCursor lowerExclusive, ForecastCursor upperInclusive, int pageSize) {
}
