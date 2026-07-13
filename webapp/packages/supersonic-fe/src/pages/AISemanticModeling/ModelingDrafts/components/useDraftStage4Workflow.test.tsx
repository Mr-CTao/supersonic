/**
 * 阶段 4 工作流 Hook 行为测试。
 *
 * 覆盖管理报告前端门禁、草稿切换后的 latest-wins、重复验证点击，以及人工保存与阶段 4
 * 写操作共享互斥锁。测试只模拟统一 service 层，不绕过 Hook 的真实状态机。
 */
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { Modal, message } from 'antd';
import {
  getModelingDraftVersionDiff,
  getModelingDraftVersions,
  getModelingValidationReport,
  getModelingValidationReports,
  restoreModelingDraftVersion,
  submitModelingDraft,
  updateModelingDraft,
  validateModelingDraft,
  type ModelingDraftItem,
  type SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';
import { useDraftSave } from './useDraftSave';
import { useDraftSubmission } from './useDraftSubmission';
import { useDraftValidation } from './useDraftValidation';
import { useDraftVersionDiff } from './useDraftVersionDiff';
import { createDraftMutationCoordinator } from './useDraftMutationCoordinator';

jest.mock('antd', () => ({
  message: { error: jest.fn(), info: jest.fn(), success: jest.fn(), warning: jest.fn() },
  Modal: { confirm: jest.fn() },
}));

jest.mock('@/services/semanticModelingDraft', () => ({
  getModelingDraftVersionDiff: jest.fn(),
  getModelingDraftVersions: jest.fn(),
  getModelingValidationReport: jest.fn(),
  getModelingValidationReports: jest.fn(),
  restoreModelingDraftVersion: jest.fn(),
  submitModelingDraft: jest.fn(),
  updateModelingDraft: jest.fn(),
  validateModelingDraft: jest.fn(),
}));

// React 18 原生测试 root 需要显式声明 act 环境，避免把受控状态刷新误报为未包装更新。
(
  globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }
).IS_REACT_ACT_ENVIRONMENT = true;

const mockGetReport = getModelingValidationReport as jest.MockedFunction<
  typeof getModelingValidationReport
>;
const mockGetVersions = getModelingDraftVersions as jest.MockedFunction<
  typeof getModelingDraftVersions
>;
const mockGetVersionDiff = getModelingDraftVersionDiff as jest.MockedFunction<
  typeof getModelingDraftVersionDiff
>;
const mockGetReports = getModelingValidationReports as jest.MockedFunction<
  typeof getModelingValidationReports
>;
const mockUpdateDraft = updateModelingDraft as jest.MockedFunction<typeof updateModelingDraft>;
const mockValidateDraft = validateModelingDraft as jest.MockedFunction<
  typeof validateModelingDraft
>;
const mockRestoreVersion = restoreModelingDraftVersion as jest.MockedFunction<
  typeof restoreModelingDraftVersion
>;
const mockSubmitDraft = submitModelingDraft as jest.MockedFunction<typeof submitModelingDraft>;

const draft = {
  businessGoal: '库存分析',
  models: [],
  targetDomain: { name: 'WMS' },
  terms: [],
  uncertainties: [],
} as SemanticModelingDraftJson;

const cleanupCallbacks: Array<() => void> = [];

/** 使用 React 18 原生 root 渲染 Hook，避免为本测试新增依赖。 */
function renderTestHook<TResult, TProps extends object>(
  hook: (props: TProps) => TResult,
  initialProps: TProps,
) {
  const container = document.createElement('div');
  const root = createRoot(container);
  let current: TResult;
  const TestComponent: React.FC<TProps> = (props) => {
    current = hook(props);
    return null;
  };
  act(() => root.render(<TestComponent {...initialProps} />));
  let unmounted = false;
  const unmount = () => {
    if (unmounted) return;
    unmounted = true;
    act(() => root.unmount());
  };
  cleanupCallbacks.push(unmount);
  return {
    result: {
      get current() {
        return current!;
      },
    },
    rerender: (props: TProps) => act(() => root.render(<TestComponent {...props} />)),
    unmount,
  };
}

/** 创建可由测试精确控制完成时机的 Promise。 */
function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

