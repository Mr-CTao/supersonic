package com.tencent.supersonic.forecast.core.algorithm;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.model.ForecastAlgorithmResult;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import com.tencent.supersonic.forecast.api.spi.ForecastAlgorithm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口移动平均算法。
 *
 * <p>
 * 实例在构造后不可变，不缓存训练结果，可由多个 Worker 线程安全复用。
 * </p>
 */
public class MovingAverageAlgorithm implements ForecastAlgorithm {

    private final int window;
    private final ForecastAlgorithmType type;

    /**
     * 创建移动平均算法。
     *
     * @param window 窗口日数，只允许 7、14、28。
     * @param type 与窗口对应的稳定算法标识。
     * @throws IllegalArgumentException 窗口与标识不匹配。
     */
    public MovingAverageAlgorithm(int window, ForecastAlgorithmType type) {
        if (window <= 0 || type == null) {
            throw new IllegalArgumentException("移动平均窗口和算法标识不能为空");
        }
        this.window = window;
        this.type = type;
    }

    /** {@inheritDoc} */
    @Override
    public ForecastAlgorithmType type() {
        return type;
    }

    /** {@inheritDoc} */
    @Override
    public ForecastAlgorithmResult evaluateAndForecast(ForecastSeries series, int validationDays,
            int horizon) {
        validate(series, validationDays, horizon);
        List<BigDecimal> values = series.values();
        int validationStart = values.size() - validationDays;
        List<BigDecimal> validationPredictions = new ArrayList<>(validationDays);
        List<BigDecimal> residuals = new ArrayList<>(validationDays);
        for (int index = validationStart; index < values.size(); index++) {
            BigDecimal prediction = mean(values, index - window, index);
            validationPredictions.add(prediction);
            residuals.add(values.get(index).subtract(prediction));
        }

        List<BigDecimal> extended = new ArrayList<>(values);
        List<BigDecimal> future = new ArrayList<>(horizon);
        for (int offset = 0; offset < horizon; offset++) {
            BigDecimal prediction = mean(extended, extended.size() - window, extended.size());
            future.add(prediction);
            extended.add(prediction);
        }
        return new ForecastAlgorithmResult(type(), validationPredictions, future, residuals);
    }

    /** 计算左闭右开窗口的非负均值。 */
    private BigDecimal mean(List<BigDecimal> values, int from, int to) {
        double sum = 0D;
        for (int index = from; index < to; index++) {
            sum += ForecastMath.value(values.get(index));
        }
        return ForecastMath.nonNegative(sum / (to - from));
    }

    /** 校验移动平均所需的前置样本。 */
    private void validate(ForecastSeries series, int validationDays, int horizon) {
        if (series == null || series.values() == null || validationDays < 1 || horizon < 1
                || series.values().size() - validationDays < window) {
            throw new IllegalArgumentException("移动平均算法的回测窗口前样本不足: " + window);
        }
    }
}
