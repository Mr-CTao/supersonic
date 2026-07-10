/**
 * AI 语义建模草稿详情工作台控制器。
 *
 * 职责：加载详情、轮询生成状态、维护 JSON/对象表单共享状态、执行乐观锁保存，并协调重新生成、生成尝试和版本历史。
 *
 * 并发说明：请求序号丢弃迟到响应；仅 GENERATING 每 2 秒轮询；保存使用 loading/dirty 双锁，409 必须显式确认重载。
 */
import {
  HistoryOutlined,
  ProfileOutlined,
  RedoOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { Button, Drawer, Empty, message, Modal, Space, Spin, Tooltip } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getModelingDraftDetail, updateModelingDraft } from '@/services/semanticModelingDraft';
import type { ModelingDraftItem } from '@/services/semanticModelingDraft';
import {
  getRequestErrorText,
  getRegenerationAvailability,
  isOptimisticLockConflict,
  parseDraftDetail,
  parseDraftJson,
  stringifyDraftJson,
  unwrapResponseData,
} from '../utils';
import DraftWorkbenchContent from './DraftWorkbenchContent';
import GenerationAttemptHistoryDrawer from './GenerationAttemptHistoryDrawer';
import RegenerateDraftModal from './RegenerateDraftModal';
import VersionHistoryDrawer from './VersionHistoryDrawer';
import styles from '../style.less';

const POLLING_INTERVAL_MS = 2000;

type Props = {
  draftId?: number;
  open: boolean;
  onClose: () => void;
  onChanged?: () => void;
};

/**
 * 草稿详情工作台控制器组件。
 *
 * @param props 草稿 ID、抽屉状态和数据变更回调。
 * @returns 可轮询、编辑、保存和查看版本的工作台。
 * @throws 不向上抛出异常；请求失败通过 message 或确认框展示。
 */
const DraftWorkbenchDrawer: React.FC<Props> = ({ draftId, open, onClose, onChanged }) => {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [detail, setDetail] = useState<ModelingDraftItem>();
  const [jsonText, setJsonText] = useState('');
  const [loadParseError, setLoadParseError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [attemptOpen, setAttemptOpen] = useState(false);
  const [regenerateOpen, setRegenerateOpen] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [pollCycle, setPollCycle] = useState(0);
  const mountedRef = useRef(true);
  const requestRef = useRef(0);
  const observedStatusRef = useRef<ModelingDraftItem['status']>();

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
  // effect 在浏览器绘制后执行，因此渲染层仍需校验 ID，彻底封住旧详情短暂可见的竞态窗口。
  const activeDetail = detail?.id === draftId ? detail : undefined;
  const editable = activeDetail?.status === 'DRAFT';
  const regenerationAvailability = useMemo(
    () => getRegenerationAvailability(activeDetail),
    [activeDetail],
  );

  useEffect(() => {
    const previousStatus = observedStatusRef.current;
    const currentStatus = activeDetail?.status;
    observedStatusRef.current = currentStatus;
    // 只在异步生成真正离开 GENERATING 时刷新父列表，避免每次两秒轮询都触发额外列表请求。
    if (previousStatus === 'GENERATING' && currentStatus && currentStatus !== 'GENERATING') {
      onChanged?.();
    }
  }, [activeDetail?.status, onChanged]);

  /** 保存当前结构化草稿，并显式处理乐观锁冲突。 */
  const saveDraft = async () => {
    if (!draftId || !activeDetail || !parsedDraft.value || !editable) {
      if (parsedDraft.error) message.error(parsedDraft.error);
      return;
    }
    setSaving(true);
    try {
      const response = await updateModelingDraft(draftId, {
        lockVersion: activeDetail.lockVersion,
        currentDraft: parsedDraft.value,
        changeSummary: '管理员在建模草稿页面手动编辑',
      });
      applyDetail(unwrapResponseData<ModelingDraftItem>(response));
      message.success('草稿已保存并生成新版本');
      onChanged?.();
    } catch (error) {
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
      if (mountedRef.current) setSaving(false);
    }
  };

  /** 关闭前确认是否放弃本地未保存修改。 */
  const closeWorkbench = () => {
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
    <>
      <Drawer
        destroyOnHidden
        maskClosable={!dirty && !saving}
        open={open}
        placement="right"
        title={`建模草稿工作台${draftId ? ` · #${draftId}` : ''}`}
      size="100vw"
        extra={
          <Space className={styles.nowrapActions}>
            <Button
              aria-label="刷新草稿"
              icon={<ReloadOutlined />}
              loading={loading}
              title="刷新草稿"
              onClick={() => void requestDetail(true, true)}
            />
            <Button
              aria-label="查看版本"
              icon={<HistoryOutlined />}
              title="查看版本"
              onClick={() => setVersionOpen(true)}
            />
            <Button
              aria-label="查看生成尝试"
              icon={<ProfileOutlined />}
              title="查看生成尝试"
              onClick={() => setAttemptOpen(true)}
            />
            {activeDetail?.status === 'GENERATION_FAILED' ? (
              <Tooltip title={regenerationAvailability.reason}>
                <span className={styles.tooltipButtonWrapper}>
                  <Button
                    aria-label="重新生成草稿"
                    disabled={!regenerationAvailability.allowed}
                    icon={<RedoOutlined />}
                    loading={regenerating}
                    title="重新生成草稿"
                    onClick={() => setRegenerateOpen(true)}
                  />
                </span>
              </Tooltip>
            ) : null}
            {editable ? (
              <Button
                aria-label="保存草稿"
                disabled={!dirty || Boolean(parsedDraft.error)}
                icon={<SaveOutlined />}
                loading={saving}
                title="保存草稿"
                type="primary"
                onClick={() => void saveDraft()}
              >
                保存
              </Button>
            ) : null}
          </Space>
        }
        onClose={closeWorkbench}
      >
        <Spin spinning={loading && !activeDetail}>
          {activeDetail ? (
            <DraftWorkbenchContent
              detail={activeDetail}
              dirty={dirty}
              draft={parsedDraft.value}
              jsonError={parsedDraft.error}
              jsonText={jsonText}
              loadParseError={loadParseError}
              regenerationAllowed={regenerationAvailability.allowed}
              regenerationReason={regenerationAvailability.reason}
              regenerating={regenerating}
              onDraftChange={(draft) => {
                setJsonText(stringifyDraftJson(draft));
                setDirty(true);
              }}
              onJsonTextChange={(value) => {
                setJsonText(value);
                setDirty(true);
              }}
              onRegenerate={() => setRegenerateOpen(true)}
            />
          ) : (
            <Empty description={loading ? '正在加载草稿' : '未找到草稿详情'} />
          )}
        </Spin>
      </Drawer>
      <VersionHistoryDrawer
        draftId={draftId}
        open={versionOpen}
        onClose={() => setVersionOpen(false)}
      />
      <GenerationAttemptHistoryDrawer
        draftId={draftId}
        open={attemptOpen}
        onClose={() => setAttemptOpen(false)}
      />
      <RegenerateDraftModal
        draft={activeDetail}
        open={regenerateOpen}
        onClose={() => setRegenerateOpen(false)}
        onConflict={() => requestDetail(true, true)}
        onSubmitted={(nextDetail) => {
          setRegenerateOpen(false);
          applyDetail(nextDetail);
          // 202 后仍轮询原 draftId；pollCycle 用于重启已因失败停止的轮询链。
          setPollCycle((value) => value + 1);
          onChanged?.();
        }}
        onSubmittingChange={(submitting) => setRegenerating(submitting)}
      />
    </>
  );
};

export default DraftWorkbenchDrawer;
