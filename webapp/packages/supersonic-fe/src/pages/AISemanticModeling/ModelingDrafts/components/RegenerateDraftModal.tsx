/**
 * AI 语义建模草稿重新生成共享弹窗。
 *
 * 职责：
 * - 从列表失败行和详情失败区共享同一份重试表单；
 * - 只读展示原草稿业务目标、主题域、数据源和选表，只允许调整 JSON-capable LLM 与脱敏样例开关；
 * - 每次打开生成一个稳定幂等键，同次打开内的失败重提持续复用。
 *
 * 并发说明：提交按钮由 submitting 锁防重；弹窗关闭或组件卸载后通过请求序号忽略迟到响应；409 必须明确提示重载，不静默覆盖。
 */
import {
  Alert,
  Descriptions,
  Form,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Typography,
  message,
} from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { getLlmCapabilities } from '@/services/llmGateway';
import {
  regenerateModelingDraft,
  type ModelingDraftItem,
  type RegenerateModelingDraftReq,
} from '@/services/semanticModelingDraft';
import {
  formatSelectedTables,
  getRequestErrorText,
  isOptimisticLockConflict,
  unwrapResponseData,
} from '../utils';
import { createIdempotencyKey } from './create/creationUtils';
import type { CapabilityOption } from './create/creationTypes';
import styles from '../style.less';

const { Paragraph, Text } = Typography;

type RegenerationFormValues = {
  chatModelId: number;
  includeSampleData: boolean;
};

type Props = {
  open: boolean;
  draft?: ModelingDraftItem;
  onClose: () => void;
  onSubmitted: (detail: ModelingDraftItem) => void;
  onConflict: () => void | Promise<unknown>;
  onSubmittingChange?: (submitting: boolean, draftId?: number) => void;
};

/** 从 Gateway 能力响应中只保留已启用的 JSON Output 模型。 */
function normalizeJsonCapabilities(response: any): CapabilityOption[] {
  const data = unwrapResponseData<any>(response) || [];
  const values = Array.isArray(data) ? data : data.list || [];
  return values.filter(
    (item: CapabilityOption) => item.enabled !== false && item.supportJsonMode === true,
  );
}

/**
 * 草稿重新生成弹窗。
 *
 * @param props 打开状态、当前草稿及成功/冲突回调。
 * @returns 只读上下文与可编辑模型选项弹窗。
 * @throws 不向上抛出请求异常；统一使用 message 或 409 确认框告知管理员。
 */
