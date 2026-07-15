/**
 * 语义缺口池管理页面。
 *
 * 职责：
 * - 展示 Chat BI 问答失败、LLM SQL 回退和用户负反馈沉淀的语义缺口；
 * - 提供助手、主题域、数据源、失败类型、状态、时间和关键词筛选；
 * - 支持详情查看、忽略、重新打开，并跳转阶段 3 草稿页发起 AI 建模。
 *
 * 并发说明：
 * - 忽略和重新打开按钮使用行级 loading 状态，防止重复点击；
 * - 搜索筛选由 ProTable 表单提交触发，不监听输入逐字请求，因此无需额外 debounce；
 * - 页面不维护跨标签页共享状态，最终一致性以后端查询结果为准。
 */
import { EyeOutlined, RobotOutlined, StopOutlined, UndoOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import {
  Button,
  Descriptions,
  Drawer,
  Input,
  message,
  Modal,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useMemo, useRef, useState } from 'react';
import styles from './style.less';
import {
  getSemanticGapDetail,
  getSemanticGaps,
  ignoreSemanticGap,
  reopenSemanticGap,
} from '@/services/semanticGap';
import type {
  SemanticGapFailureType,
  SemanticGapItem,
  SemanticGapStatus,
} from '@/services/semanticGap';

const { Paragraph, Text } = Typography;

const DEFAULT_PAGE_SIZE = 20;
const TABLE_SCROLL_X = 1260;

const FAILURE_TYPE_TEXT: Record<SemanticGapFailureType, string> = {
  NO_SELECTED_PARSE: '无可用解析',
  PARSER_EXCEPTION: '解析异常',
  WRONG_MODEL_MATCHED: '模型误命中',
  LOW_CONFIDENCE: '低置信度',
  SQL_EXECUTION_ERROR: 'SQL执行失败',
  EMPTY_RESULT_SUSPECTED: '结果疑似异常为空',
  USER_NEGATIVE_FEEDBACK: '用户负反馈',
  FALLBACK_TO_LLM_SQL: '回退LLM SQL',
  BUSINESS_DEFINITION_UNCERTAIN: '业务口径待确认',
  SEMANTIC_ASSET_MISSING: '语义资产缺失',
  TECHNICAL_VALIDATION_FAILED: '技术校验失败',
  UNKNOWN: '未知',
};

const STATUS_TEXT: Record<SemanticGapStatus, string> = {
  PENDING_ANALYSIS: '待分析',
  DRAFTING: '草稿中',
  WAITING_CONFIRMATION: '待确认',
  RELEASED: '已发布',
  IGNORED: '已忽略',
  REOPENED: '重新打开',
};

const STATUS_COLOR: Record<SemanticGapStatus, string> = {
  PENDING_ANALYSIS: 'processing',
  DRAFTING: 'blue',
  WAITING_CONFIRMATION: 'warning',
  RELEASED: 'success',
  IGNORED: 'default',
  REOPENED: 'orange',
};

/**
 * 从后端统一响应中提取 data 字段。
 *
 * @param response 后端响应，可能是 `{code,data,msg}` 或原始对象。
 * @returns 业务数据。
 * @throws 不主动抛出异常。
 */
const unwrapData = (response: any) => {
  if (response && Object.prototype.hasOwnProperty.call(response, 'code')) {
    return response.code === 200 ? response.data : undefined;
  }
  return response;
};

/**
 * 从请求异常中提取管理员可理解的错误文案。
 *
 * @param error 请求异常对象。
 * @returns 错误提示。
 * @throws 不抛出异常。
 */
const getRequestErrorText = (error: any) => {
  const data = error?.data || error?.response?.data;
  return data?.msg || data?.message || error?.message || '请求失败，请检查后端服务或网络连接';
};

/**
 * 渲染失败类型标签。
 *
 * @param value 失败类型。
 * @returns Ant Design Tag。
 * @throws 不抛出异常。
 */
const renderFailureType = (value?: SemanticGapFailureType) => {
  const color =
    value === 'NO_SELECTED_PARSE' ||
    value === 'PARSER_EXCEPTION' ||
    value === 'TECHNICAL_VALIDATION_FAILED'
      ? 'red'
      : 'blue';
  return <Tag color={color}>{value ? FAILURE_TYPE_TEXT[value] : '-'}</Tag>;
};

/**
 * 渲染状态标签。
 *
 * @param value 状态。
 * @returns Ant Design Tag。
 * @throws 不抛出异常。
 */
const renderStatus = (value?: SemanticGapStatus) => {
  return (
    <Tag color={value ? STATUS_COLOR[value] : 'default'}>{value ? STATUS_TEXT[value] : '-'}</Tag>
  );
};

/**
 * 渲染长文本段落。
 *
 * @param value 文本值。
 * @returns 可复制段落或占位符。
 * @throws 不抛出异常。
 */
const renderParagraph = (value?: string) => {
  if (!value) {
    return <Text type="secondary">-</Text>;
  }
  return (
    <Paragraph copyable={{ text: value }} style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
      {value}
    </Paragraph>
  );
};

/**
 * 将后端 ISO 时间转换成管理员更易扫读的本地时间。
 *
 * @param value 后端返回的 ISO 时间字符串。
 * @returns 本地时区下的 `YYYY-MM-DD HH:mm:ss`，解析失败时返回原值。
 * @throws 不抛出异常。
 */
const formatLocalDateTime = (value?: string) => {
  if (!value) {
    return '-';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
};

/**
 * 语义缺口池页面组件。
 *
 * @returns 可操作的语义缺口池页面。
 * @throws 不主动抛出异常；接口异常展示 message。
 */
const SemanticGapPool: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [currentGap, setCurrentGap] = useState<SemanticGapItem>();
  const [ignoreOpen, setIgnoreOpen] = useState(false);
  const [ignoreReason, setIgnoreReason] = useState('');
  const [actionLoadingId, setActionLoadingId] = useState<number>();
  const [pageInfo, setPageInfo] = useState({
    current: 1,
    pageSize: DEFAULT_PAGE_SIZE,
  });

  /**
   * 打开详情抽屉并加载最新详情。
   *
   * @param record 当前表格行。
   * @returns Promise<void>。
   * @throws 请求异常会被捕获并展示。
   */
  const openDetail = async (record: SemanticGapItem) => {
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      const response = await getSemanticGapDetail(record.id);
      setCurrentGap(unwrapData(response) || record);
    } catch (error) {
      message.error(getRequestErrorText(error));
      setCurrentGap(record);
    } finally {
      setDetailLoading(false);
    }
  };

  /**
   * 打开忽略弹窗。
   *
   * @param record 当前表格行。
   * @returns void。
   * @throws 不抛出异常。
   */
  const openIgnoreModal = (record: SemanticGapItem) => {
    setCurrentGap(record);
    setIgnoreReason('');
    setIgnoreOpen(true);
  };

  /**
   * 提交忽略操作。
   *
   * @returns Promise<void>。
   * @throws 请求异常会被捕获并展示。
   */
  const submitIgnore = async () => {
    if (!currentGap?.id) {
      return;
    }
    setActionLoadingId(currentGap.id);
    try {
      await ignoreSemanticGap(currentGap.id, ignoreReason);
      message.success('已忽略');
      setIgnoreOpen(false);
      actionRef.current?.reload();
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setActionLoadingId(undefined);
    }
  };

  /**
   * 重新打开缺口。
   *
   * @param record 当前表格行。
   * @returns Promise<void>。
   * @throws 请求异常会被捕获并展示。
   */
  const reopenGap = async (record: SemanticGapItem) => {
    setActionLoadingId(record.id);
    try {
      await reopenSemanticGap(record.id);
      message.success('已重新打开');
      actionRef.current?.reload();
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setActionLoadingId(undefined);
    }
  };

  /**
   * 跳转建模草稿页面，并通过 URL 携带当前缺口 ID。
   *
   * @param record 当前表格行。
   * @returns void。
   * @throws 不抛出异常。
   */
  const startDraft = (record: SemanticGapItem) => {
    history.push(`/ai-semantic-modeling/drafts?gapId=${encodeURIComponent(record.id)}`);
  };

  const columns: ProColumns<SemanticGapItem>[] = useMemo(
    () => [
      {
        title: '序号',
        dataIndex: 'index',
        search: false,
        width: 72,
        fixed: 'left',
        align: 'center',
        render: (_, __, index) => (pageInfo.current - 1) * pageInfo.pageSize + index + 1,
      },
      {
        title: '关键词',
        dataIndex: 'keyword',
        hideInTable: true,
        align: 'center',
        fieldProps: {
          placeholder: '问题、原因、反馈',
        },
      },
      {
        title: '助手',
        dataIndex: 'assistantId',
        valueType: 'digit',
        hideInTable: true,
        align: 'center',
      },
      {
        title: '主题域',
        dataIndex: 'domainId',
        valueType: 'digit',
        hideInTable: true,
        align: 'center',
      },
      {
        title: '数据源',
        dataIndex: 'dataSourceId',
        valueType: 'digit',
        hideInTable: true,
        align: 'center',
      },
      {
        title: '失败类型',
        dataIndex: 'failureType',
        valueType: 'select',
        align: 'center',
        valueEnum: Object.fromEntries(
          Object.entries(FAILURE_TYPE_TEXT).map(([key, text]) => [key, { text }]),
        ),
        render: (_, record) => renderFailureType(record.failureType),
      },
      {
        title: '状态',
        dataIndex: 'status',
        valueType: 'select',
        align: 'center',
        valueEnum: Object.fromEntries(
          Object.entries(STATUS_TEXT).map(([key, text]) => [key, { text }]),
        ),
        render: (_, record) => renderStatus(record.status),
      },
      {
        title: '最近出现',
        dataIndex: 'lastSeenAt',
        valueType: 'dateRange',
        align: 'center',
        search: {
          transform: (value: string[]) => ({
            startTime: value?.[0],
            endTime: value?.[1],
          }),
        },
        render: (_, record) => formatLocalDateTime(record.lastSeenAt),
      },
      {
        title: '问题',
        dataIndex: 'question',
        search: false,
        ellipsis: true,
        width: 320,
        align: 'center',
      },
      {
        title: '出现',
        dataIndex: 'occurrenceCount',
        search: false,
        width: 72,
        align: 'center',
      },
      {
        title: '负反馈',
        dataIndex: 'negativeFeedbackCount',
        search: false,
        width: 82,
        align: 'center',
      },
      {
        title: '优先级',
        dataIndex: 'priorityScore',
        search: false,
        width: 82,
        sorter: false,
        align: 'center',
      },
      {
        title: '操作',
        dataIndex: 'option',
        valueType: 'option',
        width: 160,
        fixed: 'right',
        align: 'center',
        render: (_, record) => (
          <Space size={4} style={{ whiteSpace: 'nowrap' }}>
            <Tooltip title="查看详情">
              <Button
                aria-label="查看详情"
                icon={<EyeOutlined />}
                size="small"
                title="查看详情"
                onClick={() => openDetail(record)}
              />
            </Tooltip>
            <Tooltip title="分析并建模">
              <Button
                aria-label="分析并建模"
                icon={<RobotOutlined />}
                loading={actionLoadingId === record.id}
                size="small"
                title="分析并建模"
                onClick={() => startDraft(record)}
              />
            </Tooltip>
            {record.status === 'IGNORED' ? (
              <Tooltip title="重新打开">
                <Button
                  aria-label="重新打开"
                  icon={<UndoOutlined />}
                  loading={actionLoadingId === record.id}
                  size="small"
                  title="重新打开"
                  onClick={() => reopenGap(record)}
                />
              </Tooltip>
            ) : (
              <Tooltip title="忽略">
                <Button
                  aria-label="忽略"
                  icon={<StopOutlined />}
                  loading={actionLoadingId === record.id}
                  size="small"
                  title="忽略"
                  onClick={() => openIgnoreModal(record)}
                />
              </Tooltip>
            )}
          </Space>
        ),
      },
    ],
    [actionLoadingId, pageInfo],
  );

  const recentQuestions = currentGap?.recentQuestions
    ? currentGap.recentQuestions.split('\n').filter(Boolean)
    : [];

  return (
    <>
      <ProTable<SemanticGapItem>
        actionRef={actionRef}
        className={styles.semanticGapTable}
        columns={columns}
        request={async (params) => {
          const nextPageInfo = {
            current: params.current || 1,
            pageSize: params.pageSize || DEFAULT_PAGE_SIZE,
          };
          setPageInfo((previous) => {
            if (
              previous.current === nextPageInfo.current &&
              previous.pageSize === nextPageInfo.pageSize
            ) {
              return previous;
            }
            return nextPageInfo;
          });
          const response = await getSemanticGaps({
            ...params,
            page: params.current,
            pageSize: params.pageSize,
          });
          const data = unwrapData(response) || {};
          return {
            data: data.list || [],
            success: true,
            total: data.total || 0,
          };
        }}
        rowKey="id"
        search={{
          labelWidth: 90,
          defaultCollapsed: false,
        }}
        options={{
          density: false,
        }}
        scroll={{ x: TABLE_SCROLL_X }}
        pagination={{
          defaultPageSize: DEFAULT_PAGE_SIZE,
          showSizeChanger: true,
        }}
        dateFormatter="string"
      />

      <Drawer
        destroyOnHidden
        loading={detailLoading}
        open={detailOpen}
        size="large"
        title="语义缺口详情"
        onClose={() => setDetailOpen(false)}
      >
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="状态">{renderStatus(currentGap?.status)}</Descriptions.Item>
          <Descriptions.Item label="失败类型">
            {renderFailureType(currentGap?.failureType)}
          </Descriptions.Item>
          <Descriptions.Item label="原始问题">
            {renderParagraph(currentGap?.question)}
          </Descriptions.Item>
          <Descriptions.Item label="相似问法">
            {recentQuestions.length > 0
              ? recentQuestions.map((item) => (
                  <Paragraph key={item} style={{ marginBottom: 4 }}>
                    {item}
                  </Paragraph>
                ))
              : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="失败原因">
            {renderParagraph(currentGap?.failureReason)}
          </Descriptions.Item>
          <Descriptions.Item label="失败阶段">
            {currentGap?.diagnosticStage || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="错误码">{currentGap?.errorCode || '-'}</Descriptions.Item>
          <Descriptions.Item label="traceId">{currentGap?.traceId || '-'}</Descriptions.Item>
          <Descriptions.Item label="校验位置">
            {currentGap?.errorLine
              ? `${currentGap.errorLine}:${currentGap.errorColumn || 1} ${
                  currentGap.errorToken || ''
                }`
              : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="修复建议">
            {renderParagraph(currentGap?.suggestion)}
          </Descriptions.Item>
          <Descriptions.Item label="用户反馈">
            {renderParagraph(currentGap?.feedback)}
          </Descriptions.Item>
          <Descriptions.Item label="命中模型">
            {currentGap?.matchedModelIds || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="命中指标">
            {currentGap?.matchedMetricIds || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="命中维度">
            {currentGap?.matchedDimensionIds || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="SQL">
            {renderParagraph(currentGap?.generatedSql)}
          </Descriptions.Item>
          <Descriptions.Item label="S2SQL">{renderParagraph(currentGap?.s2sql)}</Descriptions.Item>
          <Descriptions.Item label="最近出现">
            {formatLocalDateTime(currentGap?.lastSeenAt)}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">
            {formatLocalDateTime(currentGap?.createdAt)}
          </Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {formatLocalDateTime(currentGap?.updatedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="来源问答">{currentGap?.sourceQueryId || '-'}</Descriptions.Item>
          <Descriptions.Item label="忽略原因">
            {renderParagraph(currentGap?.ignoreReason)}
          </Descriptions.Item>
        </Descriptions>
        {currentGap?.matchedModelIds ? (
          <Typography.Paragraph style={{ marginTop: 16, marginBottom: 0 }} type="secondary">
            已命中 {currentGap.matchedModelIds.split(',').filter(Boolean).length}{' '}
            个现有模型，系统将优先判断是否可以复用或增强。
          </Typography.Paragraph>
        ) : null}
        {currentGap?.domainId && currentGap?.matchedModelIds ? (
          <Button
            style={{ marginTop: 16 }}
            type="primary"
            onClick={() => {
              const modelId = currentGap.matchedModelIds?.split(',')[0];
              history.push(`/model/domain/manager/${currentGap.domainId}/${modelId}`);
            }}
          >
            打开模型并重新校验
          </Button>
        ) : null}
      </Drawer>

      <Modal
        confirmLoading={actionLoadingId === currentGap?.id}
        destroyOnHidden
        okText="忽略"
        open={ignoreOpen}
        title="忽略语义缺口"
        onCancel={() => setIgnoreOpen(false)}
        onOk={submitIgnore}
      >
        <Input.TextArea
          maxLength={300}
          placeholder="请输入忽略原因"
          rows={4}
          showCount
          value={ignoreReason}
          onChange={(event) => setIgnoreReason(event.target.value)}
        />
      </Modal>
    </>
  );
};

export default SemanticGapPool;
