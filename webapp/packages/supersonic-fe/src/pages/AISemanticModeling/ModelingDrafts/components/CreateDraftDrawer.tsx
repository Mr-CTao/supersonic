/**
 * AI 语义建模草稿创建抽屉。
 *
 * 职责：组合创建表单与元数据级联 Hook，提交缺口或数据源来源的异步草稿任务，并持有稳定幂等键和 loading 锁。
 *
 * 并发说明：每次打开生成一个幂等键，同次失败重试继续复用；元数据过期响应保护由 useDraftCreationOptions 提供。
 */
import { Button, Drawer, Form, message, Space, Spin } from 'antd';
import React, { useEffect, useRef, useState } from 'react';
import { createModelingDraft, createModelingDraftFromGap } from '@/services/semanticModelingDraft';
import type { CreateModelingDraftReq } from '@/services/semanticModelingDraft';
import { extractDraftId, getRequestErrorText, unwrapResponseData } from '../utils';
import DraftCreateFormFields from './create/DraftCreateFormFields';
import type { CreateDraftFormValues } from './create/creationTypes';
import { createIdempotencyKey } from './create/creationUtils';
import { useDraftCreationOptions } from './create/useDraftCreationOptions';

type Props = {
  open: boolean;
  initialGapId?: number;
  onClose: () => void;
  onCreated: (draftId?: number) => void;
};

/**
 * 草稿创建抽屉组件。
 *
 * @param props 开关状态、可选缺口 ID 和创建完成回调。
 * @returns 数据源选表与 LLM 配置抽屉。
 * @throws 不向上抛出异常；接口失败统一通过 message 展示。
 */
const CreateDraftDrawer: React.FC<Props> = ({ open, initialGapId, onClose, onCreated }) => {
  const [form] = Form.useForm<CreateDraftFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const idempotencyKeyRef = useRef('');
  const mountedRef = useRef(true);
  const creationOptions = useDraftCreationOptions({ form, initialGapId, open });

  useEffect(() => {
    if (open) idempotencyKeyRef.current = createIdempotencyKey();
  }, [initialGapId, open]);

  useEffect(
    () => () => {
      mountedRef.current = false;
    },
    [],
  );

  /**
   * 校验表单并提交异步草稿生成任务。
   *
   * @returns Promise<void>。
   * @throws 表单或请求错误会在组件内处理并保留用户输入。
   */
  const submit = async () => {
    try {
      const values = await form.validateFields();
      const payload: CreateModelingDraftReq = {
        sourceType: initialGapId ? 'SEMANTIC_GAP' : 'DATA_SOURCE',
        sourceId: initialGapId,
        businessGoal: values.businessGoal.trim(),
        domainId: values.domainId,
        dataSourceId: values.dataSourceId,
        catalogName: values.catalogName,
        databaseName: values.databaseName,
        selectedTables: values.selectedTables,
        chatModelId: values.chatModelId,
        includeSampleData: values.includeSampleData || false,
      };
      setSubmitting(true);
      const response = initialGapId
        ? await createModelingDraftFromGap(initialGapId, payload, idempotencyKeyRef.current)
        : await createModelingDraft(payload, idempotencyKeyRef.current);
      const draftId = extractDraftId(unwrapResponseData<any>(response));
      message.success('草稿生成任务已提交，可在列表中查看进度');
      onCreated(draftId);
    } catch (error: any) {
      if (!error?.errorFields) message.error(getRequestErrorText(error));
    } finally {
      if (mountedRef.current) setSubmitting(false);
    }
  };

  const blocked =
    submitting || creationOptions.initializing || creationOptions.capabilities.length === 0;

  return (
    <Drawer
      destroyOnHidden
      maskClosable={!submitting}
      open={open}
      size="large"
      title={initialGapId ? `从语义缺口 #${initialGapId} 生成草稿` : '从数据源生成 AI 建模草稿'}
      extra={
        <Space>
          <Button disabled={submitting} onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={blocked}
            loading={submitting}
            type="primary"
            onClick={() => void submit()}
          >
            生成草稿
          </Button>
        </Space>
      }
      onClose={submitting ? undefined : onClose}
    >
      <Spin spinning={creationOptions.initializing || creationOptions.metadataLoading}>
        <DraftCreateFormFields {...creationOptions} form={form} initialGapId={initialGapId} />
      </Spin>
    </Drawer>
  );
};

export default CreateDraftDrawer;
