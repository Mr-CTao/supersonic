/**
 * 建模草稿域级不确定项编辑器。
 *
 * 职责：受控编辑不确定项 key、模型/对象关联、字段、分类、严重级别和必须人工确认的原因。
 *
 * 并发说明：组件无异步状态，修改通过不可变数组同步父级草稿。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Form, Input, Row, Select, Space, Tooltip } from 'antd';
import React from 'react';
import type { DraftUncertainty } from '@/services/semanticModelingDraft';
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editorUtils';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

type Props = {
  value?: DraftUncertainty[];
  onChange: (value: DraftUncertainty[]) => void;
};

/** 创建一条必须补充原因的新不确定项。 */
function createUncertainty(): DraftUncertainty {
  return {
    key: createDraftObjectKey('uncertainty'),
    modelKey: '',
    objectKey: '',
    field: '',
    category: 'BUSINESS_DEFINITION',
    severity: 'WARNING',
    reason: '',
  };
}

/**
 * 受控不确定项列表编辑器。
 *
 * @param props 当前不确定项数组和变更回调。
 * @returns 不确定项卡片列表。
 * @throws 不抛出异常。
 */
const UncertaintyListEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'uncertainty-row');
  /** 更新指定不确定项。 */
  const update = (index: number, patch: Partial<DraftUncertainty>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      {value.length ? (
        value.map((item, index) => (
          <Card
            key={rowKeys[index]}
            size="small"
            title={`不确定项 ${index + 1}`}
            extra={
              <Tooltip title="删除不确定项">
                <Button
                  aria-label={`删除第 ${index + 1} 个不确定项`}
                  danger
                  icon={<DeleteOutlined />}
                  size="small"
                  title="删除不确定项"
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
                      value={item.key}
                      onChange={(event) => update(index, { key: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="模型 Key">
                    <Input
                      value={item.modelKey}
                      onChange={(event) => update(index, { modelKey: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="对象 Key 引用">
                    <Input
                      value={item.objectKey}
                      onChange={(event) => update(index, { objectKey: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="字段">
                    <Input
                      value={item.field}
                      onChange={(event) => update(index, { field: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="分类" required>
                    <Input
                      value={item.category}
                      onChange={(event) => update(index, { category: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="严重级别" required>
                    <Select
                      options={['INFO', 'WARNING', 'BLOCKING'].map((severity) => ({
                        label: severity,
                        value: severity,
                      }))}
                      value={item.severity}
                      onChange={(severity) => update(index, { severity })}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label="待确认原因" required>
                <Input.TextArea
                  autoSize={{ minRows: 2, maxRows: 5 }}
                  value={item.reason}
                  onChange={(event) => update(index, { reason: event.target.value })}
                />
              </Form.Item>
            </Form>
          </Card>
        ))
      ) : (
        <Empty description="暂无不确定项" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        type="dashed"
        onClick={() => onChange([...value, createUncertainty()])}
      >
        新增不确定项
      </Button>
    </Space>
  );
};

export default UncertaintyListEditor;
