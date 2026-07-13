/**
 * 阶段 4 草稿版本历史与差异请求 Hook。
 *
 * 职责：分页读取不可变版本、协调版本选择、执行结构化 diff，并使用请求序号阻止草稿/版本切换后的
 * 迟到响应覆盖当前状态。恢复操作通过共享写协调器追加新版本，绝不修改历史快照或正式语义资产。
 */
import { message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  getModelingDraftVersionDiff,
  getModelingDraftVersions,
  restoreModelingDraftVersion,
  type AiReviseModelingDraftResp,
  type ModelingDraftChangeItem,
  type ModelingDraftVersion,
  type ModelingDraftVersionDiff,
} from '@/services/semanticModelingDraft';
import {
  getRequestErrorText,
  isModelingVersionDiffForSelection,
  unwrapResponseData,
} from '../utils';
import { createIdempotencyKey } from './create/creationUtils';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';

const VERSION_PAGE_SIZE = 50;

type Input = {
  active: boolean;
  currentVersionNo?: number;
  draftId: number;
  lockVersion?: number;
  canRestore?: boolean;
  mutationCoordinator?: DraftMutationCoordinator;
  onVersionRestored?: () => Promise<void> | void;
  recentRevision?: AiReviseModelingDraftResp;
  refreshToken?: number;
};

/**
 * 管理版本历史与差异的 latest-wins 状态。
 *
 * @param input 激活状态、草稿 ID、当前版本、最近修订和外部刷新令牌。
 * @returns 版本、选择、差异、loading 与有界操作函数。
 * @throws 不向调用方抛出请求异常；错误通过统一 message 展示。
 */
