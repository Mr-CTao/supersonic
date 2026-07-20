package com.tencent.supersonic.forecast.core.mapping;

import com.tencent.supersonic.forecast.api.enums.ForecastCanonicalStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastQuantityTransform;
import com.tencent.supersonic.forecast.api.model.ForecastCanonicalEvent;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Connector 投影行到标准事件的字典、符号、时区和软删除转换。
 */
class ForecastRowTransformerTest {

    private final ForecastRowTransformer transformer = new ForecastRowTransformer();

    @Test
    @DisplayName("按受控映射标准化出入库事件")
    void shouldTransformCanonicalEvent() {
        ForecastMappingConfig config = ForecastMappingTestFixture.validIncrementalConfig();
        config.getFields().setTaskId(ForecastMappingTestFixture.column("task_no"));
        config.getFields().setStatus(ForecastMappingTestFixture.column("status"));
        config.getFields().setDeleted(ForecastMappingTestFixture.column("deleted"));
        config.getFields().getDirection().setValueMap(Map.of("I", "INBOUND"));
        config.getFields().getStatus().setValueMap(Map.of("DONE", "COMPLETED"));
        config.getFields().getQuantity().setTransform(ForecastQuantityTransform.ABS);

        Map<String, Object> row = new HashMap<>();
        row.put(ForecastColumnAliases.SOURCE_RECORD_ID, 101L);
        row.put(ForecastColumnAliases.TASK_ID, "RK-20260718-01");
        row.put(ForecastColumnAliases.QUANTITY, "-12.50");
        row.put(ForecastColumnAliases.OCCURRED_AT, Timestamp.valueOf("2026-07-18 08:30:00"));
        row.put(ForecastColumnAliases.SOURCE_UPDATED_AT, Timestamp.valueOf("2026-07-18 09:00:00"));
        row.put(ForecastColumnAliases.WAREHOUSE_CODE, "WH-A");
        row.put(ForecastColumnAliases.DIRECTION, "I");
        row.put(ForecastColumnAliases.STATUS, "DONE");
        row.put(ForecastColumnAliases.DELETED, 1);

        ForecastCanonicalEvent event = transformer.transform(row, config);

        assertEquals("101", event.getSourceRecordId());
        assertEquals(new BigDecimal("12.50"), event.getQuantity());
        assertEquals(Instant.parse("2026-07-18T00:30:00Z"), event.getOccurredAt());
        assertEquals(ForecastDirection.INBOUND, event.getDirection());
        assertEquals(ForecastCanonicalStatus.COMPLETED, event.getCanonicalStatus());
        assertTrue(event.isDeleted());
    }

    @Test
    @DisplayName("未配置符号修正的负数量被阻断且错误不泄漏源值")
    void shouldRejectNegativeQuantityWithoutLeakingValue() {
        ForecastMappingConfig config = ForecastMappingTestFixture.validIncrementalConfig();
        Map<String, Object> row = new HashMap<>();
        row.put(ForecastColumnAliases.SOURCE_RECORD_ID, "secret-record-id");
        row.put(ForecastColumnAliases.QUANTITY, "-987654");
        row.put(ForecastColumnAliases.OCCURRED_AT, Instant.parse("2026-07-18T00:00:00Z"));
        row.put(ForecastColumnAliases.SOURCE_UPDATED_AT, Instant.parse("2026-07-18T00:00:01Z"));
        row.put(ForecastColumnAliases.WAREHOUSE_CODE, "WH-A");
        row.put(ForecastColumnAliases.DIRECTION, "OUTBOUND");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> transformer.transform(row, config));

        assertTrue(error.getMessage().contains("quantity"));
        assertFalseContains(error.getMessage(), "987654");
        assertFalseContains(error.getMessage(), "secret-record-id");
    }

    @Test
    @DisplayName("兼容老 WMS 的 JDBC 日期时间文本")
    void shouldTransformJdbcDateTimeText() {
        ForecastMappingConfig config = ForecastMappingTestFixture.validIncrementalConfig();
        Map<String, Object> row = validRow();
        row.put(ForecastColumnAliases.OCCURRED_AT, "2025-11-15 08:30:45.123");
        row.put(ForecastColumnAliases.SOURCE_UPDATED_AT, "2025-11-16");

        ForecastCanonicalEvent event = transformer.transform(row, config);

        assertEquals(Instant.parse("2025-11-15T00:30:45.123Z"), event.getOccurredAt());
        assertEquals(Instant.parse("2025-11-15T16:00:00Z"), event.getSourceUpdatedAt());
    }

    @Test
    @DisplayName("兼容 JDBC SQL Date 且按源时区当天零点解释")
    void shouldTransformSqlDate() {
        ForecastMappingConfig config = ForecastMappingTestFixture.validIncrementalConfig();
        Map<String, Object> row = validRow();
        row.put(ForecastColumnAliases.OCCURRED_AT, java.sql.Date.valueOf("2025-11-15"));
        row.put(ForecastColumnAliases.SOURCE_UPDATED_AT, java.sql.Date.valueOf("2025-11-16"));

        ForecastCanonicalEvent event = transformer.transform(row, config);

        assertEquals(Instant.parse("2025-11-14T16:00:00Z"), event.getOccurredAt());
        assertEquals(Instant.parse("2025-11-15T16:00:00Z"), event.getSourceUpdatedAt());
    }

    /** 构造只包含转换器必填别名的有效行，供时间格式回归测试复用。 */
    private Map<String, Object> validRow() {
        Map<String, Object> row = new HashMap<>();
        row.put(ForecastColumnAliases.SOURCE_RECORD_ID, "row-1");
        row.put(ForecastColumnAliases.QUANTITY, "1");
        row.put(ForecastColumnAliases.WAREHOUSE_CODE, "WH-A");
        row.put(ForecastColumnAliases.DIRECTION, "INBOUND");
        return row;
    }

    /** 断言脱敏错误不包含指定片段。 */
    private void assertFalseContains(String actual, String forbidden) {
        assertTrue(actual == null || !actual.contains(forbidden));
    }
}
