/**
 * SQL 双重校验合并与竞态保护回归测试。
 */
import {
  countBlockingChecks,
  getHealthStatusPresentation,
  getValidationStatusPresentation,
  isLatestValidation,
  mergeValidationResult,
  partitionValidationChecks,
} from './validationUtils';

describe('validationUtils', () => {
  it('blocks when source passes but compiler fails', () => {
    const result = mergeValidationResult(true, '', {
      overallStatus: 'BLOCKING',
      checks: [
        {
          type: 'SEMANTIC_COMPILER',
          status: 'BLOCKING',
          message: '模型 SQL 无法被语义解析器解析',
        },
      ],
    });
    expect(result.overallStatus).toBe('BLOCKING');
    expect(result.checks[0].status).toBe('PASSED');
  });

  it('blocks when compiler passes but source fails', () => {
    const result = mergeValidationResult(false, '数据源不可达', {
      overallStatus: 'PASSED',
      checks: [{ type: 'SEMANTIC_COMPILER', status: 'PASSED', message: '编译通过' }],
    });
    expect(result.overallStatus).toBe('BLOCKING');
    expect(result.checks[0].message).toBe('数据源不可达');
  });

  it('rejects late or aborted responses', () => {
    expect(isLatestValidation(1, 2, false)).toBe(false);
    expect(isLatestValidation(2, 2, true)).toBe(false);
    expect(isLatestValidation(2, 2, false)).toBe(true);
  });

  it('maps backend statuses to readable Chinese labels', () => {
    expect(getValidationStatusPresentation('BLOCKING')).toEqual({
      label: '阻断',
      tone: 'error',
    });
    expect(getHealthStatusPresentation('UNKNOWN')).toEqual({
      label: '待刷新',
      tone: 'default',
    });
  });

  it('counts only blocking checks for the compact panel summary', () => {
    expect(
      countBlockingChecks({
        overallStatus: 'BLOCKING',
        checks: [
          { type: 'SOURCE_DATABASE', status: 'PASSED' },
          { type: 'SEMANTIC_COMPILER', status: 'BLOCKING' },
          { type: 'FIELD_EXPRESSION', status: 'WARNING' },
        ],
      }),
    ).toBe(1);
  });

  it('keeps deferred checks out of the primary result list', () => {
    const partition = partitionValidationChecks([
      { type: 'SOURCE_DATABASE', status: 'PASSED', message: '数据源执行通过' },
      { type: 'SEMANTIC_COMPILER', status: 'PASSED', message: '语义编译通过' },
      { type: 'FIELD_EXPRESSION', status: 'SKIPPED', message: '未提交完整字段定义' },
      { type: 'SEMANTIC_QUERY_SMOKE', status: 'SKIPPED', message: '完整模型阶段执行' },
    ]);

    expect(partition.executedChecks.map((check) => check.type)).toEqual([
      'SOURCE_DATABASE',
      'SEMANTIC_COMPILER',
    ]);
    expect(partition.deferredChecks.map((check) => check.type)).toEqual([
      'FIELD_EXPRESSION',
      'SEMANTIC_QUERY_SMOKE',
    ]);
  });
});
