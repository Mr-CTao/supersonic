/**
 * AI 语义建模草稿页面纯函数单元测试。
 *
 * 覆盖结构化 JSON 解析、完整分类树、选表兼容展示、统一响应解包、乐观锁冲突、重试上限和尝试历史安全解析。
 */
import {
  buildDraftTreeData,
  formatSelectedTables,
  getRegenerationAvailability,
  getRequestErrorText,
  isOptimisticLockConflict,
  parseValidationIssues,
  parseDraftJson,
  sortModelingDraftAttempts,
  stringifyDraftJson,
  unwrapResponseData,
} from './utils';
import type {
  ModelingDraftAttempt,
  ModelingDraftItem,
  SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';

const draft: SemanticModelingDraftJson = {
  businessGoal: '支持库存分析',
  targetDomain: { name: 'WMS' },
  models: [
    {
      name: '库存明细',
      baseTable: 'stock',
      dimensions: [{ name: '仓库', field: 'warehouse_name' }],
      metrics: [{ name: '库存数量', expression: 'SUM(quantity)', aggregation: 'SUM' }],
      sensitiveFields: [{ field: 'customer_phone', level: 'HIGH' }],
      sampleQuestions: ['按仓库统计库存'],
    },
  ],
  terms: [{ name: '库存余额', mappingTarget: '库存数量' }],
  uncertainties: [{ reason: '跨表口径待确认' }],
};

describe('ModelingDrafts utils', () => {
  it('parses structured object and JSON text without echoing invalid source', () => {
    expect(parseDraftJson(draft).value).toEqual(draft);
    expect(parseDraftJson(JSON.stringify(draft)).value).toEqual(draft);
    expect(parseDraftJson('{invalid').error).toBe('JSON 格式无效，请检查括号、引号和逗号');
  });

  it('round-trips object editor changes through the shared JSON text', () => {
    const next = { ...draft, businessGoal: '对象表单已修改业务目标' };
    expect(parseDraftJson(stringifyDraftJson(next)).value).toEqual(next);
  });

  it('builds tree nodes for every required semantic draft category', () => {
    const tree = buildDraftTreeData(draft);
    const model = tree[0].children?.[0];
    const titles = model?.children?.map((item) => String(item.title)) || [];

    expect(titles).toEqual(
      expect.arrayContaining(['维度（1）', '指标（1）', '敏感字段（1）', '示例问法（1）']),
    );
    expect(String(tree[1].title)).toBe('域级术语（1）');
    expect(String(tree[2].title)).toBe('域级不确定项（1）');
  });

  it('formats legacy string arrays and structured selected tables', () => {
    expect(formatSelectedTables(['stock', 'warehouse'])).toBe('stock、warehouse');
    expect(
      formatSelectedTables([{ catalogName: 'default', databaseName: 'wms', tableName: 'stock' }]),
    ).toBe('default.wms.stock');
    expect(formatSelectedTables('["stock"]')).toBe('stock');
  });

  it('unwraps successful response and rejects business errors', () => {
    expect(unwrapResponseData({ code: 202, data: { draftId: 1 } })).toEqual({ draftId: 1 });
    expect(() => unwrapResponseData({ code: 400, msg: 'bad request' })).toThrow('bad request');
  });

  it('recognizes optimistic lock conflicts from HTTP and business errors', () => {
    expect(isOptimisticLockConflict({ response: { status: 409 } })).toBe(true);
    expect(isOptimisticLockConflict({ code: 409 })).toBe(true);
    expect(isOptimisticLockConflict({ response: { status: 500 } })).toBe(false);
  });

  it('extracts the concrete issue from nested non-2xx response wrappers', () => {
    const error = {
      message: 'http error',
      data: {
        code: 200,
        msg: 'success',
        data: {
          code: 'INVALID_REQUEST',
          message: '请求参数校验失败',
          issues: [{ path: '$header.Idempotency-Key', message: '缺少必填请求头' }],
        },
      },
    };

    expect(getRequestErrorText(error)).toBe('缺少必填请求头');
  });

  it('allows failed drafts below the limit and disables the third exhausted retry', () => {
    const failed = {
      id: 1,
      sourceType: 'DATA_SOURCE',
      dataSourceId: 3,
      status: 'GENERATION_FAILED',
      lockVersion: 2,
      manualRegenerationCount: 2,
      remainingManualRegenerations: 1,
      canRegenerate: true,
    } as ModelingDraftItem;

    expect(getRegenerationAvailability(failed).allowed).toBe(true);
    expect(
      getRegenerationAvailability({
        ...failed,
        manualRegenerationCount: 3,
        remainingManualRegenerations: 0,
      }).reason,
    ).toContain('3 次');
  });

  it('uses the backend regeneration block reason instead of silently enabling stale rows', () => {
    const availability = getRegenerationAvailability({
      id: 2,
      sourceType: 'SEMANTIC_GAP',
      dataSourceId: 3,
      status: 'GENERATION_FAILED',
      lockVersion: 4,
      canRegenerate: false,
      regenerationBlockReason: '草稿已由其他管理员重新生成',
    } as ModelingDraftItem);

    expect(availability).toEqual({
      allowed: false,
      reason: '草稿已由其他管理员重新生成',
    });
  });

  it('sorts attempt history descending without mutating input', () => {
    const attempts = [
      { attemptNo: 1, status: 'FAILED' },
      { attemptNo: 3, status: 'FAILED' },
      { attemptNo: 2, status: 'SUCCEEDED' },
    ] as ModelingDraftAttempt[];

    expect(sortModelingDraftAttempts(attempts).map((item) => item.attemptNo)).toEqual([3, 2, 1]);
    expect(attempts.map((item) => item.attemptNo)).toEqual([1, 3, 2]);
  });

  it('parses only structured validation issues and never echoes invalid diagnostic text', () => {
    expect(
      parseValidationIssues(
        '[{"path":"$.models[0]","code":"UNKNOWN_FIELD","message":"字段不存在"}]',
      ),
    ).toEqual([{ path: '$.models[0]', code: 'UNKNOWN_FIELD', message: '字段不存在' }]);
    expect(parseValidationIssues('not-json-sensitive-output')).toEqual([]);
  });
});
