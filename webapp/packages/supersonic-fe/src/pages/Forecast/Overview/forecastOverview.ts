/**
 * 预测看板日期与文案辅助模块。
 *
 * 职责：集中处理回测可选范围、推荐日期、日期区间文案和业务数据新鲜度，保证页面组件只负责
 * 状态编排，不重复散落日期魔法数字。
 */
import type { ForecastOverviewSnapshot } from '@/services/forecast';
import dayjs, { type Dayjs } from 'dayjs';

export const MINIMUM_TRAINING_DAYS = 14;
export const RECENT_BUSINESS_DATA_DAYS = 1;
export type ForecastTrainingRange = [Dayjs, Dayjs];

export type ForecastAlgorithmSummary = {
  label: string;
  rawNames: string[];
};

const FORECAST_ALGORITHM_LABELS: Record<string, string> = {
  SEASONAL_NAIVE_7: '参考上周同日',
  MOVING_AVERAGE_7: '近 7 天移动平均',
  MOVING_AVERAGE_14: '近 14 天移动平均',
  MOVING_AVERAGE_28: '近 28 天移动平均',
  SIMPLE_EXPONENTIAL_SMOOTHING_02: '指数平滑（敏感度 20%）',
  SIMPLE_EXPONENTIAL_SMOOTHING_04: '指数平滑（敏感度 40%）',
  SIMPLE_EXPONENTIAL_SMOOTHING_06: '指数平滑（敏感度 60%）',
  SIMPLE_EXPONENTIAL_SMOOTHING_08: '指数平滑（敏感度 80%）',
};

/**
 * 汇总当前快照实际使用的预测算法，并转换为业务可读名称。
 *
 * @param summaryAlgorithm 快照级算法；仅所有序列算法一致时通常有值。
 * @param breakdownAlgorithms 仓库方向明细中的算法列表。
 * @returns 单算法返回通俗名称，多算法返回“混合模型（N 种）”，同时保留原始标识供悬浮查看。
 * @throws 不主动抛出异常；空值和未知算法均按可解释文本安全降级。
 */
export function summarizeForecastAlgorithms(
  summaryAlgorithm?: string,
  breakdownAlgorithms: Array<string | undefined | null> = [],
): ForecastAlgorithmSummary {
  const rawNames = Array.from(
    new Set(
      [summaryAlgorithm, ...breakdownAlgorithms].filter((algorithm): algorithm is string =>
        Boolean(algorithm),
      ),
    ),
  );
  if (rawNames.length === 0) return { label: '-', rawNames };
  if (rawNames.length > 1) return { label: `混合模型（${rawNames.length} 种）`, rawNames };
  return {
    label: FORECAST_ALGORITHM_LABELS[rawNames[0]] ?? rawNames[0],
    rawNames,
  };
}

/**
 * 将归一化 Bias 转换为无需统计背景即可理解的方向说明。
 *
 * @param bias 预测总量减实际总量后除以实际总量的结果。
 * @returns 正数为“预测偏高”，负数为“预测偏低”，零为“基本持平”；缺失时不展示说明。
 * @throws 不主动抛出异常；空值直接返回 undefined。
 */
export function getForecastBiasDirection(bias?: number): string | undefined {
  if (bias == null) return undefined;
  if (bias > 0) return '预测偏高';
  if (bias < 0) return '预测偏低';
  return '基本持平';
}

/**
 * 返回最近一个能够覆盖完整实际区间的回测起点。
 *
 * @param latestActualDate 最新完整业务日。
 * @param horizon 回测天数。
 * @param dataStartDate 最早业务日；提供后会同时校验至少 14 日训练窗口。
 * @returns 推荐起始日；日期缺失时为空。
 */
export function getRecommendedBacktestStart(
  latestActualDate: string | undefined,
  horizon: number,
  dataStartDate?: string,
): Dayjs | undefined {
  if (!latestActualDate || horizon < 1) return undefined;
  const recommended = dayjs(latestActualDate).subtract(horizon - 1, 'day');
  if (!dataStartDate) return recommended;
  const earliest = dayjs(dataStartDate).add(MINIMUM_TRAINING_DAYS, 'day');
  return recommended.isBefore(earliest, 'day') ? undefined : recommended;
}

/**
 * 判断日期是否超出“至少 14 日训练 + 完整回测实际”的安全范围。
 *
 * @param date 待判断日期。
 * @param snapshot 当前看板数据边界。
 * @param horizon 回测天数。
 * @param customTrainingEnd 可选的自定义训练截止日；存在时预测起点必须晚于该日。
 * @returns true 表示日期不可选。
 */
export function isBacktestDateDisabled(
  date: Dayjs,
  snapshot: ForecastOverviewSnapshot | undefined,
  horizon: number,
  customTrainingEnd?: Dayjs,
): boolean {
  if (!snapshot?.dataStartDate || !snapshot.latestActualDate) return true;
  let earliest = dayjs(snapshot.dataStartDate).add(MINIMUM_TRAINING_DAYS, 'day');
  const dayAfterCustomTraining = customTrainingEnd?.add(1, 'day');
  if (dayAfterCustomTraining?.isAfter(earliest, 'day')) {
    earliest = dayAfterCustomTraining;
  }
  const latest = dayjs(snapshot.latestActualDate).subtract(horizon - 1, 'day');
  return date.isBefore(earliest, 'day') || date.isAfter(latest, 'day');
}

