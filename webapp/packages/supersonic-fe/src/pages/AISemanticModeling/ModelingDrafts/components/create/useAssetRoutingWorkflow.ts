/**
 * 语义资产路由两步创建工作流 Hook。
 *
 * 职责：编排分析、限时轮询、确认/覆盖、澄清后重分析及已确认路由的建稿续程；同时维护业务答案、稳定幂等键和 Drawer 级互斥。
 *
 * 并发说明：关闭 Drawer、切换 gap 或修改已分析范围会同步废弃活动令牌；所有异步响应写回前都校验令牌，避免迟到响应污染新会话。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  confirmSemanticAssetRoute,
  createSemanticAssetRoute,
  getSemanticAssetRoute,
  type ConfirmSemanticAssetRouteReq,
  type CreateSemanticAssetRouteReq,
  type SemanticAssetRouteDetail,
} from '@/services/semanticAssetRouting';
import { getRequestErrorText, unwrapResponseData } from '../../utils';
import { createIdempotencyKey } from './creationUtils';
import type {
  AssetRoutingAnalyzedScope,
  AssetRoutingBusyKind,
  AssetRoutingCompletion,
  AssetRoutingDecision,
  AssetRoutingOperationToken,
  AssetRoutingStep,
} from './routingTypes';
import {
  buildAssetRouteFingerprint,
  buildStableFingerprint,
  createAssetRoutingOperationCoordinator,
  isRouteOverride,
  pickCurrentBusinessAnswers,
  runConfirmedRouteContinuation,
  validateBusinessAnswers,
  validateOverrideReason,
} from './routingUtils';

const ROUTE_POLL_INTERVAL_MS = 1000;
// 60 秒只作为“开始最终状态对账”的软边界，不能早于后端 120 秒分析租约宣告失败。
const ROUTE_POLL_SOFT_TIMEOUT_MS = 60_000;
// 后端分析租约为 120 秒；额外 30 秒用于任务完成落库、网络抖动和最后一次状态对账。
const ROUTE_POLL_HARD_TIMEOUT_MS = 150_000;

type StableKey = { fingerprint: string; value: string };

type Params = {
  initialGapId?: number;
  open: boolean;
};

type ConfirmOptions = {
  decision: AssetRoutingDecision;
  createDraft: (routeAnalysisId: number, idempotencyKey: string) => Promise<any>;
};

/** 相同请求指纹复用同一个幂等键，不同指纹才轮换。 */
function getStableKey(ref: React.MutableRefObject<StableKey | undefined>, fingerprint: string) {
  if (ref.current?.fingerprint !== fingerprint) {
    ref.current = { fingerprint, value: createIdempotencyKey() };
  }
  return ref.current.value;
}

/**
 * 资产路由 Drawer 工作流。
 *
 * @param params Drawer 开关和可选 gapId，用于隔离不同创建会话。
 * @returns 当前步骤、路由结果、业务答案、互斥状态及分析/确认操作。
 * @throws Hook 本身不抛出；公开异步操作会将后端或安全校验错误抛给容器展示。
 */
