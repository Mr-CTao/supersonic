/**
 * 阶段 4 草稿验证 Hook。
 *
 * 职责：只为管理员加载当前不可变版本的最新报告，轮询 RUNNING 报告，并以共享写协调器触发
 * 单次验证。所有响应均校验请求序号和草稿身份，viewer/public 不会调用管理级报告接口。
 */
import { message } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getModelingValidationReport,
  getModelingValidationReports,
  validateModelingDraft,
  type ModelingDraftItem,
  type ModelingValidationReport,
} from '@/services/semanticModelingDraft';
import { getRequestErrorText, unwrapResponseData } from '../utils';
import {
  canLoadAdminValidationReport,
  loadAdminValidationReportIfAllowed,
} from './draftStage4Guards';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';

const POLLING_INTERVAL_MS = 2000;
const MAX_POLLING_INTERVAL_MS = 30000;
const MAX_TRANSIENT_POLL_FAILURES = 6;
export const DEFAULT_SQL_PREVIEW_LIMIT = 20;

/** 从统一请求错误中提取 HTTP 状态码，无法识别时按短暂网络错误处理。 */
function getHttpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined;
  const candidate = error as { status?: unknown; response?: { status?: unknown } };
  const status = Number(candidate.response?.status ?? candidate.status);
  return Number.isInteger(status) && status > 0 ? status : undefined;
}

/** 认证、授权、资源不存在和其他 4xx 不会因自动重试而恢复，应立即停止轮询。 */
function isPermanentPollingError(error: unknown): boolean {
  const status = getHttpStatus(error);
  return status !== undefined && status >= 400 && status < 500;
}

type Input = {
  activeDetail?: ModelingDraftItem;
  currentVersionNo?: number;
  draftId?: number;
  hasManagePermission: boolean;
  mutationCoordinator: DraftMutationCoordinator;
  open: boolean;
  onActiveTabChange: (tab: string) => void;
  requestDetail: (
    showLoading: boolean,
    showError: boolean,
  ) => Promise<ModelingDraftItem | undefined>;
};

/**
 * 管理当前版本的验证报告、轮询和验证写请求。
 *
 * @param input 当前草稿、权限、共享互斥协调器和详情刷新回调。
 * @returns 报告状态、刷新/失效函数和受互斥锁保护的验证函数。
 * @throws 不向组件抛出请求异常；统一转换为脱敏 message。
 */
