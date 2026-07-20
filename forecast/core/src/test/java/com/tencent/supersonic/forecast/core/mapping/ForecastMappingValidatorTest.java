package com.tencent.supersonic.forecast.core.mapping;

import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.core.mapping.ForecastMappingValidator.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证映射 SQL 注入边界、复合水位前提和能力降级提示。
 */
class ForecastMappingValidatorTest {

    private final ForecastMappingValidator validator = new ForecastMappingValidator();

    @Test
    @DisplayName("最小增量映射可通过并提示可选能力缺失")
    void shouldAcceptControlledSingleTableMapping() {
        ValidationReport report =
                validator.validate(ForecastMappingTestFixture.validIncrementalConfig());

        assertTrue(report.valid());
        assertTrue(report.warnings().stream().anyMatch(item -> item.contains("taskId")));
        assertTrue(report.warnings().stream().anyMatch(item -> item.contains("硬删除")));
    }

    @Test
    @DisplayName("数据库标识符不允许夹带任意 SQL")
    void shouldRejectUntrustedIdentifier() {
        ForecastMappingConfig config = ForecastMappingTestFixture.validIncrementalConfig();
        config.getSource().getSingle().setTable("task; DROP TABLE inventory");

        ValidationReport report = validator.validate(config);

        assertFalse(report.valid());
        assertTrue(report.errors().stream().anyMatch(item -> item.contains("非法数据库标识符")));
    }

    @Test
    @DisplayName("无更新时间时必须显式切换最近窗口重扫")
    void shouldRequireWatermarkForIncrementalMode() {
        ForecastMappingConfig config = ForecastMappingTestFixture.validIncrementalConfig();
        config.getFields().setSourceUpdatedAt(null);

        assertFalse(validator.validate(config).valid());

        config.setSyncMode(ForecastSyncMode.SNAPSHOT_LOOKBACK);
        ValidationReport fallback = validator.validate(config);
        assertTrue(fallback.valid());
        assertTrue(fallback.warnings().stream().anyMatch(item -> item.contains("窗口重扫")));
    }
}
