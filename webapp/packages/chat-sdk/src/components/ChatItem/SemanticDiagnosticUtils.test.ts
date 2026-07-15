/**
 * Chat 结构化语义诊断工具回归测试。
 */
import { diagnosticCopyText, diagnosticTitle } from './SemanticDiagnosticUtils';

describe('SemanticDiagnosticUtils', () => {
  it('maps stable stages without inspecting exception messages', () => {
    expect(diagnosticTitle('MODEL_SQL_COMPILE')).toBe('模型校验失败');
    expect(diagnosticTitle('UNKNOWN')).toBe('语义查询失败');
  });

  it('copies only bounded structured fields', () => {
    const result = diagnosticCopyText({
      code: 'MODEL_SQL_PARSE_FAILED',
      stage: 'MODEL_SQL_COMPILE',
      token: 'REGEXP',
      suggestion: 'RLIKE'.repeat(1000),
      traceId: 'trace-safe',
    });

    expect(result.length).toBeLessThanOrEqual(2000);
    expect(result).toContain('errorCode=MODEL_SQL_PARSE_FAILED');
    expect(result).not.toContain('select *');
  });
});
