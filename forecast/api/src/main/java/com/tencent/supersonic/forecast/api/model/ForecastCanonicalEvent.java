package com.tencent.supersonic.forecast.api.model;

import com.tencent.supersonic.forecast.api.enums.ForecastCanonicalStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 与客户源表结构无关的标准出入库事实。
 *
 * <p>
 * 对象不可变，可安全在线程间传递；持久化幂等键由 profile、stream、mappingVersion 和 sourceRecordId 共同组成。
 * </p>
 */
@Value
@Builder
public class ForecastCanonicalEvent {
    String sourceRecordId;
    String taskId;
    ForecastDirection direction;
    String warehouseCode;
    BigDecimal quantity;
    Instant occurredAt;
    String sourceStatus;
    ForecastCanonicalStatus canonicalStatus;
    Instant sourceUpdatedAt;
    boolean deleted;
}
