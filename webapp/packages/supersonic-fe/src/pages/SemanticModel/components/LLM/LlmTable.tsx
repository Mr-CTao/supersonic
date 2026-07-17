/**
 * LLM Conversation Gateway 管理页面。
 *
 * 职责：
 * - 复用现有 `/llm` 入口展示大模型连接配置；
 * - 提供阶段 1 Gateway 的模型能力、会话调试和调用日志可视化入口；
 * - 避免展示 API Key、Token 或完整敏感请求体。
 *
 * 并发说明：
 * - 连接测试、能力保存、消息发送等提交类操作均使用 loading 状态锁定按钮；
 * - 会话调试同一时间只允许一个发送请求，避免前端重复点击造成同会话消息顺序混乱。
 */
import {
  CodeOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  OpenAIOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import type { BubbleItemType, BubbleListProps } from '@ant-design/x';
import { Bubble, Sender, Think } from '@ant-design/x';
import {
  Alert,
  Button,
  Col,
  Descriptions,
  Dropdown,
  Drawer,
  Form,
  Input,
  InputNumber,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TabsProps } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import dayjs from 'dayjs';
import LlmSettingModal from './LlmSettingModal';
import styles from './style.less';
import { ISemantic } from '../../data';
import { deleteLlmConfig } from '../../service';
import { getLlmList, testLLMConn } from '@/services/system';
import { copyText } from '@/utils/utils';
import {
  createLlmConversation,
  getLlmCapabilities,
  getLlmConversation,
  getLlmInvocationLogDetail,
  getLlmInvocationLogs,
  sendLlmConversationMessage,
  updateLlmCapability,
} from '@/services/llmGateway';
import type { LlmInvocationLogQueryReq, LlmModelCapability } from '@/services/llmGateway';

const { Text, Paragraph } = Typography;
const DEFAULT_BETA_BASE_URL = 'https://api.deepseek.com/beta';
const DEFAULT_SYSTEM_PROMPT =
  '你是 LLM Conversation Gateway 调试助手，请优先输出简洁、可验证的回答。';

type GatewayTabKey = 'connections' | 'capabilities' | 'debug' | 'logs';

type TablePaginationState = {
  current: number;
  pageSize: number;
};

/**
 * 将业务列统一转换为居中、单行省略的表格列。
 *
 * @param columns 保留原有渲染器、搜索配置和列宽的业务列集合。
 * @returns 应用统一对齐与省略规则后的新列集合。
 * @throws 不主动抛出异常；调用方必须传入有效列数组。
 */
const normalizeTableColumns = <T extends Record<string, any>>(
  columns: ProColumns<T>[],
): ProColumns<T>[] => {
  return columns.map((column) => ({
    ...column,
    align: 'center',
    ellipsis: column.valueType === 'option' ? false : column.ellipsis ?? true,
  }));
};

/**
 * 计算跨分页连续展示的序号。
 *
 * @param index 当前页内从 0 开始的行索引。
 * @param pagination 当前分页状态。
 * @returns 当前记录在完整结果集中的从 1 开始序号。
 * @throws 不主动抛出异常；分页状态由受控 Pagination 保证为正数。
 */
const getSequenceNumber = (index: number, pagination: TablePaginationState) => {
  return (pagination.current - 1) * pagination.pageSize + index + 1;
};

type LlmMessage = {
  id?: number;
  role?: string;
  content?: string;
  reasoningContent?: string;
  contentType?: string;
  messageOrder?: number;
};

type LlmDebugTurn = {
  id: string;
  role: 'user' | 'assistant';
  content?: string;
  reasoningContent?: string;
  parsedJson?: any;
  status?: string;
  errorCode?: string;
  errorMessage?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  latencyMs?: number;
  providerRequestId?: string;
};

type InvocationLogItem = {
  id: number;
  conversationId?: number;
  conversationType?: string;
  providerType?: string;
  modelName?: string;
  requestId?: string;
  status?: string;
  errorCode?: string;
  errorMessage?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  latencyMs?: number;
  requestSummary?: string;
  rawResponseRef?: string;
  hasReasoningContent?: boolean;
  hasToolCalls?: boolean;
  createdAt?: string;
};

/**
 * 从系统统一响应中提取 data 字段。
 *
 * @param response 后端响应；可能是统一 `{code,data,msg}`，也可能是 Controller 原始对象。
 * @returns 提取后的业务数据。
 * @throws 不主动抛出异常；接口错误由调用方基于 code/msg 处理。
 */
const unwrapData = (response: any) => {
  if (response && Object.prototype.hasOwnProperty.call(response, 'code')) {
    return response.code === 200 ? response.data : undefined;
  }
  return response;
};

/**
 * 判断接口响应是否成功。
 *
 * @param response 后端响应。
 * @returns 成功返回 true。
 */
const isSuccessResponse = (response: any) => {
  return (
    !response || !Object.prototype.hasOwnProperty.call(response, 'code') || response.code === 200
  );
};

/**
 * 将布尔能力渲染为紧凑 Tag。
 *
 * @param value 能力是否启用。
 * @param label 能力名称。
 * @returns 能力标签。
 */
const renderCapabilityTag = (value?: boolean, label?: string) => {
  return <Tag color={value ? 'green' : 'default'}>{label || (value ? '启用' : '未启用')}</Tag>;
};

/**
 * 将错误码转成人能读懂的文案。
 *
 * @param errorCode 统一错误码。
 * @returns 管理端提示文案。
 */
const getNormalizedErrorText = (errorCode?: string) => {
  const errorMap: Record<string, string> = {
    AUTH_FAILED: '鉴权失败，请检查 API Key',
    INSUFFICIENT_BALANCE: '余额不足，请检查 DeepSeek 账户',
    RATE_LIMITED: '触发限流，请稍后重试',
    PROVIDER_UNAVAILABLE: '供应商不可用或服务繁忙',
    JSON_PARSE_FAILED: 'JSON Output 解析失败',
    TIMEOUT: '请求超时',
    BAD_REQUEST: '请求参数错误',
  };
  return errorCode ? errorMap[errorCode] || errorCode : '-';
};

/**
 * 从 umi-request 异常中提取可展示错误。
 *
 * @param error 请求异常对象。
 * @returns 优先返回后端 msg/errorCode，兜底返回通用网络错误。
 */
const getRequestErrorText = (error: any) => {
  const data = error?.data || error?.response?.data;
  return (
    data?.msg ||
    data?.message ||
    getNormalizedErrorText(data?.errorCode) ||
    error?.message ||
    '请求失败，请检查后端服务或网络连接'
  );
};

/**
 * 判断模型连接是否 DeepSeek。
 *
 * @param item 大模型连接配置。
 * @returns DeepSeek 连接返回 true。
 */
const isDeepSeekModel = (item?: ISemantic.ILlmItem) => {
  const provider = item?.config?.provider || '';
  const baseUrl = item?.config?.baseUrl || '';
  const modelName = item?.config?.modelName || '';
  return (
    provider.toUpperCase() === 'DEEPSEEK' ||
    baseUrl.toLowerCase().includes('deepseek.com') ||
    modelName.toLowerCase().startsWith('deepseek-')
  );
};

/**
 * 格式化 JSON 展示文本。
 *
 * @param value JSON 对象或字符串。
 * @returns 可读 JSON 字符串。
 */
const formatJson = (value: any) => {
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch (error) {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
};

/**
 * 生成会话调试消息对象。
 *
 * @param sequenceRef 当前 Tab 内的消息序号；只在组件实例内递增，不涉及跨用户共享状态。
 * @param turn 待写入的调试消息。
 * @returns 带稳定 key 的调试消息，供 Bubble.List 受控渲染。
 * @throws 不抛出异常。
 */
const createDebugTurn = (
  sequenceRef: React.MutableRefObject<number>,
  turn: Omit<LlmDebugTurn, 'id'>,
): LlmDebugTurn => {
  sequenceRef.current += 1;
  return {
    id: `llm-debug-${Date.now()}-${sequenceRef.current}`,
    ...turn,
  };
};

type DebugCopyButtonProps = {
  title: string;
  value?: string;
};

type DebugSenderTextAreaProps = React.ComponentProps<typeof Input.TextArea>;

/**
 * 渲染 Ant Design X Sender 的无边框输入区。
 *
 * @param props Sender 透传给输入组件的 TextArea 属性。
 * @returns 去掉内层边框和焦点描边后的 TextArea。
 * @throws 不抛出异常。
 */
const DebugSenderTextArea = React.forwardRef<any, DebugSenderTextAreaProps>(
  ({ style, ...restProps }, ref) => {
    return (
      <Input.TextArea
        {...restProps}
        ref={ref}
        variant="borderless"
        style={{
          ...style,
          background: 'transparent',
          border: 'none',
          boxShadow: 'none',
          fontSize: 15,
          lineHeight: 1.65,
          outline: 'none',
          padding: 0,
          resize: 'none',
        }}
      />
    );
  },
);
DebugSenderTextArea.displayName = 'DebugSenderTextArea';

const debugSenderFooterButtonStyle: React.CSSProperties = {
  borderRadius: 8,
  boxShadow: '0 2px 8px rgba(15, 23, 42, 0.06)',
  fontSize: 15,
  height: 40,
  paddingInline: 14,
};

/**
 * 获取 Sender footer 输出模式按钮文案。
 *
 * @param value 当前响应格式。
 * @returns 输出模式展示文案。
 * @throws 不抛出异常。
 */
const getResponseFormatLabel = (value?: string) => {
  return value === 'json' ? 'JSON Output' : 'Text Output';
};

/**
 * 获取 Sender footer 推理强度按钮文案。
 *
 * @param value 当前 reasoning effort。
 * @returns 推理强度展示文案。
 * @throws 不抛出异常。
 */
const getReasoningEffortLabel = (value?: string) => {
  return `Effort: ${value === 'max' ? 'max' : 'high'}`;
};

/**
 * 渲染调试信息复制按钮。
 *
 * @param props 复制按钮标题和待复制文本。
 * @returns 紧凑 icon-only 复制按钮。
 * @throws 不抛出异常；复制反馈由 copyText 统一处理。
 */
const DebugCopyButton: React.FC<DebugCopyButtonProps> = ({ title, value }) => {
  const disabled = !value || value === '-';
  return (
    <Tooltip title={title}>
      <Button
        aria-label={title}
        size="small"
        type="text"
        icon={<CopyOutlined />}
        disabled={disabled}
        onClick={() => {
          if (value && value !== '-') {
            copyText(value);
          }
        }}
      />
    </Tooltip>
  );
};

/**
 * 获取 assistant 气泡中优先展示的正文。
 *
 * @param turn assistant 调试消息。
 * @returns 正常响应、错误信息或占位文本。
 * @throws 不抛出异常。
 */
const getAssistantDisplayContent = (turn: LlmDebugTurn) => {
  return turn.content || turn.errorMessage || '-';
};

/**
 * 渲染 assistant 响应下方的调试指标。
 *
 * @param turn assistant 调试消息。
 * @returns token、耗时、request id 和错误码等脱敏调试信息。
 * @throws 不抛出异常。
 */
const renderAssistantDebugFooter = (turn: LlmDebugTurn) => {
  const parsedJsonText = formatJson(turn.parsedJson);
  return (
    <Space size={[4, 4]} wrap style={{ maxWidth: '100%' }}>
      <Tag color={turn.status === 'FAILED' ? 'red' : 'green'}>{turn.status || 'SUCCESS'}</Tag>
      {turn.errorCode && (
        <Tooltip title={getNormalizedErrorText(turn.errorCode)}>
          <Tag color="red">{turn.errorCode}</Tag>
        </Tooltip>
      )}
      <Tag color="blue">{turn.latencyMs ?? '-'} ms</Tag>
      <Tag>Prompt {turn.promptTokens ?? '-'}</Tag>
      <Tag>Completion {turn.completionTokens ?? '-'}</Tag>
      <Tag>Total {turn.totalTokens ?? '-'}</Tag>
      {turn.providerRequestId && <Tag color="purple">Request ID</Tag>}
      <DebugCopyButton title="复制响应" value={getAssistantDisplayContent(turn)} />
      <DebugCopyButton title="复制 JSON" value={parsedJsonText} />
      <DebugCopyButton title="复制 Request ID" value={turn.providerRequestId} />
    </Space>
  );
};

/**
 * 渲染 assistant 气泡正文。
 *
 * @param turn assistant 调试消息。
 * @returns 响应正文、思考内容和 JSON Output 展示区。
 * @throws 不抛出异常。
 */
const renderAssistantDebugContent = (turn: LlmDebugTurn) => {
  const parsedJsonText = formatJson(turn.parsedJson);
  const hasParsedJson = parsedJsonText !== '-';
  return (
    <div style={{ maxWidth: '100%' }}>
      <Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
        {getAssistantDisplayContent(turn)}
      </Paragraph>
      {turn.reasoningContent && (
        <Think
          title="Reasoning Content"
          defaultExpanded={false}
          style={{ marginTop: 10 }}
          styles={{
            content: {
              color: 'rgba(0, 0, 0, 0.65)',
              whiteSpace: 'pre-wrap',
            },
          }}
        >
          {turn.reasoningContent}
        </Think>
      )}
      {hasParsedJson && (
        <pre
          style={{
            background: '#f6f8fa',
            border: '1px solid #f0f0f0',
            borderRadius: 6,
            margin: '10px 0 0',
            maxHeight: 260,
            overflow: 'auto',
            padding: 10,
            whiteSpace: 'pre-wrap',
          }}
        >
          {parsedJsonText}
        </pre>
      )}
      {turn.errorMessage && turn.content && (
        <Alert showIcon type="error" style={{ marginTop: 10 }} title={turn.errorMessage} />
      )}
    </div>
  );
};

/**
 * 将 Gateway 调试消息映射为 Ant Design X Bubble.List 数据。
 *
 * @param turns 当前本地调试消息序列。
 * @returns Bubble.List 受控数据项。
 * @throws 不抛出异常。
 */
const buildDebugBubbleItems = (turns: LlmDebugTurn[]): BubbleItemType[] => {
  return turns.map((turn) => {
    if (turn.role === 'user') {
      return {
        key: turn.id,
        role: 'user',
        content: turn.content || '-',
      };
    }
    return {
      key: turn.id,
      role: 'ai',
      content: renderAssistantDebugContent(turn),
      footer: renderAssistantDebugFooter(turn),
      status: turn.status === 'FAILED' ? 'error' : 'success',
    };
  });
};

const debugBubbleRole: BubbleListProps['role'] = {
  user: {
    placement: 'end',
    shape: 'corner',
    variant: 'filled',
    styles: {
      content: {
        background: '#e6f4ff',
        whiteSpace: 'pre-wrap',
      },
    },
  },
  ai: {
    placement: 'start',
    shape: 'corner',
    variant: 'outlined',
    styles: {
      content: {
        maxWidth: 760,
      },
    },
    footerPlacement: 'outer-start',
  },
};

const LlmTable: React.FC = () => {
  const [activeKey, setActiveKey] = useState<GatewayTabKey>('connections');
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [llmItem, setLlmItem] = useState<ISemantic.ILlmItem>();
  const [llmList, setLlmList] = useState<ISemantic.ILlmItem[]>([]);
  const [capabilities, setCapabilities] = useState<LlmModelCapability[]>([]);
  const [tableLoading, setTableLoading] = useState<boolean>(false);

  // 连接列表使用现有 Chat 模块接口，阶段 1 前端只在此基础上叠加 Gateway 调试能力。
  const queryLlmList = async () => {
    setTableLoading(true);
    try {
      const response = await getLlmList();
      if (isSuccessResponse(response)) {
        setLlmList(unwrapData(response) || []);
      } else {
        message.error(response?.msg || '获取大模型连接失败');
      }
    } finally {
      setTableLoading(false);
    }
  };

  // 能力配置由 Gateway 接口维护，页面按 chatModelId + modelName 与连接列表做轻量关联。
  const queryCapabilities = async () => {
    try {
      const response = await getLlmCapabilities();
      if (isSuccessResponse(response)) {
        setCapabilities(unwrapData(response) || []);
        return;
      }
      message.error(response?.msg || '获取模型能力失败');
    } catch (error) {
      message.error(getRequestErrorText(error));
    }
  };

  useEffect(() => {
    queryLlmList();
    queryCapabilities();
  }, []);

  // 使用复合 key 避免不同连接复用同名模型时能力摘要串线。
  const capabilityByModel = useMemo(() => {
    return capabilities.reduce<Record<string, LlmModelCapability>>((map, item) => {
      map[`${item.chatModelId}-${item.modelName}`] = item;
      return map;
    }, {});
  }, [capabilities]);

  // 刷新列表和能力，保证新增/编辑连接后四个 Tab 看到一致数据。
  const refreshAll = () => {
    queryLlmList();
    queryCapabilities();
  };

  const tabItems: TabsProps['items'] = [
    {
      key: 'connections',
      label: '连接配置',
      children: (
        <ConnectionConfigTab
          llmList={llmList}
          loading={tableLoading}
          capabilityByModel={capabilityByModel}
          onRefresh={refreshAll}
          onCreate={() => {
            setLlmItem(undefined);
            setCreateModalVisible(true);
          }}
          onEdit={(record) => {
            setLlmItem(record);
            setCreateModalVisible(true);
          }}
          onOpenTab={(key, record) => {
            setLlmItem(record);
            setActiveKey(key);
          }}
        />
      ),
    },
    {
      key: 'capabilities',
      label: '模型能力',
      children: (
        <CapabilityTab
          llmList={llmList}
          capabilities={capabilities}
          onRefresh={queryCapabilities}
        />
      ),
    },
    {
      key: 'debug',
      label: '会话调试',
      children: <ConversationDebugTab llmList={llmList} selectedModel={llmItem} />,
    },
    {
      key: 'logs',
      label: '调用日志',
      children: <InvocationLogTab llmList={llmList} />,
    },
  ];

  return (
    <div className={styles.llmPage}>
      <Tabs
        className={styles.llmTabs}
        activeKey={activeKey}
        onChange={(key) => setActiveKey(key as GatewayTabKey)}
        items={tabItems}
      />
      {createModalVisible && (
        <LlmSettingModal
          open={createModalVisible}
          llmItem={llmItem}
          onCancel={() => {
            setCreateModalVisible(false);
          }}
          onSubmit={() => {
            setCreateModalVisible(false);
            refreshAll();
          }}
        />
      )}
    </div>
  );
};

type ConnectionConfigTabProps = {
  llmList: ISemantic.ILlmItem[];
  loading: boolean;
  capabilityByModel: Record<string, LlmModelCapability>;
  onRefresh: () => void;
  onCreate: () => void;
  onEdit: (record: ISemantic.ILlmItem) => void;
  onOpenTab: (key: GatewayTabKey, record: ISemantic.ILlmItem) => void;
};

/**
 * 连接配置 Tab。
 *
 * @param props 连接列表、能力映射和操作回调。
 * @returns 连接配置表格。
 */
const ConnectionConfigTab: React.FC<ConnectionConfigTabProps> = ({
  llmList,
  loading,
  capabilityByModel,
  onRefresh,
  onCreate,
  onEdit,
  onOpenTab,
}) => {
  const [testingId, setTestingId] = useState<number>();
  const [pagination, setPagination] = useState<TablePaginationState>({
    current: 1,
    pageSize: 20,
  });

  // 操作列连接测试使用 loading 文案锁定单条记录，避免连续点击打出重复探测请求。
  const handleTestConnection = async (record: ISemantic.ILlmItem) => {
    setTestingId(record.id);
    try {
      const response = await testLLMConn(record.config || {});
      if (isSuccessResponse(response) && unwrapData(response)) {
        message.success('连接测试通过');
        return;
      }
      message.error(response?.msg || getNormalizedErrorText(response?.errorCode) || '连接测试失败');
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setTestingId(undefined);
    }
  };

  const columns = normalizeTableColumns<ISemantic.ILlmItem>([
    {
      dataIndex: 'sequence',
      title: '序号',
      width: 80,
      search: false,
      render: (_, __, index) => getSequenceNumber(index, pagination),
    },
    { dataIndex: 'name', title: '连接名称', width: 180 },
    {
      dataIndex: ['config', 'provider'],
      title: '供应商',
      width: 120,
      search: false,
      render: (_, record) => (
        <Tag color={isDeepSeekModel(record) ? 'blue' : 'default'}>
          {isDeepSeekModel(record) ? 'DEEPSEEK' : record.config?.provider || '-'}
        </Tag>
      ),
    },
    {
      dataIndex: ['config', 'modelName'],
      title: '模型名称',
      search: false,
      width: 160,
    },
    {
      dataIndex: ['config', 'baseUrl'],
      title: 'API Base URL',
      search: false,
      width: 210,
    },
    {
      title: 'Beta Base URL',
      search: false,
      width: 210,
      render: (_, record) => (isDeepSeekModel(record) ? DEFAULT_BETA_BASE_URL : '-'),
    },
    {
      title: '能力摘要',
      search: false,
      width: 240,
      render: (_, record) => {
        const capability =
          capabilityByModel[`${record.id}-${record.config?.modelName || ''}`] || {};
        return (
          <Space size={4}>
            {capability.supportJsonMode && <Tag color="green">JSON</Tag>}
            {capability.supportStream && <Tag color="cyan">Stream</Tag>}
            {capability.supportThinking && <Tag color="blue">Thinking</Tag>}
            {capability.supportToolCalling && <Tag color="purple">Tool</Tag>}
            {capability.supportFimCompletion && <Tag color="orange">FIM</Tag>}
            {!capability.modelName && <Tag>未配置</Tag>}
          </Space>
        );
      },
    },
    {
      dataIndex: 'isOpen',
      title: '启用状态',
      width: 100,
      search: false,
      render: (value) => (
        <Tag color={Number(value) === 1 ? 'green' : 'default'}>
          {Number(value) === 1 ? '公开' : '私有'}
        </Tag>
      ),
    },
    {
      dataIndex: 'updatedAt',
      title: '更新时间',
      width: 170,
      search: false,
      // ProTable 在不同版本中可能先把 value 转为 ReactNode，直接读取 record 可避免出现 Invalid Date。
      render: (_, record) =>
        record.updatedAt && record.updatedAt !== '-'
          ? dayjs(record.updatedAt).format('YYYY-MM-DD HH:mm:ss')
          : '-',
    },
    {
      dataIndex: 'createdBy',
      title: '创建人',
      search: false,
      width: 110,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 280,
      fixed: 'right',
      render: (_, record) => (
        <Space size={8} style={{ whiteSpace: 'nowrap' }}>
          <a onClick={() => onEdit(record)}>编辑</a>
          <a onClick={() => handleTestConnection(record)}>
            {testingId === record.id ? '测试中' : '测试'}
          </a>
          <a onClick={() => onOpenTab('capabilities', record)}>能力</a>
          <a onClick={() => onOpenTab('debug', record)}>调试</a>
          <a onClick={() => onOpenTab('logs', record)}>日志</a>
          <Popconfirm
            title="确认删除？"
            okText="是"
            cancelText="否"
            onConfirm={async () => {
              const response = await deleteLlmConfig(record.id);
              if (isSuccessResponse(response)) {
                message.success('删除成功');
                onRefresh();
              } else {
                message.error(response?.msg || '删除失败');
              }
            }}
          >
            <a>删除</a>
          </Popconfirm>
        </Space>
      ),
    },
  ]);

  return (
    <ProTable<ISemantic.ILlmItem>
      className={styles.llmDataTable}
      rowKey="id"
      columns={columns}
      dataSource={llmList}
      loading={loading}
      search={false}
      scroll={{ x: 1860, y: '100%' }}
      tableAlertRender={false}
      size="small"
      pagination={{
        current: pagination.current,
        pageSize: pagination.pageSize,
        showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条/总共 ${total} 条`,
        onChange: (current, pageSize) => setPagination({ current, pageSize }),
      }}
      options={{ reload: onRefresh, density: false, fullScreen: false }}
      toolBarRender={() => [
        <Button key="create" type="primary" onClick={onCreate}>
          创建大模型连接
        </Button>,
      ]}
    />
  );
};

type CapabilityTabProps = {
  llmList: ISemantic.ILlmItem[];
  capabilities: LlmModelCapability[];
  onRefresh: () => void;
};

/**
 * 模型能力 Tab。
 *
 * @param props 模型连接列表、能力列表和刷新回调。
 * @returns 能力表格和编辑 Drawer。
 */
const CapabilityTab: React.FC<CapabilityTabProps> = ({ llmList, capabilities, onRefresh }) => {
  const [editing, setEditing] = useState<LlmModelCapability>();
  const [saveLoading, setSaveLoading] = useState<boolean>(false);
  const [pagination, setPagination] = useState<TablePaginationState>({
    current: 1,
    pageSize: 20,
  });
  const [form] = Form.useForm();

  // 打开抽屉时同步当前行数据，关闭后由 destroyOnHidden 清理表单 DOM。
  useEffect(() => {
    if (editing) {
      form.setFieldsValue(editing);
    }
  }, [editing, form]);

  const openEdit = (record: LlmModelCapability) => {
    setEditing(record);
  };

  // 能力保存是阶段 1 的管理动作，按钮 loading 防止同一配置被重复提交。
  const handleSave = async () => {
    const values = await form.validateFields();
    setSaveLoading(true);
    try {
      const response = await updateLlmCapability({ ...(editing || {}), ...values });
      if (isSuccessResponse(response)) {
        message.success('能力配置已保存');
        setEditing(undefined);
        onRefresh();
        return;
      }
      message.error(response?.msg || '保存能力配置失败');
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setSaveLoading(false);
    }
  };

  const dataSource = capabilities.length
    ? capabilities
    : llmList.map((item) => ({
        chatModelId: item.id,
        providerType: isDeepSeekModel(item) ? 'DEEPSEEK' : item.config?.provider,
        modelName: item.config?.modelName || '-',
        enabled: true,
      }));

  const columns = normalizeTableColumns<LlmModelCapability>([
    {
      dataIndex: 'sequence',
      title: '序号',
      width: 80,
      search: false,
      render: (_, __, index) => getSequenceNumber(index, pagination),
    },
    { dataIndex: 'providerType', title: '供应商', width: 120 },
    { dataIndex: 'modelName', title: '模型名称', width: 180 },
    { dataIndex: 'maxContextTokens', title: '最大上下文', width: 120, search: false },
    {
      dataIndex: 'supportStream',
      title: 'Stream',
      width: 90,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    {
      dataIndex: 'supportJsonMode',
      title: 'JSON Output',
      width: 120,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    {
      dataIndex: 'supportThinking',
      title: 'Thinking',
      width: 100,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    {
      dataIndex: 'supportToolCalling',
      title: 'Tool Calls',
      width: 110,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    {
      dataIndex: 'supportFimCompletion',
      title: 'FIM',
      width: 90,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    {
      dataIndex: 'supportContextCache',
      title: 'Context Cache',
      width: 130,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    {
      dataIndex: 'supportSystemPrompt',
      title: 'System Prompt',
      width: 130,
      search: false,
      render: (value) => renderCapabilityTag(!!value),
    },
    { dataIndex: 'recommendedTemperature', title: '推荐温度', width: 100, search: false },
    {
      dataIndex: 'usageScene',
      title: '适用场景',
      width: 180,
      search: false,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 90,
      render: (_, record) => (
        <a style={{ whiteSpace: 'nowrap' }} onClick={() => openEdit(record)}>
          编辑
        </a>
      ),
    },
  ]);

  return (
    <div className={styles.llmTableTab}>
      <Alert
        className={styles.capabilityAlert}
        showIcon
        type="info"
        title="DeepSeek 的对话前缀续写、FIM 补全和 strict tool calling 属于 Beta 能力，需要使用独立 Beta Base URL，不能覆盖普通 /chat/completions。"
      />
      <ProTable<LlmModelCapability>
        className={styles.llmDataTable}
        rowKey={(record) => `${record.chatModelId}-${record.modelName}`}
        columns={columns}
        dataSource={dataSource}
        search={false}
        scroll={{ x: 1660, y: '100%' }}
        tableAlertRender={false}
        size="small"
        pagination={{
          current: pagination.current,
          pageSize: pagination.pageSize,
          showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条/总共 ${total} 条`,
          onChange: (current, pageSize) => setPagination({ current, pageSize }),
        }}
        options={{ reload: onRefresh, density: false, fullScreen: false }}
      />
      <Drawer
        width={560}
        title="编辑模型能力"
        open={!!editing}
        destroyOnHidden
        onClose={() => setEditing(undefined)}
        extra={
          <Button type="primary" loading={saveLoading} onClick={handleSave}>
            保存
          </Button>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item name="chatModelId" label="连接 ID" rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} disabled />
          </Form.Item>
          <Form.Item name="providerType" label="供应商">
            <Input />
          </Form.Item>
          <Form.Item name="modelName" label="模型名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="maxContextTokens" label="最大上下文">
                <InputNumber style={{ width: '100%' }} min={1} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="recommendedTemperature" label="推荐温度">
                <InputNumber style={{ width: '100%' }} min={0} max={2} step={0.1} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={[12, 8]}>
            {[
              ['supportStream', 'Stream'],
              ['supportJsonMode', 'JSON Output'],
              ['supportThinking', 'Thinking'],
              ['supportToolCalling', 'Tool Calls'],
              ['supportChatPrefixCompletion', 'Chat Prefix'],
              ['supportFimCompletion', 'FIM Completion'],
              ['supportContextCache', 'Context Cache'],
              ['supportSystemPrompt', 'System Prompt'],
              ['enabled', '启用'],
            ].map(([name, label]) => (
              <Col span={8} key={name}>
                <Form.Item name={name} label={label} valuePropName="checked">
                  <Switch />
                </Form.Item>
              </Col>
            ))}
          </Row>
          <Form.Item name="usageScene" label="适用场景">
            <Input placeholder="如：语义建模、SQL 修复、问答解释" />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

type ConversationDebugTabProps = {
  llmList: ISemantic.ILlmItem[];
  selectedModel?: ISemantic.ILlmItem;
};

/**
 * 会话调试 Tab。
 *
 * @param props 可选的当前模型连接。
 * @returns 多轮调试面板。
 */
const ConversationDebugTab: React.FC<ConversationDebugTabProps> = ({ llmList, selectedModel }) => {
  const [form] = Form.useForm();
  const [conversationId, setConversationId] = useState<number>();
  const [turns, setTurns] = useState<LlmDebugTurn[]>([]);
  const [sending, setSending] = useState<boolean>(false);
  const [draftMessage, setDraftMessage] = useState<string>('');
  const [conversationMessages, setConversationMessages] = useState<LlmMessage[]>([]);
  const turnSequenceRef = useRef<number>(0);
  const watchedChatModelId = Form.useWatch('chatModelId', form);
  const watchedResponseFormat = Form.useWatch('responseFormat', form) || 'text';
  const watchedThinkingEnabled = Form.useWatch('thinkingEnabled', form);
  const watchedReasoningEffort = Form.useWatch('reasoningEffort', form) || 'high';

  // 从连接配置页跳转调试时预填连接和模型名，降低管理员重复选择成本。
  useEffect(() => {
    if (selectedModel?.id) {
      form.setFieldsValue({
        chatModelId: selectedModel.id,
        modelName: selectedModel.config?.modelName,
      });
    }
  }, [selectedModel, form]);

  // Bubble.List 是受控组件，使用 memo 避免表单状态变化时重复构造复杂消息节点。
  const bubbleItems = useMemo(() => buildDebugBubbleItems(turns), [turns]);
  const selectedConnection = useMemo(() => {
    return llmList.find((item) => item.id === watchedChatModelId);
  }, [llmList, watchedChatModelId]);

  /**
   * 更新 Sender footer 中的调试参数。
   *
   * @param field 调试参数字段名。
   * @param value 调试参数值。
   * @returns 无返回值。
   * @throws 不抛出异常；字段值由 Ant Design Form 维护。
   */
  const updateDebugOption = (field: string, value: any) => {
    form.setFieldValue(field, value);
  };

  // 首轮发送前创建本地会话，后续消息都复用 conversationId，让 Gateway 拼接完整 messages。
  const handleCreateConversation = async (values: any) => {
    try {
      const response = await createLlmConversation({
        conversationType: 'LLM_DEBUG',
        chatModelId: values.chatModelId,
        providerId: values.chatModelId,
        modelName: values.modelName,
        systemPrompt: values.systemPrompt || DEFAULT_SYSTEM_PROMPT,
      });
      if (!isSuccessResponse(response)) {
        message.error(response?.msg || '创建调试会话失败');
        return undefined;
      }
      const data = unwrapData(response);
      setConversationId(data?.conversationId);
      setConversationMessages(data?.messages || []);
      return data?.conversationId;
    } catch (error) {
      message.error(getRequestErrorText(error));
      return undefined;
    }
  };

  // 串行发送消息，保证前端视图顺序和后端会话消息顺序一致。
  const handleSend = async (messageContent?: string) => {
    const userContent = (messageContent || draftMessage || '').trim();
    if (!userContent) {
      message.warning('请输入消息');
      return;
    }
    let values: any;
    try {
      const validatedValues = await form.validateFields();
      // hidden 字段由 Sender footer 控件维护，这里合并完整表单值，避免调试参数被校验结果裁掉。
      values = {
        ...form.getFieldsValue(true),
        ...validatedValues,
      };
    } catch (error: any) {
      if (!error?.errorFields) {
        message.error(getRequestErrorText(error));
      }
      return;
    }
    setTurns((prev) => [
      ...prev,
      createDebugTurn(turnSequenceRef, { role: 'user', content: userContent }),
    ]);
    setSending(true);
    try {
      const nextConversationId = conversationId || (await handleCreateConversation(values));
      if (!nextConversationId) {
        setTurns((prev) => [
          ...prev,
          createDebugTurn(turnSequenceRef, {
            role: 'assistant',
            status: 'FAILED',
            errorMessage: '创建调试会话失败，请检查登录态或后端 Gateway 服务。',
          }),
        ]);
        return;
      }
      const response = await sendLlmConversationMessage(nextConversationId, {
        content: userContent,
        responseFormat: values.responseFormat,
        temperature: values.temperature,
        maxTokens: values.maxTokens,
        timeoutMs: values.timeoutMs,
        stream: false,
        // 必须显式传 false；否则首次发送时 undefined 会被后端/供应商默认策略当作开启 Thinking。
        thinkingEnabled: Boolean(values.thinkingEnabled),
        reasoningEffort: values.reasoningEffort,
      });
      if (!isSuccessResponse(response)) {
        message.error(response?.msg || '发送消息失败');
        setTurns((prev) => [
          ...prev,
          createDebugTurn(turnSequenceRef, {
            role: 'assistant',
            status: 'FAILED',
            errorCode: response?.errorCode,
            errorMessage: response?.msg || '发送消息失败',
          }),
        ]);
        return;
      }
      const data = unwrapData(response);
      setTurns((prev) => [
        ...prev,
        createDebugTurn(turnSequenceRef, {
          role: 'assistant',
          content: data?.assistantContent,
          reasoningContent: data?.reasoningContent,
          parsedJson: data?.parsedJson,
          status: data?.status,
          errorCode: data?.errorCode,
          errorMessage: data?.errorMessage,
          promptTokens: data?.promptTokens,
          completionTokens: data?.completionTokens,
          totalTokens: data?.totalTokens,
          latencyMs: data?.latencyMs,
          providerRequestId: data?.providerRequestId,
        }),
      ]);
      setDraftMessage('');
      const conversationResponse = await getLlmConversation(nextConversationId);
      if (isSuccessResponse(conversationResponse)) {
        setConversationMessages(unwrapData(conversationResponse)?.messages || []);
      }
    } catch (error) {
      message.error(getRequestErrorText(error));
      setTurns((prev) => [
        ...prev,
        createDebugTurn(turnSequenceRef, {
          role: 'assistant',
          status: 'FAILED',
          errorMessage: getRequestErrorText(error),
        }),
      ]);
    } finally {
      setSending(false);
    }
  };

  // 清空只重置本地调试会话，不删除后端日志，方便调用日志 Tab 继续追踪历史请求。
  const handleClear = () => {
    setConversationId(undefined);
    setTurns([]);
    setConversationMessages([]);
    turnSequenceRef.current = 0;
    setDraftMessage('');
  };

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} xl={7}>
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            responseFormat: 'text',
            thinkingEnabled: false,
            systemPrompt: DEFAULT_SYSTEM_PROMPT,
            reasoningEffort: 'high',
            temperature: 0,
            timeoutMs: 60000,
          }}
        >
          <div
            style={{
              background: '#fff',
              border: '1px solid #f0f0f0',
              borderRadius: 8,
              padding: 16,
            }}
          >
            <Form.Item name="responseFormat" hidden>
              <Input />
            </Form.Item>
            <Form.Item name="thinkingEnabled" valuePropName="checked" hidden>
              <Switch />
            </Form.Item>
            <Form.Item name="reasoningEffort" hidden>
              <Input />
            </Form.Item>
            <Form.Item
              name="chatModelId"
              label="连接"
              rules={[{ required: true, message: '请选择连接' }]}
            >
              <Select
                placeholder="选择 DeepSeek 连接"
                options={llmList.map((item) => ({
                  label: `${item.name} / ${item.config?.modelName || '-'}`,
                  value: item.id,
                }))}
                onChange={(value) => {
                  const target = llmList.find((item) => item.id === value);
                  form.setFieldValue('modelName', target?.config?.modelName);
                }}
              />
            </Form.Item>
            <Form.Item name="modelName" label="模型名称">
              <Input placeholder="默认使用连接中的模型名称" />
            </Form.Item>
            <Form.Item name="systemPrompt" label="System Prompt">
              <Input.TextArea rows={3} />
            </Form.Item>
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item name="temperature" label="Temperature">
                  <InputNumber style={{ width: '100%' }} min={0} max={2} step={0.1} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="maxTokens" label="Max Tokens">
                  <InputNumber style={{ width: '100%' }} min={1} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="timeoutMs" label="Timeout(ms)">
              <InputNumber style={{ width: '100%' }} min={1000} />
            </Form.Item>
          </div>
        </Form>
      </Col>
      <Col xs={24} xl={17}>
        <div
          style={{
            background: '#fff',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            display: 'flex',
            flexDirection: 'column',
            height: 'calc(100vh - 170px)',
            minHeight: 640,
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              alignItems: 'center',
              borderBottom: '1px solid #f0f0f0',
              display: 'flex',
              gap: 12,
              justifyContent: 'space-between',
              padding: '12px 16px',
            }}
          >
            <Space size={[12, 4]} wrap>
              <Text>Conversation ID：{conversationId || '-'}</Text>
              <Text>Messages：{conversationMessages.length}</Text>
              <Text>
                估算 Token：
                {conversationMessages
                  .reduce((sum, item) => sum + Math.max(1, (item.content || '').length / 4), 0)
                  .toFixed(0)}
              </Text>
            </Space>
            <Tooltip title="清空会话">
              <Button
                aria-label="清空会话"
                icon={<DeleteOutlined />}
                size="small"
                onClick={handleClear}
                disabled={sending}
              />
            </Tooltip>
          </div>
          <div style={{ flex: 1, minHeight: 0, padding: 16 }}>
            {bubbleItems.length === 0 ? (
              <Alert
                showIcon
                type="info"
                title="选择连接后发送消息，第二轮会自动复用同一个本地 conversation。JSON Output 失败会展示统一错误码。"
              />
            ) : (
              <Bubble.List
                autoScroll
                items={bubbleItems}
                role={debugBubbleRole}
                style={{ height: '100%' }}
                styles={{
                  scroll: {
                    paddingInlineEnd: 8,
                  },
                }}
              />
            )}
          </div>
          <div
            style={{
              borderTop: '1px solid #f0f0f0',
              padding: 16,
            }}
          >
            <Sender
              value={draftMessage}
              loading={sending}
              disabled={sending}
              placeholder="输入调试消息"
              submitType="enter"
              autoSize={{ minRows: 2, maxRows: 6 }}
              components={{ input: DebugSenderTextArea }}
              footer={() => (
                <div
                  style={{
                    alignItems: 'center',
                    display: 'flex',
                    gap: 8,
                    justifyContent: 'flex-start',
                    minHeight: 32,
                  }}
                >
                  <Space size={[10, 8]} wrap>
                    <Tag
                      color="blue"
                      style={{
                        alignItems: 'center',
                        borderRadius: 8,
                        display: 'inline-flex',
                        fontSize: 14,
                        height: 40,
                        marginInlineEnd: 0,
                        paddingInline: 12,
                      }}
                    >
                      {selectedConnection?.name ||
                        selectedConnection?.config?.provider ||
                        'Gateway'}
                    </Tag>
                    <Dropdown
                      trigger={['click']}
                      disabled={sending}
                      menu={{
                        selectedKeys: [watchedResponseFormat],
                        items: [
                          { key: 'text', label: 'Text Output' },
                          { key: 'json', label: 'JSON Output' },
                        ],
                        onClick: ({ key }) => updateDebugOption('responseFormat', key),
                      }}
                    >
                      <Button icon={<CodeOutlined />} style={debugSenderFooterButtonStyle}>
                        {getResponseFormatLabel(watchedResponseFormat)}
                        <DownOutlined style={{ fontSize: 12 }} />
                      </Button>
                    </Dropdown>
                    <Sender.Switch
                      value={Boolean(watchedThinkingEnabled)}
                      disabled={sending}
                      icon={<OpenAIOutlined />}
                      checkedChildren="Deep Think: on"
                      unCheckedChildren="Deep Think: off"
                      onChange={(checked) => updateDebugOption('thinkingEnabled', checked)}
                      styles={{
                        content: debugSenderFooterButtonStyle,
                      }}
                    />
                    <Tooltip
                      title={
                        watchedThinkingEnabled
                          ? 'Reasoning Effort'
                          : '开启 Thinking 后可调整 Reasoning Effort'
                      }
                    >
                      <span>
                        <Dropdown
                          trigger={['click']}
                          disabled={sending || !watchedThinkingEnabled}
                          menu={{
                            selectedKeys: [watchedReasoningEffort],
                            items: [
                              { key: 'high', label: 'Effort: high' },
                              { key: 'max', label: 'Effort: max' },
                            ],
                            onClick: ({ key }) => updateDebugOption('reasoningEffort', key),
                          }}
                        >
                          <Button
                            icon={<ThunderboltOutlined />}
                            disabled={sending || !watchedThinkingEnabled}
                            style={debugSenderFooterButtonStyle}
                          >
                            {getReasoningEffortLabel(watchedReasoningEffort)}
                            <DownOutlined style={{ fontSize: 12 }} />
                          </Button>
                        </Dropdown>
                      </span>
                    </Tooltip>
                    {conversationId && (
                      <Tag
                        style={{
                          alignItems: 'center',
                          borderRadius: 8,
                          display: 'inline-flex',
                          fontSize: 14,
                          height: 40,
                          marginInlineEnd: 0,
                          paddingInline: 12,
                        }}
                      >
                        Conversation #{conversationId}
                      </Tag>
                    )}
                  </Space>
                </div>
              )}
              suffix={(originNode) => (
                <div
                  style={{
                    alignItems: 'flex-end',
                    display: 'flex',
                    height: '100%',
                    paddingBottom: 2,
                  }}
                >
                  {originNode}
                </div>
              )}
              styles={{
                root: {
                  borderColor: '#d9d9d9',
                  borderRadius: 16,
                  boxShadow: '0 8px 24px rgba(15, 23, 42, 0.08)',
                },
                content: {
                  alignItems: 'stretch',
                  padding: '16px 14px 6px 16px',
                },
                input: {
                  minHeight: 76,
                },
                suffix: {
                  alignItems: 'stretch',
                },
                footer: {
                  padding: '0 14px 14px 16px',
                },
              }}
              onChange={(value) => setDraftMessage(value)}
              onSubmit={handleSend}
            />
          </div>
        </div>
      </Col>
    </Row>
  );
};

