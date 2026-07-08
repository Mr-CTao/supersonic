/**
 * 模块说明：ChatMsg 图表数据规整工具。
 * 职责描述：在查询结果进入 ECharts 前统一处理空分类、非法数值和排序取值，避免单个图表组件各自兜底不一致导致运行时崩溃。
 */

import { ColumnType } from '../../common/type';

export const EMPTY_CHART_CATEGORY_NAME = '未知';

/**
 * 将后端返回的分类值规整为 ECharts 可安全消费的字符串。
 *
 * @param value 原始分类值，可能来自维度字段、指标名称或聚合结果。
 * @param fallback 当分类为空、null 或 undefined 时使用的展示文案。
 * @returns 非空字符串，保证不会把 null / undefined 透传给 ECharts legend。
 * @throws 不主动抛出异常；异常输入会被转换为 fallback 或字符串形式。
 */
export function normalizeChartCategoryName(
  value: unknown,
  fallback: string = EMPTY_CHART_CATEGORY_NAME
) {
  if (value === null || value === undefined) {
    return fallback;
  }
  const categoryName = String(value).trim();
  return categoryName || fallback;
}

/**
 * 将图表指标值转换为有限数字。
 *
 * @param value 原始指标值，通常来自 queryResults 的数值字段。
 * @returns 可用于 ECharts series 的有限数字；空值、空字符串、NaN 和 Infinity 返回 undefined。
 * @throws 不主动抛出异常；无法转换的输入会返回 undefined。
 */
export function toFiniteChartNumber(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  const numericValue = Number(value);
  return isFinite(numericValue) ? numericValue : undefined;
}

/**
 * 判断指标值是否适合渲染到普通图表。
 *
 * @param value 原始指标值。
 * @returns true 表示该值是有限数字，可以进入柱状图或趋势图。
 * @throws 不主动抛出异常；异常输入会返回 false。
 */
export function isRenderableMetricValue(value: unknown) {
  return toFiniteChartNumber(value) !== undefined;
}

/**
 * 判断指标值是否适合渲染到饼图。
 *
 * @param value 原始指标值。
 * @returns true 表示该值是有限且非负的数字，可以作为饼图扇区值。
 * @throws 不主动抛出异常；异常输入会返回 false。
 */
export function isRenderablePieMetricValue(value: unknown) {
  const numericValue = toFiniteChartNumber(value);
  return numericValue !== undefined && numericValue >= 0;
}

/**
 * 根据字段格式配置生成趋势图的渲染数值。
 *
 * @param value 原始指标值。
 * @param metricField 指标字段配置，用于兼容已有 percent / decimal 的展示倍数逻辑。
 * @returns 有限数字或 null；null 会让 ECharts 绘制断点，而不是把 NaN 写入 series。
 * @throws 不主动抛出异常；异常输入会返回 null。
 */
export function normalizeTrendMetricValue(value: unknown, metricField: ColumnType) {
  const numericValue = toFiniteChartNumber(value);
  if (numericValue === undefined) {
    return null;
  }
  return (metricField.dataFormatType === 'percent' || metricField.dataFormatType === 'decimal') &&
    metricField.dataFormat?.needMultiply100
    ? numericValue * 100
    : numericValue;
}

/**
 * 获取图表排序时使用的安全数值。
 *
 * @param value 原始或已规整数值。
 * @returns 有限数字；非法值统一按 0 排序，避免比较函数返回 NaN。
 * @throws 不主动抛出异常；异常输入会返回 0。
 */
export function getSortableChartValue(value: unknown) {
  const numericValue = toFiniteChartNumber(value);
  return numericValue === undefined ? 0 : numericValue;
}