const RegenerateDraftModal: React.FC<Props> = ({
  open,
  draft,
  onClose,
  onSubmitted,
  onConflict,
  onSubmittingChange,
}) => {
  const [form] = Form.useForm<RegenerationFormValues>();
  const [initializing, setInitializing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [capabilities, setCapabilities] = useState<CapabilityOption[]>([]);
  const requestSequenceRef = useRef(0);
  const idempotencyKeyRef = useRef('');
  const mountedRef = useRef(true);

  useEffect(() => {
    // React StrictMode 会在开发环境模拟一次 effect 卸载/重建，每次建立时都必须恢复 mounted 标记。
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestSequenceRef.current += 1;
    };
  }, []);

  useEffect(() => {
    if (!open || !draft) return undefined;
    let active = true;
    const requestId = ++requestSequenceRef.current;
    idempotencyKeyRef.current = createIdempotencyKey();
    setCapabilities([]);
    setInitializing(true);
    form.setFieldsValue({
      chatModelId: draft.chatModelId,
      includeSampleData: Boolean(draft.includeSampleData),
    });

    /** 每次打开重新获取能力，避免已停用模型仍被继承提交。 */
    const initialize = async () => {
      try {
        const values = normalizeJsonCapabilities(await getLlmCapabilities());
        if (!active || requestId !== requestSequenceRef.current) return;
        setCapabilities(values);
        const inheritedModelAvailable = values.some(
          (item) => item.chatModelId === draft.chatModelId,
        );
        if (!inheritedModelAvailable) form.setFieldValue('chatModelId', undefined);
      } catch (error) {
        if (active && requestId === requestSequenceRef.current) {
          message.error(getRequestErrorText(error));
        }
      } finally {
        if (active && requestId === requestSequenceRef.current) setInitializing(false);
      }
    };
    void initialize();
    return () => {
      active = false;
      requestSequenceRef.current += 1;
    };
  }, [draft, form, open]);

  /** 使用最新 lockVersion 对原草稿 ID 提交一次手工重新生成。 */
  const submit = async () => {
    if (!draft || submitting) return;
    try {
      const values = await form.validateFields();
      const payload: RegenerateModelingDraftReq = {
        lockVersion: draft.lockVersion,
        chatModelId: values.chatModelId,
        includeSampleData: Boolean(values.includeSampleData),
      };
      setSubmitting(true);
      onSubmittingChange?.(true, draft.id);
      const response = await regenerateModelingDraft(draft.id, payload, idempotencyKeyRef.current);
      if (!mountedRef.current) return;
      const detail = unwrapResponseData<ModelingDraftItem>(response);
      message.success('已提交重新生成，将在原草稿上继续刷新进度');
      onSubmitted(detail);
    } catch (error: any) {
      if (error?.errorFields) return;
      if (isOptimisticLockConflict(error)) {
        Modal.confirm({
          title: '草稿状态已变化',
          content: '当前重试基于旧的锁版本或失败状态，请重新加载后再决定是否生成。',
          okText: '重新加载',
          cancelText: '留在当前弹窗',
          onOk: async () => {
            onClose();
            await onConflict();
          },
        });
      } else {
        message.error(getRequestErrorText(error));
      }
    } finally {
      if (mountedRef.current) {
        setSubmitting(false);
        onSubmittingChange?.(false, draft.id);
      }
    }
  };

  const modelOptions = capabilities.map((item) => ({
    label: `${item.providerType || 'LLM'} / ${item.modelName}`,
    value: item.chatModelId,
  }));

  return (
    <Modal
      cancelButtonProps={{ disabled: submitting }}
      cancelText="取消"
      centered
      confirmLoading={submitting}
      destroyOnHidden
      maskClosable={!submitting}
      okButtonProps={{ disabled: initializing || capabilities.length === 0 }}
      okText="重新生成"
      open={open}
      title={`重新生成草稿${draft ? ` · #${draft.id}` : ''}`}
      width={760}
      onCancel={submitting ? undefined : onClose}
      onOk={() => void submit()}
    >
      <Spin spinning={initializing}>
        {draft ? (
          <Space className={styles.regenerationModalContent} orientation="vertical" size={16}>
            <Alert
              showIcon
              type="info"
              title="保留原草稿上下文"
              description="重新生成仍使用原业务目标、主题域、数据源和选表，不允许在此绕过原权限边界。"
            />
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="业务目标">
                <Paragraph className={styles.regenerationGoal}>
                  {draft.businessGoal || '-'}
                </Paragraph>
              </Descriptions.Item>
              <Descriptions.Item label="目标主题域">
                {draft.domainId ? `#${draft.domainId}` : '未选择'}
              </Descriptions.Item>
              <Descriptions.Item label="数据源">
                {`#${draft.dataSourceId}`}
                {[draft.catalogName, draft.databaseName].filter(Boolean).length
                  ? ` · ${[draft.catalogName, draft.databaseName].filter(Boolean).join('.')}`
                  : ''}
              </Descriptions.Item>
              <Descriptions.Item label="选表">
                {formatSelectedTables(draft.selectedTables)}
              </Descriptions.Item>
            </Descriptions>

            {capabilities.length === 0 && !initializing ? (
              <Alert
                showIcon
                type="error"
                title="没有可用的 JSON-capable LLM"
                description="请先在大模型管理中启用 JSON Output 能力后再重试。"
              />
            ) : null}

            <Form<RegenerationFormValues> form={form} layout="vertical">
              <Form.Item
                label="生成模型"
                name="chatModelId"
                rules={[{ required: true, message: '请选择支持 JSON Output 的模型' }]}
              >
                <Select
                  options={modelOptions}
                  placeholder="请选择 JSON-capable LLM"
                  showSearch
                  optionFilterProp="label"
                />
              </Form.Item>
              <Form.Item label="使用脱敏样例" name="includeSampleData" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Form>
            <Alert
              showIcon
              type="warning"
              title="样例数据外发提示"
              description="开启后，每张表最多采样 3 行并由服务端脱敏后进入模型上下文；页面不会展示 Prompt、样例行或模型原文。"
            />
            <Text type="secondary">
              重新生成只新增尝试记录，成功后在同一草稿 ID 内创建新的 AI 草稿版本。
            </Text>
          </Space>
        ) : null}
      </Spin>
    </Modal>
  );
};

export default RegenerateDraftModal;
