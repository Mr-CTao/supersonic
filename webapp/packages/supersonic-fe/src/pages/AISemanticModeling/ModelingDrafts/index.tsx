/**
 * AI 语义建模草稿列表页面。
 *
 * 职责：分页筛选草稿、从数据源或 gapId 打开创建抽屉、对失败行发起受限重新生成，并进入同一草稿详情轮询；不提供验证、审批或发布入口。
 *
 * 并发说明：列表由显式筛选与分页触发；创建幂等、详情轮询和保存乐观锁由对应子组件负责。
 */
import { PlusOutlined } from '@ant-design/icons';
import type { ActionType } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import { Button, message } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { getModelingDrafts } from '@/services/semanticModelingDraft';
import type { ModelingDraftItem } from '@/services/semanticModelingDraft';
import scrollbarStyles from '../tableScrollbar.less';
import CreateDraftDrawer from './components/CreateDraftDrawer';
import DraftWorkbenchDrawer from './components/DraftWorkbenchDrawer';
import RegenerateDraftModal from './components/RegenerateDraftModal';
import { createDraftListColumns } from './draftListColumns';
import { getRequestErrorText, unwrapResponseData } from './utils';
import styles from './style.less';

const DEFAULT_PAGE_SIZE = 20;
const TABLE_SCROLL_X = 1220;

/**
 * 从 URL 查询参数读取有效 gapId。
 *
 * @param search location.search。
 * @returns 正整数 gapId 或 undefined。
 * @throws 不抛出异常。
 */
function parseGapId(search: string): number | undefined {
  const value = Number(new URLSearchParams(search).get('gapId'));
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

/**
 * AI 语义建模草稿列表组件。
 *
 * @returns 草稿列表、创建抽屉和详情工作台。
 * @throws 不向上抛出异常；列表错误通过 message 展示。
 */
const ModelingDrafts: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const location = useLocation();
  const [createOpen, setCreateOpen] = useState(false);
  const [initialGapId, setInitialGapId] = useState<number>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [selectedDraftId, setSelectedDraftId] = useState<number>();
  const [regenerateDraft, setRegenerateDraft] = useState<ModelingDraftItem>();
  const [regeneratingDraftId, setRegeneratingDraftId] = useState<number>();
  const [pageInfo, setPageInfo] = useState({ current: 1, pageSize: DEFAULT_PAGE_SIZE });

  useEffect(() => {
    const gapId = parseGapId(location.search);
    if (gapId) {
      setInitialGapId(gapId);
      setCreateOpen(true);
    }
  }, [location.search]);

  /** 关闭创建抽屉并清除 URL gapId，避免刷新后重复打开。 */
  const closeCreate = () => {
    setCreateOpen(false);
    setInitialGapId(undefined);
    if (location.search) history.replace('/ai-semantic-modeling/drafts');
  };

  /** 创建成功后刷新列表，并在有 ID 时直接打开生成进度。 */
  const handleCreated = (draftId?: number) => {
    closeCreate();
    actionRef.current?.reload();
    if (draftId) {
      setSelectedDraftId(draftId);
      setDetailOpen(true);
    }
  };

  /** 打开选中草稿详情工作台。 */
  const openDetail = (record: ModelingDraftItem) => {
    setSelectedDraftId(record.id);
    setDetailOpen(true);
  };

  const columns = useMemo(
    () =>
      createDraftListColumns({
        current: pageInfo.current,
        pageSize: pageInfo.pageSize,
        onOpenDetail: openDetail,
        onRegenerate: setRegenerateDraft,
        regeneratingDraftId,
      }),
    [pageInfo, regeneratingDraftId],
  );

  return (
    <>
      <ProTable<ModelingDraftItem>
        actionRef={actionRef}
        className={`${styles.draftTable} ${scrollbarStyles.tableScrollbar}`}
        columns={columns}
        request={async (params) => {
          const next = {
            current: params.current || 1,
            pageSize: params.pageSize || DEFAULT_PAGE_SIZE,
          };
          setPageInfo((previous) =>
            previous.current === next.current && previous.pageSize === next.pageSize
              ? previous
              : next,
          );
          try {
            const response = await getModelingDrafts({
              ...params,
              page: params.current,
              pageSize: params.pageSize,
            });
            const data = unwrapResponseData<any>(response) || {};
            return { data: data.list || [], success: true, total: data.total || 0 };
          } catch (error) {
            message.error(getRequestErrorText(error));
            return { data: [], success: false, total: 0 };
          }
        }}
        rowKey="id"
        search={{ labelWidth: 88, defaultCollapsed: true }}
        options={{ density: false }}
        scroll={{ x: TABLE_SCROLL_X }}
        pagination={{ defaultPageSize: DEFAULT_PAGE_SIZE, showSizeChanger: true }}
        dateFormatter="string"
        toolBarRender={() => [
          <Button
            icon={<PlusOutlined />}
            key="create"
            type="primary"
            onClick={() => {
              setInitialGapId(undefined);
              setCreateOpen(true);
            }}
          >
            从数据源生成草稿
          </Button>,
        ]}
      />
      <CreateDraftDrawer
        initialGapId={initialGapId}
        open={createOpen}
        onClose={closeCreate}
        onCreated={handleCreated}
      />
      <DraftWorkbenchDrawer
        draftId={selectedDraftId}
        open={detailOpen}
        onChanged={() => actionRef.current?.reload()}
        onClose={() => setDetailOpen(false)}
      />
      <RegenerateDraftModal
        draft={regenerateDraft}
        open={Boolean(regenerateDraft)}
        onClose={() => setRegenerateDraft(undefined)}
        onConflict={() => actionRef.current?.reload()}
        onSubmitted={(detail) => {
          const draftId = detail?.id || regenerateDraft?.id;
          setRegenerateDraft(undefined);
          actionRef.current?.reload();
          if (draftId) {
            setSelectedDraftId(draftId);
            setDetailOpen(true);
          }
        }}
        onSubmittingChange={(submitting, draftId) =>
          setRegeneratingDraftId(submitting ? draftId : undefined)
        }
      />
    </>
  );
};

export default ModelingDrafts;
