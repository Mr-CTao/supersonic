package com.tencent.supersonic.forecast.core.algorithm;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.model.ForecastAlgorithmResult;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import com.tencent.supersonic.forecast.api.spi.ForecastAlgorithm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用七天前同星期值的周期朴素算法。
 *
 * <p>
 * 该算法对周节奏明显的出入库序列提供低复杂度基线，无共享可变状态，线程安全。
 * </p>
 */
public class SeasonalNaiveAlgorithm implements ForecastAlgorithm {

    private static final int PERIOD = 7;

    /** {@inheritDoc} */
    @Override
    public ForecastAlgorithmType type() {
        return ForecastAlgorithmType.SEASONAL_NAIVE_7;
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
            BigDecimal prediction =
                    ForecastMath.nonNegative(ForecastMath.value(values.get(index - PERIOD)));
            validationPredictions.add(prediction);
            residuals.add(values.get(index).subtract(prediction));
        }

        List<BigDecimal> extended = new ArrayList<>(values);
        List<BigDecimal> future = new ArrayList<>(horizon);
        for (int offset = 0; offset < horizon; offset++) {
            BigDecimal prediction = ForecastMath
                    .nonNegative(ForecastMath.value(extended.get(extended.size() - PERIOD)));
            future.add(prediction);
            extended.add(prediction);
        }
        return new ForecastAlgorithmResult(type(), validationPredictions, future, residuals);
    }

    /** 校验周期朴素算法运行所需的最小样本。 */
    private void validate(ForecastSeries series, int validationDays, int horizon) {
        if (series == null || series.values() == null || validationDays < 1 || horizon < 1
                || series.values().size() - validationDays < PERIOD) {
            throw new IllegalArgumentException("周期朴素算法至少需要回测窗口前七天样本");
        }
    }
}
