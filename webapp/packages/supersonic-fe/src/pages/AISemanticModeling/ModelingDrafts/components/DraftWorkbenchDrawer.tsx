/**
 * AI 语义建模草稿详情工作台控制器：负责详情轮询和状态组合，保存、阶段 4 工作流及纯视图分别由
 * 专用 Hook/组件承担。详情请求使用序号丢弃迟到响应，GENERATING 状态每 2 秒轮询。
 */
import { message, Modal } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getModelingDraftDetail, type ModelingDraftItem } from '@/services/semanticModelingDraft';
import {
  getRequestErrorText,
  getRegenerationAvailability,
  hasModelingManagePermission,
  parseDraftDetail,
  parseDraftJson,
  stringifyDraftJson,
  unwrapResponseData,
} from '../utils';
import DraftWorkbenchDrawerView from './DraftWorkbenchDrawerView';
import { useDraftMutationCoordinator } from './useDraftMutationCoordinator';
import { useDraftSave } from './useDraftSave';
import { useDraftStage4Workflow } from './useDraftStage4Workflow';
import { useDraftStatusNotification } from './useDraftStatusNotification';

const POLLING_INTERVAL_MS = 2000;

type Props = {
  draftId?: number;
  open: boolean;
  onClose: () => void;
  onChanged?: () => void;
};

/**
 * 草稿详情工作台控制器。
 * @param props 草稿 ID、Drawer 状态和变更回调。
 * @returns 已组合详情、保存和阶段 4 工作流的 Drawer。
 * @throws 不向上抛出异常；请求错误在内部归一化展示。
 */
