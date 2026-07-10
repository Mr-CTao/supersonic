/**
 * 建模草稿域级术语编辑器。
 *
 * 职责：受控编辑术语 key、名称、别名、说明和本地维度/指标 targets，支持新增与删除术语。
 *
 * 并发说明：组件不维护独立表单副本，嵌套 target 修改直接合并为新的术语数组。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Card, Col, Empty, Form, Input, Row, Select, Space, Tooltip } from 'antd';
import React from 'react';
import type { DraftTerm } from '@/services/semanticModelingDraft';
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editorUtils';
import TermTargetEditor from './TermTargetEditor';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

type Props = {
  value?: DraftTerm[];
  onChange: (value: DraftTerm[]) => void;
};

/** 创建一条 targets 待确认的新术语。 */
function createTerm(): DraftTerm {
  return {
    key: createDraftObjectKey('term'),
    name: '新术语',
    aliases: [],
    targets: [],
    description: '',
  };
}

/**
 * 受控域级术语列表编辑器。
 *
 * @param props 当前术语数组和变更回调。
 * @returns 术语卡片列表。
 * @throws 不抛出异常。
 */
const TermListEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'term-row');
  /** 更新指定术语。 */
  const update = (index: number, patch: Partial<DraftTerm>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      {value.length ? (
        value.map((term, index) => (
          <Card
            key={rowKeys[index]}
            size="small"
            title={`术语 ${index + 1} · ${term.name || '未命名'}`}
            extra={
              <Tooltip title="删除术语">
                <Button
                  aria-label={`删除术语 ${term.name || index + 1}`}
                  danger
                  icon={<DeleteOutlined />}
                  size="small"
                  title="删除术语"
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
                      value={term.key}
                      onChange={(event) => update(index, { key: event.target.value })}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item label="名称" required>
                    <Input
                      value={term.name}
                      onChange={(event) => update(index, { name: event.target.value })}
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
                      value={term.aliases || []}
                      onChange={(aliases) => update(index, { aliases })}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item label="说明">
                <Input.TextArea
                  autoSize={{ minRows: 1, maxRows: 3 }}
                  value={term.description}
                  onChange={(event) => update(index, { description: event.target.value })}
                />
              </Form.Item>
              <Form.Item label="映射目标" required extra="仅填写当前草稿中的维度或指标对象 key。">
                <TermTargetEditor
                  value={term.targets}
                  onChange={(targets) => update(index, { targets })}
                />
              </Form.Item>
            </Form>
          </Card>
        ))
      ) : (
        <Empty description="暂无域级术语" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        type="dashed"
        onClick={() => onChange([...value, createTerm()])}
      >
        新增术语
      </Button>
    </Space>
  );
};

export default TermListEditor;
