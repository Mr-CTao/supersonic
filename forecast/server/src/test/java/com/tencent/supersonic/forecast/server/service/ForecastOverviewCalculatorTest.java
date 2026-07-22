package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.forecast.api.enums.ForecastActualComparisonType;
import com.tencent.supersonic.forecast.api.enums.ForecastAnchorMode;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSnapshotResp;
import com.tencent.supersonic.forecast.core.algorithm.ForecastModelSelector;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.DailyActual;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ForecastOverviewCalculator} 日期隔离与回测指标测试。
 *
 * <p>
 * 测试使用恒定序列，使预期值可人工核对，并重点防止旧数据到今天之间的空白日期再次被补零 污染训练样本。
 */
class ForecastOverviewCalculatorTest {

    private final ForecastOverviewCalculator calculator =
            new ForecastOverviewCalculator(new ForecastModelSelector());

    /** 历史回测应使用预测区间内的已知实际计算同期 KPI 和 WAPE。 */
    @Test
    void shouldCompareBacktestForecastWithKnownActuals() {
        LocalDate trainingStart = LocalDate.of(2025, 1, 1);
        LocalDate trainingEnd = LocalDate.of(2025, 1, 28);
        LocalDate forecastStart = trainingEnd.plusDays(1);
        LocalDate forecastEnd = forecastStart.plusDays(6);
        List<DailyActual> actuals = constantActuals(trainingStart, forecastEnd, BigDecimal.TEN);

        ForecastOverviewSnapshotResp result =
                calculator.calculate(input(ForecastAnchorMode.BACKTEST,
                        ForecastActualComparisonType.FORECAST_PERIOD, trainingStart, trainingEnd,
                        forecastStart, forecastEnd, forecastStart, forecastEnd, actuals));

        assertEquals(0, new BigDecimal("70").compareTo(result.getPredictedTotal()));
        assertEquals(0, new BigDecimal("70").compareTo(result.getActualTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getWape()));
        assertEquals(ForecastDirection.INBOUND, result.getDirection());
        assertEquals(1, result.getBreakdown().size());
        assertNotNull(
                result.getSeries().stream().filter(point -> point.date().equals(forecastStart))
                        .findFirst().orElseThrow().actual());
        assertNotNull(
                result.getSeries().stream().filter(point -> point.date().equals(forecastStart))
                        .findFirst().orElseThrow().forecast());
    }

    /** 今天模式可以跨越历史数据后的空白期，但空白期不得进入训练序列。 */
    @Test
    void shouldProjectAcrossStaleGapWithoutTrainingOnSyntheticZeros() {
        LocalDate trainingStart = LocalDate.of(2025, 1, 1);
        LocalDate trainingEnd = LocalDate.of(2025, 1, 30);
        LocalDate forecastStart = LocalDate.of(2025, 2, 10);
        LocalDate forecastEnd = forecastStart.plusDays(6);
        List<DailyActual> actuals =
                constantActuals(trainingStart, trainingEnd, BigDecimal.valueOf(5));

        ForecastOverviewSnapshotResp result = calculator.calculate(input(ForecastAnchorMode.TODAY,
                ForecastActualComparisonType.PREVIOUS_PERIOD, trainingStart, trainingEnd,
                forecastStart, forecastEnd, trainingEnd.minusDays(6), trainingEnd, actuals));

        assertEquals(0, new BigDecimal("35").compareTo(result.getPredictedTotal()));
        assertEquals(0, new BigDecimal("35").compareTo(result.getActualTotal()));
        assertEquals(forecastStart, result.getForecastStartDate());
        assertEquals(7L,
                result.getSeries().stream().filter(point -> point.forecast() != null).count());
    }

    /** 创建计算输入，固定与日期语义无关的 Profile 元数据。 */
    private ForecastOverviewCalculator.Input input(ForecastAnchorMode anchorMode,
            ForecastActualComparisonType comparisonType, LocalDate trainingStart,
            LocalDate trainingEnd, LocalDate forecastStart, LocalDate forecastEnd,
            LocalDate actualStart, LocalDate actualEnd, List<DailyActual> actuals) {
        return new ForecastOverviewCalculator.Input(1L, ForecastMetric.QUANTITY,
                ForecastDirection.INBOUND, 7, anchorMode, comparisonType, trainingStart,
                actuals.get(actuals.size() - 1).date(), trainingStart, trainingEnd, forecastStart,
                forecastEnd, actualStart, actualEnd, LocalDate.of(2026, 7, 21),
                Instant.parse("2026-07-21T01:00:00Z"), Instant.parse("2026-07-21T02:00:00Z"),
                actuals);
    }

    /** 生成包含起止日期的恒定入库实际序列。 */
    private List<DailyActual> constantActuals(LocalDate start, LocalDate end, BigDecimal quantity) {
        List<DailyActual> values = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            values.add(new DailyActual(date, "WH-01", ForecastDirection.INBOUND, quantity, 1L));
        }
        return List.copyOf(values);
    }
}
