/**
 * Forecast 调度计划展示工具。
 *
 * 职责：将服务端使用的 Spring 六段 CRON 转换为业务用户可读的中文执行计划。
 * 仅解释能够无歧义识别的固定时刻计划；复杂表达式使用明确的自定义计划文案，避免误导用户。
 */

const CUSTOM_SCHEDULE_TEXT = '自定义执行计划';
const WILDCARD_FIELDS = new Set(['*', '?']);
const WEEKDAY_TEXT: Record<string, string> = {
  '0': '周日',
  '1': '周一',
  '2': '周二',
  '3': '周三',
  '4': '周四',
  '5': '周五',
  '6': '周六',
  '7': '周日',
  SUN: '周日',
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
};

/** 判断日期字段是否表示“不限制”。 */
function isWildcard(value: string): boolean {
  return WILDCARD_FIELDS.has(value);
}

/** 将纯数字字段转换为有范围约束的整数。 */
function parseNumber(value: string, min: number, max: number): number | undefined {
  if (!/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return parsed >= min && parsed <= max ? parsed : undefined;
}

/** 将秒、分、时字段转换为 24 小时时间。 */
function formatClock(second: string, minute: string, hour: string): string | undefined {
  const parsedSecond = parseNumber(second, 0, 59);
  const parsedMinute = parseNumber(minute, 0, 59);
  const parsedHour = parseNumber(hour, 0, 23);
  if (parsedSecond === undefined || parsedMinute === undefined || parsedHour === undefined) {
    return undefined;
  }

  const clock = `${String(parsedHour).padStart(2, '0')}:${String(parsedMinute).padStart(2, '0')}`;
  return parsedSecond === 0 ? clock : `${clock}:${String(parsedSecond).padStart(2, '0')}`;
}

/** 将单个星期字段（名称或数字）转换为中文。 */
function formatWeekday(value: string): string | undefined {
  return WEEKDAY_TEXT[value.toUpperCase()];
}

/** 将星期列表或范围转换为中文周期。 */
function formatWeeklyPeriod(dayOfWeek: string): string | undefined {
  const segments = dayOfWeek.split(',');
  const formatted = segments.map((segment) => {
    const range = segment.split('-');
    if (range.length === 1) return formatWeekday(range[0]);
    if (range.length !== 2) return undefined;
    const start = formatWeekday(range[0]);
    const end = formatWeekday(range[1]);
    return start && end ? `${start}至${end}` : undefined;
  });
  return formatted.every(Boolean) ? `每${formatted.join('、')}` : undefined;
}

/** 将每月日期列表或范围转换为中文周期。 */
function formatMonthlyPeriod(dayOfMonth: string): string | undefined {
  const segments = dayOfMonth.split(',');
  const formatted = segments.map((segment) => {
    const range = segment.split('-');
    if (range.length === 1) {
      const day = parseNumber(range[0], 1, 31);
      return day === undefined ? undefined : String(day);
    }
    if (range.length !== 2) return undefined;
    const start = parseNumber(range[0], 1, 31);
    const end = parseNumber(range[1], 1, 31);
    return start !== undefined && end !== undefined ? `${start} 至 ${end}` : undefined;
  });
  return formatted.every(Boolean) ? `每月 ${formatted.join('、')} 日` : undefined;
}

/**
 * 将 Spring 六段 CRON 转换为中文执行计划。
 *
 * @param cron 服务端返回的秒、分、时、日、月、星期六段 CRON。
 * @returns 可直接展示的中文计划；空值返回“-”，复杂规则返回“自定义执行计划”。
 * @throws 不会抛出异常；无法可靠解析的输入会安全降级。
 *
 * @example
 * formatForecastSchedule('0 30 2 * * *'); // “每天 02:30”
 */
export function formatForecastSchedule(cron?: string | null): string {
  if (!cron?.trim()) return '-';
  const fields = cron.trim().split(/\s+/);
  if (fields.length !== 6) return CUSTOM_SCHEDULE_TEXT;

  const [second, minute, hour, dayOfMonth, month, dayOfWeek] = fields;
  const clock = formatClock(second, minute, hour);
  if (!clock) return CUSTOM_SCHEDULE_TEXT;

  // Profile 默认使用固定时刻；只有日期范围也能被无歧义解释时才输出具体周期。
  if (month === '*' && isWildcard(dayOfMonth) && isWildcard(dayOfWeek)) {
    return `每天 ${clock}`;
  }
  if (month === '*' && isWildcard(dayOfMonth)) {
    const weeklyPeriod = formatWeeklyPeriod(dayOfWeek);
    return weeklyPeriod ? `${weeklyPeriod} ${clock}` : CUSTOM_SCHEDULE_TEXT;
  }
  if (month === '*' && isWildcard(dayOfWeek)) {
    const monthlyPeriod = formatMonthlyPeriod(dayOfMonth);
    return monthlyPeriod ? `${monthlyPeriod} ${clock}` : CUSTOM_SCHEDULE_TEXT;
  }
  return CUSTOM_SCHEDULE_TEXT;
}
