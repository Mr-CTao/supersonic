/**
 * 大模型连接设置弹窗。
 *
 * 职责：
 * - 复用现有动态表单创建或编辑 LLM 连接；
 * - 为连接测试和保存按钮提供 loading 锁，避免管理员重复点击；
 * - 不在弹窗层读取或展示 API Key 明文，敏感字段仍由表单控件和后端配置策略处理。
 *
 * 并发说明：
 * - 弹窗不维护共享业务状态；
 * - 提交类动作通过本地 loading 状态串行化，后端仍需负责真实幂等与并发保护。
 */
import React, { useState, useRef } from 'react';
import { Button, Modal, Space } from 'antd';
import LlmCreateForm from './LlmCreateForm';
import { ISemantic } from '../../data';

export type CreateFormProps = {
  onCancel: () => void;
  llmItem?: ISemantic.ILlmItem;
  open: boolean;
  onSubmit: (values?: any) => void;
};

const DatabaseSettingModal: React.FC<CreateFormProps> = ({ onCancel, llmItem, open, onSubmit }) => {
  const [testLoading, setTestLoading] = useState<boolean>(false);
  const [saveLoading, setSaveLoading] = useState<boolean>(false);

  const createFormRef = useRef<any>({});

  // 连接测试依赖子表单实时校验后的配置，loading 锁用于避免重复测试请求。
  const handleTestConnection = async () => {
    setTestLoading(true);
    try {
      await createFormRef.current.testLlmConnection();
    } finally {
      setTestLoading(false);
    }
  };

  // 保存动作由子表单统一组装配置；弹窗层只负责按钮状态和重复提交保护。
  const handleSave = async () => {
    setSaveLoading(true);
    try {
      await createFormRef.current.saveLlmConfig();
    } finally {
      setSaveLoading(false);
    }
  };

  const renderFooter = () => {
    return (
      <>
        <Space>
          <Button
            type="primary"
            loading={testLoading}
            onClick={() => {
              handleTestConnection();
            }}
          >
            连接测试
          </Button>

          <Button
            type="primary"
            loading={saveLoading}
            onClick={() => {
              handleSave();
            }}
          >
            保 存
          </Button>
        </Space>
      </>
    );
  };

  return (
    <Modal
      width={800}
      destroyOnClose
      title="大模型设置"
      style={{ top: 48 }}
      maskClosable={false}
      open={open}
      footer={renderFooter()}
      onCancel={onCancel}
    >
      <LlmCreateForm
        ref={createFormRef}
        llmItem={llmItem}
        onSubmit={() => {
          onSubmit?.();
        }}
      />
    </Modal>
  );
};

export default DatabaseSettingModal;