type InvocationLogTabProps = {
  llmList: ISemantic.ILlmItem[];
};

/**
 * 调用日志 Tab。
 *
 * @param props 大模型连接列表，用于筛选项。
 * @returns 调用日志表格和详情抽屉。
 */
const InvocationLogTab: React.FC<InvocationLogTabProps> = ({ llmList }) => {
  const actionRef = useRef<ActionType>();
  const [detail, setDetail] = useState<InvocationLogItem>();
  const [detailOpen, setDetailOpen] = useState<boolean>(false);
  const [pagination, setPagination] = useState<TablePaginationState>({
    current: 1,
    pageSize: 20,
  });

  const columns = normalizeTableColumns<InvocationLogItem>([
    {
      dataIndex: 'sequence',
      title: '序号',
      width: 80,
      search: false,
      render: (_, __, index) => getSequenceNumber(index, pagination),
    },
    {
      dataIndex: 'providerType',
      title: '供应商',
      valueType: 'select',
      valueEnum: {
        DEEPSEEK: { text: 'DEEPSEEK' },
        OPEN_AI: { text: 'OPEN_AI' },
      },
      width: 120,
    },
    {
      dataIndex: 'modelName',
      title: '模型',
      valueType: 'select',
      fieldProps: {
        showSearch: true,
        options: Array.from(
          new Set(llmList.map((item) => item.config?.modelName).filter(Boolean)),
        ).map((modelName) => ({ label: modelName, value: modelName })),
      },
      width: 160,
    },
    {
      dataIndex: 'status',
      title: '状态',
      valueType: 'select',
      valueEnum: {
        SUCCESS: { text: '成功', status: 'Success' },
        FAILED: { text: '失败', status: 'Error' },
        TIMEOUT: { text: '超时', status: 'Warning' },
        RATE_LIMITED: { text: '限流', status: 'Warning' },
      },
      width: 110,
    },
    {
      dataIndex: 'errorCode',
      title: '错误码',
      valueType: 'select',
      valueEnum: {
        AUTH_FAILED: { text: 'AUTH_FAILED' },
        INSUFFICIENT_BALANCE: { text: 'INSUFFICIENT_BALANCE' },
        RATE_LIMITED: { text: 'RATE_LIMITED' },
        PROVIDER_UNAVAILABLE: { text: 'PROVIDER_UNAVAILABLE' },
        JSON_PARSE_FAILED: { text: 'JSON_PARSE_FAILED' },
        TIMEOUT: { text: 'TIMEOUT' },
        BAD_REQUEST: { text: 'BAD_REQUEST' },
      },
      width: 170,
    },
    {
      dataIndex: 'conversationId',
      title: 'Conversation ID',
      width: 140,
    },
    {
      dataIndex: 'createdAt',
      title: '调用时间',
      valueType: 'dateTimeRange',
      width: 170,
      render: (_, record) =>
        record.createdAt ? dayjs(record.createdAt).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    { dataIndex: 'conversationType', title: '会话类型', search: false, width: 130 },
    { dataIndex: 'latencyMs', title: '耗时(ms)', search: false, width: 100 },
    { dataIndex: 'promptTokens', title: 'Prompt Tokens', search: false, width: 130 },
    { dataIndex: 'completionTokens', title: 'Completion Tokens', search: false, width: 150 },
    { dataIndex: 'totalTokens', title: 'Total Tokens', search: false, width: 120 },
    {
      title: '操作',
      valueType: 'option',
      width: 80,
      render: (_, record) => (
        <a
          style={{ whiteSpace: 'nowrap' }}
          onClick={async () => {
            try {
              const response = await getLlmInvocationLogDetail(record.id);
              if (isSuccessResponse(response)) {
                setDetail(unwrapData(response));
                setDetailOpen(true);
              } else {
                message.error(response?.msg || '获取日志详情失败');
              }
            } catch (error) {
              message.error(getRequestErrorText(error));
            }
          }}
        >
          详情
        </a>
      ),
    },
  ]);

  return (
    <div className={styles.llmTableTab}>
      <ProTable<InvocationLogItem>
        className={styles.llmDataTable}
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        scroll={{ x: 1600, y: '100%' }}
        tableAlertRender={false}
        size="small"
        pagination={{
          current: pagination.current,
          pageSize: pagination.pageSize,
          showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条/总共 ${total} 条`,
          onChange: (current, pageSize) => setPagination({ current, pageSize }),
        }}
        options={{ density: false, fullScreen: false }}
        request={async (params) => {
          const query: LlmInvocationLogQueryReq = {
            providerType: params.providerType,
            modelName: params.modelName,
            status: params.status,
            errorCode: params.errorCode,
            conversationId: params.conversationId ? Number(params.conversationId) : undefined,
            pageNo: params.current,
            pageSize: params.pageSize,
          };
          if (Array.isArray(params.createdAt)) {
            query.startTime = params.createdAt[0];
            query.endTime = params.createdAt[1];
          }
          try {
            const response = await getLlmInvocationLogs(query);
            if (isSuccessResponse(response)) {
              return { data: unwrapData(response) || [], success: true };
            }
            message.error(response?.msg || '查询调用日志失败');
          } catch (error) {
            message.error(getRequestErrorText(error));
          }
          return { data: [], success: false };
        }}
      />
      <Drawer
        width={720}
        title="调用日志详情"
        open={detailOpen}
        destroyOnHidden
        onClose={() => setDetailOpen(false)}
      >
        <Descriptions size="small" bordered column={2}>
          <Descriptions.Item label="请求 ID" span={2}>
            {detail?.requestId || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="供应商">{detail?.providerType || '-'}</Descriptions.Item>
          <Descriptions.Item label="模型">{detail?.modelName || '-'}</Descriptions.Item>
          <Descriptions.Item label="状态">{detail?.status || '-'}</Descriptions.Item>
          <Descriptions.Item label="错误码">
            {detail?.errorCode ? <Tag color="red">{detail.errorCode}</Tag> : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="耗时">{detail?.latencyMs ?? '-'} ms</Descriptions.Item>
          <Descriptions.Item label="Token">
            {detail?.promptTokens ?? '-'} / {detail?.completionTokens ?? '-'} /{' '}
            {detail?.totalTokens ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label="含 Reasoning">
            {detail?.hasReasoningContent ? '是' : '否'}
          </Descriptions.Item>
          <Descriptions.Item label="含 Tool Calls">
            {detail?.hasToolCalls ? '是' : '否'}
          </Descriptions.Item>
          <Descriptions.Item label="错误信息" span={2}>
            {detail?.errorMessage || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="请求摘要" span={2}>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{detail?.requestSummary || '-'}</pre>
          </Descriptions.Item>
          <Descriptions.Item label="响应摘要" span={2}>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{detail?.rawResponseRef || '-'}</pre>
          </Descriptions.Item>
        </Descriptions>
      </Drawer>
    </div>
  );
};

export default LlmTable;
