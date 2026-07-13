/**
 * 草稿生成状态通知 Hook。
 *
 * 职责：仅在异步生成真正离开 GENERATING 时通知父列表刷新，避免详情两秒轮询放大列表请求。
 */
import { useEffect, useRef } from 'react';
import type { ModelingDraftStatus } from '@/services/semanticModelingDraft';

/**
 * 监听生成完成状态迁移。
 *
 * @param status 当前活动草稿状态。
 * @param onChanged 生成完成后触发的父列表刷新回调。
 * @returns 无返回值。
 * @throws 不抛出异常。
 */
export function useDraftStatusNotification(
  status: ModelingDraftStatus | undefined,
  onChanged?: () => void,
): void {
  const observedStatusRef = useRef<ModelingDraftStatus>();

  useEffect(() => {
    const previousStatus = observedStatusRef.current;
    observedStatusRef.current = status;
    if (previousStatus === 'GENERATING' && status && status !== 'GENERATING') onChanged?.();
  }, [onChanged, status]);
}
