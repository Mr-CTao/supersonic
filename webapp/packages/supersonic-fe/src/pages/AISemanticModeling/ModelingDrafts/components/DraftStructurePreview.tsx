/**
 * AI 语义建模草稿分类预览组件。
 *
 * 职责：
 * - 按模型分类展示维度、指标、敏感字段和示例问法；
 * - 展示业务目标、目标主题域、术语及可关联模型的域级不确定项；
 * - 仅渲染经过 JSON 解析的结构化草稿，不展示模型原始输出或样例数据。
 *
 * 并发说明：
 * - 本组件为纯展示组件，不发起请求、不维护共享状态，无需并发保护。
 */
import {
  Alert,
  Card,
  Collapse,
  Descriptions,
  Empty,
  List,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React from 'react';
import type {
  DraftDimension,
  DraftMetric,
  DraftSensitiveField,
  DraftTerm,
  DraftUncertainty,
  SemanticModelDraftModel,
  SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';
import styles from '../style.less';

const { Paragraph, Text, Title } = Typography;

type Props = {
  draft?: SemanticModelingDraftJson;
};

/** 将字符串数组渲染为紧凑标签，空数组显示短横线。 */
const renderStringTags = (values?: string[]) => {
  if (!values?.length) {
    return '-';
  }
  return (
    <Space size={[4, 4]} wrap>
      {values.map((item) => (
        <Tag key={item}>{item}</Tag>
      ))}
    </Space>
  );
};

const dimensionColumns: ColumnsType<DraftDimension> = [
  { title: '名称', dataIndex: 'name', width: 140 },
  { title: '字段', dataIndex: 'field', width: 160 },
  { title: '语义类型', dataIndex: 'semanticType', width: 120 },
  { title: '别名', dataIndex: 'aliases', width: 180, render: renderStringTags },
  { title: '说明', dataIndex: 'description' },
];

const metricColumns: ColumnsType<DraftMetric> = [
  { title: '名称', dataIndex: 'name', width: 140 },
  {
    title: '聚合',
    dataIndex: 'aggregation',
    width: 120,
    render: (value) => <Tag color="blue">{value}</Tag>,
  },
  { title: '表达式', dataIndex: 'expression', width: 220 },
  { title: '别名', dataIndex: 'aliases', width: 180, render: renderStringTags },
  { title: '说明', dataIndex: 'description' },
  {
    title: '结构化过滤',
    dataIndex: 'filters',
    width: 220,
    render: (value) => (value?.length ? JSON.stringify(value) : '-'),
  },
];

const termColumns: ColumnsType<DraftTerm> = [
  { title: '术语', dataIndex: 'name', width: 140 },
  { title: '别名', dataIndex: 'aliases', width: 180, render: renderStringTags },
  {
    title: '映射目标',
    key: 'mappingTarget',
    width: 180,
    render: (_, record) => {
      if (record.targets?.length) {
        return (
          <Space size={[4, 4]} wrap>
            {record.targets.map((target) => (
              <Tag key={`${target.type}-${target.objectKey}`}>
                {target.type}: {target.objectKey}
              </Tag>
            ))}
          </Space>
        );
      }
      return record.mappingTargetKey || record.mappingTarget || '-';
    },
  },
  { title: '说明', dataIndex: 'description' },
];

const sensitiveColumns: ColumnsType<DraftSensitiveField> = [
  { title: '字段', dataIndex: 'field', width: 180 },
  {
    title: '等级',
    dataIndex: 'level',
    width: 100,
    render: (value) => (
      <Tag color={value === 'HIGH' ? 'red' : value === 'MEDIUM' ? 'orange' : 'blue'}>{value}</Tag>
    ),
  },
  { title: '脱敏策略', dataIndex: 'maskingStrategy', width: 180 },
  { title: '原因', dataIndex: 'reason' },
];

/**
 * 渲染一组不确定项。
 *
 * @param items 不确定项数组。
 * @returns 醒目的警告列表或“暂无”占位。
 * @throws 不抛出异常。
 */
function renderUncertainties(items?: DraftUncertainty[]) {
  if (!items?.length) {
    return <Text type="secondary">暂无不确定项</Text>;
  }
  return (
    <List
      size="small"
      dataSource={items}
      renderItem={(item, index) => (
        <List.Item key={item.key || `${item.field || item.objectKey || 'uncertainty'}-${index}`}>
          <Alert
            className={styles.uncertaintyAlert}
            showIcon
            type="warning"
            title={
              <Space size={4} wrap>
                <Text strong>
                  {item.field || item.objectKey || item.type || `不确定项 ${index + 1}`}
                </Text>
                {item.category ? <Tag color="orange">{item.category}</Tag> : null}
                {item.severity ? <Tag color="red">{item.severity}</Tag> : null}
              </Space>
            }
            description={
              <Space orientation="vertical" size={2}>
                <Text>{item.reason}</Text>
                {item.modelKey ? <Text type="secondary">关联模型：{item.modelKey}</Text> : null}
                {item.suggestion ? <Text type="secondary">建议：{item.suggestion}</Text> : null}
              </Space>
            }
          />
        </List.Item>
      )}
    />
  );
}

/**
 * 渲染单个模型的全部草稿分类。
 *
 * @param model 模型草稿。
 * @param modelIndex 模型序号，用于稳定表格 rowKey。
 * @returns 模型详情区域。
 * @throws 不抛出异常。
 */
function renderModel(model: SemanticModelDraftModel, modelIndex: number) {
  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Descriptions bordered column={2} size="small">
        <Descriptions.Item label="英文标识">{model.bizName || '-'}</Descriptions.Item>
        <Descriptions.Item label="基础表">{model.baseTable || '-'}</Descriptions.Item>
        <Descriptions.Item label="主时间字段">{model.primaryTimeField || '-'}</Descriptions.Item>
        <Descriptions.Item label="说明">{model.description || '-'}</Descriptions.Item>
      </Descriptions>

      <section>
        <Title level={5}>维度（{model.dimensions?.length || 0}）</Title>
        <Table<DraftDimension>
          columns={dimensionColumns}
          dataSource={model.dimensions || []}
          locale={{ emptyText: '暂无维度' }}
          pagination={false}
          rowKey={(record, index) => record.key || `${modelIndex}-dimension-${index}`}
          scroll={{ x: 760 }}
          size="small"
        />
      </section>

      <section>
        <Title level={5}>指标（{model.metrics?.length || 0}）</Title>
        <Table<DraftMetric>
          columns={metricColumns}
          dataSource={model.metrics || []}
          locale={{ emptyText: '暂无指标' }}
          pagination={false}
          rowKey={(record, index) => record.key || `${modelIndex}-metric-${index}`}
          scroll={{ x: 900 }}
          size="small"
        />
      </section>

      <section>
        <Title level={5}>敏感字段（{model.sensitiveFields?.length || 0}）</Title>
        <Table<DraftSensitiveField>
          columns={sensitiveColumns}
          dataSource={model.sensitiveFields || []}
          locale={{ emptyText: '暂无敏感字段建议' }}
          pagination={false}
          rowKey={(record, index) => `${record.field}-${index}`}
          scroll={{ x: 720 }}
          size="small"
        />
      </section>

      <section>
        <Title level={5}>示例问法（{model.sampleQuestions?.length || 0}）</Title>
        <List
          bordered
          dataSource={model.sampleQuestions || []}
          locale={{ emptyText: '暂无示例问法' }}
          size="small"
          renderItem={(item, index) => <List.Item key={`${index}-${item}`}>{item}</List.Item>}
        />
      </section>
    </Space>
  );
}

/**
 * 草稿分类预览组件。
 *
 * @param props 已解析的结构化草稿。
 * @returns 按语义资产类别组织的只读预览。
 * @throws 不抛出异常。
 */
const DraftStructurePreview: React.FC<Props> = ({ draft }) => {
  if (!draft) {
    return <Empty description="暂无可预览的结构化草稿" />;
  }

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title="业务上下文">
        <Descriptions column={1} size="small">
          <Descriptions.Item label="业务目标">
            <Paragraph style={{ marginBottom: 0 }}>{draft.businessGoal || '-'}</Paragraph>
          </Descriptions.Item>
          <Descriptions.Item label="目标主题域">
            {[draft.targetDomain?.name, draft.targetDomain?.bizName].filter(Boolean).join(' / ') ||
              '-'}
          </Descriptions.Item>
          <Descriptions.Item label="主题域说明">
            {draft.targetDomain?.description || '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Collapse
        defaultActiveKey={(draft.models || []).map((_, index) => `model-${index}`)}
        items={(draft.models || []).map((model, index) => ({
          key: `model-${index}`,
          label: `${model.name || `模型 ${index + 1}`} · ${model.baseTable || '未指定基础表'}`,
          children: renderModel(model, index),
        }))}
      />

      <Card size="small" title={`域级术语（${draft.terms?.length || 0}）`}>
        <Table<DraftTerm>
          columns={termColumns}
          dataSource={draft.terms || []}
          locale={{ emptyText: '暂无域级术语' }}
          pagination={false}
          rowKey={(record, index) => record.key || `domain-term-${index}`}
          scroll={{ x: 720 }}
          size="small"
        />
      </Card>

      <Card size="small" title={`域级不确定项（${draft.uncertainties?.length || 0}）`}>
        {renderUncertainties(draft.uncertainties)}
      </Card>
    </Space>
  );
};

export default DraftStructurePreview;
