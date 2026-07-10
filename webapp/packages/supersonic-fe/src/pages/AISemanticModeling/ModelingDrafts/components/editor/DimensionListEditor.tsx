/**
 * 建模草稿维度对象编辑器。
 *
 * 职责：受控编辑维度 key、名称、英文标识、字段、语义类型、别名和说明，支持新增与删除维度。
 *
 * 并发说明：组件不维护异步状态，所有修改通过不可变数组立即回传父组件。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Form, Input, Row, Select, Space, Tooltip } from 'antd';
import React from 'react';
import type { DraftDimension } from '@/services/semanticModelingDraft';
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editorUtils';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

type Props = {
  value?: DraftDimension[];
  onChange: (value: DraftDimension[]) => void;
};

/** 创建一条字段待管理员确认的空维度。 */
function createDimension(): DraftDimension {
  return {
    key: createDraftObjectKey('dimension'),
    name: '新维度',
    bizName: '',
    field: '',
    semanticType: 'categorical',
    aliases: [],
    description: '',
  };
}

/**
 * 受控维度列表编辑器。
 *
 * @param props 当前维度数组和变更回调。
 * @returns 维度卡片列表。
 * @throws 不抛出异常。
 */
const DimensionListEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'dimension-row');
  /** 更新指定维度并返回新数组。 */
  const update = (index: number, patch: Partial<DraftDimension>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      {value.length ? (
        value.map((dimension, index) => (
          <Card
            key={rowKeys[index]}
            size="small"
            title={`维度 ${index + 1} · ${dimension.name || '未命名'}`}
            extra={
              <Tooltip title="删除维度">
                <Button
                  aria-label={`删除维度 ${dimension.name || index + 1}`}
                  danger
                  icon={<DeleteOutlined />}
                  size="small"
                  title="删除维度"
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
                      value={dimension.key}
                      onChange={(event) => update(index, { key: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="名称" required>
                    <Input
                      value={dimension.name}
                      onChange={(event) => update(index, { name: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="英文标识" required>
                    <Input
                      value={dimension.bizName}
                      onChange={(event) => update(index, { bizName: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="字段" required>
                    <Input
                      value={dimension.field}
                      onChange={(event) => update(index, { field: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="语义类型">
                    <Input
                      value={dimension.semanticType}
                      onChange={(event) => update(index, { semanticType: event.target.value })}
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
                      value={dimension.aliases || []}
                      onChange={(aliases) => update(index, { aliases })}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label="说明">
                <Input.TextArea
                  autoSize={{ minRows: 2, maxRows: 4 }}
                  value={dimension.description}
                  onChange={(event) => update(index, { description: event.target.value })}
                />
              </Form.Item>
            </Form>
          </Card>
        ))
      ) : (
        <Empty description="暂无维度" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        type="dashed"
        onClick={() => onChange([...value, createDimension()])}
      >
        新增维度
      </Button>
    </Space>
  );
};

export default DimensionListEditor;
