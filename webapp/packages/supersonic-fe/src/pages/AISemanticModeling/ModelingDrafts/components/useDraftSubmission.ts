/**
 * 阶段 4 草稿提交审批 Hook。
 *
 * 职责：在管理员确认后携带当前版本、当前完整报告和幂等键提交审批；共享写协调器阻止与保存、
 * AI 修订或验证并发。响应不确定时保持 fail-closed，完成服务端状态对账前不恢复写能力。
 */
import { message, Modal } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  getModelingValidationReport,
  getModelingValidationReports,
  submitModelingDraft,
  type ModelingDraftItem,
  type ModelingValidationReport,
  type SubmitModelingDraftResp,
} from '@/services/semanticModelingDraft';
import {
  getRequestErrorText,
  isOptimisticLockConflict,
  type ModelingSubmitGateState,
  unwrapResponseData,
} from '../utils';
import { createIdempotencyKey } from './create/creationUtils';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';

type Input = {
  activeDetail?: ModelingDraftItem;
  currentVersionNo?: number;
  draftId?: number;
  mutationCoordinator: DraftMutationCoordinator;
  open: boolean;
  validationReport?: ModelingValidationReport;
  applyReconciledValidationReport: (
    draftId: number,
    versionNo: number,
    report?: ModelingValidationReport,
  ) => boolean;
  applyDetail: (detail: ModelingDraftItem) => void;
  onActiveTabChange: (tab: string) => void;
  onChanged?: () => void;
  requestDetail: (
    showLoading: boolean,
    showError: boolean,
  ) => Promise<ModelingDraftItem | undefined>;
};

/**
 * 管理提交审批幂等键、未知结果 fail-closed 状态与确认弹窗。
 *
 * @param input 当前草稿、验证报告、共享协调器和服务端状态刷新回调。
 * @returns 提交状态、未知结果状态及确认函数。
 * @throws 不向组件抛出请求异常；统一转换为脱敏 message。
 */
