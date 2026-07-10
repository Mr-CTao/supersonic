/**
 * 建模草稿指标对象编辑器。
 *
 * 职责：受控编辑指标 key、名称、字段、允许的聚合方式、单列表达式、别名、说明及结构化过滤条件。
 *
 * 并发说明：组件不发起请求，所有嵌套修改均通过不可变对象同步父组件，避免 JSON 与表单状态分叉。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Form, Input, Row, Select, Space, Tooltip } from 'antd';
import React from 'react';
import type { DraftMetric } from '@/services/semanticModelingDraft';
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editorUtils';
import MetricFilterEditor from './MetricFilterEditor';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

const AGGREGATIONS: DraftMetric['aggregation'][] = [
  'SUM',
  'COUNT',
  'COUNT_DISTINCT',
  'AVG',
  'MAX',
  'MIN',
];

type Props = {
  value?: DraftMetric[];
  onChange: (value: DraftMetric[]) => void;
};

/** 创建一条由管理员填写字段、后端可自动补齐规范表达式的新指标。 */
function createMetric(): DraftMetric {
  return {
    key: createDraftObjectKey('metric'),
    name: '新指标',
    bizName: '',
    field: '',
    aggregation: 'SUM',
    expression: '',
    aliases: [],
    filters: [],
    description: '',
  };
}

/**
 * 受控指标列表编辑器。
 *
 * @param props 当前指标数组和变更回调。
 * @returns 指标卡片和结构化过滤器。
 * @throws 不抛出异常。
 */
const MetricListEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'metric-row');
  /** 更新指定指标并同步父级草稿。 */
  const update = (index: number, patch: Partial<DraftMetric>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      {value.length ? (
        value.map((metric, index) => (
          <Card
            key={rowKeys[index]}
            size="small"
            title={`指标 ${index + 1} · ${metric.name || '未命名'}`}
            extra={
              <Tooltip title="删除指标">
                <Button
                  aria-label={`删除指标 ${metric.name || index + 1}`}
                  danger
                  icon={<DeleteOutlined />}
                  size="small"
                  title="删除指标"
                  onClick={() => onChange(removeArrayItem(value, index))}
                />
              </Tooltip>
            }
          >
            <Form layout="vertical">
              <Row gutter={12}>
                <Col span={8}>
                  <Form.Item label="对象 Key" required>
                    <Input
                      value={metric.key}
                      onChange={(event) => update(index, { key: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="名称" required>
                    <Input
                      value={metric.name}
                      onChange={(event) => update(index, { name: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="英文标识" required>
                    <Input
                      value={metric.bizName}
                      onChange={(event) => update(index, { bizName: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="字段" required>
                    <Input
                      value={metric.field}
                      onChange={(event) => update(index, { field: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="聚合方式" required>
                    <Select
                      options={AGGREGATIONS.map((aggregation) => ({
                        label: aggregation,
                        value: aggregation,
                      }))}
                      value={metric.aggregation}
                      onChange={(aggregation) => update(index, { aggregation })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="别名">
                    <Select
                      mode="tags"
                      open={false}
                      placeholder="输入后回车"
                      tokenSeparators={[',', '，']}
                      value={metric.aliases || []}
                      onChange={(aliases) => update(index, { aliases })}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                label="表达式"
                extra="可选；留空时后端按聚合方式和字段自动补齐，仅接受 Schema 1.0 支持的单列聚合表达式。"
              >
                <Input
                  placeholder="例如 SUM(quantity)"
                  value={metric.expression ?? ''}
                  onChange={(event) => update(index, { expression: event.target.value })}
                />
              </Form.Item>
              <Form.Item label="说明">
                <Input.TextArea
                  autoSize={{ minRows: 2, maxRows: 4 }}
                  value={metric.description}
                  onChange={(event) => update(index, { description: event.target.value })}
                />
              </Form.Item>
              <Form.Item label="结构化过滤器">
                <MetricFilterEditor
                  value={metric.filters}
                  onChange={(filters) => update(index, { filters })}
                />
              </Form.Item>
            </Form>
          </Card>
        ))
      ) : (
        <Empty description="暂无指标" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        type="dashed"
        onClick={() => onChange([...value, createMetric()])}
      >
        新增指标
      </Button>
    </Space>
  );
};

export default MetricListEditor;