export function useAssetRoutingWorkflow({ initialGapId, open }: Params) {
  const sessionKey = open ? `asset-routing:${initialGapId ?? 'data-source'}` : '';
  const coordinatorRef = useRef(createAssetRoutingOperationCoordinator());
  const pollWaitersRef = useRef(new Map<ReturnType<typeof setTimeout>, () => void>());
  const lastOpenSessionRef = useRef('');
  const mountedRef = useRef(true);
  const analysisKeyRef = useRef<StableKey>();
  const confirmKeyRef = useRef<StableKey>();
  const draftKeyRef = useRef<StableKey>();
  const pendingScopeFingerprintRef = useRef('');
  const [step, setStep] = useState<AssetRoutingStep>('SCOPE');
  const [route, setRoute] = useState<SemanticAssetRouteDetail>();
  const [analyzedScope, setAnalyzedScope] = useState<AssetRoutingAnalyzedScope>();
  const analyzedScopeRef = useRef<AssetRoutingAnalyzedScope>();
  const [businessAnswers, setBusinessAnswers] = useState<Record<string, unknown>>({});
  const [answerErrors, setAnswerErrors] = useState<Record<string, string>>({});
  const [busyKind, setBusyKind] = useState<AssetRoutingBusyKind>();
  const [errorText, setErrorText] = useState('');
  const [progressText, setProgressText] = useState('');
  const [scopeInvalidationText, setScopeInvalidationText] = useState('');

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      coordinatorRef.current.invalidate('');
      pollWaitersRef.current.forEach((resolve, timer) => {
        clearTimeout(timer);
        resolve();
      });
      pollWaitersRef.current.clear();
    };
  }, []);

  /** 创建可在卸载时立即清理并唤醒的轮询等待，避免遗留定时器。 */
  const waitForPoll = useCallback(
    () =>
      new Promise<void>((resolve) => {
        const timer = setTimeout(() => {
          pollWaitersRef.current.delete(timer);
          resolve();
        }, ROUTE_POLL_INTERVAL_MS);
        pollWaitersRef.current.set(timer, resolve);
      }),
    [],
  );

  useEffect(() => {
    coordinatorRef.current.invalidate(sessionKey);
    pollWaitersRef.current.forEach((resolve, timer) => {
      clearTimeout(timer);
      resolve();
    });
    pollWaitersRef.current.clear();
    setBusyKind(undefined);
    if (!open) {
      setProgressText('');
      return;
    }
    if (lastOpenSessionRef.current && lastOpenSessionRef.current !== sessionKey) {
      setStep('SCOPE');
      setRoute(undefined);
      setAnalyzedScope(undefined);
      analyzedScopeRef.current = undefined;
      setBusinessAnswers({});
      setAnswerErrors({});
      setErrorText('');
      setProgressText('');
      setScopeInvalidationText('');
      analysisKeyRef.current = undefined;
      confirmKeyRef.current = undefined;
      draftKeyRef.current = undefined;
    }
    lastOpenSessionRef.current = sessionKey;
  }, [open, sessionKey]);

  /**
   * 轮询到终态；软边界只触发服务端对账，超过后端租约和缓冲期后才 fail-closed。
   *
   * <p>首次分析和提交澄清答案后的重新分析共用本方法，避免两条路径出现不同的
   * 超时与幂等行为。硬超时前会强制执行最后一次 GET：如果后台已经完成，则直接
   * 消费终态；只有服务端仍返回活动态时才向用户报告未知结果。</p>
   */
  const pollToTerminal = useCallback(
    async (initial: SemanticAssetRouteDetail, token: AssetRoutingOperationToken) => {
      let latest = initial;
      const startedAt = Date.now();
      let softBoundaryReconciled = false;

      /** 查询最新服务端快照，并在请求前后校验会话令牌以隔离迟到响应。 */
      const reconcileLatest = async () => {
        if (!coordinatorRef.current.isCurrent(token)) {
          throw new Error('资产路由会话已变化，已忽略迟到响应');
        }
        const reconciled = unwrapResponseData<SemanticAssetRouteDetail>(
          await getSemanticAssetRoute(latest.id),
        );
        if (!coordinatorRef.current.isCurrent(token)) {
          throw new Error('资产路由会话已变化，已忽略迟到响应');
        }
        return reconciled;
      };

      while (latest.status === 'PENDING' || latest.status === 'ANALYZING') {
        const elapsed = Date.now() - startedAt;
        if (elapsed >= ROUTE_POLL_HARD_TIMEOUT_MS) {
          // 绝对边界前必须再次读取服务端，防止完成落库与前端计时恰好交错而误报失败。
          latest = await reconcileLatest();
          if (latest.status === 'PENDING' || latest.status === 'ANALYZING') {
            throw new Error('资产路由分析仍未完成，请稍后继续查询；系统不会自动新建草稿');
          }
          break;
        }
        if (elapsed >= ROUTE_POLL_SOFT_TIMEOUT_MS && !softBoundaryReconciled) {
          // 软边界只做一次立即对账；仍在合法分析窗口内时继续轮询原 routeId。
          softBoundaryReconciled = true;
          setProgressText('分析耗时较长，仍在后台处理中，正在确认最终结果');
          latest = await reconcileLatest();
          continue;
        }
        await waitForPoll();
        latest = await reconcileLatest();
      }
      return latest;
    },
    [waitForPoll],
  );

  /** 将成功结果切入第二步，并合并服务端已保存答案。 */
  const applySuccessfulRoute = useCallback((next: SemanticAssetRouteDetail) => {
    setRoute(next);
    setStep('DECISION');
    setBusinessAnswers((previous) => ({ ...previous, ...(next.businessAnswers ?? {}) }));
    setAnswerErrors({});
    setErrorText('');
    setProgressText('');
    setScopeInvalidationText('');
  }, []);

  /** 发起新分析，或继续轮询相同范围下尚未结束的分析。 */
  const analyze = useCallback(
    async (request: CreateSemanticAssetRouteReq): Promise<SemanticAssetRouteDetail | undefined> => {
      const fingerprint = buildAssetRouteFingerprint(request);
      const token = coordinatorRef.current.tryStart('ANALYZE');
      if (!token) throw new Error('当前已有操作正在进行，请稍候');
      pendingScopeFingerprintRef.current = fingerprint;
      setBusyKind('ANALYZE');
      setErrorText('');
      setProgressText('');
      try {
        let next = analyzedScopeRef.current?.fingerprint === fingerprint ? route : undefined;
        if (next?.status === 'FAILED' || next?.status === 'EXPIRED') {
          // 失败/过期快照不可通过原幂等键重新执行；显式轮换键创建一份新的可审计分析。
          analysisKeyRef.current = undefined;
          next = undefined;
        }
        if (!next || !['PENDING', 'ANALYZING', 'SUCCEEDED'].includes(next.status)) {
          const idempotencyKey = getStableKey(analysisKeyRef, fingerprint);
          next = unwrapResponseData<SemanticAssetRouteDetail>(
            await createSemanticAssetRoute(request, idempotencyKey),
          );
        }
        if (!coordinatorRef.current.isCurrent(token)) return undefined;
        const scope = { fingerprint, request };
        analyzedScopeRef.current = scope;
        setAnalyzedScope(scope);
        setRoute(next);
        next = await pollToTerminal(next, token);
        if (!coordinatorRef.current.isCurrent(token)) return undefined;
        setRoute(next);
        if (next.status !== 'SUCCEEDED' || !next.recommendedAction) {
          throw new Error(next.failureMessage || '资产路由分析失败，请重试；系统不会自动新建草稿');
        }
        applySuccessfulRoute(next);
        return next;
      } catch (error) {
        if (coordinatorRef.current.isCurrent(token) && mountedRef.current) {
          setProgressText('');
          setErrorText(getRequestErrorText(error));
        }
        throw error;
      } finally {
        if (coordinatorRef.current.finish(token) && mountedRef.current) setBusyKind(undefined);
      }
    },
    [applySuccessfulRoute, pollToTerminal, route],
  );

  /** 编辑影响指纹的范围后立即废弃旧推荐，但保留业务答案供下一版本复用。 */
  const markScopeChanged = useCallback(
    (request: Partial<CreateSemanticAssetRouteReq>) => {
      const fingerprint = buildAssetRouteFingerprint(request);
      const baseline = analyzedScopeRef.current?.fingerprint || pendingScopeFingerprintRef.current;
      if (!baseline || baseline === fingerprint) return;
      coordinatorRef.current.invalidate(sessionKey);
      setBusyKind(undefined);
      setRoute(undefined);
      setStep('SCOPE');
      setAnalyzedScope(undefined);
      analyzedScopeRef.current = undefined;
      setAnswerErrors({});
      setErrorText('');
      setProgressText('');
      setScopeInvalidationText('分析范围已变化，请重新分析现有资产');
    },
    [sessionKey],
  );

  /** 更新一个结构化业务答案，并清除该字段已有错误。 */
  const updateBusinessAnswer = useCallback((key: string, value: unknown) => {
    setBusinessAnswers((previous) => ({ ...previous, [key]: value }));
    setAnswerErrors((previous) => {
      if (!previous[key]) return previous;
      const next = { ...previous };
      delete next[key];
      return next;
    });
  }, []);

  /** 确认未知结果时只接受与本次动作相符的服务端快照。 */
  const reconcileConfirmation = useCallback(
    async (current: SemanticAssetRouteDetail, data: ConfirmSemanticAssetRouteReq) => {
      const reconciled = unwrapResponseData<SemanticAssetRouteDetail>(
        await getSemanticAssetRoute(current.id),
      );
      if (data.action === 'NEEDS_CLARIFICATION') {
        return reconciled.analysisVersion > current.analysisVersion ? reconciled : undefined;
      }
      if (reconciled.confirmedAction !== data.action) return undefined;
      if (data.candidateHandle && reconciled.confirmedCandidateHandle !== data.candidateHandle) {
        return undefined;
      }
      return reconciled;
    },
    [],
  );

  /** 确认推荐/覆盖推荐，并仅对已确认的新建或增强动作执行建稿续程。 */
  const confirmAndContinue = useCallback(
    async ({ decision, createDraft }: ConfirmOptions): Promise<AssetRoutingCompletion> => {
      if (!route?.recommendedAction || !analyzedScopeRef.current) {
        throw new Error('当前没有可确认的资产路由结果');
      }
      const currentAnswers = pickCurrentBusinessAnswers(route.businessQuestions, businessAnswers);
      const errors = validateBusinessAnswers(route.businessQuestions, currentAnswers);
      setAnswerErrors(errors);
      if (Object.keys(errors).length) throw new Error('请先回答必填业务问题');
      if (
        decision.action !== route.recommendedAction &&
        !route.allowedActions?.includes(decision.action)
      ) {
        throw new Error('服务端未允许当前处理方式，请重新分析');
      }
      if (isRouteOverride(route, decision.action, decision.candidateHandle)) {
        const reasonError = validateOverrideReason(decision.overrideReason);
        if (reasonError) throw new Error(reasonError);
      }
      const token = coordinatorRef.current.tryStart('CONFIRM');
      if (!token) throw new Error('当前已有操作正在进行，请稍候');
      setBusyKind('CONFIRM');
      setErrorText('');
      setProgressText('');
      const data: ConfirmSemanticAssetRouteReq = {
        analysisVersion: route.analysisVersion,
        action: decision.action,
        candidateHandle: decision.candidateHandle,
        businessAnswers: currentAnswers,
        overrideReason: decision.overrideReason?.trim() || undefined,
      };
      const confirmationFingerprint = buildStableFingerprint({ routeId: route.id, ...data });
      try {
        const idempotencyKey = getStableKey(confirmKeyRef, confirmationFingerprint);
        let confirmed: SemanticAssetRouteDetail;
        try {
          confirmed = unwrapResponseData<SemanticAssetRouteDetail>(
            await confirmSemanticAssetRoute(route.id, data, idempotencyKey),
          );
        } catch (error) {
          confirmed = (await reconcileConfirmation(route, data)) as SemanticAssetRouteDetail;
          if (!confirmed) throw error;
        }
        if (!coordinatorRef.current.isCurrent(token)) {
          throw new Error('资产路由会话已变化，已忽略迟到响应');
        }
        confirmed = await pollToTerminal(confirmed, token);
        if (!coordinatorRef.current.isCurrent(token)) {
          throw new Error('资产路由会话已变化，已忽略迟到响应');
        }
        if (confirmed.status !== 'SUCCEEDED') {
          throw new Error(confirmed.failureMessage || '路由确认失败，请重新分析');
        }
        applySuccessfulRoute(confirmed);
        if (decision.action === 'NEEDS_CLARIFICATION') {
          return { kind: 'REANALYZED', route: confirmed };
        }
        if (confirmed.confirmedAction !== decision.action) {
          throw new Error('服务端确认快照与当前处理方式不一致，请重新分析');
        }
        if (decision.action === 'REUSE_EXISTING') {
          return { kind: 'REUSED', route: confirmed };
        }
        setBusyKind('CREATE_DRAFT');
        const draftFingerprint = buildStableFingerprint({
          routeId: confirmed.id,
          analysisVersion: confirmed.analysisVersion,
          action: confirmed.confirmedAction,
        });
        const draftResponse = await runConfirmedRouteContinuation(
          confirmed.confirmedAction,
          confirmed.id,
          getStableKey(draftKeyRef, draftFingerprint),
          createDraft,
        );
        if (!coordinatorRef.current.isCurrent(token)) {
          throw new Error('资产路由会话已变化，已忽略迟到响应');
        }
        return { kind: 'DRAFT_CREATED', route: confirmed, draftResponse };
      } catch (error) {
        if (coordinatorRef.current.isCurrent(token) && mountedRef.current) {
          setProgressText('');
          setErrorText(getRequestErrorText(error));
        }
        throw error;
      } finally {
        if (coordinatorRef.current.finish(token) && mountedRef.current) setBusyKind(undefined);
      }
    },
    [applySuccessfulRoute, businessAnswers, pollToTerminal, reconcileConfirmation, route],
  );

  const requiredAnswerErrors = useMemo(
    () => validateBusinessAnswers(route?.businessQuestions, businessAnswers),
    [businessAnswers, route?.businessQuestions],
  );

  return {
    analyzedScope,
    answerErrors: { ...requiredAnswerErrors, ...answerErrors },
    businessAnswers,
    busyKind,
    errorText,
    progressText,
    route,
    scopeInvalidationText,
    step,
    analyze,
    confirmAndContinue,
    markScopeChanged,
    setStep,
    updateBusinessAnswer,
  };
}
