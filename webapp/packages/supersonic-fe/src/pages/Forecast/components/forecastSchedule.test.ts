/**
 * Forecast 调度计划展示工具测试。
 *
 * 职责：固定常用 Spring 六段 CRON 的中文展示，并验证复杂规则不会被错误解释。
 */
import { formatForecastSchedule } from './forecastSchedule';

describe('formatForecastSchedule', () => {
  it('将默认同步和预测计划显示为每天固定时间', () => {
    expect(formatForecastSchedule('0 0 1 * * *')).toBe('每天 01:00');
    expect(formatForecastSchedule('0 30 2 * * *')).toBe('每天 02:30');
  });

  it('保留非零秒并兼容问号通配符', () => {
    expect(formatForecastSchedule('15 5 14 ? * *')).toBe('每天 14:05:15');
  });

  it('显示每周固定时间计划', () => {
    expect(formatForecastSchedule('0 30 3 * * SUN')).toBe('每周日 03:30');
    expect(formatForecastSchedule('0 0 9 * * MON-FRI')).toBe('每周一至周五 09:00');
    expect(formatForecastSchedule('0 0 9 * * MON,WED,FRI')).toBe('每周一、周三、周五 09:00');
  });

  it('显示每月固定时间计划', () => {
    expect(formatForecastSchedule('0 15 4 1,15 * *')).toBe('每月 1、15 日 04:15');
  });

  it('对空值和无法可靠解释的复杂规则安全降级', () => {
    expect(formatForecastSchedule()).toBe('-');
    expect(formatForecastSchedule('0 */15 * * * *')).toBe('自定义执行计划');
    expect(formatForecastSchedule('invalid cron')).toBe('自定义执行计划');
  });
});
