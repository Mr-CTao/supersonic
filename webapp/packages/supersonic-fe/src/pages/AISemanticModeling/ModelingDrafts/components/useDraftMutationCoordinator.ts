/**
 * 草稿写操作互斥协调器。
 *
 * 职责：在人工保存、AI 修订、版本恢复、验证、提交审批和未知结果对账之间提供同步互斥与 latest-wins 令牌。
 * 协调器只保护当前 Drawer 内的客户端请求窗口；服务端幂等键、乐观锁和事务仍是最终一致性边界。
 */
import { useEffect, useRef } from 'react';

export type DraftMutationKind =
  | 'SAVE'
  | 'REVISION'
  | 'RESTORE'
  | 'VALIDATION'
  | 'SUBMISSION'
  | 'RECONCILIATION';

export type DraftMutationToken = Readonly<{
  draftId: number;
  kind: DraftMutationKind;
  sequence: number;
}>;

export type DraftMutationCoordinator = {
  finish: (token: DraftMutationToken) => boolean;
  invalidate: (activeDraftId?: number) => void;
  isBusy: () => boolean;
  isCurrent: (token: DraftMutationToken) => boolean;
  tryStart: (kind: DraftMutationKind, draftId: number) => DraftMutationToken | undefined;
};

/**
 * 创建一个 Drawer 级写操作协调器。
 *
 * @returns 可同步申请互斥令牌、判断迟到响应并使旧请求失效的协调器。
 * @throws 不抛出异常；无活动草稿或已有写操作时 tryStart 返回 undefined。
 */
export function createDraftMutationCoordinator(): DraftMutationCoordinator {
  let activeDraftId: number | undefined;
  let activeToken: DraftMutationToken | undefined;
  let sequence = 0;

  return {
    /** 仅由当前令牌释放互斥锁，防止迟到请求清除新草稿的锁。 */
    finish(token) {
      if (activeToken !== token || activeDraftId !== token.draftId) return false;
      activeToken = undefined;
      return true;
    },
    /** 切换草稿、关闭 Drawer 或卸载时递增序号并废弃旧请求。 */
    invalidate(nextDraftId) {
      sequence += 1;
      activeDraftId = nextDraftId;
      activeToken = undefined;
    },
    /** 同步读取当前是否存在写操作；用于补足 React 状态尚未重渲染的点击窗口。 */
    isBusy() {
      return Boolean(activeToken);
    },
    /** 只有活动草稿及令牌身份均匹配时，异步响应才允许写回页面状态。 */
    isCurrent(token) {
      return activeDraftId === token.draftId && activeToken === token;
    },
    /** 同一 Drawer 同一时刻只签发一个保存或阶段 4 写操作令牌。 */
    tryStart(kind, draftId) {
      if (!draftId || activeDraftId !== draftId || activeToken) return undefined;
      const token = Object.freeze({ draftId, kind, sequence: ++sequence });
      activeToken = token;
      return token;
    },
  };
}

/**
 * 将协调器生命周期绑定到当前 Drawer 草稿。
 *
 * @param draftId 当前草稿 ID。
 * @param open Drawer 是否打开。
 * @returns 在保存与阶段 4 Hook 之间共享的稳定协调器实例。
 * @throws 不抛出异常；关闭或卸载会同步使所有令牌失效。
 */
export function useDraftMutationCoordinator(
  draftId: number | undefined,
  open: boolean,
): DraftMutationCoordinator {
  const coordinatorRef = useRef<DraftMutationCoordinator>();
  if (!coordinatorRef.current) coordinatorRef.current = createDraftMutationCoordinator();
  const coordinator = coordinatorRef.current;

  useEffect(() => {
    coordinator.invalidate(open ? draftId : undefined);
    return () => coordinator.invalidate(undefined);
  }, [coordinator, draftId, open]);

  return coordinator;
}
