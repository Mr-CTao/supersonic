package com.tencent.supersonic.forecast.api.model;

import java.time.Instant;

/**
 * 由更新时间和稳定记录标识组成的 keyset 复合水位。
 *
 * @param updatedAt 源记录更新时间；快照模式可为空。
 * @param recordId 同一更新时间内的稳定排序键。
 */
public record ForecastCursor(Instant updatedAt, String recordId) {

    /**
     * 创建尚未读取任何记录的空水位。
     *
     * @return 空水位。
     */
    public static ForecastCursor empty() {
        return new ForecastCursor(null, null);
    }

    /**
     * 判断水位是否尚未初始化。
     *
     * @return 两个分量均为空时返回 true。
     */
    public boolean isEmpty() {
        return updatedAt == null && (recordId == null || recordId.isBlank());
    }
}
