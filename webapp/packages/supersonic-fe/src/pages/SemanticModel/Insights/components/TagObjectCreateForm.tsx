/**
 * 标签对象创建与编辑弹窗。
 *
 * 职责：
 * - 提供标签对象名称、英文名称和描述的表单录入；
 * - 根据是否存在 `tagItem.id` 自动选择创建或更新接口；
 * - 在标签能力开启后支撑“主题域 -> 标签对象 -> 模型绑定 -> 标签市场”的基础配置链路。
 */
import React, { useEffect, useRef } from 'react';
import { Form, Button, Modal, Steps, Input, Select, message } from 'antd';

import { formLayout } from '@/components/FormHelper/utils';
import styles from '../../components/style.less';

import { createTagObject, updateTagObject } from '../../service';
import { ISemantic } from '../../data';

export type CreateFormProps = {
  datasourceId?: number;
  domainId: number;
  createModalVisible: boolean;
  tagItem?: ISemantic.ITagItem;
  onCancel?: () => void;
  onSubmit?: (values: any) => void;
};

const FormItem = Form.Item;
const { TextArea } = Input;

const TagObjectCreateForm: React.FC<CreateFormProps> = ({
  domainId,
  onCancel,
  createModalVisible,
  tagItem,
  onSubmit,
}) => {
  const isEdit = !!tagItem?.id;
  const formValRef = useRef({} as any);
  const [form] = Form.useForm();
  const updateFormVal = (val: any) => {
    const formVal = {
      ...formValRef.current,
      ...val,
    };
    formValRef.current = formVal;
  };

  const handleNext = async () => {
    const fieldsValue = await form.validateFields();
    const submitForm = {
      ...formValRef.current,
      ...fieldsValue,
    };
    updateFormVal(submitForm);

    await saveTag(submitForm);
  };

  const initData = () => {
    if (!tagItem) {
      return;
    }

    const initValue = {
      ...tagItem,
    };
    const editInitFormVal = {
      ...formValRef.current,
      ...initValue,
    };

    updateFormVal(editInitFormVal);
    form.setFieldsValue(initValue);
  };

  useEffect(() => {
    if (isEdit) {
      initData();
    }
  }, [tagItem]);

  /**
   * 保存标签对象表单。
   *
   * @param fieldsValue 已通过表单校验的标签对象字段。
   * @returns 保存完成后无显式返回；成功时触发父组件刷新。
   * @throws 表单校验异常由调用方 `handleNext` 透传给 Ant Design Form。
   */
  const saveTag = async (fieldsValue: any) => {
    const queryParams = {
      domainId: isEdit ? tagItem.domainId : domainId,
      ...fieldsValue,
    };

    let saveTagQuery = createTagObject;
    if (queryParams.id) {
      saveTagQuery = updateTagObject;
    }
    const { code, msg } = await saveTagQuery(queryParams);
    if (code === 200) {
      message.success('编辑标签成功');
      onSubmit?.(queryParams);
      return;
    }
    message.error(msg);
  };

  const renderContent = () => {
    return (
      <>
        <FormItem hidden={true} name="id" label="ID">
          <Input placeholder="id" />
        </FormItem>
        <FormItem
          name="name"
          label="标签对象名称"
          rules={[{ required: true, message: '请输入标签对象名称' }]}
        >
          <Input placeholder="名称不可重复" />
        </FormItem>
        <FormItem
          name="bizName"
          label="英文名称"
          rules={[{ required: true, message: '请输入英文名称' }]}
        >
          <Input placeholder="名称不可重复" disabled={isEdit} />
        </FormItem>
        <FormItem
          name="description"
          label={'描述'}
          rules={[{ required: true, message: '请输入业务口径' }]}
        >
          <TextArea placeholder="请输入业务口径" />
        </FormItem>
      </>
    );
  };
  const renderFooter = () => {
    return (
      <>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" onClick={handleNext}>
          完成
        </Button>
      </>
    );
  };
  return (
    <Modal
      forceRender
      width={800}
      style={{ top: 48 }}
      // styles={{ padding: '32px 40px 48px' }}
      destroyOnHidden
      title={`${isEdit ? '编辑' : '新建'}标签对象`}
      maskClosable={false}
      open={createModalVisible}
      footer={renderFooter()}
      onCancel={onCancel}
    >
      <>
        <Form
          {...formLayout}
          form={form}
          initialValues={{
            ...formValRef.current,
          }}
          className={styles.form}
        >
          {renderContent()}
        </Form>
      </>
    </Modal>
  );
};

export default TagObjectCreateForm;
