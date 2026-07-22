/**
 * 预测看板日期边界与业务文案单元测试。
 *
 * 职责：锁定历史回测与自定义训练不会越过当前方向的实际数据边界，也不会在训练不足
 * 14 天或存在数据泄漏时允许生成结果。
 */
import type { ForecastOverviewSnapshot } from '@/services/forecast';
import dayjs from 'dayjs';
import {
  formatCompactForecastDateRange,
  formatForecastDateRange,
  getForecastBiasDirection,
  getBusinessDataFreshness,
  getMaximumTrainingEnd,
  getRecommendedBacktestStart,
  getTrainingRangeValidationMessage,
  isBacktestDateDisabled,
  isTrainingDateDisabled,
  summarizeForecastAlgorithms,
} from './forecastOverview';

const SNAPSHOT = {
  dataStartDate: '2025-01-01',
  latestActualDate: '2025-03-31',
  forecastStartDate: '2025-04-01',
} as ForecastOverviewSnapshot;

describe('forecastOverview', () => {
  it('推荐最近一个完整七日回测窗口', () => {
    expect(getRecommendedBacktestStart('2025-03-31', 7, '2025-01-01')?.format('YYYY-MM-DD')).toBe(
      '2025-03-25',
    );
  });

  it('训练窗口和实际窗口无法同时满足时不推荐无效日期', () => {
    expect(getRecommendedBacktestStart('2025-11-15', 14, '2025-10-20')).toBeUndefined();
  });

  it('禁用训练不足和实际窗口不完整的日期', () => {
    expect(isBacktestDateDisabled(dayjs('2025-01-14'), SNAPSHOT, 7)).toBe(true);
    expect(isBacktestDateDisabled(dayjs('2025-01-15'), SNAPSHOT, 7)).toBe(false);
    expect(isBacktestDateDisabled(dayjs('2025-03-25'), SNAPSHOT, 7)).toBe(false);
    expect(isBacktestDateDisabled(dayjs('2025-03-26'), SNAPSHOT, 7)).toBe(true);
  });

  it('自定义训练截止日会同步收紧回测起点', () => {
    const customTrainingEnd = dayjs('2025-02-10');
    expect(isBacktestDateDisabled(dayjs('2025-02-10'), SNAPSHOT, 7, customTrainingEnd)).toBe(true);
    expect(isBacktestDateDisabled(dayjs('2025-02-11'), SNAPSHOT, 7, customTrainingEnd)).toBe(false);
  });

  it('同年日期范围仅在起点展示年份', () => {
    expect(formatForecastDateRange('2025-09-01', '2025-09-07')).toBe('2025-09-01 至 09-07');
  });

  it('基准状态栏日期使用紧凑且不可拆分的区间连接符', () => {
    expect(formatCompactForecastDateRange('2025-09-01', '2025-09-07')).toBe('2025-09-01～09-07');
    expect(formatCompactForecastDateRange('2025-12-31', '2026-01-01')).toBe(
      '2025-12-31～2026-01-01',
    );
  });

  it('区分同步状态之外的业务数据新鲜度', () => {
    expect(getBusinessDataFreshness(1)).toEqual({ color: 'success', label: '业务数据及时' });
    expect(getBusinessDataFreshness(294)).toEqual({ color: 'warning', label: '业务数据较旧' });
  });

  it('将单一算法转换为业务可读名称', () => {
    expect(summarizeForecastAlgorithms('MOVING_AVERAGE_7')).toEqual({
      label: '近 7 天移动平均',
      rawNames: ['MOVING_AVERAGE_7'],
    });
  });

  it('多个仓库方向使用不同算法时汇总为混合模型并去重', () => {
    expect(
      summarizeForecastAlgorithms(undefined, [
        'MOVING_AVERAGE_7',
        'SEASONAL_NAIVE_7',
        'MOVING_AVERAGE_7',
      ]),
    ).toEqual({
      label: '混合模型（2 种）',
      rawNames: ['MOVING_AVERAGE_7', 'SEASONAL_NAIVE_7'],
    });
  });

  it('将 Bias 正负号转换为业务方向说明', () => {
    expect(getForecastBiasDirection(0.12)).toBe('预测偏高');
    expect(getForecastBiasDirection(-0.12)).toBe('预测偏低');
    expect(getForecastBiasDirection(0)).toBe('基本持平');
    expect(getForecastBiasDirection()).toBeUndefined();
  });

  it('训练截止日取最新实际与预测前一日中的较早值', () => {
    expect(getMaximumTrainingEnd(SNAPSHOT)?.format('YYYY-MM-DD')).toBe('2025-03-31');
    expect(getMaximumTrainingEnd(SNAPSHOT, dayjs('2025-03-25'))?.format('YYYY-MM-DD')).toBe(
      '2025-03-24',
    );
  });

  it('训练日期只允许落在所选方向边界且早于预测起点', () => {
    const forecastStart = dayjs('2025-03-25');
    expect(isTrainingDateDisabled(dayjs('2024-12-31'), SNAPSHOT, forecastStart)).toBe(true);
    expect(isTrainingDateDisabled(dayjs('2025-03-24'), SNAPSHOT, forecastStart)).toBe(false);
    expect(isTrainingDateDisabled(dayjs('2025-03-25'), SNAPSHOT, forecastStart)).toBe(true);
  });

  it('自定义训练区间按含首尾日期接受十四日并拒绝十三日', () => {
    expect(
      getTrainingRangeValidationMessage(
        [dayjs('2025-01-18'), dayjs('2025-01-31')],
        SNAPSHOT,
        dayjs('2025-02-01'),
      ),
    ).toBeUndefined();
    expect(
      getTrainingRangeValidationMessage(
        [dayjs('2025-01-19'), dayjs('2025-01-31')],
        SNAPSHOT,
        dayjs('2025-02-01'),
      ),
    ).toContain('至少需要 14 个自然日');
  });

  it('自定义训练区间拒绝方向数据边界之外和预测起点之后的日期', () => {
    expect(
      getTrainingRangeValidationMessage(
        [dayjs('2024-12-31'), dayjs('2025-01-31')],
        SNAPSHOT,
        dayjs('2025-02-01'),
      ),
    ).toContain('必须位于');
    expect(
      getTrainingRangeValidationMessage(
        [dayjs('2025-02-01'), dayjs('2025-03-01')],
        SNAPSHOT,
        dayjs('2025-03-01'),
      ),
    ).toContain('避免未来数据泄漏');
  });
});
