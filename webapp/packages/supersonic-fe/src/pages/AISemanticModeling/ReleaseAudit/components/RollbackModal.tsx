/**
 * AI 新增对象回滚确认弹窗。
 *
 * 职责：强制管理员填写回滚原因并展示服务端限定范围提示；不允许客户端选择或上传对象 ID。
 */
import { Alert, Form, Input, Modal } from 'antd';
import React, { useEffect } from 'react';
import type { SemanticReleaseItem } from '@/services/semanticRelease';

type RollbackModalProps = {
  release?: SemanticReleaseItem;
  open: boolean;
  confirmLoading: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => Promise<void>;
};

/**
 * 渲染回滚原因表单。
 *
 * @param props 发布记录、loading 和提交回调。
 * @returns 回滚确认 Modal。
 * @throws 表单校验失败时阻止提交，不向父组件抛出。
 */
const RollbackModal: React.FC<RollbackModalProps> = ({
  release,
  open,
  confirmLoading,
  onCancel,
  onConfirm,
}) => {
  const [form] = Form.useForm<{ reason: string }>();

  useEffect(() => {
    if (!open) form.resetFields();
  }, [form, open]);

  return (
    <Modal
      title={`回滚发布 ${release?.releaseNo || ''}`}
      open={open}
      okText="确认回滚"
      okButtonProps={{ danger: true }}
      confirmLoading={confirmLoading}
      onCancel={onCancel}
      onOk={async () => {
        const values = await form.validateFields();
        await onConfirm(values.reason.trim());
      }}
      destroyOnClose
    >
      <Alert
        type="warning"
        showIcon
        message="仅逆序删除本发布记录登记的 AI 新增对象，并重新刷新 dict 与 embedding。"
        style={{ marginBottom: 16 }}
      />
      <Form form={form} layout="vertical">
        <Form.Item
          label="回滚原因"
          name="reason"
          rules={[
            { required: true, whitespace: true, message: '请填写回滚原因' },
            { max: 1000, message: '回滚原因不能超过 1000 个字符' },
          ]}
        >
          <Input.TextArea rows={4} maxLength={1000} showCount />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default RollbackModal;
