package com.tencent.supersonic.forecast.core.algorithm;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.model.ForecastAlgorithmResult;
import com.tencent.supersonic.forecast.api.model.ForecastModelSelection;
import com.tencent.supersonic.forecast.api.model.ForecastPoint;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import com.tencent.supersonic.forecast.api.spi.ForecastAlgorithm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对内置 Java 算法执行滚动回测并自动选择最优模型。
 *
 * <p>
 * Selector 仅持有不可变算法列表，不缓存序列或结果，可安全被多线程 Worker 共享。
 * </p>
 */
public class ForecastModelSelector {

    private static final int MINIMUM_DAYS = 14;
    private static final int FULL_SELECTION_DAYS = 28;
    private static final int DEFAULT_HORIZON = 30;
    private static final int MIN_INTERVAL_RESIDUALS = 14;

    private final List<ForecastAlgorithm> algorithms;

    /**
     * 使用 MVP 固定候选集合创建 Selector。
     */
    public ForecastModelSelector() {
        this(defaultAlgorithms());
    }

    /**
     * 使用给定候选算法创建 Selector，主要供扩展和测试。
     *
     * @param algorithms 非空候选集合。
     * @throws IllegalArgumentException 集合为空。
     */
    public ForecastModelSelector(List<ForecastAlgorithm> algorithms) {
        if (algorithms == null || algorithms.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个预测算法");
        }
        this.algorithms = List.copyOf(algorithms);
    }

    /**
     * 按 MVP 冷启动和回测规则选择模型并生成未来三十天结果。
     *
     * @param series 连续日序列。
     * @return 可持久化选模结果。
     * @throws IllegalArgumentException 序列为空、包含负值或 horizon 非法。
     */
    public ForecastModelSelection select(ForecastSeries series) {
        return select(series, DEFAULT_HORIZON);
    }

    /**
     * 按指定未来天数选择模型。
     *
     * @param series 连续日序列。
     * @param horizon 未来天数。
     * @return 选模结果。
     * @throws IllegalArgumentException 参数非法。
     */
    public ForecastModelSelection select(ForecastSeries series, int horizon) {
        validateSeries(series, horizon);
        int sampleSize = series.values().size();
        if (sampleSize < MINIMUM_DAYS) {
            return new ForecastModelSelection(null, ForecastDataStatus.INSUFFICIENT_DATA, List.of(),
                    null, null, null, sampleSize, 0);
        }

        int validationDays = sampleSize < FULL_SELECTION_DAYS ? 7
                : Math.max(7, Math.min(28, (int) Math.floor(sampleSize * 0.2D)));
        List<ForecastAlgorithm> candidates = sampleSize < FULL_SELECTION_DAYS ? algorithms.stream()
                .filter(item -> item.type() == ForecastAlgorithmType.MOVING_AVERAGE_7).toList()
                : algorithms;
        List<ScoredCandidate> scored = new ArrayList<>();
        for (ForecastAlgorithm algorithm : candidates) {
            try {
                ForecastAlgorithmResult result =
                        algorithm.evaluateAndForecast(series, validationDays, horizon);
                scored.add(score(series, result, validationDays));
            } catch (IllegalArgumentException ignored) {
                // 候选窗口需要更多历史时跳过，不能使其他可用序列失败。
            }
        }
        if (scored.isEmpty()) {
            return new ForecastModelSelection(null, ForecastDataStatus.INSUFFICIENT_DATA, List.of(),
                    null, null, null, sampleSize, validationDays);
        }

        ScoredCandidate selected = scored.stream().min(candidateComparator()).orElseThrow();
        List<ForecastPoint> points = intervalPoints(selected.result());
        ForecastDataStatus status =
                sampleSize < FULL_SELECTION_DAYS ? ForecastDataStatus.LOW_CONFIDENCE
                        : ForecastDataStatus.READY;
        return new ForecastModelSelection(selected.result().algorithm(), status, points,
                selected.wape(), selected.mae(), selected.bias(), sampleSize, validationDays);
    }

