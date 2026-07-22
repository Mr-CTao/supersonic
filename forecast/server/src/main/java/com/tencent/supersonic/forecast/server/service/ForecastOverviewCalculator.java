package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastActualComparisonType;
import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastAnchorMode;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.model.ForecastModelSelection;
import com.tencent.supersonic.forecast.api.model.ForecastPoint;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import com.tencent.supersonic.forecast.api.response.ForecastBreakdownResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSnapshotResp;
import com.tencent.supersonic.forecast.api.response.ForecastSeriesPointResp;
import com.tencent.supersonic.forecast.core.algorithm.ForecastModelSelector;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.DailyActual;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 历史回测与“今天”模式的只读预测计算器。
 *
 * <p>
 * 计算器只依赖调用方提供的日实际快照，所有中间状态都保存在方法局部变量中，因此 Spring 单例实例可被并发请求安全复用，无需 {@code lock} 或
 * {@code SemaphoreSlim}。正式发布结果仍由 Worker 持久化，本类不会写数据库，也不会改变 Profile 当前发布指针。
 */
@Component
public class ForecastOverviewCalculator {

    private static final int MAX_PREVIEW_DAYS = 730;
    private static final int METRIC_SCALE = 8;
    private static final int HISTORY_DISPLAY_DAYS = 30;

    private final ForecastModelSelector modelSelector;

    /**
     * 创建只读预测计算器。
     *
     * @param modelSelector 与正式 Worker 共用的自动选模器。
     */
    public ForecastOverviewCalculator(ForecastModelSelector modelSelector) {
        this.modelSelector = modelSelector;
    }

