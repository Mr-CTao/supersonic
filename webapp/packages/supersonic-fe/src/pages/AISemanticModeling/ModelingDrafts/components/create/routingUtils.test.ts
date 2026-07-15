/**
 * 语义资产路由 Drawer 纯函数与并发边界测试。
 *
 * 职责：覆盖四动作按钮、必答业务问题、覆盖原因、REUSE 不建稿、稳定指纹，以及切会话后迟到响应不能释放新互斥锁。
 */
import type { SemanticAssetRouteDetail } from '@/services/semanticAssetRouting';
import {
  buildAssetRouteFingerprint,
  createAssetRoutingOperationCoordinator,
  isRouteOverride,
  pickCurrentBusinessAnswers,
  ROUTE_PRIMARY_BUTTON_TEXT,
  runConfirmedRouteContinuation,
  shouldCreateDraftForAction,
  validateBusinessAnswers,
  validateOverrideReason,
} from './routingUtils';

const route: SemanticAssetRouteDetail = {
  id: 11,
  status: 'SUCCEEDED',
  recommendedAction: 'EXTEND_EXISTING',
  primaryCandidate: {
    candidateHandle: 'candidate_1',
    assetType: 'MODEL',
    name: '库存汇总',
  },
  analysisVersion: 2,
};

describe('asset routing UI contracts', () => {
  it('uses the required Chinese primary button text for every server action', () => {
    expect(ROUTE_PRIMARY_BUTTON_TEXT).toEqual({
      REUSE_EXISTING: '使用现有资产并重新验证',
      EXTEND_EXISTING: '确认增强并生成增量草稿',
      CREATE_NEW: '确认新建并生成草稿',
      NEEDS_CLARIFICATION: '回答问题后重新分析',
    });
  });

  it('reports field-level errors for unanswered required questions but accepts boolean false', () => {
    const questions = [
      { key: 'grain', question: '按什么粒度？', required: true, answerType: 'SINGLE_SELECT' },
      { key: 'positive_stock', question: '只统计正库存？', required: true, answerType: 'BOOLEAN' },
      { key: 'note', question: '补充说明', required: false, answerType: 'TEXT' },
    ];

    expect(validateBusinessAnswers(questions, { positive_stock: false })).toEqual({
      grain: '请先回答该业务问题',
    });
    expect(
      pickCurrentBusinessAnswers(questions, {
        positive_stock: false,
        grain: 'MATERIAL',
        stale_question: '不能提交',
      }),
    ).toEqual({ positive_stock: false, grain: 'MATERIAL' });
  });

  it('requires a bounded reason whenever the action or candidate overrides the recommendation', () => {
    expect(isRouteOverride(route, 'CREATE_NEW')).toBe(true);
    expect(isRouteOverride(route, 'EXTEND_EXISTING', 'candidate_2')).toBe(true);
    expect(validateOverrideReason('   ')).toBe('更改推荐动作或候选时必须填写原因');
    expect(validateOverrideReason('业务确认需要新建')).toBeUndefined();
    expect(validateOverrideReason('a'.repeat(501))).toBe('覆盖原因不能超过 500 个字符');
  });

  it('never calls the create callback for REUSE_EXISTING', async () => {
    const createDraft = jest.fn().mockResolvedValue({ code: 202 });

    const result = await runConfirmedRouteContinuation(
      'REUSE_EXISTING',
      11,
      'stable-draft-key',
      createDraft,
    );

    expect(result).toBeUndefined();
    expect(createDraft).not.toHaveBeenCalled();
    expect(shouldCreateDraftForAction('REUSE_EXISTING')).toBe(false);
  });

  it('creates exactly one draft for an already confirmed EXTEND_EXISTING route', async () => {
    const createDraft = jest.fn().mockResolvedValue({ code: 202, data: { id: 9 } });

    await runConfirmedRouteContinuation('EXTEND_EXISTING', 11, 'stable-draft-key', createDraft);

    expect(createDraft).toHaveBeenCalledTimes(1);
    expect(createDraft).toHaveBeenCalledWith(11, 'stable-draft-key');
  });

  it('treats selected tables as a set when computing the route fingerprint', () => {
    const base = {
      sourceType: 'DATA_SOURCE' as const,
      businessGoal: '库存分析',
      dataSourceId: 7,
      databaseName: 'wms',
      selectedTables: ['stock', 'material'],
      chatModelId: 2,
      includeSampleData: false,
    };

    expect(buildAssetRouteFingerprint(base)).toBe(
      buildAssetRouteFingerprint({ ...base, selectedTables: ['material', 'stock'] }),
    );
  });
});

describe('asset routing latest-wins coordinator', () => {
  it('discards an old gap response and does not let it release the new gap lock', () => {
    const coordinator = createAssetRoutingOperationCoordinator('gap:1');
    const oldToken = coordinator.tryStart('ANALYZE')!;

    coordinator.invalidate('gap:2');
    const newToken = coordinator.tryStart('CONFIRM')!;

    expect(coordinator.isCurrent(oldToken)).toBe(false);
    expect(coordinator.finish(oldToken)).toBe(false);
    expect(coordinator.isCurrent(newToken)).toBe(true);
    expect(coordinator.finish(newToken)).toBe(true);
  });
});