const DraftWorkbenchDrawer: React.FC<Props> = ({ draftId, open, onClose, onChanged }) => {
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<ModelingDraftItem>();
  const [jsonText, setJsonText] = useState('');
  const [loadParseError, setLoadParseError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [attemptOpen, setAttemptOpen] = useState(false);
  const [regenerateOpen, setRegenerateOpen] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [pollCycle, setPollCycle] = useState(0);
  const [activeTab, setActiveTab] = useState('semantic-structure');
  const mountedRef = useRef(true);
  const requestRef = useRef(0);
  const versionChangedRef = useRef<() => void>(() => undefined);

  useEffect(() => {
    // StrictMode 开发态会重建 effect，显式恢复标记避免后续合法请求被当作迟到响应。
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestRef.current += 1;
    };
  }, []);

  useEffect(() => {
    // 切换草稿或关闭抽屉时立即失效旧请求与旧详情，避免请求窗口内误操作上一条草稿。
    requestRef.current += 1;
    setDetail(undefined);
    setJsonText('');
    setLoadParseError('');
    setDirty(false);
    setVersionOpen(false);
    setAttemptOpen(false);
    setRegenerateOpen(false);
    setActiveTab('semantic-structure');
  }, [draftId, open]);

  /** 将最新详情同步为对象表单与 JSON 共用的文本状态。 */
  const applyDetail = useCallback((nextDetail: ModelingDraftItem) => {
    const parsed = parseDraftDetail(nextDetail);
    setDetail(nextDetail);
    setJsonText(parsed.value ? stringifyDraftJson(parsed.value) : '');
    setLoadParseError(parsed.value ? '' : parsed.error || '草稿结构无法解析');
    setDirty(false);
  }, []);

  /**
   * 查询一次最新详情。
   *
   * @param showLoading 是否显示首屏 loading。
   * @param showError 是否弹出错误；后台轮询关闭以避免刷屏。
   * @returns 最新详情，失败或过期时返回 undefined。
   * @throws 请求错误在方法内归一化处理。
   */
  const requestDetail = useCallback(
    async (showLoading: boolean, showError: boolean) => {
      if (!draftId) return undefined;
      const requestId = ++requestRef.current;
      if (showLoading) setLoading(true);
      try {
        const data = unwrapResponseData<ModelingDraftItem>(await getModelingDraftDetail(draftId));
        if (mountedRef.current && requestId === requestRef.current) {
          applyDetail(data);
          return data;
        }
      } catch (error) {
        if (showError && mountedRef.current && requestId === requestRef.current) {
          message.error(getRequestErrorText(error));
        }
      } finally {
        if (showLoading && mountedRef.current && requestId === requestRef.current) {
          setLoading(false);
        }
      }
      return undefined;
    },
    [applyDetail, draftId],
  );

  useEffect(() => {
    if (!open || !draftId) return undefined;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    /** 首次加载后仅对生成中或暂时失败的查询继续轮询。 */
    const poll = async (first: boolean) => {
      const data = await requestDetail(first, first);
      if (!cancelled && (!data || data.status === 'GENERATING')) {
        timer = setTimeout(() => void poll(false), POLLING_INTERVAL_MS);
      }
    };
    void poll(true);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
      requestRef.current += 1;
    };
  }, [draftId, open, pollCycle, requestDetail]);

  const parsedDraft = useMemo(() => parseDraftJson(jsonText), [jsonText]);
  // 渲染层校验 ID，封住 effect 执行前旧详情短暂可见的竞态窗口。
  const activeDetail = detail?.id === draftId ? detail : undefined;
  useDraftStatusNotification(activeDetail?.status, onChanged);
  const hasManagePermission = hasModelingManagePermission(activeDetail);
  const currentVersionNo = activeDetail
    ? Number(activeDetail.currentVersionNo ?? activeDetail.currentVersion ?? 0)
    : undefined;
  // 同一协调器覆盖人工保存和全部阶段 4 写操作，消除两个独立 ref 之间的竞态窗口。
  const mutationCoordinator = useDraftMutationCoordinator(draftId, open);
  const saveWorkflow = useDraftSave({
    activeDetail,
    draftId,
    editable: activeDetail?.status === 'DRAFT' && hasManagePermission,
    mutationCoordinator,
    open,
    parsedDraft: parsedDraft.value,
    parsedDraftError: parsedDraft.error,
    applyDetail,
    onChanged,
    onVersionChanged: () => versionChangedRef.current(),
    requestDetail,
  });
  const { saving, saveDraft } = saveWorkflow;
  const stage4 = useDraftStage4Workflow({
    activeDetail,
    currentVersionNo,
    dirty,
    draftId,
    hasManagePermission,
    mutationCoordinator,
    open,
    parsedDraftError: parsedDraft.error,
    saving,
    applyDetail,
    onActiveTabChange: setActiveTab,
    onChanged,
    requestDetail,
  });
  versionChangedRef.current = stage4.invalidateAfterVersionChange;
  const {
    revisionDisabledReason,
    revisionInstruction,
    revisionResult,
    revising,
    reconcilingSubmitOutcome,
    setRevisionInstruction,
    setSqlPreviewLimit,
    sqlPreviewLimit,
    stage4Busy,
    submitGate,
    submitting,
    validating,
    validationDisabledReason,
    validationLoading,
    validationReport,
    validationRunning,
    versionRefreshToken,
    confirmSubmitApproval,
    refreshValidation,
    repairValidationBlockingItems,
    runValidation,
    submitAiRevision,
  } = stage4;
  const writeBlockedReason = !hasManagePermission
    ? '当前账号只有草稿读取权限；保存、重新生成、AI 修订、验证和提交审批仅对数据源及主题域管理员开放。'
    : stage4.submitOutcomeUnknown
    ? '提交响应结果尚未确认。为避免服务端已进入待审批而继续修改，页面暂时只读；请点击刷新完成对账。'
    : undefined;
  const editable = activeDetail?.status === 'DRAFT' && !writeBlockedReason;
  const regenerationAvailability = useMemo(
    () =>
      !hasManagePermission
        ? { allowed: false, reason: '当前账号没有草稿管理权限' }
        : getRegenerationAvailability(activeDetail),
    [activeDetail, hasManagePermission],
  );

  /** 恢复成功后统一失效旧报告、diff 和修订摘要，并以服务端新版本覆盖本地详情。 */
  const handleVersionRestored = useCallback(async () => {
    stage4.invalidateAfterVersionChange();
    const latest = await requestDetail(true, true);
    if (latest) onChanged?.();
  }, [onChanged, requestDetail, stage4]);

  /** 关闭前确认是否放弃本地未保存修改。 */
  const closeWorkbench = () => {
    if (saving || revising || validating || submitting || reconcilingSubmitOutcome) {
      message.warning('当前操作尚未完成，请稍候再关闭工作台');
      return;
    }
    if (!dirty) return onClose();
    Modal.confirm({
      title: '放弃未保存修改？',
      content: '关闭工作台后，本地对象表单和 JSON 编辑内容不会保留。',
      okText: '放弃并关闭',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: onClose,
    });
  };

  return (
    <DraftWorkbenchDrawerView
      activeDetail={activeDetail}
      activeTab={activeTab}
      attemptOpen={attemptOpen}
      currentVersionNo={currentVersionNo}
      dirty={dirty}
      draft={parsedDraft.value}
      draftId={draftId}
      editable={editable}
      jsonError={parsedDraft.error}
      jsonText={jsonText}
      loadParseError={loadParseError}
      loading={loading || reconcilingSubmitOutcome}
      mutationCoordinator={mutationCoordinator}
      open={open}
      regenerateOpen={regenerateOpen}
      regenerating={regenerating}
      regenerationAvailability={regenerationAvailability}
      revisionDisabledReason={revisionDisabledReason}
      revisionInstruction={revisionInstruction}
      revisionResult={revisionResult}
      revising={revising}
      saving={saving}
      sqlPreviewLimit={sqlPreviewLimit}
      stage4Busy={stage4Busy}
      submitGate={submitGate}
      submitting={submitting}
      validating={validating}
      validationDisabledReason={validationDisabledReason}
      validationLoading={validationLoading}
      validationReport={validationReport}
      validationRunning={validationRunning}
      versionOpen={versionOpen}
      versionRefreshToken={versionRefreshToken}
      writeBlockedReason={writeBlockedReason}
      onActiveTabChange={setActiveTab}
      onAttemptOpenChange={setAttemptOpen}
      onClose={closeWorkbench}
      onDraftChange={(draft) => {
        setJsonText(stringifyDraftJson(draft));
        setDirty(true);
      }}
      onJsonTextChange={(value) => {
        setJsonText(value);
        setDirty(true);
      }}
      onRefresh={() =>
        void (stage4.submitOutcomeUnknown
          ? stage4.reconcileSubmitOutcome()
          : requestDetail(true, true))
      }
      onRegenerateOpenChange={setRegenerateOpen}
      onRegenerateSubmitted={(nextDetail) => {
        setRegenerateOpen(false);
        applyDetail(nextDetail);
        setPollCycle((value) => value + 1);
        onChanged?.();
      }}
      onRegeneratingChange={setRegenerating}
      onRepairBlockingItems={repairValidationBlockingItems}
      onRevisionInstructionChange={setRevisionInstruction}
      onRunValidation={() => void runValidation()}
      onSave={() => void saveDraft()}
      onSqlPreviewLimitChange={setSqlPreviewLimit}
      onSubmitApproval={confirmSubmitApproval}
      onSubmitRevision={() => void submitAiRevision()}
      onValidationRefresh={refreshValidation}
      onVersionOpenChange={setVersionOpen}
      onVersionRestored={handleVersionRestored}
    />
  );
};

export default DraftWorkbenchDrawer;