    /**
     * 计算一次不落库的预测看板快照。
     *
     * <p>
     * 例如历史回测起点为 2025-09-01 时，调用方应把训练截止设为 2025-08-31， 并把 2025-09-01 至预测结束日的已知实际一并放入
     * {@code input.actuals()}。
     *
     * @param input 已完成权限、日期边界与数据范围校验的计算输入。
     * @return 与正式看板相同结构的只读快照。
     * @throws InvalidArgumentException 预测跨越天数过大或日期关系非法。
     */
    public ForecastOverviewSnapshotResp calculate(Input input) {
        validateInput(input);
        Map<SeriesKey, Map<LocalDate, DailyActual>> grouped = groupActuals(input.actuals());
        Map<LocalDate, BigDecimal> actualByDate = aggregateActuals(input.actuals(), input.metric());
        Map<LocalDate, ForecastAccumulator> forecastByDate = new LinkedHashMap<>();
        List<ForecastBreakdownResp> breakdown = new ArrayList<>();
        Set<ForecastAlgorithmType> algorithms = new LinkedHashSet<>();
        ForecastDataStatus overallStatus = ForecastDataStatus.READY;

        long gapDays =
                ChronoUnit.DAYS.between(input.trainingEnd().plusDays(1), input.forecastStart());
        long projectionDaysLong = gapDays + input.horizon();
        if (projectionDaysLong > MAX_PREVIEW_DAYS) {
            throw new InvalidArgumentException("预测起点距离最新业务数据过远，请改用跟随数据或历史回测");
        }
        // 先校验长整型上限再收窄，避免恶意或异常日期触发 Math.toIntExact 的非业务异常。
        int projectionDays = Math.toIntExact(projectionDaysLong);

        for (Map.Entry<SeriesKey, Map<LocalDate, DailyActual>> entry : grouped.entrySet()) {
            if (!supportsMetric(entry.getValue(), input.metric())) {
                continue;
            }
            LocalDate seriesStart = resolveSeriesStart(entry.getValue(), input.trainingStart(),
                    input.trainingEnd());
            if (seriesStart == null) {
                continue;
            }
            List<BigDecimal> values = fillSeries(seriesStart, input.trainingEnd().plusDays(1),
                    entry.getValue(), input.metric());
            ForecastModelSelection selection = modelSelector.select(
                    new ForecastSeries(entry.getKey().stableKey(input.metric()), values),
                    projectionDays);
            List<ForecastPoint> visiblePoints =
                    selection.points().stream().filter(point -> point.offset() > gapDays
                            && point.offset() <= gapDays + input.horizon()).toList();
            accumulateForecast(input.trainingEnd(), visiblePoints, forecastByDate);
            Metrics seriesMetrics =
                    input.actualComparisonType() == ForecastActualComparisonType.FORECAST_PERIOD
                            ? calculateMetrics(input.trainingEnd(), visiblePoints, entry.getValue(),
                                    input.metric())
                            : new Metrics(selection.wape(), selection.mae(), selection.bias());
            BigDecimal predictedTotal = visiblePoints.stream().map(ForecastPoint::point)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            breakdown.add(
                    ForecastBreakdownResp.builder().warehouseCode(entry.getKey().warehouseCode())
                            .direction(entry.getKey().direction()).predictedTotal(predictedTotal)
                            .dataStatus(selection.dataStatus()).algorithm(selection.algorithm())
                            .wape(seriesMetrics.wape()).mae(seriesMetrics.mae())
                            .bias(seriesMetrics.bias()).build());
            overallStatus = worseStatus(overallStatus, selection.dataStatus());
            if (selection.algorithm() != null) {
                algorithms.add(selection.algorithm());
            }
        }

        breakdown.sort(Comparator.comparing(ForecastBreakdownResp::getPredictedTotal,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (breakdown.isEmpty()) {
            overallStatus = ForecastDataStatus.INSUFFICIENT_DATA;
        }
        BigDecimal predictedTotal = forecastByDate.values().stream()
                .map(ForecastAccumulator::forecast).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualTotal =
                sumActualRange(actualByDate, input.actualStart(), input.actualEnd());
        Metrics overallMetrics =
                input.actualComparisonType() == ForecastActualComparisonType.FORECAST_PERIOD
                        ? calculateAggregateMetrics(forecastByDate, actualByDate,
                                input.forecastStart(), input.forecastEnd())
                        : averageMetrics(breakdown);

        return ForecastOverviewSnapshotResp.builder().profileId(input.profileId())
                .metric(input.metric()).direction(input.direction()).horizon(input.horizon())
                .anchorMode(input.anchorMode()).actualComparisonType(input.actualComparisonType())
                .dataStartDate(input.dataStart()).latestActualDate(input.latestActual())
                .trainingStartDate(input.trainingStart()).trainingEndDate(input.trainingEnd())
                .forecastStartDate(input.forecastStart()).forecastEndDate(input.forecastEnd())
                .actualStartDate(input.actualStart()).actualEndDate(input.actualEnd())
                .businessDataLagDays(
                        Math.max(0L, ChronoUnit.DAYS.between(input.latestActual(), input.today())))
                .predictedTotal(predictedTotal).actualTotal(actualTotal).dataStatus(overallStatus)
                .algorithm(algorithms.size() == 1 ? algorithms.iterator().next() : null)
                .wape(overallMetrics.wape()).mae(overallMetrics.mae()).bias(overallMetrics.bias())
                .lastSyncAt(input.lastSyncAt()).lastForecastAt(input.lastForecastAt())
                .series(buildSeries(input, actualByDate, forecastByDate))
                .breakdown(List.copyOf(breakdown)).build();
    }

    /** 校验日期关系，防止调用方把未来实际误放入训练窗口。 */
    private void validateInput(Input input) {
        if (input == null || input.profileId() == null || input.metric() == null
                || input.anchorMode() == null || input.actualComparisonType() == null
                || input.dataStart() == null || input.latestActual() == null
                || input.trainingStart() == null || input.trainingEnd() == null
                || input.forecastStart() == null || input.forecastEnd() == null
                || input.actualStart() == null || input.actualEnd() == null || input.today() == null
                || input.actuals() == null || input.horizon() < 1) {
            throw new InvalidArgumentException("预测预览参数不完整");
        }
        if (input.trainingStart().isAfter(input.trainingEnd())
                || !input.trainingEnd().isBefore(input.forecastStart())
                || input.forecastEnd().isBefore(input.forecastStart())
                || input.actualEnd().isBefore(input.actualStart())) {
            throw new InvalidArgumentException("预测预览日期范围非法");
        }
    }

    /** 按仓库和方向组织日实际，后续所有关联均在内存批量完成，避免 N+1 查询。 */
    private Map<SeriesKey, Map<LocalDate, DailyActual>> groupActuals(List<DailyActual> actuals) {
        Map<SeriesKey, Map<LocalDate, DailyActual>> grouped = new LinkedHashMap<>();
        for (DailyActual actual : actuals) {
            SeriesKey key = new SeriesKey(actual.warehouseCode(), actual.direction());
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(actual.date(),
                    actual);
        }
        return grouped;
    }

    /** 汇总所有序列的逐日实际值，供 KPI 和总趋势复用。 */
    private Map<LocalDate, BigDecimal> aggregateActuals(List<DailyActual> actuals,
            ForecastMetric metric) {
        Map<LocalDate, BigDecimal> values = new LinkedHashMap<>();
        for (DailyActual actual : actuals) {
            values.merge(actual.date(), metricValue(actual, metric), BigDecimal::add);
        }
        return values;
    }

    /** 任务数是可选字段；没有产生过任务 ID 的序列不伪造任务数模型。 */
    private boolean supportsMetric(Map<LocalDate, DailyActual> actuals, ForecastMetric metric) {
        return metric == ForecastMetric.QUANTITY
                || actuals.values().stream().anyMatch(actual -> actual.taskCount() > 0L);
    }

    /** 找到训练窗口内第一条真实业务日，窗口前未知日期不补零。 */
    private LocalDate resolveSeriesStart(Map<LocalDate, DailyActual> actuals,
            LocalDate trainingStart, LocalDate trainingEnd) {
        return actuals.keySet().stream()
                .filter(date -> !date.isBefore(trainingStart) && !date.isAfter(trainingEnd))
                .min(LocalDate::compareTo).orElse(null);
    }

    /** 在已知训练窗口内连续补零，保留周周期和移动窗口的真实日间距。 */
    private List<BigDecimal> fillSeries(LocalDate start, LocalDate endExclusive,
            Map<LocalDate, DailyActual> actuals, ForecastMetric metric) {
        List<BigDecimal> values = new ArrayList<>();
        for (LocalDate date = start; date.isBefore(endExclusive); date = date.plusDays(1)) {
            DailyActual actual = actuals.get(date);
            values.add(actual == null ? BigDecimal.ZERO : metricValue(actual, metric));
        }
        return List.copyOf(values);
    }

    /** 将可见预测点按日期聚合，并在任一序列缺少区间时取消总区间，避免伪造完整置信带。 */
    private void accumulateForecast(LocalDate trainingEnd, List<ForecastPoint> points,
            Map<LocalDate, ForecastAccumulator> forecastByDate) {
        for (ForecastPoint point : points) {
            LocalDate date = trainingEnd.plusDays(point.offset());
            forecastByDate.computeIfAbsent(date, ignored -> new ForecastAccumulator()).add(point);
        }
    }

    /** 计算单序列回测指标。 */
    private Metrics calculateMetrics(LocalDate trainingEnd, List<ForecastPoint> points,
            Map<LocalDate, DailyActual> actuals, ForecastMetric metric) {
        List<BigDecimal> predicted = new ArrayList<>();
        List<BigDecimal> actual = new ArrayList<>();
        for (ForecastPoint point : points) {
            LocalDate date = trainingEnd.plusDays(point.offset());
            DailyActual dailyActual = actuals.get(date);
            predicted.add(point.point());
            actual.add(dailyActual == null ? BigDecimal.ZERO : metricValue(dailyActual, metric));
        }
        return calculateMetrics(actual, predicted);
    }

    /** 计算总趋势在回测日期内的加权误差。 */
    private Metrics calculateAggregateMetrics(Map<LocalDate, ForecastAccumulator> forecasts,
            Map<LocalDate, BigDecimal> actuals, LocalDate start, LocalDate end) {
        List<BigDecimal> predicted = new ArrayList<>();
        List<BigDecimal> actual = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ForecastAccumulator forecast = forecasts.get(date);
            predicted.add(forecast == null ? BigDecimal.ZERO : forecast.forecast());
            actual.add(actuals.getOrDefault(date, BigDecimal.ZERO));
        }
        return calculateMetrics(actual, predicted);
    }

    /** 使用统一分母计算 WAPE、MAE 和归一化 Bias。 */
    private Metrics calculateMetrics(List<BigDecimal> actual, List<BigDecimal> predicted) {
        if (actual.isEmpty() || actual.size() != predicted.size()) {
            return Metrics.empty();
        }
        BigDecimal absoluteError = BigDecimal.ZERO;
        BigDecimal actualTotal = BigDecimal.ZERO;
        BigDecimal predictedTotal = BigDecimal.ZERO;
        for (int index = 0; index < actual.size(); index++) {
            BigDecimal actualValue = actual.get(index);
            BigDecimal predictedValue = predicted.get(index);
            absoluteError = absoluteError.add(predictedValue.subtract(actualValue).abs());
            actualTotal = actualTotal.add(actualValue.abs());
            predictedTotal = predictedTotal.add(predictedValue);
        }
        BigDecimal mae = absoluteError.divide(BigDecimal.valueOf(actual.size()), METRIC_SCALE,
                RoundingMode.HALF_UP);
        if (actualTotal.signum() == 0) {
            return new Metrics(null, mae, null);
        }
        BigDecimal wape = absoluteError.divide(actualTotal, METRIC_SCALE, RoundingMode.HALF_UP);
        BigDecimal bias =
                predictedTotal.subtract(actual.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        .divide(actualTotal, METRIC_SCALE, RoundingMode.HALF_UP);
        return new Metrics(wape, mae, bias);
    }

    /** 正式/今日预览沿用各序列训练回测指标的简单平均，与决策库现有聚合口径一致。 */
    private Metrics averageMetrics(List<ForecastBreakdownResp> values) {
        return new Metrics(average(values.stream().map(ForecastBreakdownResp::getWape).toList()),
                average(values.stream().map(ForecastBreakdownResp::getMae).toList()),
                average(values.stream().map(ForecastBreakdownResp::getBias).toList()));
    }

    /** 忽略空指标并计算平均值。 */
    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> available = values.stream().filter(java.util.Objects::nonNull).toList();
        if (available.isEmpty()) {
            return null;
        }
        return available.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(available.size()), METRIC_SCALE, RoundingMode.HALF_UP);
    }

