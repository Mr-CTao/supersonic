/**
 * 建模草稿列表列配置模块。
 *
 * 职责：集中定义来源/状态文案、分页序号、草稿摘要，以及紧凑的详情/重新生成操作列。
 */
import { EyeOutlined, RedoOutlined } from '@ant-design/icons';
import type { ProColumns } from '@ant-design/pro-components';
import { Button, Space, Tag, Tooltip, Typography } from 'antd';
import dayjs from 'dayjs';
import type {
  ModelingDraftItem,
  ModelingDraftSourceType,
  ModelingDraftStatus,
} from '@/services/semanticModelingDraft';
import { formatSelectedTables, getRegenerationAvailability } from './utils';
import styles from './style.less';

const { Text } = Typography;

const SOURCE_TYPE_TEXT: Record<ModelingDraftSourceType, string> = {
  SEMANTIC_GAP: '语义缺口',
  DATA_SOURCE: '数据源选表',
};

/** 按服务端持久化动作展示处理方式；无路由引用的历史记录不伪装成新路由结果。 */
function renderRouteAction(record: ModelingDraftItem) {
  const action = record.routeAction || record.currentDraft?.action;
  if (action === 'EXTEND_EXISTING') {
    return <Tag color="purple">增强</Tag>;
  }
  if (action === 'CREATE_NEW' && record.routeAnalysisId) {
    return <Tag color="blue">新建</Tag>;
  }
  return <Tag>历史未路由</Tag>;
}

const STATUS_TEXT: Record<ModelingDraftStatus, string> = {
  GENERATING: '生成中',
  DRAFT: '草稿',
  GENERATION_FAILED: '生成失败',
  PENDING_APPROVAL: '待审批',
};

const STATUS_COLOR: Record<ModelingDraftStatus, string> = {
  GENERATING: 'processing',
  DRAFT: 'blue',
  GENERATION_FAILED: 'error',
  PENDING_APPROVAL: 'success',
};

type Params = {
  current: number;
  pageSize: number;
  onOpenDetail: (record: ModelingDraftItem) => void;
  onRegenerate: (record: ModelingDraftItem) => void;
  regeneratingDraftId?: number;
};

/** 格式化审计时间，失败时保留原值。 */
function formatDateTime(value?: string): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/**
 * 创建草稿列表列配置。
 *
 * @param params 当前分页和详情回调。
 * @returns ProTable 列数组。
 * @throws 不抛出异常。
 */
export function createDraftListColumns({
  current,
  pageSize,
  onOpenDetail,
  onRegenerate,
  regeneratingDraftId,
}: Params): ProColumns<ModelingDraftItem>[] {
  return [
    {
      title: '序号',
      dataIndex: 'index',
      search: false,
      width: 72,
      fixed: 'left',
      align: 'center',
      render: (_, __, index) => (current - 1) * pageSize + index + 1,
    },
    {
      title: '关键词',
      dataIndex: 'keyword',
      hideInTable: true,
      fieldProps: { placeholder: '标题或业务目标' },
    },
    {
      title: '来源',
      dataIndex: 'sourceType',
      valueType: 'select',
      width: 120,
      valueEnum: Object.fromEntries(
        Object.entries(SOURCE_TYPE_TEXT).map(([key, text]) => [key, { text }]),
      ),
      render: (_, record) => (
        <Space size={4}>
          <Tag>{SOURCE_TYPE_TEXT[record.sourceType]}</Tag>
          {record.sourceType === 'SEMANTIC_GAP' && record.sourceId ? (
            <Text type="secondary">#{record.sourceId}</Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      valueType: 'select',
      width: 110,
      valueEnum: Object.fromEntries(
        Object.entries(STATUS_TEXT).map(([key, text]) => [key, { text }]),
      ),
      render: (_, record) => (
        <Tag color={STATUS_COLOR[record.status]}>{STATUS_TEXT[record.status]}</Tag>
      ),
    },
    {
      title: '处理方式',
      dataIndex: 'routeAction',
      search: false,
      width: 112,
      render: (_, record) => renderRouteAction(record),
    },
    {
      title: '数据源',
      dataIndex: 'dataSourceId',
      valueType: 'digit',
      width: 100,
      align: 'center',
    },
    {
      title: '草稿标题 / 业务目标',
      dataIndex: 'title',
      search: false,
      width: 300,
      ellipsis: true,
      render: (_, record) => record.title || record.businessGoal || '-',
    },
    {
      title: '选表',
      dataIndex: 'selectedTables',
      search: false,
      width: 260,
      ellipsis: true,
      render: (_, record) => formatSelectedTables(record.selectedTables),
    },
    {
      title: '版本',
      dataIndex: 'currentVersionNo',
      search: false,
      width: 78,
      align: 'center',
      render: (_, record) => record.currentVersionNo ?? record.currentVersion ?? 0,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      search: false,
      width: 170,
      render: (_, record) => formatDateTime(record.updatedAt),
    },
    {
      title: '操作',
      dataIndex: 'option',
      valueType: 'option',
      width: 112,
      fixed: 'right',
      align: 'center',
      render: (_, record) => {
        const availability = getRegenerationAvailability(record);
        const regenerating = regeneratingDraftId === record.id;
        return (
          <Space className={styles.nowrapActions} size={4}>
            <Tooltip title="查看草稿">
              <Button
                aria-label="查看草稿"
                icon={<EyeOutlined />}
                size="small"
                title="查看草稿"
                onClick={() => onOpenDetail(record)}
              />
            </Tooltip>
            {record.status === 'GENERATION_FAILED' && record.canManage === true ? (
              <Tooltip title={availability.reason}>
                <span className={styles.tooltipButtonWrapper}>
                  <Button
                    aria-label="重新生成草稿"
                    disabled={!availability.allowed}
                    icon={<RedoOutlined />}
                    loading={regenerating}
                    size="small"
                    title="重新生成草稿"
                    onClick={() => onRegenerate(record)}
                  />
                </span>
              </Tooltip>
            ) : null}
          </Space>
        );
      },
    },
  ];
}
