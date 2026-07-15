/**
 * 语义资产路由覆盖推荐弹窗。
 *
 * 职责：仅展示服务端允许的动作和当前有权候选，强制填写覆盖原因，并将选择交回服务端重新校验；不在前端直接改写推荐结果。
 */
import { Alert, Form, Input, Modal, Select, Space, Typography } from 'antd';
import React, { useEffect, useMemo } from 'react';
import type {
  SemanticAssetRouteAction,
  SemanticAssetRouteCandidate,
  SemanticAssetRouteDetail,
} from '@/services/semanticAssetRouting';
import type { AssetRoutingDecision } from './routingTypes';
import { ROUTE_ACTION_TEXT } from './routingUtils';

const { Text } = Typography;

type OverrideFormValues = {
  action: SemanticAssetRouteAction;
  candidateHandle?: string;
  overrideReason: string;
};

type Props = {
  loading?: boolean;
  open: boolean;
  route: SemanticAssetRouteDetail;
  onCancel: () => void;
  onSubmit: (decision: AssetRoutingDecision) => Promise<void>;
};

/** 返回动作允许使用的候选；增强动作只保留可管理候选。 */
function getCandidateOptions(
  route: SemanticAssetRouteDetail,
  action?: SemanticAssetRouteAction,
): SemanticAssetRouteCandidate[] {
  const candidates = [route.primaryCandidate, ...(route.alternativeCandidates ?? [])].filter(
    (item): item is SemanticAssetRouteCandidate => Boolean(item?.candidateHandle),
  );
  return action === 'EXTEND_EXISTING'
    ? candidates.filter((candidate) => candidate.manageable === true)
    : candidates;
}

/**
 * 渲染覆盖推荐弹窗。
 *
 * @param props 路由详情、开关状态、loading 和提交回调。
 * @returns 带服务端动作白名单、候选句柄和必填原因的 Modal。
 * @throws 表单校验错误由 Ant Design 字段内展示；提交错误由上层统一提示。
 */
const AssetRouteOverrideModal: React.FC<Props> = ({
  loading = false,
  open,
  route,
  onCancel,
  onSubmit,
}) => {
  const [form] = Form.useForm<OverrideFormValues>();
  const selectedAction = Form.useWatch('action', form);
  const allowedActions = route.allowedActions?.length
    ? route.allowedActions
    : route.recommendedAction
    ? [route.recommendedAction]
    : [];
  const candidates = useMemo(
    () => getCandidateOptions(route, selectedAction),
    [route, selectedAction],
  );

  useEffect(() => {
    if (!open) return;
    form.setFieldsValue({
      action: route.recommendedAction,
      candidateHandle: route.primaryCandidate?.candidateHandle,
      overrideReason: '',
    });
  }, [form, open, route]);

  /** 校验覆盖表单后交由工作流确认，后端仍会复核动作、候选、权限与版本。 */
  const submit = async () => {
    const values = await form.validateFields();
    await onSubmit({
      action: values.action,
      candidateHandle: values.candidateHandle,
      overrideReason: values.overrideReason.trim(),
    });
  };

  const candidateRequired =
    selectedAction === 'EXTEND_EXISTING' || selectedAction === 'REUSE_EXISTING';
  return (
    <Modal
      confirmLoading={loading}
      destroyOnHidden
      okText="确认更改"
      open={open}
      title="更改处理方式"
      onCancel={loading ? undefined : onCancel}
      onOk={() => void submit()}
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          showIcon
          type="warning"
          title="覆盖推荐会由服务端重新校验"
          description="新建相似资产或选择不兼容粒度可能增加问答检索歧义；页面不会绕过权限和版本门禁。"
        />
        <Form form={form} layout="vertical">
          <Form.Item
            label="处理方式"
            name="action"
            rules={[{ required: true, message: '请选择服务端允许的处理方式' }]}
          >
            <Select
              aria-label="覆盖处理方式"
              options={allowedActions.map((action) => ({
                label: ROUTE_ACTION_TEXT[action],
                value: action,
              }))}
              onChange={() => form.setFieldValue('candidateHandle', undefined)}
            />
          </Form.Item>
          {candidateRequired ? (
            <Form.Item
              label="目标候选"
              name="candidateHandle"
              rules={[{ required: true, message: '请选择有权限的候选资产' }]}
            >
              <Select
                aria-label="覆盖目标候选"
                options={candidates.map((candidate) => ({
                  label: candidate.bizName || candidate.name,
                  value: candidate.candidateHandle,
                }))}
                placeholder="仅展示服务端授权候选"
              />
            </Form.Item>
          ) : null}
          <Form.Item
            label="覆盖原因"
            name="overrideReason"
            rules={[
              { required: true, whitespace: true, message: '请说明更改推荐的业务原因' },
              { max: 500, message: '覆盖原因不能超过 500 个字符' },
            ]}
          >
            <Input.TextArea maxLength={500} rows={4} showCount />
          </Form.Item>
        </Form>
        {!allowedActions.some((action) => action !== route.recommendedAction) ? (
          <Text type="secondary">当前权限和风险策略未允许切换到其他处理动作。</Text>
        ) : null}
      </Space>
    </Modal>
  );
};

export default AssetRouteOverrideModal;