export function useDraftVersionDiff({
  active,
  currentVersionNo,
  draftId,
  lockVersion,
  canRestore = false,
  mutationCoordinator,
  onVersionRestored,
  recentRevision,
  refreshToken,
}: Input) {
  const [versions, setVersions] = useState<ModelingDraftVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionsLoadingMore, setVersionsLoadingMore] = useState(false);
  const [versionPage, setVersionPage] = useState(0);
  const [versionTotal, setVersionTotal] = useState(0);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diff, setDiff] = useState<ModelingDraftVersionDiff>();
  const [fromVersionNo, setFromVersionNo] = useState<number>();
  const [toVersionNo, setToVersionNo] = useState<number>();
  const [localRefreshToken, setLocalRefreshToken] = useState(0);
  const [restoringVersionNo, setRestoringVersionNo] = useState<number>();
  const versionRequestRef = useRef(0);
  const diffRequestRef = useRef(0);
  const restoreIdempotencyKeyRef = useRef('');
  const restoreSignatureRef = useRef('');

  useEffect(
    () => () => {
      diffRequestRef.current += 1;
    },
    [active, currentVersionNo, draftId],
  );

  useEffect(() => {
    if (!active || !draftId) return undefined;
    let mounted = true;
    const requestId = ++versionRequestRef.current;
    diffRequestRef.current += 1;
    setDiff(undefined);
    setDiffLoading(false);
    setVersions([]);
    setVersionPage(0);
    setVersionTotal(0);
    setFromVersionNo(undefined);
    setToVersionNo(undefined);
    setVersionsLoadingMore(false);
    setVersionsLoading(true);
    /** 首批版本默认选择“上一版本到当前版本”。 */
    const loadVersions = async () => {
      try {
        const data =
          unwrapResponseData<any>(
            await getModelingDraftVersions(draftId, { page: 1, pageSize: VERSION_PAGE_SIZE }),
          ) || {};
        const list = (Array.isArray(data) ? data : data.list || []) as ModelingDraftVersion[];
        const sorted = [...list].sort((left, right) => right.versionNo - left.versionNo);
        if (!mounted || requestId !== versionRequestRef.current) return;
        setVersions(sorted);
        setVersionPage(1);
        setVersionTotal(Number(Array.isArray(data) ? sorted.length : data.total ?? sorted.length));
        const target =
          sorted.find((item) => item.versionNo === currentVersionNo)?.versionNo ||
          currentVersionNo ||
          sorted[0]?.versionNo;
        setToVersionNo(target);
        setFromVersionNo(sorted.find((item) => target && item.versionNo < target)?.versionNo);
      } catch (error) {
        if (mounted && requestId === versionRequestRef.current) {
          message.error(getRequestErrorText(error));
        }
      } finally {
        if (mounted && requestId === versionRequestRef.current) setVersionsLoading(false);
      }
    };
    void loadVersions();
    return () => {
      mounted = false;
      versionRequestRef.current += 1;
    };
  }, [active, currentVersionNo, draftId, localRefreshToken, refreshToken]);

  /** 追加更早版本，避免一次性无界读取所有版本。 */
  const loadMoreVersions = useCallback(async () => {
    if (
      !active ||
      !draftId ||
      versionsLoading ||
      versionsLoadingMore ||
      versions.length >= versionTotal
    ) {
      return;
    }
    const nextPage = versionPage + 1;
    const requestId = ++versionRequestRef.current;
    setVersionsLoadingMore(true);
    try {
      const data =
        unwrapResponseData<any>(
          await getModelingDraftVersions(draftId, {
            page: nextPage,
            pageSize: VERSION_PAGE_SIZE,
          }),
        ) || {};
      const list = (Array.isArray(data) ? data : data.list || []) as ModelingDraftVersion[];
      if (requestId !== versionRequestRef.current) return;
      setVersions((previous) => {
        const merged = new Map(previous.map((item) => [item.versionNo, item]));
        list.forEach((item) => merged.set(item.versionNo, item));
        return [...merged.values()].sort((left, right) => right.versionNo - left.versionNo);
      });
      setVersionPage(nextPage);
      setVersionTotal(
        Number(
          Array.isArray(data)
            ? Math.max(versionTotal, versions.length + list.length)
            : data.total ?? versionTotal,
        ),
      );
    } catch (error) {
      if (requestId === versionRequestRef.current) message.error(getRequestErrorText(error));
    } finally {
      if (requestId === versionRequestRef.current) setVersionsLoadingMore(false);
    }
  }, [
    active,
    draftId,
    versionPage,
    versionTotal,
    versions.length,
    versionsLoading,
    versionsLoadingMore,
  ]);

  /** 查询当前版本对，快速切换时只接受最后一次响应。 */
  const loadDiff = useCallback(async () => {
    if (!fromVersionNo || !toVersionNo || fromVersionNo === toVersionNo) return;
    const requestId = ++diffRequestRef.current;
    setDiff(undefined);
    setDiffLoading(true);
    try {
      const data = unwrapResponseData<ModelingDraftVersionDiff>(
        await getModelingDraftVersionDiff(draftId, fromVersionNo, toVersionNo),
      );
      if (requestId !== diffRequestRef.current) return;
      if (!isModelingVersionDiffForSelection(data, draftId, fromVersionNo, toVersionNo)) {
        message.error('版本差异响应与当前选择不一致，请重新比较');
        return;
      }
      setDiff(data);
    } catch (error) {
      if (requestId === diffRequestRef.current) message.error(getRequestErrorText(error));
    } finally {
      if (requestId === diffRequestRef.current) setDiffLoading(false);
    }
  }, [draftId, fromVersionNo, toVersionNo]);

  useEffect(() => {
    if (active && fromVersionNo && toVersionNo && fromVersionNo !== toVersionNo) {
      void loadDiff();
    }
  }, [active, fromVersionNo, loadDiff, toVersionNo]);

  const immediateDiff = useMemo<ModelingDraftVersionDiff | undefined>(() => {
    if (!recentRevision) return undefined;
    return {
      draftId: recentRevision.draftId,
      fromVersionNo: recentRevision.baseVersionNo,
      toVersionNo: recentRevision.newVersionNo,
      summary: recentRevision.changeSummary,
      items: recentRevision.changes,
      truncated: false,
    };
  }, [recentRevision]);
  const visibleDiff = isModelingVersionDiffForSelection(diff, draftId, fromVersionNo, toVersionNo)
    ? diff
    : isModelingVersionDiffForSelection(immediateDiff, draftId, fromVersionNo, toVersionNo)
    ? immediateDiff
    : undefined;

  /** 选择起始版本并立即失效旧差异。 */
  const selectFromVersion = (value: number) => {
    diffRequestRef.current += 1;
    setFromVersionNo(value);
    setDiff(undefined);
  };

  /** 选择目标版本并立即失效旧差异。 */
  const selectToVersion = (value: number) => {
    diffRequestRef.current += 1;
    setToVersionNo(value);
    setDiff(undefined);
  };

  /**
   * 将历史快照追加恢复为当前版本的下一版本。
   *
   * @param targetVersionNo 要恢复的历史版本号。
   * @returns 恢复请求完成后解析；重复点击或前置条件不满足时直接返回。
   * @throws 不向组件抛出请求异常；错误通过统一 message 展示。
   */
  const restoreVersion = async (targetVersionNo: number) => {
    if (
      !canRestore ||
      !mutationCoordinator ||
      !currentVersionNo ||
      lockVersion === undefined ||
      targetVersionNo === currentVersionNo
    ) {
      message.warning('当前草稿状态或版本不允许恢复');
      return;
    }
    const token = mutationCoordinator.tryStart('RESTORE', draftId);
    if (!token) {
      message.warning('草稿正在执行其他写操作，请等待完成');
      return;
    }
    const restoreSignature = `${draftId}:${targetVersionNo}:${currentVersionNo}:${lockVersion}`;
    if (restoreSignatureRef.current !== restoreSignature) {
      restoreSignatureRef.current = restoreSignature;
      restoreIdempotencyKeyRef.current = createIdempotencyKey();
    }
    setRestoringVersionNo(targetVersionNo);
    try {
      await restoreModelingDraftVersion(
        draftId,
        targetVersionNo,
        { currentVersionNo, lockVersion },
        restoreIdempotencyKeyRef.current,
      );
      if (!mutationCoordinator.isCurrent(token)) return;
      // 先失效本地历史与 diff，再由 Drawer 读取服务端最新详情和验证状态。
      versionRequestRef.current += 1;
      diffRequestRef.current += 1;
      setDiff(undefined);
      setLocalRefreshToken((value) => value + 1);
      await onVersionRestored?.();
      if (!mutationCoordinator.isCurrent(token)) return;
      message.success(`已将版本 ${targetVersionNo} 恢复为新的草稿版本`);
    } catch (error) {
      if (mutationCoordinator.isCurrent(token)) message.error(getRequestErrorText(error));
    } finally {
      if (mutationCoordinator.finish(token)) setRestoringVersionNo(undefined);
    }
  };

  return {
    compareDisabled: !fromVersionNo || !toVersionNo || fromVersionNo === toVersionNo || diffLoading,
    diffItems: (visibleDiff?.items || []) as ModelingDraftChangeItem[],
    diffLoading,
    fromVersionNo,
    hasMoreVersions: versions.length < versionTotal,
    toVersionNo,
    versionOptions: versions.map((item) => ({
      label: `版本 ${item.versionNo} · ${item.changeSummary || '无摘要'}`,
      value: item.versionNo,
    })),
    versionTotal,
    versions,
    versionsLoading,
    versionsLoadingMore,
    restoringVersionNo,
    visibleDiff,
    loadDiff,
    loadMoreVersions,
    refreshVersions: () => setLocalRefreshToken((value) => value + 1),
    restoreVersion,
    selectFromVersion,
    selectToVersion,
  };
}