/** 构造满足工作流所需的最小草稿详情。 */
function createDetail(id: number): ModelingDraftItem {
  return {
    id,
    currentDraft: draft,
    currentVersionNo: 1,
    dataSourceId: 7,
    lockVersion: 1,
    sourceType: 'DATA_SOURCE',
    status: 'DRAFT',
  } as ModelingDraftItem;
}

describe('useDraftStage4Workflow coordination', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // 默认保持报告请求 pending，避免与当前用例无关的 effect 完成时机干扰断言。
    mockGetReports.mockReturnValue(new Promise(() => undefined) as any);
    mockGetVersions.mockResolvedValue({ code: 200, data: { list: [], total: 0 } } as any);
    mockGetVersionDiff.mockResolvedValue({ code: 200, data: undefined } as any);
  });

  afterEach(() => {
    cleanupCallbacks.splice(0).forEach((cleanup) => cleanup());
  });

  it('does not request admin validation reports for a read-only viewer', () => {
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);

    renderTestHook(
      () =>
        useDraftValidation({
          activeDetail: createDetail(1),
          currentVersionNo: 1,
          draftId: 1,
          hasManagePermission: false,
          mutationCoordinator: coordinator,
          open: true,
          onActiveTabChange: jest.fn(),
          requestDetail: jest.fn(),
        }),
      {},
    );

    expect(mockGetReports).not.toHaveBeenCalled();
    expect(mockGetReport).not.toHaveBeenCalled();
  });

  it('ignores draft A report response after switching to draft B', async () => {
    const draftAReports = createDeferred<any>();
    mockGetReports.mockImplementation((draftId) =>
      draftId === 1
        ? draftAReports.promise
        : Promise.resolve({ code: 200, data: { list: [] } } as any),
    );
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const { rerender } = renderTestHook(
      ({ draftId }) =>
        useDraftValidation({
          activeDetail: createDetail(draftId),
          currentVersionNo: 1,
          draftId,
          hasManagePermission: true,
          mutationCoordinator: coordinator,
          open: true,
          onActiveTabChange: jest.fn(),
          requestDetail: jest.fn(),
        }),
      { draftId: 1 },
    );

    coordinator.invalidate(2);
    rerender({ draftId: 2 });
    await act(async () => {
      draftAReports.resolve({
        code: 200,
        data: { list: [{ id: 101, draftId: 1, draftVersionNo: 1, status: 'PASSED' }] },
      });
      await draftAReports.promise;
    });

    expect(mockGetReport).not.toHaveBeenCalledWith(101);
  });

  it('does not let a late draft A mutation release draft B lock', () => {
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const draftAToken = coordinator.tryStart('SAVE', 1)!;

    coordinator.invalidate(2);
    const draftBToken = coordinator.tryStart('VALIDATION', 2)!;

    expect(coordinator.isCurrent(draftAToken)).toBe(false);
    expect(coordinator.finish(draftAToken)).toBe(false);
    expect(coordinator.isCurrent(draftBToken)).toBe(true);
  });

  it('deduplicates validation clicks before React rerenders loading state', async () => {
    const validationResponse = createDeferred<any>();
    mockValidateDraft.mockReturnValue(validationResponse.promise);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const { result } = renderTestHook(
      () =>
        useDraftValidation({
          activeDetail: createDetail(1),
          currentVersionNo: 1,
          draftId: 1,
          hasManagePermission: true,
          mutationCoordinator: coordinator,
          open: true,
          onActiveTabChange: jest.fn(),
          requestDetail: jest.fn(),
        }),
      {},
    );

    let firstRequest!: Promise<void>;
    act(() => {
      firstRequest = result.current.runValidation();
      void result.current.runValidation();
    });
    expect(mockValidateDraft).toHaveBeenCalledTimes(1);

    await act(async () => {
      validationResponse.resolve({
        code: 200,
        data: { id: 9, draftId: 1, draftVersionNo: 1, status: 'PASSED' },
      });
      await firstRequest;
    });
  });

  it('prevents validation while a save request is in flight', async () => {
    const saveResponse = createDeferred<any>();
    mockUpdateDraft.mockReturnValue(saveResponse.promise);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const detail = { ...createDetail(1), canManage: true };
    const applyDetail = jest.fn();
    const saveHook = renderTestHook(
      () =>
        useDraftSave({
          activeDetail: detail,
          draftId: 1,
          editable: true,
          mutationCoordinator: coordinator,
          open: true,
          parsedDraft: draft,
          applyDetail,
          onVersionChanged: jest.fn(),
          requestDetail: jest.fn(),
        }),
      {},
    );
    const validationHook = renderTestHook(
      () =>
        useDraftValidation({
          activeDetail: detail,
          currentVersionNo: 1,
          draftId: 1,
          hasManagePermission: true,
          mutationCoordinator: coordinator,
          open: true,
          onActiveTabChange: jest.fn(),
          requestDetail: jest.fn(),
        }),
      {},
    );

    let saveRequest!: Promise<void>;
    act(() => {
      saveRequest = saveHook.result.current.saveDraft();
      void validationHook.result.current.runValidation();
    });
    expect(mockUpdateDraft).toHaveBeenCalledTimes(1);
    expect(mockValidateDraft).not.toHaveBeenCalled();

    await act(async () => {
      saveResponse.resolve({ code: 200, data: { ...detail, canManage: null } });
      await saveRequest;
    });
    expect(applyDetail).toHaveBeenCalledWith(expect.objectContaining({ canManage: true }));
  });

  it('deduplicates restore clicks and refreshes only after a successful append-only restore', async () => {
    const restoreResponse = createDeferred<any>();
    mockRestoreVersion.mockReturnValue(restoreResponse.promise);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const onVersionRestored = jest.fn().mockResolvedValue(undefined);
    const { result } = renderTestHook(
      () =>
        useDraftVersionDiff({
          active: true,
          canRestore: true,
          currentVersionNo: 3,
          draftId: 1,
          lockVersion: 8,
          mutationCoordinator: coordinator,
          onVersionRestored,
        }),
      {},
    );

    let firstRequest!: Promise<void>;
    act(() => {
      firstRequest = result.current.restoreVersion(1);
      void result.current.restoreVersion(1);
    });
    expect(mockRestoreVersion).toHaveBeenCalledTimes(1);

    await act(async () => {
      restoreResponse.resolve({ code: 200, data: { draftId: 1, newVersionNo: 4 } });
      await firstRequest;
    });
    expect(onVersionRestored).toHaveBeenCalledTimes(1);
  });

  it('ignores a late restore response after switching drafts', async () => {
    const restoreResponse = createDeferred<any>();
    mockRestoreVersion.mockReturnValue(restoreResponse.promise);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const onVersionRestored = jest.fn();
    const hook = renderTestHook(
      ({ draftId }) =>
        useDraftVersionDiff({
          active: true,
          canRestore: true,
          currentVersionNo: 3,
          draftId,
          lockVersion: 8,
          mutationCoordinator: coordinator,
          onVersionRestored,
        }),
      { draftId: 1 },
    );
    let request!: Promise<void>;
    act(() => {
      request = hook.result.current.restoreVersion(1);
    });

    coordinator.invalidate(2);
    hook.rerender({ draftId: 2 });
    await act(async () => {
      restoreResponse.resolve({ code: 200, data: { draftId: 1, newVersionNo: 4 } });
      await request;
    });
    expect(onVersionRestored).not.toHaveBeenCalled();
  });

  it('reuses the restore idempotency key after an unknown network outcome', async () => {
    mockRestoreVersion
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ code: 200, data: { draftId: 1, newVersionNo: 4 } } as any);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const onVersionRestored = jest.fn();
    const { result } = renderTestHook(
      () =>
        useDraftVersionDiff({
          active: true,
          canRestore: true,
          currentVersionNo: 3,
          draftId: 1,
          lockVersion: 8,
          mutationCoordinator: coordinator,
          onVersionRestored,
        }),
      {},
    );

    await act(async () => result.current.restoreVersion(1));
    await act(async () => result.current.restoreVersion(1));

    expect(mockRestoreVersion).toHaveBeenCalledTimes(2);
    expect(mockRestoreVersion.mock.calls[0][3]).toBe(mockRestoreVersion.mock.calls[1][3]);
    expect(onVersionRestored).toHaveBeenCalledTimes(1);
  });

  it('stops polling immediately when a RUNNING report reaches a terminal state', async () => {
    jest.useFakeTimers();
    try {
      mockGetReports.mockResolvedValue({
        code: 200,
        data: { list: [{ id: 9, draftId: 1, draftVersionNo: 1, status: 'RUNNING' }] },
      } as any);
      mockGetReport
        .mockResolvedValueOnce({
          code: 200,
          data: { id: 9, draftId: 1, draftVersionNo: 1, status: 'RUNNING' },
        } as any)
        .mockResolvedValueOnce({
          code: 200,
          data: { id: 9, draftId: 1, draftVersionNo: 1, status: 'SYSTEM_FAILED' },
        } as any);
      const coordinator = createDraftMutationCoordinator();
      coordinator.invalidate(1);
      const hook = renderTestHook(
        () =>
          useDraftValidation({
            activeDetail: createDetail(1),
            currentVersionNo: 1,
            draftId: 1,
            hasManagePermission: true,
            mutationCoordinator: coordinator,
            open: true,
            onActiveTabChange: jest.fn(),
            requestDetail: jest.fn(),
          }),
        {},
      );
      await act(async () => Promise.resolve());
      await act(async () => {
        jest.advanceTimersByTime(2000);
        await Promise.resolve();
      });
      expect(hook.result.current.validationRunning).toBe(false);
      jest.advanceTimersByTime(60000);
      expect(mockGetReport).toHaveBeenCalledTimes(2);
    } finally {
      jest.useRealTimers();
    }
  });

  it('stops polling on a permanent 4xx error without repeated notifications', async () => {
    jest.useFakeTimers();
    try {
      mockGetReports.mockResolvedValue({
        code: 200,
        data: { list: [{ id: 9, draftId: 1, draftVersionNo: 1, status: 'RUNNING' }] },
      } as any);
      mockGetReport
        .mockResolvedValueOnce({
          code: 200,
          data: { id: 9, draftId: 1, draftVersionNo: 1, status: 'RUNNING' },
        } as any)
        .mockRejectedValueOnce({ response: { status: 403 } });
      const coordinator = createDraftMutationCoordinator();
      coordinator.invalidate(1);
      renderTestHook(
        () =>
          useDraftValidation({
            activeDetail: createDetail(1),
            currentVersionNo: 1,
            draftId: 1,
            hasManagePermission: true,
            mutationCoordinator: coordinator,
            open: true,
            onActiveTabChange: jest.fn(),
            requestDetail: jest.fn(),
          }),
        {},
      );
      await act(async () => Promise.resolve());
      await act(async () => {
        jest.advanceTimersByTime(2000);
        await Promise.resolve();
      });
      jest.advanceTimersByTime(60000);
      expect(mockGetReport).toHaveBeenCalledTimes(2);
      expect(message.error).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
  });

  it('backs off transient polling errors and clears timers after unmount', async () => {
    jest.useFakeTimers();
    try {
      mockGetReports.mockResolvedValue({
        code: 200,
        data: { list: [{ id: 9, draftId: 1, draftVersionNo: 1, status: 'RUNNING' }] },
      } as any);
      mockGetReport
        .mockResolvedValueOnce({
          code: 200,
          data: { id: 9, draftId: 1, draftVersionNo: 1, status: 'RUNNING' },
        } as any)
        .mockRejectedValue(new Error('temporary'));
      const coordinator = createDraftMutationCoordinator();
      coordinator.invalidate(1);
      const hook = renderTestHook(
        () =>
          useDraftValidation({
            activeDetail: createDetail(1),
            currentVersionNo: 1,
            draftId: 1,
            hasManagePermission: true,
            mutationCoordinator: coordinator,
            open: true,
            onActiveTabChange: jest.fn(),
            requestDetail: jest.fn(),
          }),
        {},
      );
      await act(async () => Promise.resolve());
      await act(async () => {
        jest.advanceTimersByTime(2000);
        await Promise.resolve();
      });
      expect(mockGetReport).toHaveBeenCalledTimes(2);
      await act(async () => {
        jest.advanceTimersByTime(1999);
        await Promise.resolve();
      });
      expect(mockGetReport).toHaveBeenCalledTimes(2);
      await act(async () => {
        jest.advanceTimersByTime(1);
        await Promise.resolve();
      });
      expect(mockGetReport).toHaveBeenCalledTimes(3);
      hook.unmount();
      jest.advanceTimersByTime(60000);
      expect(mockGetReport).toHaveBeenCalledTimes(3);
    } finally {
      jest.useRealTimers();
    }
  });

  it('keeps the unknown submit lock after automatic reconciliation fails and clears it after manual refresh', async () => {
    mockSubmitDraft.mockRejectedValue(new Error('network'));
    const detail = createDetail(1);
    const requestDetail = jest.fn().mockResolvedValueOnce(undefined).mockResolvedValueOnce(detail);
    mockGetReports.mockResolvedValue({
      code: 200,
      data: { list: [{ id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' }] },
    } as any);
    mockGetReport.mockResolvedValue({
      code: 200,
      data: { id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' },
    } as any);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const applyReconciledValidationReport = jest.fn().mockReturnValue(true);
    const hook = renderTestHook(
      () =>
        useDraftSubmission({
          activeDetail: detail,
          currentVersionNo: 1,
          draftId: 1,
          mutationCoordinator: coordinator,
          open: true,
          validationReport: { id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' } as any,
          applyDetail: jest.fn(),
          applyReconciledValidationReport,
          onActiveTabChange: jest.fn(),
          requestDetail,
        }),
      {},
    );
    act(() => hook.result.current.confirmSubmitApproval({ allowed: true } as any));
    const confirmOptions = (Modal.confirm as jest.Mock).mock.calls[0][0];
    await act(async () => {
      await confirmOptions.onOk();
    });
    expect(hook.result.current.submitOutcomeUnknown).toBe(true);

    await act(async () => {
      expect(await hook.result.current.reconcileSubmitOutcome()).toBe(true);
    });
    expect(hook.result.current.submitOutcomeUnknown).toBe(false);
    expect(applyReconciledValidationReport).toHaveBeenCalledWith(
      1,
      1,
      expect.objectContaining({ id: 11, status: 'PASSED' }),
    );
  });

  it('clears the unknown submit marker when the server confirms pending approval', async () => {
    mockSubmitDraft.mockRejectedValue(new Error('network'));
    const pending = { ...createDetail(1), status: 'PENDING_APPROVAL' } as ModelingDraftItem;
    const requestDetail = jest.fn().mockResolvedValue(pending);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const applyReconciledValidationReport = jest.fn().mockReturnValue(true);
    const applyDetail = jest.fn();
    const hook = renderTestHook(
      () =>
        useDraftSubmission({
          activeDetail: createDetail(1),
          currentVersionNo: 1,
          draftId: 1,
          mutationCoordinator: coordinator,
          open: true,
          validationReport: { id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' } as any,
          applyDetail,
          applyReconciledValidationReport,
          onActiveTabChange: jest.fn(),
          requestDetail,
        }),
      {},
    );

    act(() => hook.result.current.confirmSubmitApproval({ allowed: true } as any));
    await act(async () => {
      await (Modal.confirm as jest.Mock).mock.calls[0][0].onOk();
    });

    expect(hook.result.current.submitOutcomeUnknown).toBe(false);
    expect(applyDetail).toHaveBeenCalledWith(pending);
    expect(applyReconciledValidationReport).toHaveBeenCalledWith(1, 1, undefined);
    expect(mockGetReports).not.toHaveBeenCalled();
  });

  it('keeps the unknown lock when the same draft version has no validation report', async () => {
    mockSubmitDraft.mockRejectedValue(new Error('network'));
    mockGetReports.mockResolvedValue({ code: 200, data: { list: [] } } as any);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const applyReconciledValidationReport = jest.fn().mockReturnValue(true);
    const hook = renderTestHook(
      () =>
        useDraftSubmission({
          activeDetail: createDetail(1),
          currentVersionNo: 1,
          draftId: 1,
          mutationCoordinator: coordinator,
          open: true,
          validationReport: { id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' } as any,
          applyDetail: jest.fn(),
          applyReconciledValidationReport,
          onActiveTabChange: jest.fn(),
          requestDetail: jest.fn().mockResolvedValue(createDetail(1)),
        }),
      {},
    );

    act(() => hook.result.current.confirmSubmitApproval({ allowed: true } as any));
    await act(async () => {
      await (Modal.confirm as jest.Mock).mock.calls[0][0].onOk();
    });

    expect(hook.result.current.submitOutcomeUnknown).toBe(true);
    expect(applyReconciledValidationReport).not.toHaveBeenCalled();
  });

  it('unlocks a higher draft version without requiring a report and clears the old report', async () => {
    mockSubmitDraft.mockRejectedValue(new Error('network'));
    const newer = { ...createDetail(1), currentVersionNo: 2, lockVersion: 2 } as ModelingDraftItem;
    // 即使列表中仍有旧版本 PASSED 报告，高版本分支也不能读取或复用它。
    mockGetReports.mockResolvedValue({
      code: 200,
      data: { list: [{ id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' }] },
    } as any);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const applyReconciledValidationReport = jest.fn().mockReturnValue(true);
    const applyDetail = jest.fn();
    const hook = renderTestHook(
      () =>
        useDraftSubmission({
          activeDetail: createDetail(1),
          currentVersionNo: 1,
          draftId: 1,
          mutationCoordinator: coordinator,
          open: true,
          validationReport: { id: 11, draftId: 1, draftVersionNo: 1, status: 'PASSED' } as any,
          applyDetail,
          applyReconciledValidationReport,
          onActiveTabChange: jest.fn(),
          requestDetail: jest.fn().mockResolvedValue(newer),
        }),
      {},
    );

    act(() => hook.result.current.confirmSubmitApproval({ allowed: true } as any));
    await act(async () => {
      await (Modal.confirm as jest.Mock).mock.calls[0][0].onOk();
    });

    expect(hook.result.current.submitOutcomeUnknown).toBe(false);
    expect(applyDetail).toHaveBeenCalledWith(newer);
    expect(applyReconciledValidationReport).toHaveBeenCalledWith(1, 2, undefined);
    expect(mockGetReports).not.toHaveBeenCalled();
    expect(message.warning).toHaveBeenCalledWith('服务端草稿版本已变化，请重新验证后再提交');
  });

  it('does not let late submission reconciliation mutate a newly selected draft', async () => {
    mockSubmitDraft.mockRejectedValue(new Error('network'));
    const lateDetail = createDeferred<ModelingDraftItem>();
    const requestDetail = jest
      .fn()
      .mockResolvedValueOnce(undefined)
      .mockReturnValueOnce(lateDetail.promise);
    const coordinator = createDraftMutationCoordinator();
    coordinator.invalidate(1);
    const applyReconciledValidationReport = jest.fn().mockReturnValue(true);
    const hook = renderTestHook(
      ({ draftId }) =>
        useDraftSubmission({
          activeDetail: createDetail(draftId),
          currentVersionNo: 1,
          draftId,
          mutationCoordinator: coordinator,
          open: true,
          validationReport: {
            id: 11,
            draftId,
            draftVersionNo: 1,
            status: 'PASSED',
          } as any,
          applyDetail: jest.fn(),
          applyReconciledValidationReport,
          onActiveTabChange: jest.fn(),
          requestDetail,
        }),
      { draftId: 1 },
    );
    act(() => hook.result.current.confirmSubmitApproval({ allowed: true } as any));
    const confirmOptions = (Modal.confirm as jest.Mock).mock.calls[0][0];
    await act(async () => {
      await confirmOptions.onOk();
    });
    expect(hook.result.current.submitOutcomeUnknown).toBe(true);

    let reconciliation!: Promise<boolean>;
    act(() => {
      reconciliation = hook.result.current.reconcileSubmitOutcome();
    });
    coordinator.invalidate(2);
    hook.rerender({ draftId: 2 });
    await act(async () => {
      lateDetail.resolve(createDetail(1));
      expect(await reconciliation).toBe(false);
    });

    expect(applyReconciledValidationReport).not.toHaveBeenCalled();
    expect(hook.result.current.submitOutcomeUnknown).toBe(false);
  });
});