export function useDraftSubmission({
  activeDetail,
  currentVersionNo,
  draftId,
  mutationCoordinator,
  open,
  validationReport,
  applyReconciledValidationReport,
  applyDetail,
  onActiveTabChange,
  onChanged,
  requestDetail,
}: Input) {
  const [submitting, setSubmitting] = useState(false);
  const [reconciling, setReconciling] = useState(false);
  const [submitOutcomeUnknown, setSubmitOutcomeUnknown] = useState(false);
  const idempotencyKeyRef = useRef('');
  const signatureRef = useRef('');
  const submittedVersionRef = useRef<number>();

  useEffect(() => {
    setSubmitting(false);
    setReconciling(false);
    setSubmitOutcomeUnknown(false);
    idempotencyKeyRef.current = '';
    signatureRef.current = '';
    submittedVersionRef.current = undefined;
  }, [draftId, open]);

  /** 读取并校验服务端当前版本的最新完整验证报告。 */
  const loadLatestCurrentReport = async (
    expectedDraftId: number,
    expectedVersionNo: number,
  ): Promise<ModelingValidationReport> => {
    const data =
      unwrapResponseData<any>(
        await getModelingValidationReports(expectedDraftId, { page: 1, pageSize: 50 }),
      ) || {};
    const reports = (Array.isArray(data) ? data : data.list || []) as ModelingValidationReport[];
    const summary = reports
      .filter((item) => Number(item.draftVersionNo) === expectedVersionNo)
      .sort((left, right) => Number(right.id) - Number(left.id))[0];
    if (!summary) throw new Error('CURRENT_VALIDATION_REPORT_NOT_FOUND');
    const report = unwrapResponseData<ModelingValidationReport>(
      await getModelingValidationReport(summary.id),
    );
    if (
      Number(report.draftId) !== expectedDraftId ||
      Number(report.draftVersionNo) !== expectedVersionNo
    ) {
      throw new Error('CURRENT_VALIDATION_REPORT_MISMATCH');
    }
    return report;
  };

  /**
   * 在既有协调器令牌内对账详情、版本和最新验证报告；任一关键读取失败都保持未知锁。
   */
  const reconcileWithToken = async (
    token: NonNullable<ReturnType<DraftMutationCoordinator['tryStart']>>,
    submittedVersionNo: number,
  ): Promise<boolean> => {
    const detail = await requestDetail(false, false);
    if (!detail || !mutationCoordinator.isCurrent(token)) return false;
    const serverVersionNo = Number(detail.currentVersionNo ?? detail.currentVersion ?? 0);
    if (!serverVersionNo || !submittedVersionNo) return false;
    if (detail.status === 'PENDING_APPROVAL') {
      if (!applyReconciledValidationReport(token.draftId, serverVersionNo, undefined)) return false;
      applyDetail(detail);
      setSubmitOutcomeUnknown(false);
      onActiveTabChange('validation');
      return true;
    }
    if (detail.status !== 'DRAFT') {
      // 服务端已确认进入其他只读终态时，可以解除“未知”标记，但不能恢复编辑能力。
      if (!applyReconciledValidationReport(token.draftId, serverVersionNo, undefined)) return false;
      applyDetail(detail);
      setSubmitOutcomeUnknown(false);
      return true;
    }

    if (serverVersionNo > submittedVersionNo) {
      // 更高 DRAFT 版本已证明原提交版本不再处于待审批；新版本没有报告是合法状态，旧报告必须清空。
      if (!applyReconciledValidationReport(token.draftId, serverVersionNo, undefined)) return false;
      applyDetail(detail);
      setSubmitOutcomeUnknown(false);
      message.warning('服务端草稿版本已变化，请重新验证后再提交');
      return true;
    }
    if (serverVersionNo !== submittedVersionNo) {
      // 版本回退或无法解释的版本号不能作为解除同版本未知状态的证据。
      return false;
    }
    const report = await loadLatestCurrentReport(token.draftId, serverVersionNo);
    if (!mutationCoordinator.isCurrent(token)) return false;
    if (!applyReconciledValidationReport(token.draftId, serverVersionNo, report)) return false;
    applyDetail(detail);
    setSubmitOutcomeUnknown(false);
    return true;
  };

  /**
   * 手动重新对账未知提交结果。
   *
   * @returns 服务端详情、版本与验证报告全部确认后返回 true，否则保持 fail-closed。
   * @throws 不向组件抛出请求异常；仅展示一次脱敏错误。
   */
  const reconcileSubmitOutcome = async (): Promise<boolean> => {
    if (!draftId || !submitOutcomeUnknown) return true;
    const token = mutationCoordinator.tryStart('RECONCILIATION', draftId);
    if (!token) {
      message.warning('草稿正在执行其他写操作，请等待完成');
      return false;
    }
    setReconciling(true);
    try {
      const submittedVersionNo = submittedVersionRef.current;
      const reconciled = submittedVersionNo
        ? await reconcileWithToken(token, submittedVersionNo)
        : false;
      if (!reconciled && mutationCoordinator.isCurrent(token)) {
        message.error('暂时无法确认提交结果，草稿将继续保持只读，请稍后重试');
      }
      return reconciled;
    } catch (_error) {
      if (mutationCoordinator.isCurrent(token)) {
        message.error('暂时无法确认提交结果，草稿将继续保持只读，请稍后重试');
      }
      return false;
    } finally {
      if (mutationCoordinator.finish(token)) setReconciling(false);
    }
  };

  /** 服务端原子复核版本、最新报告和必需检查后提交待审批。 */
  const executeSubmitApproval = async (submitGate: ModelingSubmitGateState) => {
    if (
      !draftId ||
      !activeDetail ||
      !currentVersionNo ||
      !validationReport ||
      !submitGate.allowed
    ) {
      message.warning(submitGate.reason);
      return;
    }
    const token = mutationCoordinator.tryStart('SUBMISSION', draftId);
    if (!token) {
      message.warning('草稿正在执行其他写操作，请等待完成');
      return;
    }
    const signature = `${draftId}:${currentVersionNo}:${validationReport.id}`;
    if (signatureRef.current !== signature) {
      signatureRef.current = signature;
      idempotencyKeyRef.current = createIdempotencyKey();
    }
    submittedVersionRef.current = currentVersionNo;
    setSubmitting(true);
    try {
      const result = unwrapResponseData<SubmitModelingDraftResp>(
        await submitModelingDraft(
          draftId,
          { versionNo: currentVersionNo, validationReportId: validationReport.id },
          idempotencyKeyRef.current,
        ),
      );
      if (!mutationCoordinator.isCurrent(token)) return;
      if (result.status === 'PENDING_APPROVAL') {
        applyDetail({ ...activeDetail, status: 'PENDING_APPROVAL' });
      }
      await requestDetail(false, false);
      if (!mutationCoordinator.isCurrent(token)) return;
      onActiveTabChange('validation');
      message.success('草稿已提交审批；本阶段不会自动发布语义资产');
      onChanged?.();
    } catch (error) {
      if (!mutationCoordinator.isCurrent(token)) return;
      setSubmitOutcomeUnknown(true);
      message[isOptimisticLockConflict(error) ? 'warning' : 'error'](
        isOptimisticLockConflict(error)
          ? '提交门禁状态已变化，正在重新加载最新版本和验证报告'
          : getRequestErrorText(error),
      );
      try {
        await reconcileWithToken(token, currentVersionNo);
      } catch (_reconciliationError) {
        // 首次自动对账失败时必须保留未知锁；手动刷新会重新执行完整对账。
      }
    } finally {
      if (mutationCoordinator.finish(token)) setSubmitting(false);
    }
  };

  /** 在执行提交前要求管理员明确确认 WARNING 报告。 */
  const confirmSubmitApproval = (submitGate: ModelingSubmitGateState) => {
    if (!submitGate.allowed) {
      message.warning(submitGate.reason);
      return;
    }
    Modal.confirm({
      title: '提交当前草稿版本审批？',
      content:
        validationReport?.status === 'WARNING'
          ? '验证报告包含非阻塞警告。提交后草稿将切换为只读待审批状态。'
          : '验证已通过。提交后草稿将切换为只读待审批状态。',
      okText: '提交审批',
      cancelText: '取消',
      onOk: () => executeSubmitApproval(submitGate),
    });
  };

  return {
    confirmSubmitApproval,
    reconcileSubmitOutcome,
    reconciling,
    submitOutcomeUnknown,
    submitting,
  };
}
