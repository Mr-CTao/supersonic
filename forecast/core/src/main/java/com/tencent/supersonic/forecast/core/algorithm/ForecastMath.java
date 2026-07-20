package com.tencent.supersonic.forecast.core.algorithm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 预测算法共享的数值规范化工具。
 *
 * <p>
 * 职责：统一精度、非负约束和 double/BigDecimal 转换，避免不同算法产生不可比较的舍入行为。
 * </p>
 */
final class ForecastMath {

    static final int SCALE = 6;

    private ForecastMath() {}

    /** 将任意数值规范为非负六位小数。 */
    static BigDecimal nonNegative(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("预测值必须是有限数值");
        }
        return BigDecimal.valueOf(Math.max(0D, value)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 将 BigDecimal 安全转换为 double。 */
    static double value(BigDecimal number) {
        return number == null ? 0D : number.doubleValue();
    }
}