    /** 构造计划明确的固定算法候选。 */
    private static List<ForecastAlgorithm> defaultAlgorithms() {
        return List.of(new SeasonalNaiveAlgorithm(),
                new MovingAverageAlgorithm(7, ForecastAlgorithmType.MOVING_AVERAGE_7),
                new MovingAverageAlgorithm(14, ForecastAlgorithmType.MOVING_AVERAGE_14),
                new MovingAverageAlgorithm(28, ForecastAlgorithmType.MOVING_AVERAGE_28),
                new SimpleExponentialSmoothingAlgorithm(0.2D,
                        ForecastAlgorithmType.SIMPLE_EXPONENTIAL_SMOOTHING_02),
                new SimpleExponentialSmoothingAlgorithm(0.4D,
                        ForecastAlgorithmType.SIMPLE_EXPONENTIAL_SMOOTHING_04),
                new SimpleExponentialSmoothingAlgorithm(0.6D,
                        ForecastAlgorithmType.SIMPLE_EXPONENTIAL_SMOOTHING_06),
                new SimpleExponentialSmoothingAlgorithm(0.8D,
                        ForecastAlgorithmType.SIMPLE_EXPONENTIAL_SMOOTHING_08));
    }

    /** 校验输入，负值在进入算法前即被拒绝。 */
    private void validateSeries(ForecastSeries series, int horizon) {
        if (series == null || series.values() == null || horizon < 1) {
            throw new IllegalArgumentException("预测序列和未来天数不能为空");
        }
        if (series.values().stream().anyMatch(value -> value == null || value.signum() < 0)) {
            throw new IllegalArgumentException("预测序列只能包含非负数值");
        }
    }

    /** 计算候选的 WAPE、MAE 和归一化 Bias。 */
    private ScoredCandidate score(ForecastSeries series, ForecastAlgorithmResult result,
            int validationDays) {
        List<BigDecimal> actuals = series.values().subList(series.values().size() - validationDays,
                series.values().size());
        double absoluteError = 0D;
        double signedForecastError = 0D;
        double actualSum = 0D;
        for (int index = 0; index < validationDays; index++) {
            double actual = ForecastMath.value(actuals.get(index));
            double predicted = ForecastMath.value(result.validationPredictions().get(index));
            absoluteError += Math.abs(actual - predicted);
            signedForecastError += predicted - actual;
            actualSum += actual;
        }
        BigDecimal mae = decimal(absoluteError / validationDays);
        BigDecimal wape = actualSum == 0D ? null : decimal(absoluteError / actualSum);
        BigDecimal bias = actualSum == 0D ? decimal(0D) : decimal(signedForecastError / actualSum);
        BigDecimal primary = wape == null ? mae : wape;
        return new ScoredCandidate(result, primary, wape, mae, bias);
    }

    /** 先按主指标，再按绝对 Bias，最后按算法枚举顺序稳定选择。 */
    private Comparator<ScoredCandidate> candidateComparator() {
        return Comparator.comparing(ScoredCandidate::primary)
                .thenComparing(candidate -> candidate.bias().abs())
                .thenComparing(candidate -> candidate.result().algorithm().ordinal());
    }

    /** 使用残差 P10/P90 生成非负经验区间。 */
    private List<ForecastPoint> intervalPoints(ForecastAlgorithmResult result) {
        BigDecimal lowerResidual = null;
        BigDecimal upperResidual = null;
        if (result.residuals().size() >= MIN_INTERVAL_RESIDUALS) {
            List<BigDecimal> sorted = result.residuals().stream().sorted().toList();
            lowerResidual = percentile(sorted, 0.10D);
            upperResidual = percentile(sorted, 0.90D);
        }
        List<ForecastPoint> points = new ArrayList<>(result.futureValues().size());
        for (int index = 0; index < result.futureValues().size(); index++) {
            BigDecimal point = result.futureValues().get(index);
            BigDecimal lower = lowerResidual == null ? null
                    : point.add(lowerResidual).max(BigDecimal.ZERO).setScale(ForecastMath.SCALE,
                            RoundingMode.HALF_UP);
            BigDecimal upper = upperResidual == null ? null
                    : point.add(upperResidual).max(BigDecimal.ZERO).setScale(ForecastMath.SCALE,
                            RoundingMode.HALF_UP);
            points.add(new ForecastPoint(index + 1, point, lower, upper));
        }
        return List.copyOf(points);
    }

    /** 使用线性插值计算确定性百分位。 */
    private BigDecimal percentile(List<BigDecimal> sorted, double percentile) {
        double position = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = position - lower;
        double value = ForecastMath.value(sorted.get(lower)) * (1D - fraction)
                + ForecastMath.value(sorted.get(upper)) * fraction;
        return decimal(value);
    }

    /** 统一指标精度。 */
    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(ForecastMath.SCALE, RoundingMode.HALF_UP);
    }

    /** 单个候选的内部评分，不越过公共 API 边界。 */
    private record ScoredCandidate(ForecastAlgorithmResult result, BigDecimal primary,
            BigDecimal wape, BigDecimal mae, BigDecimal bias) {}
}
