package com.tencent.supersonic.forecast.api.enums;

/**
 * 预测看板的时间基准模式。
 *
 * <p>
 * 该枚举只描述用户查看或生成预测时的日期语义，不改变数据同步范围。历史回测与今日预览 都是只读计算，不会覆盖 Profile 当前已发布的正式预测结果。
 */
public enum ForecastAnchorMode {

    /** 跟随最新完整业务日，预测首日为该业务日的次日。 */
    LATEST_DATA,

    /** 以用户选择的历史日期为预测首日，并用后续已知实际数据评估效果。 */
    BACKTEST,

    /** 以 Profile 时区下的今天为预测首日。 */
    TODAY
}
