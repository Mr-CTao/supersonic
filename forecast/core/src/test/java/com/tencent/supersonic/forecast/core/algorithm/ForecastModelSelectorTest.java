package com.tencent.supersonic.forecast.core.algorithm;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.model.ForecastModelSelection;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MVP 冷启动、滚动回测、零实际量指标和经验区间规则。
 */
class ForecastModelSelectorTest {

    private final ForecastModelSelector selector = new ForecastModelSelector();

    @Test
    @DisplayName("少于十四天时不伪造预测结果")
    void shouldMarkShortSeriesAsInsufficient() {
        ForecastModelSelection selection = selector.select(series(13, index -> 10), 30);

        assertEquals(ForecastDataStatus.INSUFFICIENT_DATA, selection.dataStatus());
        assertNull(selection.algorithm());
        assertTrue(selection.points().isEmpty());
        assertEquals(13, selection.trainingSize());
    }

    @Test
    @DisplayName("十四至二十七天只使用七日移动平均低置信度兜底")
    void shouldUseLowConfidenceFallback() {
        ForecastModelSelection selection = selector.select(series(20, index -> index + 1), 30);

        assertEquals(ForecastDataStatus.LOW_CONFIDENCE, selection.dataStatus());
        assertEquals(ForecastAlgorithmType.MOVING_AVERAGE_7, selection.algorithm());
        assertEquals(30, selection.points().size());
        assertTrue(selection.points().stream()
                .allMatch(point -> point.lower() == null && point.upper() == null));
    }

    @Test
    @DisplayName("充足周周期样本选出稳定模型并生成经验区间")
    void shouldBacktestWeeklySeriesAndBuildIntervals() {
        ForecastModelSelection selection = selector
                .select(series(70, index -> new int[] {4, 8, 12, 16, 12, 8, 4}[index % 7]), 30);

        assertEquals(ForecastDataStatus.READY, selection.dataStatus());
        assertEquals(ForecastAlgorithmType.SEASONAL_NAIVE_7, selection.algorithm());
        assertEquals(BigDecimal.ZERO.setScale(6), selection.wape());
        assertEquals(30, selection.points().size());
        assertFalse(selection.points().stream()
                .anyMatch(point -> point.lower() == null || point.upper() == null));
    }

    @Test
    @DisplayName("实际总量为零时使用 MAE 且不计算 WAPE")
    void shouldUseMaeWhenActualSumIsZero() {
        ForecastModelSelection selection = selector.select(series(35, index -> 0), 7);

        assertNull(selection.wape());
        assertNotNull(selection.mae());
        assertEquals(BigDecimal.ZERO.setScale(6), selection.bias());
    }

    /** 创建确定性非负测试序列。 */
    private ForecastSeries series(int size, IntValue value) {
        List<BigDecimal> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(BigDecimal.valueOf(value.at(index)));
        }
        return new ForecastSeries("profile|warehouse|OUTBOUND|QUANTITY", List.copyOf(values));
    }

    /** 避免测试数据构造依赖随机数的简单整数函数。 */
    @FunctionalInterface
    private interface IntValue {
        int at(int index);
    }
}
