/**
 * 语义资产路由第一步范围确认组件。
 *
 * 职责：复用现有建模上下文字段，展示范围失效提示，并把所有影响指纹的字段变化回传给工作流容器；不发起网络请求。
 */
import { Alert, Space } from 'antd';
import type { FormInstance } from 'antd';
import React from 'react';
import DraftCreateFormFields from './DraftCreateFormFields';
import type { CreateDraftFormValues } from './creationTypes';
import type { DraftCreationOptions } from './useDraftCreationOptions';

type Props = {
  form: FormInstance<CreateDraftFormValues>;
  initialGapId?: number;
  invalidationText?: string;
  options: DraftCreationOptions;
  onValuesChange: (
    changedValues: Partial<CreateDraftFormValues>,
    values: CreateDraftFormValues,
  ) => void;
};

/**
 * 渲染“确认分析范围”步骤。
 *
 * @param props 表单、元数据选项、失效提示及字段变化回调。
 * @returns 复用现有字段的第一步内容。
 * @throws 不抛出异常。
 */
const AssetRouteScopeStep: React.FC<Props> = ({
  form,
  initialGapId,
  invalidationText,
  options,
  onValuesChange,
}) => (
  <Space orientation="vertical" size={16} style={{ width: '100%' }}>
    {invalidationText ? (
      <Alert showIcon type="warning" title="原分析结果已失效" description={invalidationText} />
    ) : null}
    <DraftCreateFormFields
      {...options}
      form={form}
      initialGapId={initialGapId}
      onValuesChange={onValuesChange}
    />
  </Space>
);

export default AssetRouteScopeStep;
