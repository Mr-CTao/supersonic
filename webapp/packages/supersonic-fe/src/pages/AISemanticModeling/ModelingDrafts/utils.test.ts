/**
 * AI 语义建模草稿页面纯函数单元测试。
 *
 * 覆盖结构化 JSON 解析、完整分类树、选表兼容展示、统一响应解包、乐观锁冲突、重试上限、
 * 验证报告提交门禁、阻塞项 AI 指令和差异安全展示。
 */
import {
  buildValidationRepairInstruction,
  buildDraftTreeData,
  formatModelingDiffValue,
  formatSelectedTables,
  getModelingRevisionFailureDisposition,
  getIncompleteRequiredValidationChecks,
  getModelingSubmitGateState,
  getRegenerationAvailability,
  getRequestErrorCode,
  getRequestErrorText,
  hasServerResponse,
  hasModelingManagePermission,
  isModelingVersionDiffForSelection,
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
  ModelingValidationReport,
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
  const passedRequiredChecks = [
    'JSON_SCHEMA',
    'TABLE_FIELD_EXISTENCE',
    'METRIC_EXPRESSION_FIELD',
    'SENSITIVE_FIELD',
    'NAME_CONFLICT',
    'RETRIEVAL_POLLUTION',
    'SAMPLE_QUESTION',
    'SEMANTIC_SQL_GENERATION',
    'SQL_READ_ONLY',
    'PERFORMANCE_RISK',
  ].map((category) => ({ category, status: 'PASSED' as const }));

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

  it('builds an additions-only tree for an extend-existing draft', () => {
    const incrementalDraft: SemanticModelingDraftJson = {
      schemaVersion: '2.0',
      action: 'EXTEND_EXISTING',
      businessGoal: '补充呆滞时长',
      models: [],
      targetAsset: {
        candidateHandle: 'candidate_1',
        assetType: 'MODEL',
        name: '库存汇总',
        baseVersion: 7,
        baseTable: 'stock_summary',
      },
      additions: {
        dimensions: [
          {
            key: 'sluggishDuration',
            name: '呆滞时长',
            field: 'sluggish_duration_days',
          },
        ],
        metrics: [],
        terms: [],
      },
      modifications: [],
      regressionQuestions: ['查询呆滞时长前十'],
      uncertainties: [],
    };

    const tree = buildDraftTreeData(incrementalDraft);

    expect(String(tree[0].title)).toContain('目标资产（只读）');
    expect(String(tree[0].title)).toContain('库存汇总');
    expect(tree.map((node) => String(node.title))).not.toContain('模型（0）');
    expect(tree[1].children?.map((node) => String(node.title))).toEqual(
      expect.arrayContaining(['新增维度（1）', '新增指标（0）', '回归问法（1）']),
    );
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

  it('classifies revision failures by business code instead of treating every 409 as stale base', () => {
    const responseError = (code: string, status = 409) => ({
      data: { code: status, data: { code, message: code } },
      response: { status },
    });
    const running = responseError('REVISION_RUNNING');

    expect(getRequestErrorCode(running)).toBe('REVISION_RUNNING');
    expect(hasServerResponse(running)).toBe(true);
    expect(getModelingRevisionFailureDisposition(running)).toMatchObject({
      baseVersionConflict: false,
      reuseIdempotencyKey: true,
      serverResponded: true,
    });
    expect(getModelingRevisionFailureDisposition(new TypeError('Failed to fetch'))).toMatchObject({
      baseVersionConflict: false,
      reuseIdempotencyKey: true,
      serverResponded: false,
    });
    expect(
      getModelingRevisionFailureDisposition(responseError('LOCK_VERSION_CONFLICT')),
    ).toMatchObject({
      baseVersionConflict: true,
      reuseIdempotencyKey: false,
    });

    [
      'REVISION_ATTEMPT_TERMINAL',
      'REVISION_LEASE_EXPIRED',
      'REVISION_FAILED',
      'OUTPUT_INVALID',
      'IDEMPOTENCY_CONFLICT',
      'AI_REVISION_FAILED',
      'MODEL_OUTPUT_INVALID',
      'IDEMPOTENCY_KEY_CONFLICT',
    ].forEach((code) => {
      expect(getModelingRevisionFailureDisposition(responseError(code, 422))).toMatchObject({
        baseVersionConflict: false,
        reuseIdempotencyKey: false,
        serverResponded: true,
      });
    });
    expect(
      getModelingRevisionFailureDisposition(responseError('UNEXPECTED_SERVER_ERROR', 502)),
    ).toMatchObject({ reuseIdempotencyKey: false, serverResponded: true });
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

  it('allows only a current passed or warning report without blocking items to submit', () => {
    const report: ModelingValidationReport = {
      id: 11,
      draftId: 1,
      draftVersionNo: 4,
      status: 'PASSED',
      blockingItems: [],
      requiredCheckResults: passedRequiredChecks,
      canSubmit: true,
    };

    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report,
      }),
    ).toEqual({ allowed: true, reason: '验证已通过' });
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report: { ...report, status: 'WARNING' },
      }).allowed,
    ).toBe(true);
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 5,
        dirty: false,
        draftStatus: 'DRAFT',
        report,
      }).reason,
    ).toContain('旧版本');
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: true,
        draftStatus: 'DRAFT',
        report,
      }).reason,
    ).toContain('先保存');
    expect(
      getModelingSubmitGateState({
        busy: true,
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report,
      }).allowed,
    ).toBe(false);
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report: { ...report, blockingItems: [{ code: 'UNKNOWN_FIELD', message: '字段不存在' }] },
      }).reason,
    ).toContain('阻塞项');
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report: { ...report, blockingItems: [{ unexpected: '仍应阻塞' }] as any },
      }).allowed,
    ).toBe(false);
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report: { ...report, canSubmit: false, submissionBlockReason: '服务端门禁拒绝' },
      }).reason,
    ).toBe('服务端门禁拒绝');
  });

  it('fails closed for missing, NOT_RUN and unknown required validation checks', () => {
    const baseReport: ModelingValidationReport = {
      id: 12,
      draftId: 1,
      draftVersionNo: 4,
      status: 'PASSED',
      blockingItems: [],
      canSubmit: true,
      requiredCheckResults: passedRequiredChecks,
    };

    expect(
      getIncompleteRequiredValidationChecks({ ...baseReport, requiredCheckResults: undefined }),
    ).toHaveLength(10);
    expect(
      getIncompleteRequiredValidationChecks({
        ...baseReport,
        requiredCheckResults: passedRequiredChecks.map((item) =>
          item.category === 'PERFORMANCE_RISK' ? { ...item, status: 'NOT_RUN' } : item,
        ),
      }),
    ).toContain('PERFORMANCE_RISK');
    expect(
      getModelingSubmitGateState({
        currentVersionNo: 4,
        dirty: false,
        draftStatus: 'DRAFT',
        report: {
          ...baseReport,
          requiredCheckResults: passedRequiredChecks.map((item) =>
            item.category === 'SQL_READ_ONLY' ? { ...item, status: 'MYSTERY' } : item,
          ),
        },
      }).reason,
    ).toContain('SQL_READ_ONLY');
  });

  it('treats absent or false canManage as read-only', () => {
    expect(hasModelingManagePermission(undefined)).toBe(false);
    expect(hasModelingManagePermission({ canManage: false } as ModelingDraftItem)).toBe(false);
    expect(hasModelingManagePermission({ canManage: true } as ModelingDraftItem)).toBe(true);
  });

  it('builds a bounded AI repair instruction from structured validation blockers', () => {
    const instruction = buildValidationRepairInstruction([
      { path: '$.models[0].metrics[0]', code: 'UNKNOWN_FIELD', message: 'quantity 字段不存在' },
      { category: 'SQL_SAFETY', message: 'SQL 不是只读语句' },
    ]);

    expect(instruction).toContain('保持其他已经确认的语义对象和业务口径不变');
    expect(instruction).toContain('UNKNOWN_FIELD');
    expect(instruction).toContain('quantity 字段不存在');
    expect(instruction).toContain('SQL 不是只读语句');
    expect(buildValidationRepairInstruction([])).toBe('');
    expect(
      buildValidationRepairInstruction(
        Array.from({ length: 20 }, (_, index) => ({
          code: `BLOCK_${index}`,
          message: 'x'.repeat(1000),
        })),
      ).length,
    ).toBeLessThanOrEqual(2000);
  });

  it('accepts a version diff only for the current draft and exact selected pair', () => {
    const diff = {
      draftId: 7,
      fromVersionNo: 3,
      toVersionNo: 4,
      items: [],
    };

    expect(isModelingVersionDiffForSelection(diff, 7, 3, 4)).toBe(true);
    expect(isModelingVersionDiffForSelection(diff, 8, 3, 4)).toBe(false);
    expect(isModelingVersionDiffForSelection(diff, 7, 2, 4)).toBe(false);
    expect(isModelingVersionDiffForSelection(diff, 7, 3, 5)).toBe(false);
  });

  it('formats diff values without allowing unbounded text', () => {
    expect(formatModelingDiffValue({ field: 'quantity' })).toContain('quantity');
    expect(formatModelingDiffValue('x'.repeat(2100))).toHaveLength(2001);
    expect(formatModelingDiffValue(undefined)).toBe('-');
  });
});