export function useDraftValidation({
  activeDetail,
  currentVersionNo,
  draftId,
  hasManagePermission,
  mutationCoordinator,
  open,
  onActiveTabChange,
  requestDetail,
}: Input) {
  const [validationReport, setValidationReport] = useState<ModelingValidationReport>();
  const [validationLoading, setValidationLoading] = useState(false);
  const [validating, setValidating] = useState(false);
  const [sqlPreviewLimit, setSqlPreviewLimit] = useState(DEFAULT_SQL_PREVIEW_LIMIT);
  const [validationRefreshToken, setValidationRefreshToken] = useState(0);
  const reportRequestRef = useRef(0);
  const activeDraftIdRef = useRef<number>();

  useEffect(() => {
    activeDraftIdRef.current = open ? draftId : undefined;
    reportRequestRef.current += 1;
    setValidationReport(undefined);
    setValidationLoading(false);
    setValidating(false);
    setSqlPreviewLimit(DEFAULT_SQL_PREVIEW_LIMIT);
  }, [draftId, open]);

  useEffect(() => {
    const context = {
      currentVersionNo,
      draftId,
      hasManagePermission,
      open,
      status: activeDetail?.status,
    };
    if (!canLoadAdminValidationReport(context)) {
      reportRequestRef.current += 1;
      setValidationReport(undefined);
      setValidationLoading(false);
      return undefined;
    }
    let active = true;
    const requestId = ++reportRequestRef.current;
    setValidationReport(undefined);
    setValidationLoading(true);

    /** 只使用当前不可变版本的最新报告，绝不回退旧版本通过结果。 */
    const loadLatestReport = async () => {
      try {
        const response = await loadAdminValidationReportIfAllowed(context, () =>
          getModelingValidationReports(draftId!, { page: 1, pageSize: 1 }),
        );
        if (!response) return;
        const data = unwrapResponseData<any>(response) || {};
        const reports = (
          Array.isArray(data) ? data : data.list || []
        ) as ModelingValidationReport[];
        if (!active || requestId !== reportRequestRef.current) return;
        const summary = reports
          .filter((item) => Number(item.draftVersionNo) === Number(currentVersionNo))
          .sort((left, right) => Number(right.id) - Number(left.id))[0];
        if (!summary) return;
        const report = unwrapResponseData<ModelingValidationReport>(
          await getModelingValidationReport(summary.id),
        );
        if (active && requestId === reportRequestRef.current) setValidationReport(report);
      } catch (error) {
        if (active && requestId === reportRequestRef.current) {
          message.error(getRequestErrorText(error));
        }
      } finally {
        if (active && requestId === reportRequestRef.current) setValidationLoading(false);
      }
    };
    void loadLatestReport();
    return () => {
      active = false;
      reportRequestRef.current += 1;
    };
  }, [
    activeDetail?.status,
    currentVersionNo,
    draftId,
    hasManagePermission,
    open,
    validationRefreshToken,
  ]);

  useEffect(() => {
    if (
      !open ||
      !hasManagePermission ||
      !draftId ||
      !validationReport?.id ||
      validationReport.status !== 'RUNNING'
    ) {
      return undefined;
    }
    let active = true;
    let errorShown = false;
    let transientFailureCount = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;

    /** 按失败次数指数退避并限制最大间隔，避免短暂故障持续轰炸服务。 */
    const scheduleNextPoll = (delay: number) => {
      timer = setTimeout(() => void pollReport(), delay);
    };

    /** 跟踪同一 RUNNING 报告；永久错误或重试耗尽时停止并等待用户手动刷新。 */
    const pollReport = async () => {
      try {
        const report = unwrapResponseData<ModelingValidationReport>(
          await getModelingValidationReport(validationReport.id),
        );
        if (!active || activeDraftIdRef.current !== draftId) return;
        errorShown = false;
        transientFailureCount = 0;
        setValidationReport(report);
        if (report.status === 'RUNNING') {
          scheduleNextPoll(POLLING_INTERVAL_MS);
        }
      } catch (error) {
        if (!active || activeDraftIdRef.current !== draftId) return;
        if (isPermanentPollingError(error)) {
          if (!errorShown) message.error(getRequestErrorText(error));
          errorShown = true;
          return;
        }
        transientFailureCount += 1;
        if (!errorShown) {
          errorShown = true;
          message.error(getRequestErrorText(error));
        }
        if (transientFailureCount >= MAX_TRANSIENT_POLL_FAILURES) return;
        const delay = Math.min(
          POLLING_INTERVAL_MS * 2 ** (transientFailureCount - 1),
          MAX_POLLING_INTERVAL_MS,
        );
        scheduleNextPoll(delay);
      }
    };
    scheduleNextPoll(POLLING_INTERVAL_MS);
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [draftId, hasManagePermission, open, validationReport?.id, validationReport?.status]);

  /** 使当前报告请求及已展示结果立即失效。 */
  const invalidateValidationReport = useCallback(() => {
    reportRequestRef.current += 1;
    setValidationLoading(false);
    setValidationReport(undefined);
  }, []);

  /**
   * 接受提交对账读取到的当前版本报告。
   *
   * @param expectedDraftId 对账开始时的草稿 ID。
   * @param expectedVersionNo 对账读取到的服务端当前版本。
   * @param report 服务端该版本的最新完整报告。
   * @returns 草稿和报告仍匹配当前 Drawer 时返回 true，否则拒绝迟到结果。
   */
  const applyReconciledValidationReport = useCallback(
    (expectedDraftId: number, expectedVersionNo: number, report?: ModelingValidationReport) => {
      if (
        activeDraftIdRef.current !== expectedDraftId ||
        (report &&
          (Number(report.draftId) !== expectedDraftId ||
            Number(report.draftVersionNo) !== expectedVersionNo))
      ) {
        return false;
      }
      reportRequestRef.current += 1;
      setValidationLoading(false);
      setValidationReport(report);
      return true;
    },
    [],
  );

  /** 未知写结果重新读取服务端详情；刷新完成前不复用旧报告。 */
  const reloadAfterUnknownOutcome = useCallback(async () => {
    const expectedDraftId = draftId;
    invalidateValidationReport();
    const detail = await requestDetail(true, true);
    if (!expectedDraftId || activeDraftIdRef.current !== expectedDraftId || !detail) return false;
    setValidationRefreshToken((value) => value + 1);
    return true;
  }, [draftId, invalidateValidationReport, requestDetail]);

  /** 对当前不可变版本触发一次验证；共享协调器封住保存与其他阶段 4 写操作。 */
  const runValidation = async (disabledReason?: string) => {
    if (!draftId || !currentVersionNo || disabledReason) {
      message.warning(disabledReason || '当前草稿版本不可验证');
      return;
    }
    const token = mutationCoordinator.tryStart('VALIDATION', draftId);
    if (!token) {
      message.warning('草稿正在执行其他写操作，请等待完成');
      return;
    }
    invalidateValidationReport();
    setValidating(true);
    onActiveTabChange('validation');
    try {
      const report = unwrapResponseData<ModelingValidationReport>(
        await validateModelingDraft(draftId, {
          versionNo: currentVersionNo,
          validationOptions: { sqlPreviewLimit },
        }),
      );
      if (!mutationCoordinator.isCurrent(token)) return;
      setValidationReport(report);
      if (report.status === 'RUNNING') message.info('验证任务已开始，页面会自动刷新报告');
      else if (report.status === 'PASSED') message.success('当前版本验证通过');
      else message.warning('验证已完成，请检查报告中的阻塞项和警告项');
    } catch (error) {
      if (!mutationCoordinator.isCurrent(token)) return;
      message.error(getRequestErrorText(error));
      await reloadAfterUnknownOutcome();
    } finally {
      if (mutationCoordinator.finish(token)) setValidating(false);
    }
  };

  return {
    applyReconciledValidationReport,
    invalidateValidationReport,
    reloadAfterUnknownOutcome,
    runValidation,
    setSqlPreviewLimit,
    sqlPreviewLimit,
    validating,
    validationLoading,
    validationRefreshToken,
    validationReport,
    validationRunning: validationReport?.status === 'RUNNING',
    refreshValidation: () => setValidationRefreshToken((value) => value + 1),
  };
}
