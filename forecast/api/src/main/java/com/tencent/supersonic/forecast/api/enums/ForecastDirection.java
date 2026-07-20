package com.tencent.supersonic.forecast.api.enums;

/**
 * 预测数据的标准出入库方向。
 *
 * <p>
 * 职责：屏蔽客户源系统中方向编码差异，作为标准事件、日汇总和预测结果的统一维度。
 * </p>
 */
public enum ForecastDirection {
    INBOUND, OUTBOUND
}
