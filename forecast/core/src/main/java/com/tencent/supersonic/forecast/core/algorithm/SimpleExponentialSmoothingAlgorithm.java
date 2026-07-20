package com.tencent.supersonic.forecast.core.algorithm;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.model.ForecastAlgorithmResult;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import com.tencent.supersonic.forecast.api.spi.ForecastAlgorithm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 简单指数平滑算法。
 *
 * <p>
 * 适合无明显趋势的短期日序列；alpha 和类型在构造后不可变，因此实例线程安全。
 * </p>
 */
public class SimpleExponentialSmoothingAlgorithm implements ForecastAlgorithm {

    private final double alpha;
    private final ForecastAlgorithmType type;

    /**
     * 创建指数平滑候选。
     *
     * @param alpha 平滑系数，必须位于 (0,1]。
     * @param type 稳定算法标识。
     * @throws IllegalArgumentException 参数非法。
     */
    public SimpleExponentialSmoothingAlgorithm(double alpha, ForecastAlgorithmType type) {
        if (!(alpha > 0D && alpha <= 1D) || type == null) {
            throw new IllegalArgumentException("指数平滑 alpha 必须位于 (0,1]");
        }
        this.alpha = alpha;
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
        double level = ForecastMath.value(values.get(0));
        for (int index = 1; index < validationStart; index++) {
            level = smooth(level, ForecastMath.value(values.get(index)));
        }

        List<BigDecimal> validationPredictions = new ArrayList<>(validationDays);
        List<BigDecimal> residuals = new ArrayList<>(validationDays);
        for (int index = validationStart; index < values.size(); index++) {
            BigDecimal prediction = ForecastMath.nonNegative(level);
            validationPredictions.add(prediction);
            residuals.add(values.get(index).subtract(prediction));
            level = smooth(level, ForecastMath.value(values.get(index)));
        }

        BigDecimal futureValue = ForecastMath.nonNegative(level);
        List<BigDecimal> future = new ArrayList<>(horizon);
        for (int offset = 0; offset < horizon; offset++) {
            future.add(futureValue);
        }
        return new ForecastAlgorithmResult(type(), validationPredictions, future, residuals);
    }

    /** 使用当前实际值更新平滑水平。 */
    private double smooth(double previousLevel, double actual) {
        return alpha * actual + (1D - alpha) * previousLevel;
    }

    /** 校验指数平滑运行参数。 */
    private void validate(ForecastSeries series, int validationDays, int horizon) {
        if (series == null || series.values() == null || validationDays < 1 || horizon < 1
                || series.values().size() - validationDays < 1) {
            throw new IllegalArgumentException("指数平滑算法样本不足");
        }
    }
}
