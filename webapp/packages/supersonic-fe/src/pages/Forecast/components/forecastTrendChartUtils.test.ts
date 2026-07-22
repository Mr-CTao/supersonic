/**
 * 预测趋势图展示窗口单元测试。
 *
 * 职责：锁定历史与预测/回测区间等长的展示规则，以及区间说明文字的居中索引。
 */
import type { ForecastSeriesPoint } from '@/services/forecast';
import dayjs from 'dayjs';
import { buildForecastTrendWindow } from './forecastTrendChartUtils';

/** 创建仅包含日期的趋势点，保持测试数据聚焦于展示窗口规则。 */
function buildPoints(startDate: string, count: number): ForecastSeriesPoint[] {
  return Array.from({ length: count }, (_, index) => ({
    date: dayjs(startDate).add(index, 'day').format('YYYY-MM-DD'),
    actual: index,
    forecast: index,
  }));
}

describe('forecastTrendChart', () => {
  it('七日回测只保留回测前七日历史并计算两个区间中心', () => {
    const data = buildPoints('2025-10-10', 37);

    const result = buildForecastTrendWindow(data, '2025-11-09');

    expect(result.data).toHaveLength(14);
    expect(result.data[0].date).toBe('2025-11-02');
    expect(result.forecastStartIndex).toBe(7);
    expect(result.historyPointCount).toBe(7);
    expect(result.historyLabelIndex).toBe(3);
    expect(result.comparisonLabelIndex).toBe(10);
  });

  it('历史点不足预测区间长度时保留全部已有历史', () => {
    const data = buildPoints('2025-11-06', 10);

    const result = buildForecastTrendWindow(data, '2025-11-09');

    expect(result.data).toHaveLength(10);
    expect(result.forecastStartIndex).toBe(3);
    expect(result.historyPointCount).toBe(3);
    expect(result.historyLabelIndex).toBe(1);
    expect(result.comparisonLabelIndex).toBe(6);
  });

  it('缺少有效预测分界时按原序列安全降级', () => {
    const data = buildPoints('2025-11-01', 7);

    expect(buildForecastTrendWindow(data).data).toBe(data);
    expect(buildForecastTrendWindow(data, '2025-12-01').data).toBe(data);
  });
});
