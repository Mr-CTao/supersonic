package com.tencent.supersonic.forecast.api.spi;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.model.ForecastAlgorithmResult;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;

/**
 * 可替换的单变量日序列预测算法。
 */
public interface ForecastAlgorithm {

    /**
     * 返回稳定算法标识。
     *
     * @return 算法类型。
     */
    ForecastAlgorithmType type();

    /**
     * 对尾部窗口滚动回测并生成未来预测。
     *
     * @param series 连续日序列。
     * @param validationDays 回测日数。
     * @param horizon 未来预测日数。
     * @return 候选算法结果。
     * @throws IllegalArgumentException 参数或样本不足以运行该算法。
     */
    ForecastAlgorithmResult evaluateAndForecast(ForecastSeries series, int validationDays,
            int horizon);
}
