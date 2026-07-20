package com.tencent.supersonic.forecast.api.model;

import java.util.List;

/**
 * Connector 返回的一页标准事件及下一复合水位。
 *
 * @param events 当前页事件。
 * @param nextCursor 当前页最后一条记录的位置。
 * @param exhausted 是否已到达固定上界。
 */
public record ForecastPage(List<ForecastCanonicalEvent> events, ForecastCursor nextCursor,
        boolean exhausted) {
}
