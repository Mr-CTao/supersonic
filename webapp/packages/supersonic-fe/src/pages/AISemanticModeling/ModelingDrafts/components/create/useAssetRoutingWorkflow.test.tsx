/**
 * 语义资产路由工作流 Hook 行为测试。
 *
 * 职责：验证真实 Hook 的 REUSE 确认不会触发建稿，以及切换 gap 后旧分析响应不会写回当前 Drawer。
 */
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import {
  confirmSemanticAssetRoute,
  createSemanticAssetRoute,
  getSemanticAssetRoute,
  type CreateSemanticAssetRouteReq,
  type SemanticAssetRouteDetail,
} from '@/services/semanticAssetRouting';
import { useAssetRoutingWorkflow } from './useAssetRoutingWorkflow';

jest.mock('@/services/semanticAssetRouting', () => ({
  confirmSemanticAssetRoute: jest.fn(),
  createSemanticAssetRoute: jest.fn(),
  getSemanticAssetRoute: jest.fn(),
}));

(
  globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;

const mockCreateRoute = createSemanticAssetRoute as jest.MockedFunction<
  typeof createSemanticAssetRoute
>;
const mockConfirmRoute = confirmSemanticAssetRoute as jest.MockedFunction<
  typeof confirmSemanticAssetRoute
>;
const mockGetRoute = getSemanticAssetRoute as jest.MockedFunction<typeof getSemanticAssetRoute>;
const cleanups: Array<() => void> = [];

const request: CreateSemanticAssetRouteReq = {
  sourceType: 'SEMANTIC_GAP',
  sourceId: 3,
  businessGoal: '库存查询',
  dataSourceId: 7,
  databaseName: 'wms',
  selectedTables: ['stock'],
  chatModelId: 2,
  includeSampleData: false,
};

const reuseRoute: SemanticAssetRouteDetail = {
  id: 11,
  status: 'SUCCEEDED',
  recommendedAction: 'REUSE_EXISTING',
  primaryCandidate: {
    candidateHandle: 'candidate_1',
    assetType: 'MODEL',
    name: '库存汇总',
  },
  businessQuestions: [],
  allowedActions: ['REUSE_EXISTING'],
  canConfirm: true,
  analysisVersion: 1,
};

const clarificationRoute: SemanticAssetRouteDetail = {
  ...reuseRoute,
  id: 12,
  recommendedAction: 'NEEDS_CLARIFICATION',
  businessQuestions: [
    {
      key: 'current_step_field',
      question: '请提供表示任务当前步骤的字段名称。',
      required: true,
      answerType: 'TEXT',
    },
  ],
  allowedActions: ['NEEDS_CLARIFICATION'],
};

const analyzingRoute: SemanticAssetRouteDetail = {
  ...clarificationRoute,
  status: 'ANALYZING',
  recommendedAction: undefined,
};

/** 使用 React 18 原生 root 渲染 Hook，避免为测试引入额外依赖。 */
function renderWorkflow(initialGapId: number) {
  const container = document.createElement('div');
  const root = createRoot(container);
  let current: ReturnType<typeof useAssetRoutingWorkflow>;
  const TestComponent: React.FC<{ gapId: number; open: boolean }> = ({ gapId, open }) => {
    current = useAssetRoutingWorkflow({ initialGapId: gapId, open });
    return null;
  };
  act(() => root.render(<TestComponent gapId={initialGapId} open />));
  const unmount = () => act(() => root.unmount());
  cleanups.push(unmount);
  return {
    result: {
      get current() {
        return current!;
      },
    },
    rerender: (gapId: number, open = true) =>
      act(() => root.render(<TestComponent gapId={gapId} open={open} />)),
  };
}

/** 创建由测试控制完成时机的 Promise。 */
function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

/**
 * 逐秒推进现代 fake timers，并在每一步冲刷 Promise 微任务。
 *
 * @param seconds 需要模拟的轮询秒数。
 * @returns 所有定时器回调及其后续请求微任务均已获得执行机会。
 */
async function advancePollingSeconds(seconds: number) {
  for (let elapsed = 0; elapsed < seconds; elapsed += 1) {
    await act(async () => {
      jest.advanceTimersByTime(1_000);
      await Promise.resolve();
      await Promise.resolve();
    });
  }
}

describe('useAssetRoutingWorkflow', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
  });

  afterEach(() => {
    cleanups.splice(0).forEach((cleanup) => cleanup());
    jest.useRealTimers();
  });

  it('confirms REUSE_EXISTING without calling the draft creation callback', async () => {
    mockCreateRoute.mockResolvedValue({ code: 202, data: reuseRoute } as any);
    mockConfirmRoute.mockResolvedValue({
      code: 200,
      data: { ...reuseRoute, confirmedAction: 'REUSE_EXISTING' },
    } as any);
    const createDraft = jest.fn();
    const { result } = renderWorkflow(3);

    await act(async () => {
      await result.current.analyze(request);
    });
    let completion: Awaited<ReturnType<typeof result.current.confirmAndContinue>> | undefined;
    await act(async () => {
      completion = await result.current.confirmAndContinue({
        decision: { action: 'REUSE_EXISTING', candidateHandle: 'candidate_1' },
        createDraft,
      });
    });

    expect(completion?.kind).toBe('REUSED');
    expect(createDraft).not.toHaveBeenCalled();
    expect(mockGetRoute).not.toHaveBeenCalled();
  });

  it('ignores a late gap A analysis response after switching to gap B', async () => {
    const deferred = createDeferred<any>();
    mockCreateRoute.mockReturnValue(deferred.promise);
    const { result, rerender } = renderWorkflow(3);
    let analysisPromise!: Promise<SemanticAssetRouteDetail | undefined>;

    act(() => {
      analysisPromise = result.current.analyze(request);
    });
    rerender(4);
    await act(async () => {
      deferred.resolve({ code: 202, data: reuseRoute });
      await analysisPromise;
    });

    expect(result.current.route).toBeUndefined();
    expect(result.current.step).toBe('SCOPE');
    expect(mockGetRoute).not.toHaveBeenCalled();
  });

  it('keeps reconciling the original analysis when it completes after the soft timeout', async () => {
    mockCreateRoute.mockResolvedValue({ code: 202, data: analyzingRoute } as any);
    let pollCount = 0;
    mockGetRoute.mockImplementation(async () => {
      pollCount += 1;
      return {
        code: 200,
        data: pollCount >= 73 ? clarificationRoute : analyzingRoute,
      } as any;
    });
    const { result } = renderWorkflow(3);
    let analysisPromise!: Promise<SemanticAssetRouteDetail | undefined>;

    act(() => {
      analysisPromise = result.current.analyze(request);
    });
    await act(async () => Promise.resolve());
    await advancePollingSeconds(61);
    expect(result.current.progressText).toBe('分析耗时较长，仍在后台处理中，正在确认最终结果');
    expect(result.current.errorText).toBe('');
    await advancePollingSeconds(12);

    await expect(analysisPromise).resolves.toMatchObject({
      id: clarificationRoute.id,
      status: 'SUCCEEDED',
      recommendedAction: 'NEEDS_CLARIFICATION',
    });
    expect(mockCreateRoute).toHaveBeenCalledTimes(1);
    expect(mockGetRoute).toHaveBeenCalledTimes(73);
    expect(result.current.errorText).toBe('');
    expect(result.current.progressText).toBe('');
    expect(result.current.step).toBe('DECISION');
  });

  it('uses the same extended reconciliation window after answering clarification questions', async () => {
    mockCreateRoute.mockResolvedValue({ code: 202, data: clarificationRoute } as any);
    mockConfirmRoute.mockResolvedValue({
      code: 202,
      data: { ...analyzingRoute, analysisVersion: 2 },
    } as any);
    let pollCount = 0;
    const reanalyzedRoute: SemanticAssetRouteDetail = {
      ...clarificationRoute,
      analysisVersion: 2,
      businessAnswers: { current_step_field: 'CurrentStep' },
    };
    mockGetRoute.mockImplementation(async () => {
      pollCount += 1;
      return {
        code: 200,
        data: pollCount >= 73 ? reanalyzedRoute : { ...analyzingRoute, analysisVersion: 2 },
      } as any;
    });
    const { result } = renderWorkflow(3);

    await act(async () => {
      await result.current.analyze(request);
    });
    act(() => {
      result.current.updateBusinessAnswer('current_step_field', 'CurrentStep');
    });
    let confirmationPromise!: ReturnType<typeof result.current.confirmAndContinue>;
    act(() => {
      confirmationPromise = result.current.confirmAndContinue({
        decision: { action: 'NEEDS_CLARIFICATION', candidateHandle: 'candidate_1' },
        createDraft: jest.fn(),
      });
    });
    await act(async () => Promise.resolve());
    await advancePollingSeconds(73);

    await expect(confirmationPromise).resolves.toMatchObject({
      kind: 'REANALYZED',
      route: { analysisVersion: 2, status: 'SUCCEEDED' },
    });
    expect(mockConfirmRoute).toHaveBeenCalledTimes(1);
    expect(mockCreateRoute).toHaveBeenCalledTimes(1);
    expect(mockGetRoute).toHaveBeenCalledTimes(73);
    expect(result.current.errorText).toBe('');
    expect(result.current.progressText).toBe('');
  });

  it('performs one final reconciliation at the hard timeout and reuses the route on retry', async () => {
    mockCreateRoute.mockResolvedValue({ code: 202, data: analyzingRoute } as any);
    mockGetRoute.mockResolvedValue({ code: 200, data: analyzingRoute } as any);
    const { result } = renderWorkflow(3);
    let analysisPromise!: Promise<SemanticAssetRouteDetail | undefined>;

    act(() => {
      analysisPromise = result.current.analyze(request);
    });
    const observedFailure = analysisPromise.catch((error) => error as Error);
    await act(async () => Promise.resolve());
    await advancePollingSeconds(150);

    await expect(observedFailure).resolves.toMatchObject({
      message: '资产路由分析仍未完成，请稍后继续查询；系统不会自动新建草稿',
    });
    expect(mockCreateRoute).toHaveBeenCalledTimes(1);
    expect(result.current.progressText).toBe('');

    mockGetRoute.mockResolvedValue({ code: 200, data: clarificationRoute } as any);
    let retryPromise!: Promise<SemanticAssetRouteDetail | undefined>;
    act(() => {
      retryPromise = result.current.analyze(request);
    });
    await act(async () => Promise.resolve());
    await advancePollingSeconds(1);

    await expect(retryPromise).resolves.toMatchObject({
      id: clarificationRoute.id,
      status: 'SUCCEEDED',
    });
    expect(mockCreateRoute).toHaveBeenCalledTimes(1);
    expect(result.current.errorText).toBe('');
  });
});
