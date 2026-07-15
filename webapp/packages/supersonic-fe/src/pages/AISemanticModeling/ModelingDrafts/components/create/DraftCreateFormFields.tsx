/**
 * 建模草稿创建表单字段组件。
 *
 * 职责：渲染业务目标、主题域、数据源级联、1-10 张选表、JSON-capable LLM 和脱敏样例开关，不负责提交请求。
 *
 * 并发说明：字段组件无独立异步状态，级联加载与过期响应保护由 useDraftCreationOptions 负责。
 */
import { Alert, Form, Input, Select, Space, Switch, Typography } from 'antd';
import type { FormInstance } from 'antd';
import React from 'react';
import type { DraftCreationOptions } from './useDraftCreationOptions';
import { MAX_SELECTED_TABLES, type CreateDraftFormValues } from './creationTypes';

const { Text } = Typography;

type Props = DraftCreationOptions & {
  form: FormInstance<CreateDraftFormValues>;
  initialGapId?: number;
  onValuesChange?: (
    changedValues: Partial<CreateDraftFormValues>,
    values: CreateDraftFormValues,
  ) => void;
};

/**
 * 创建表单字段组件。
 *
 * @param props 表单实例、缺口来源和元数据 Hook 返回值。
 * @returns 完整创建字段与安全提示。
 * @throws 不抛出异常；级联请求错误由 Hook 展示。
 */
const DraftCreateFormFields: React.FC<Props> = ({
  form,
  initialGapId,
  initializing,
  databaseOptions,
  domainOptions,
  capabilities,
  catalogOptions,
  databaseNameOptions,
  tableOptions,
  catalogRequired,
  loadDatabaseChildren,
  loadDatabaseNames,
  loadTables,
  onValuesChange,
}) => (
  <Space orientation="vertical" size={16} style={{ width: '100%' }}>
    {initialGapId ? (
      <Alert
        showIcon
        type="info"
        title="已带入语义缺口上下文"
        description="系统会优先判断现有资产能否复用或增强；请先确认数据库、参与分析的表和业务目标。"
      />
    ) : null}
    {capabilities.length === 0 && !initializing ? (
      <Alert
        showIcon
        type="error"
        title="没有可用的 JSON-capable LLM"
        description="请先在大模型管理中启用模型并打开 JSON Output 能力；规则证据不足时，页面不会跳过语义比较直接创建草稿。"
      />
    ) : null}
    <Form<CreateDraftFormValues>
      form={form}
      layout="vertical"
      initialValues={{ includeSampleData: false }}
      onValuesChange={onValuesChange}
    >
      <Form.Item
        label="业务目标"
        name="businessGoal"
        rules={[
          { required: true, whitespace: true, message: '请输入建模业务目标' },
          { max: 2000, message: '业务目标不能超过 2000 个字符' },
        ]}
      >
        <Input.TextArea
          maxLength={2000}
          placeholder="例如：支持按仓库、库位、物料分析当前库存"
          rows={4}
          showCount
        />
      </Form.Item>
      <Form.Item label="目标主题域（可选）" name="domainId">
        <Select
          allowClear
          options={domainOptions}
          placeholder="仅展示当前用户可管理的主题域"
          showSearch
          optionFilterProp="label"
        />
      </Form.Item>
      <Form.Item
        label="数据源"
        name="dataSourceId"
        rules={[{ required: true, message: '请选择数据源' }]}
      >
        <Select
          options={databaseOptions.map((item) => ({ label: item.name, value: item.id }))}
          placeholder="请选择有使用权限的数据源"
          showSearch
          optionFilterProp="label"
          onChange={(databaseId) => {
            const database = databaseOptions.find((item) => item.id === databaseId);
            void loadDatabaseChildren(databaseId, database?.type);
          }}
        />
      </Form.Item>
      {catalogRequired ? (
        <Form.Item
          label="Catalog"
          name="catalogName"
          rules={[{ required: true, message: '请选择 Catalog' }]}
        >
          <Select
            options={catalogOptions.map((item) => ({ label: item, value: item }))}
            placeholder="请选择 Catalog"
            showSearch
            onChange={(value) => void loadDatabaseNames(value)}
          />
        </Form.Item>
      ) : null}
      <Form.Item
        label="数据库"
        name="databaseName"
        rules={[{ required: true, message: '请选择数据库' }]}
      >
        <Select
          options={databaseNameOptions.map((item) => ({ label: item, value: item }))}
          placeholder="请选择数据库"
          showSearch
          onChange={(value) => void loadTables(value)}
        />
      </Form.Item>
      <Form.Item
        label="参与建模的表或视图"
        name="selectedTables"
        rules={[
          { required: true, type: 'array', min: 1, message: '请至少选择一张表或视图' },
          {
            validator: async (_, value?: string[]) => {
              if ((value?.length || 0) > MAX_SELECTED_TABLES) {
                throw new Error(`第一版最多选择 ${MAX_SELECTED_TABLES} 张表`);
              }
            },
          },
        ]}
      >
        <Select
          maxCount={MAX_SELECTED_TABLES}
          mode="multiple"
          options={tableOptions.map((item) => ({ label: item, value: item }))}
          placeholder="仅使用管理员明确选择的表，最多 10 张"
          showSearch
          optionFilterProp="label"
        />
      </Form.Item>
      <Form.Item
        label="语义建议模型"
        name="chatModelId"
        rules={[{ required: true, message: '请选择支持 JSON Output 的模型' }]}
      >
        <Select
          options={capabilities.map((item) => ({
            label: `${item.providerType || 'LLM'} / ${item.modelName}`,
            value: item.chatModelId,
          }))}
          placeholder="请选择 JSON-capable LLM"
          showSearch
          optionFilterProp="label"
        />
      </Form.Item>
      <Form.Item label="使用脱敏样例" name="includeSampleData" valuePropName="checked">
        <Switch />
      </Form.Item>
      <Alert
        showIcon
        type="warning"
        title="样例数据默认关闭"
        description="开启后，每张表最多采样 3 行并由服务端脱敏后进入模型上下文；页面不会展示或保存样例行。若无法确认数据外发边界，请保持关闭。"
      />
      <Text type="secondary">分析不会修改正式语义资产；只有确认新建或增强后才会生成独立草稿。</Text>
    </Form>
  </Space>
);

export default DraftCreateFormFields;
