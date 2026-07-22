package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.ActualDateRange;
import com.tencent.supersonic.forecast.server.service.ForecastTrainingWindowResolver.TrainingWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ForecastTrainingWindowResolver} 自动窗口、自定义边界与防数据泄漏测试。
 *
 * <p>
 * 测试固定使用一个方向的数据边界，确保 API 即使绕过前端日期禁用逻辑，也不能提交越界、 不完整或把回测实际混入训练集的日期参数。
 */
class ForecastTrainingWindowResolverTest {

    private static final ActualDateRange DATA_RANGE =
            new ActualDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));

    /** 未传自定义日期时继续使用 Profile 配置的自动窗口。 */
    @Test
    void shouldResolveAutomaticWindowFromConfiguredHistoryDays() {
        TrainingWindow result = ForecastTrainingWindowResolver.resolve(DATA_RANGE,
                LocalDate.of(2025, 4, 1), 30, null, null);

        assertEquals(LocalDate.of(2025, 3, 2), result.startInclusive());
        assertEquals(LocalDate.of(2025, 3, 31), result.endInclusive());
        assertFalse(result.custom());
    }

    /** 自定义训练窗口按含首尾日期计算，恰好十四日应被接受。 */
    @Test
    void shouldAcceptFourteenInclusiveTrainingDays() {
        TrainingWindow result = ForecastTrainingWindowResolver.resolve(DATA_RANGE,
                LocalDate.of(2025, 2, 1), 30, LocalDate.of(2025, 1, 18), LocalDate.of(2025, 1, 31));

        assertEquals(LocalDate.of(2025, 1, 18), result.startInclusive());
        assertEquals(LocalDate.of(2025, 1, 31), result.endInclusive());
        assertTrue(result.custom());
    }

    /** 少于十四个自然日的自定义窗口不可用于模型计算。 */
    @Test
    void shouldRejectShortCustomWindow() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class,
                () -> ForecastTrainingWindowResolver.resolve(DATA_RANGE, LocalDate.of(2025, 2, 1),
                        30, LocalDate.of(2025, 1, 19), LocalDate.of(2025, 1, 31)));

        assertTrue(exception.getMessage().contains("至少需要 14 个自然日"));
    }

    /** 自定义边界不得早于或晚于当前方向的真实数据范围。 */
    @Test
    void shouldRejectWindowOutsideSelectedDirectionDataRange() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class,
                () -> ForecastTrainingWindowResolver.resolve(DATA_RANGE, LocalDate.of(2025, 4, 1),
                        30, LocalDate.of(2024, 12, 31), LocalDate.of(2025, 3, 31)));

        assertTrue(exception.getMessage().contains("当前统计口径的数据范围"));
    }

    /** 训练截止日必须早于预测首日，禁止将待回测区间混入训练样本。 */
    @Test
    void shouldRejectTrainingEndAtForecastStart() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class,
                () -> ForecastTrainingWindowResolver.resolve(DATA_RANGE, LocalDate.of(2025, 3, 1),
                        30, LocalDate.of(2025, 2, 1), LocalDate.of(2025, 3, 1)));

        assertTrue(exception.getMessage().contains("避免未来数据泄漏"));
    }

    /** 起止日期必须成对传入，避免服务端误把半个区间当作自动模式。 */
    @Test
    void shouldRejectPartialCustomWindow() {
        assertThrows(InvalidArgumentException.class, () -> ForecastTrainingWindowResolver
                .hasCustomWindow(LocalDate.of(2025, 1, 1), null));
    }
}
