/**
 * 语义建模草稿人工保存 Hook。
 *
 * 职责：使用乐观锁保存当前结构化草稿，并通过 Drawer 共享写协调器丢弃切换后的迟到响应。
 * 保存成功后通知阶段 4 工作流失效旧验证报告；Hook 不处理 AI 修订、验证或提交审批。
 */
import { message, Modal } from 'antd';
import { useEffect, useState } from 'react';
import {
  updateModelingDraft,
  type ModelingDraftItem,
  type SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';
import { getRequestErrorText, isOptimisticLockConflict, unwrapResponseData } from '../utils';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';

type Input = {
  activeDetail?: ModelingDraftItem;
  draftId?: number;
  editable: boolean;
  mutationCoordinator: DraftMutationCoordinator;
  open: boolean;
  parsedDraft?: SemanticModelingDraftJson;
  parsedDraftError?: string;
  applyDetail: (detail: ModelingDraftItem) => void;
  onChanged?: () => void;
  onVersionChanged: () => void;
  requestDetail: (
    showLoading: boolean,
    showError: boolean,
  ) => Promise<ModelingDraftItem | undefined>;
};

/** 兼容旧后端缺失权限字段的保存响应，仅保留此前已由服务端确认的管理能力。 */
function preserveVerifiedManageCapability(
  nextDetail: ModelingDraftItem,
  activeDetail: ModelingDraftItem,
): ModelingDraftItem {
  if (nextDetail.canManage !== null && nextDetail.canManage !== undefined) return nextDetail;
  return activeDetail.canManage === true ? { ...nextDetail, canManage: true } : nextDetail;
}

/**
 * 管理人工保存 loading、乐观锁冲突和 latest-wins。
 *
 * @param input 当前草稿、解析结果及父控制器回调。
 * @returns saving 和受重复点击锁保护的 saveDraft。
 * @throws 不向组件抛出异常；错误通过统一 message/Modal 展示。
 */
export function useDraftSave({
  activeDetail,
  draftId,
  editable,
  mutationCoordinator,
  open,
  parsedDraft,
  parsedDraftError,
  applyDetail,
  onChanged,
  onVersionChanged,
  requestDetail,
}: Input) {
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setSaving(false);
  }, [draftId, open]);

  /** 保存当前草稿；共享协调器保证同一 Drawer 只接受一个有效写响应。 */
  const saveDraft = async () => {
    if (!draftId || !activeDetail || !parsedDraft || !editable) {
      if (parsedDraftError) message.error(parsedDraftError);
      return;
    }
    // 共享协调器在 React 重渲染前同步锁住保存和全部阶段 4 写动作。
    const token = mutationCoordinator.tryStart('SAVE', draftId);
    if (!token) {
      message.warning('草稿正在执行其他写操作，请等待完成');
      return;
    }
    setSaving(true);
    try {
      const nextDetail = unwrapResponseData<ModelingDraftItem>(
        await updateModelingDraft(draftId, {
          lockVersion: activeDetail.lockVersion,
          currentDraft: parsedDraft,
          changeSummary: '管理员在建模草稿页面手动编辑',
        }),
      );
      if (!mutationCoordinator.isCurrent(token)) return;
      // 后端新版本会显式返回 true；此兼容分支防止滚动升级期间旧实例的 null 响应使管理员临时只读。
      applyDetail(preserveVerifiedManageCapability(nextDetail, activeDetail));
      onVersionChanged();
      message.success('草稿已保存并生成新版本');
      onChanged?.();
    } catch (error) {
      if (!mutationCoordinator.isCurrent(token)) return;
      if (isOptimisticLockConflict(error)) {
        Modal.confirm({
          title: '草稿已被其他操作更新',
          content: '当前编辑基于旧版本，不能静默覆盖。重新加载会丢弃本地未保存内容。',
          okText: '重新加载最新版本',
          cancelText: '保留本地内容',
          onOk: () => requestDetail(true, true),
        });
      } else {
        message.error(getRequestErrorText(error));
      }
    } finally {
      if (mutationCoordinator.finish(token)) setSaving(false);
    }
  };

  return { saving, saveDraft };
}
