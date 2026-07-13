/**
 * 阶段 4 工作流组合 Hook。
 *
 * 职责：组合 AI 修订、验证报告和提交审批三个单一职责 Hook，统一计算写操作禁用原因与提交
 * 门禁。请求、轮询和幂等细节保留在子 Hook，人工保存通过同一个协调器参与互斥。
 */
import { message } from 'antd';
import { useMemo, useState } from 'react';
import type { ModelingDraftItem } from '@/services/semanticModelingDraft';
import { buildValidationRepairInstruction, getModelingSubmitGateState } from '../utils';
import { useDraftRevision } from './useDraftRevision';
import { useDraftSubmission } from './useDraftSubmission';
import { useDraftValidation } from './useDraftValidation';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';

type Input = {
  activeDetail?: ModelingDraftItem;
  currentVersionNo?: number;
  dirty: boolean;
  draftId?: number;
  hasManagePermission: boolean;
  mutationCoordinator: DraftMutationCoordinator;
  open: boolean;
  parsedDraftError?: string;
  saving: boolean;
  applyDetail: (detail: ModelingDraftItem) => void;
  onActiveTabChange: (tab: string) => void;
  onChanged?: () => void;
  requestDetail: (
    showLoading: boolean,
    showError: boolean,
  ) => Promise<ModelingDraftItem | undefined>;
};

/**
 * 组合阶段 4 状态、禁用原因和受共享互斥锁保护的操作函数。
 *
 * @param input 当前草稿、版本、权限、本地编辑状态和 Drawer 级写协调器。
 * @returns 阶段 4 面板状态、提交门禁、刷新/失效函数及用户操作回调。
 * @throws 不向组件抛出请求异常；子 Hook 统一转换为脱敏 message。
 */