    /** 汇总实际对比区间。 */
    private BigDecimal sumActualRange(Map<LocalDate, BigDecimal> actuals, LocalDate start,
            LocalDate end) {
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            total = total.add(actuals.getOrDefault(date, BigDecimal.ZERO));
        }
        return total;
    }

    /** 合并历史实际、回测实际、预测点与经验区间为单一有序日期序列。 */
    private List<ForecastSeriesPointResp> buildSeries(Input input,
            Map<LocalDate, BigDecimal> actualByDate,
            Map<LocalDate, ForecastAccumulator> forecastByDate) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        LocalDate historyStart = input.trainingEnd().minusDays(HISTORY_DISPLAY_DAYS - 1L);
        if (historyStart.isBefore(input.trainingStart())) {
            historyStart = input.trainingStart();
        }
        for (LocalDate date = historyStart; !date.isAfter(input.trainingEnd()); date =
                date.plusDays(1)) {
            dates.add(date);
        }
        for (LocalDate date = input.forecastStart(); !date.isAfter(input.forecastEnd()); date =
                date.plusDays(1)) {
            dates.add(date);
        }
        List<ForecastSeriesPointResp> points = new ArrayList<>();
        for (LocalDate date : dates) {
            boolean showActual = !date.isAfter(input.trainingEnd())
                    || input.actualComparisonType() == ForecastActualComparisonType.FORECAST_PERIOD;
            ForecastAccumulator forecast = forecastByDate.get(date);
            points.add(new ForecastSeriesPointResp(date,
                    showActual ? actualByDate.getOrDefault(date, BigDecimal.ZERO) : null,
                    forecast == null ? null : forecast.forecast(),
                    forecast == null ? null : forecast.lower(),
                    forecast == null ? null : forecast.upper()));
        }
        return List.copyOf(points);
    }

    /** 返回两个数据状态中更保守的一个。 */
    private ForecastDataStatus worseStatus(ForecastDataStatus left, ForecastDataStatus right) {
        return statusRank(left) <= statusRank(right) ? left : right;
    }

    /** 状态值越小表示越不适合直接用于业务决策。 */
    private int statusRank(ForecastDataStatus status) {
        return switch (status) {
            case FAILED -> 0;
            case INSUFFICIENT_DATA -> 1;
            case STALE -> 2;
            case LOW_CONFIDENCE -> 3;
            case READY -> 4;
        };
    }

    /** 提取指定指标值。 */
    private BigDecimal metricValue(DailyActual actual, ForecastMetric metric) {
        return metric == ForecastMetric.QUANTITY ? actual.quantity()
                : BigDecimal.valueOf(actual.taskCount());
    }

    /**
     * 只读计算输入。
     *
     * @param profileId Profile ID。
     * @param metric 数量或任务数。
     * @param horizon 预测天数。
     * @param anchorMode 预测基准模式。
     * @param actualComparisonType 实际对比口径。
     * @param dataStart 数据集最早完整业务日。
     * @param latestActual 数据集最新完整业务日。
     * @param trainingStart 训练起始日。
     * @param trainingEnd 训练截止日。
     * @param forecastStart 预测首日。
     * @param forecastEnd 预测末日。
     * @param actualStart 实际 KPI 起始日。
     * @param actualEnd 实际 KPI 末日。
     * @param today Profile 时区下的今天。
     * @param lastSyncAt 最近同步时间。
     * @param lastForecastAt 最近正式预测时间。
     * @param actuals 一次批量查询得到的日实际快照。
     */
    public record Input(Long profileId, ForecastMetric metric, ForecastDirection direction,
            int horizon, ForecastAnchorMode anchorMode,
            ForecastActualComparisonType actualComparisonType, LocalDate dataStart,
            LocalDate latestActual, LocalDate trainingStart, LocalDate trainingEnd,
            LocalDate forecastStart, LocalDate forecastEnd, LocalDate actualStart,
            LocalDate actualEnd, LocalDate today, Instant lastSyncAt, Instant lastForecastAt,
            List<DailyActual> actuals) {}

    /** 仓库方向序列键。 */
    private record SeriesKey(String warehouseCode, ForecastDirection direction) {

        /** 生成与正式 Worker 一致的稳定模型序列键。 */
        private String stableKey(ForecastMetric metric) {
            return warehouseCode + '|' + direction.name() + '|' + metric.name();
        }
    }

    /** 单次误差指标。 */
    private record Metrics(BigDecimal wape, BigDecimal mae, BigDecimal bias) {

        /** 返回没有可比较样本时的空指标。 */
        private static Metrics empty() {
            return new Metrics(null, null, null);
        }
    }

    /** 多序列逐日预测累加器，仅在单次请求线程内使用。 */
    private static final class ForecastAccumulator {

        private BigDecimal forecast = BigDecimal.ZERO;
        private BigDecimal lower = BigDecimal.ZERO;
        private BigDecimal upper = BigDecimal.ZERO;
        private boolean intervalComplete = true;

        /** 累加一个序列点；任一序列无区间时，总区间保持为空。 */
        private void add(ForecastPoint point) {
            forecast = forecast.add(point.point());
            if (point.lower() == null || point.upper() == null) {
                intervalComplete = false;
            } else {
                lower = lower.add(point.lower());
                upper = upper.add(point.upper());
            }
        }

        /** 返回聚合预测值。 */
        private BigDecimal forecast() {
            return forecast;
        }

        /** 返回完整区间下界；任一序列缺失时为空。 */
        private BigDecimal lower() {
            return intervalComplete ? lower : null;
        }

        /** 返回完整区间上界；任一序列缺失时为空。 */
        private BigDecimal upper() {
            return intervalComplete ? upper : null;
        }
    }
}
