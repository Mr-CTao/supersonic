/**
 * 预测趋势图展示窗口辅助模块。
 *
 * 职责：从服务端返回的较长历史序列中截取与预测/回测区间等长的历史展示窗口，并计算
 * “近期历史（训练区间末 N 天）”和预测区间说明在分类轴上的居中位置。该模块只改变
 * 图表展示范围，不参与模型训练、预测计算或指标统计。
 */
import type { ForecastSeriesPoint } from '@/services/forecast';

export type ForecastTrendWindow = {
  data: ForecastSeriesPoint[];
  forecastStartIndex?: number;
  /** 实际绘制在预测分界线左侧的连续历史自然日数量。 */
  historyPointCount?: number;
  historyLabelIndex?: number;
  comparisonLabelIndex?: number;
};

/**
 * 构建与预测区间等长的趋势图历史展示窗口。
 *
 * @param data 服务端按日期升序返回的历史和预测点。
 * @param forecastStartDate 预测或回测起始日，格式为 YYYY-MM-DD。
 * @returns 截取后的图表点、历史展示天数，以及分界点和两个区间标签的分类轴索引；
 * 缺少有效分界时返回原数据。
 * @throws 不主动抛出异常；空数组或无效分界按原数据安全降级。
 *
 * @example
 * 30 个历史点加 7 个回测点会收敛为“最近 7 个历史点 + 7 个回测点”，避免历史区间
 * 过长压缩回测曲线和点位标签。
 */
export function buildForecastTrendWindow(
  data: ForecastSeriesPoint[],
  forecastStartDate?: string,
): ForecastTrendWindow {
  if (!forecastStartDate || data.length === 0) return { data };

  const forecastStartIndex = data.findIndex((item) => item.date >= forecastStartDate);
  if (forecastStartIndex <= 0 || forecastStartIndex >= data.length) return { data };

  // 用预测区间天数反推历史展示天数，使左右两段在图上等宽，便于直观看出回测偏差。
  const comparisonPointCount = data.length - forecastStartIndex;
  const visibleHistoryStartIndex = Math.max(0, forecastStartIndex - comparisonPointCount);
  const visibleData = data.slice(visibleHistoryStartIndex);
  const visibleForecastStartIndex = forecastStartIndex - visibleHistoryStartIndex;
  const comparisonLastIndex = visibleData.length - 1;

  return {
    data: visibleData,
    forecastStartIndex: visibleForecastStartIndex,
    historyPointCount: visibleForecastStartIndex,
    historyLabelIndex: Math.floor((visibleForecastStartIndex - 1) / 2),
    comparisonLabelIndex:
      visibleForecastStartIndex + Math.floor((comparisonLastIndex - visibleForecastStartIndex) / 2),
  };
}