export function useDraftStage4Workflow({
  activeDetail,
  currentVersionNo,
  dirty,
  draftId,
  hasManagePermission,
  mutationCoordinator,
  open,
  parsedDraftError,
  saving,
  applyDetail,
  onActiveTabChange,
  onChanged,
  requestDetail,
}: Input) {
  const [versionRefreshToken, setVersionRefreshToken] = useState(0);
  const validation = useDraftValidation({
    activeDetail,
    currentVersionNo,
    draftId,
    hasManagePermission,
    mutationCoordinator,
    open,
    onActiveTabChange,
    requestDetail,
  });
  const revision = useDraftRevision({
    activeDetail,
    currentVersionNo,
    draftId,
    mutationCoordinator,
    open,
    applyDetail,
    invalidateValidationReport: validation.invalidateValidationReport,
    onActiveTabChange,
    onChanged,
    onVersionCreated: () => setVersionRefreshToken((value) => value + 1),
    reloadAfterUnknownOutcome: validation.reloadAfterUnknownOutcome,
    requestDetail,
  });
  const submission = useDraftSubmission({
    activeDetail,
    currentVersionNo,
    draftId,
    mutationCoordinator,
    open,
    validationReport: validation.validationReport,
    applyReconciledValidationReport: validation.applyReconciledValidationReport,
    applyDetail,
    onActiveTabChange,
    onChanged,
    requestDetail,
  });
  const stage4Busy =
    revision.revising ||
    validation.validating ||
    submission.submitting ||
    submission.reconciling ||
    validation.validationRunning ||
    submission.submitOutcomeUnknown;

  const revisionDisabledReason = useMemo(() => {
    if (!hasManagePermission) return '当前账号没有草稿管理权限';
    if (submission.submitOutcomeUnknown) return '提交结果尚未确认，请刷新草稿完成服务端状态对账';
    if (activeDetail?.status === 'PENDING_APPROVAL') return '草稿已提交审批，不能继续 AI 修订';
    if (activeDetail?.status !== 'DRAFT') return '仅草稿状态可以进行 AI 修订';
    if (dirty) return '请先保存或放弃本地未保存修改';
    if (parsedDraftError) return parsedDraftError;
    if (saving) return '草稿正在保存，请稍候';
    if (revision.revising) return 'AI 正在修订草稿，请等待当前请求完成';
    if (validation.validating || validation.validationRunning) {
      return '当前版本正在验证，请等待验证完成';
    }
    if (submission.submitting) return '草稿正在提交审批，请稍候';
    return undefined;
  }, [
    activeDetail?.status,
    dirty,
    hasManagePermission,
    parsedDraftError,
    revision.revising,
    saving,
    submission.submitOutcomeUnknown,
    submission.submitting,
    validation.validating,
    validation.validationRunning,
  ]);

  const validationDisabledReason = useMemo(() => {
    if (!hasManagePermission) return '当前账号没有草稿管理权限';
    if (submission.submitOutcomeUnknown) return '提交结果尚未确认，请刷新草稿完成服务端状态对账';
    if (activeDetail?.status === 'PENDING_APPROVAL') return '草稿已提交审批，无需重复验证';
    if (activeDetail?.status !== 'DRAFT') return '仅草稿状态可以执行验证';
    if (dirty) return '请先保存当前修改；验证只能绑定不可变版本';
    if (saving) return '草稿正在保存，请稍候';
    if (revision.revising) return 'AI 正在修订草稿，请等待新版本生成';
    if (submission.submitting) return '草稿正在提交审批，请稍候';
    if (validation.validationRunning) return '当前版本已有验证任务运行中';
    return undefined;
  }, [
    activeDetail?.status,
    dirty,
    hasManagePermission,
    revision.revising,
    saving,
    submission.submitOutcomeUnknown,
    submission.submitting,
    validation.validationRunning,
  ]);

  const submitGate = useMemo(
    () =>
      getModelingSubmitGateState({
        busy: saving || validation.validationLoading || stage4Busy || !hasManagePermission,
        currentVersionNo,
        dirty,
        draftStatus: activeDetail?.status,
        report: validation.validationReport,
      }),
    [
      activeDetail?.status,
      currentVersionNo,
      dirty,
      hasManagePermission,
      saving,
      stage4Busy,
      validation.validationLoading,
      validation.validationReport,
    ],
  );

  /** 将当前报告阻塞项预填到 AI 修订，不自动调用模型。 */
  const repairValidationBlockingItems = () => {
    const instruction = validation.validationReport
      ? buildValidationRepairInstruction(validation.validationReport)
      : '';
    if (!instruction) {
      message.warning('当前报告没有可带入 AI 修订的阻塞项');
      return;
    }
    revision.setRevisionInstruction(instruction);
    onActiveTabChange('ai-revision');
  };

  /** 人工保存生成新版本后立即失效旧报告、修订摘要和 diff 缓存。 */
  const invalidateAfterVersionChange = () => {
    validation.invalidateValidationReport();
    validation.refreshValidation();
    revision.clearRevisionResult();
    setVersionRefreshToken((value) => value + 1);
  };

  return {
    revisionDisabledReason,
    revisionInstruction: revision.revisionInstruction,
    revisionResult: revision.revisionResult,
    revising: revision.revising,
    setRevisionInstruction: revision.setRevisionInstruction,
    setSqlPreviewLimit: validation.setSqlPreviewLimit,
    sqlPreviewLimit: validation.sqlPreviewLimit,
    stage4Busy,
    submitGate,
    submitOutcomeUnknown: submission.submitOutcomeUnknown,
    reconcilingSubmitOutcome: submission.reconciling,
    submitting: submission.submitting,
    validating: validation.validating,
    validationDisabledReason,
    validationLoading: validation.validationLoading,
    validationRefreshToken: validation.validationRefreshToken,
    validationReport: validation.validationReport,
    validationRunning: validation.validationRunning,
    versionRefreshToken,
    confirmSubmitApproval: () => submission.confirmSubmitApproval(submitGate),
    invalidateAfterVersionChange,
    repairValidationBlockingItems,
    refreshValidation: validation.refreshValidation,
    reconcileSubmitOutcome: submission.reconcileSubmitOutcome,
    runValidation: () => validation.runValidation(validationDisabledReason),
    submitAiRevision: () => revision.submitAiRevision(revisionDisabledReason),
  };
}