/**
 * 计算当前统计口径下允许选择的最晚训练截止日。
 *
 * @param snapshot 当前方向返回的数据边界与预测日期。
 * @param forecastStartDate 可选的表单预测起点；历史回测草稿优先使用该值。
 * @returns “最新实际日”和“预测起点前一日”中的较早者；上下文不完整时为空。
 */
export function getMaximumTrainingEnd(
  snapshot: ForecastOverviewSnapshot | undefined,
  forecastStartDate?: Dayjs,
): Dayjs | undefined {
  if (!snapshot?.latestActualDate) return undefined;
  const latestActual = dayjs(snapshot.latestActualDate);
  const effectiveForecastStart =
    forecastStartDate ??
    (snapshot.forecastStartDate ? dayjs(snapshot.forecastStartDate) : undefined);
  if (!effectiveForecastStart) return latestActual;
  const dayBeforeForecast = effectiveForecastStart.subtract(1, 'day');
  return dayBeforeForecast.isBefore(latestActual, 'day') ? dayBeforeForecast : latestActual;
}

/**
 * 判断训练日期是否超出当前方向的数据范围或越过预测起点。
 *
 * @param date 待判断日期。
 * @param snapshot 当前方向返回的数据边界。
 * @param forecastStartDate 可选的表单预测起点。
 * @returns true 表示日期不可选。
 */
export function isTrainingDateDisabled(
  date: Dayjs,
  snapshot: ForecastOverviewSnapshot | undefined,
  forecastStartDate?: Dayjs,
): boolean {
  if (!snapshot?.dataStartDate) return true;
  const maximumEnd = getMaximumTrainingEnd(snapshot, forecastStartDate);
  if (!maximumEnd) return true;
  return date.isBefore(dayjs(snapshot.dataStartDate), 'day') || date.isAfter(maximumEnd, 'day');
}

/**
 * 校验完整自定义训练区间并返回可直接展示的业务错误。
 *
 * @param range 用户选择的含首尾日期区间。
 * @param snapshot 当前方向返回的数据边界。
 * @param forecastStartDate 可选的表单预测起点。
 * @returns 合法时为空，否则返回错误文案。
 */
export function getTrainingRangeValidationMessage(
  range: ForecastTrainingRange,
  snapshot: ForecastOverviewSnapshot | undefined,
  forecastStartDate?: Dayjs,
): string | undefined {
  if (!snapshot?.dataStartDate || !snapshot.latestActualDate) {
    return '当前统计口径尚无可用于训练的完整业务日数据';
  }
  const [start, end] = range;
  if (start.isAfter(end, 'day')) return '训练起始日不能晚于训练截止日';
  const dataStart = dayjs(snapshot.dataStartDate);
  const latestActual = dayjs(snapshot.latestActualDate);
  if (start.isBefore(dataStart, 'day') || end.isAfter(latestActual, 'day')) {
    return `训练区间必须位于 ${snapshot.dataStartDate} 至 ${snapshot.latestActualDate} 内`;
  }
  const effectiveForecastStart =
    forecastStartDate ??
    (snapshot.forecastStartDate ? dayjs(snapshot.forecastStartDate) : undefined);
  if (effectiveForecastStart && !end.isBefore(effectiveForecastStart, 'day')) {
    return '训练截止日必须早于预测起始日，避免未来数据泄漏';
  }
  if (end.diff(start, 'day') + 1 < MINIMUM_TRAINING_DAYS) {
    return `自定义训练区间至少需要 ${MINIMUM_TRAINING_DAYS} 个自然日`;
  }
  return undefined;
}

/**
 * 将完整日期区间格式化为紧凑中文文案。
 *
 * @param start 起始日期。
 * @param end 结束日期。
 * @returns 同年时省略结束年份的区间；缺失时返回“-”。
 */
export function formatForecastDateRange(start?: string, end?: string): string {
  if (!start || !end) return '-';
  const startDate = dayjs(start);
  const endDate = dayjs(end);
  return startDate.year() === endDate.year()
    ? `${startDate.format('YYYY-MM-DD')} 至 ${endDate.format('MM-DD')}`
    : `${startDate.format('YYYY-MM-DD')} 至 ${endDate.format('YYYY-MM-DD')}`;
}

/**
 * 将日期区间格式化为适合单行状态栏的紧凑文案。
 *
 * @param start 起始日期。
 * @param end 结束日期。
 * @returns 保留原日期信息并用不带空格的波浪线连接；缺失时返回“-”。
 * @throws 不主动抛出异常；缺失日期由标准区间格式化逻辑安全降级。
 */
export function formatCompactForecastDateRange(start?: string, end?: string): string {
  return formatForecastDateRange(start, end).replace(' 至 ', '～');
}

/**
 * 根据距今天数返回业务可读的新鲜度标签。
 *
 * @param lagDays 最新业务日距今天数。
 * @returns 标签颜色和文案。
 */
export function getBusinessDataFreshness(lagDays?: number): {
  color: 'success' | 'warning' | 'default';
  label: string;
} {
  if (lagDays == null) return { color: 'default', label: '无业务数据' };
  if (lagDays <= RECENT_BUSINESS_DATA_DAYS) {
    return { color: 'success', label: '业务数据及时' };
  }
  return { color: 'warning', label: '业务数据较旧' };
}
