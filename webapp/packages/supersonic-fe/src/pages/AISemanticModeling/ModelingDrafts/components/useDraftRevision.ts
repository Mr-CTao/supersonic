/**
 * 阶段 4 AI 修订 Hook。
 *
 * 职责：基于明确 baseVersionNo 和幂等键提交 AI 修订，维护未知结果重试语义，并通过共享写
 * 协调器阻止与保存、验证或提交审批并发。迟到响应不会覆盖已切换草稿。
 */
import { message } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  aiReviseModelingDraft,
  type AiReviseModelingDraftResp,
  type ModelingDraftItem,
} from '@/services/semanticModelingDraft';
import {
  getModelingRevisionFailureDisposition,
  getRequestErrorText,
  parseDraftJson,
  stringifyDraftJson,
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
  applyDetail: (detail: ModelingDraftItem) => void;
  invalidateValidationReport: () => void;
  onActiveTabChange: (tab: string) => void;
  onChanged?: () => void;
  onVersionCreated: () => void;
  reloadAfterUnknownOutcome: () => Promise<boolean>;
  requestDetail: (
    showLoading: boolean,
    showError: boolean,
  ) => Promise<ModelingDraftItem | undefined>;
};

/**
 * 管理 AI 修订输入、幂等重试、乐观版本基线和 latest-wins。
 *
 * @param input 当前草稿、共享协调器、报告失效及详情刷新回调。
 * @returns 修订状态、输入 setter、结果清理和受互斥锁保护的提交函数。
 * @throws 不向组件抛出请求异常；统一转换为脱敏 message。
 */
export function useDraftRevision({
  activeDetail,
  currentVersionNo,
  draftId,
  mutationCoordinator,
  open,
  applyDetail,
  invalidateValidationReport,
  onActiveTabChange,
  onChanged,
  onVersionCreated,
  reloadAfterUnknownOutcome,
  requestDetail,
}: Input) {
  const [revisionInstruction, setRevisionInstruction] = useState('');
  const [revisionResult, setRevisionResult] = useState<AiReviseModelingDraftResp>();
  const [revising, setRevising] = useState(false);
  const idempotencyKeyRef = useRef('');
  const instructionSignatureRef = useRef('');
  const baseVersionRef = useRef<number>();
  const outcomeUnknownRef = useRef(false);

  useEffect(() => {
    setRevisionInstruction('');
    setRevisionResult(undefined);
    setRevising(false);
    idempotencyKeyRef.current = '';
    instructionSignatureRef.current = '';
    baseVersionRef.current = undefined;
    outcomeUnknownRef.current = false;
  }, [draftId, open]);

  /** 基于明确 baseVersionNo 修订，并仅在网络结果未知时复用同一个幂等键。 */
  const submitAiRevision = async (disabledReason?: string) => {
    const instruction = revisionInstruction.trim();
    if (!draftId || !activeDetail || !currentVersionNo || disabledReason) {
      message.warning(disabledReason || '当前草稿版本不可修订');
      return;
    }
    if (!instruction) {
      message.warning('请输入 AI 修订意见');
      return;
    }
    const signature = `${currentVersionNo}:${instruction}`;
    const pendingBaseVersion = baseVersionRef.current;
    const retryingUnknownOutcome = Boolean(
      outcomeUnknownRef.current &&
        pendingBaseVersion &&
        instructionSignatureRef.current === `${pendingBaseVersion}:${instruction}` &&
        [pendingBaseVersion, pendingBaseVersion + 1].includes(currentVersionNo),
    );
    if (!retryingUnknownOutcome && instructionSignatureRef.current !== signature) {
      instructionSignatureRef.current = signature;
      baseVersionRef.current = currentVersionNo;
      outcomeUnknownRef.current = false;
      idempotencyKeyRef.current = createIdempotencyKey();
    }
    const token = mutationCoordinator.tryStart('REVISION', draftId);
    if (!token) {
      message.warning('草稿正在执行其他写操作，请等待完成');
      return;
    }
    const requestBaseVersionNo = baseVersionRef.current ?? currentVersionNo;
    invalidateValidationReport();
    setRevising(true);
    try {
      const result = unwrapResponseData<AiReviseModelingDraftResp>(
        await aiReviseModelingDraft(
          draftId,
          { instruction, baseVersionNo: requestBaseVersionNo },
          idempotencyKeyRef.current,
        ),
      );
      if (!mutationCoordinator.isCurrent(token)) return;
      const revisedDraft = parseDraftJson(result.draftJson);
      if (!revisedDraft.value) throw new Error(revisedDraft.error || 'AI 修订结果无法解析');
      applyDetail({
        ...activeDetail,
        currentDraft: revisedDraft.value,
        draftJson: stringifyDraftJson(revisedDraft.value),
        currentVersion: result.newVersionNo,
        currentVersionNo: result.newVersionNo,
        lockVersion: result.lockVersion,
      });
      setRevisionResult(result);
      setRevisionInstruction('');
      instructionSignatureRef.current = '';
      baseVersionRef.current = undefined;
      outcomeUnknownRef.current = false;
      idempotencyKeyRef.current = '';
      onVersionCreated();
      await requestDetail(true, true);
      if (!mutationCoordinator.isCurrent(token)) return;
      onActiveTabChange('version-diff');
      message.success(`AI 修订已生成版本 ${result.newVersionNo}`);
      onChanged?.();
    } catch (error) {
      if (!mutationCoordinator.isCurrent(token)) return;
      const disposition = getModelingRevisionFailureDisposition(error);
      if (!disposition.reuseIdempotencyKey) {
        instructionSignatureRef.current = '';
        baseVersionRef.current = undefined;
        outcomeUnknownRef.current = false;
        idempotencyKeyRef.current = '';
      } else {
        outcomeUnknownRef.current = true;
      }
      message[disposition.baseVersionConflict ? 'warning' : 'error'](
        disposition.baseVersionConflict
          ? 'AI 修订基线版本已过期，正在重新加载最新版本'
          : getRequestErrorText(error),
      );
      await reloadAfterUnknownOutcome();
    } finally {
      if (mutationCoordinator.finish(token)) setRevising(false);
    }
  };

  return {
    clearRevisionResult: () => setRevisionResult(undefined),
    revisionInstruction,
    revisionResult,
    revising,
    setRevisionInstruction,
    submitAiRevision,
  };
}
