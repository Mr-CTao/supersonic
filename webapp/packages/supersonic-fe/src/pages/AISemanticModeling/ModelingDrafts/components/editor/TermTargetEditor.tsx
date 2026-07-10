/**
 * 建模草稿术语目标编辑器。
 *
 * 职责：受控编辑域级术语关联的维度或指标对象 key，避免引用正式语义资产 ID。
 *
 * 并发说明：组件无异步状态，每次修改立即返回新的 targets 数组。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Col, Empty, Input, Row, Select, Space, Tooltip } from 'antd';
import React from 'react';
import type { DraftTermTarget } from '@/services/semanticModelingDraft';
import { removeArrayItem, replaceArrayItem } from './editorUtils';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

type Props = {
  value?: DraftTermTarget[];
  onChange: (value: DraftTermTarget[]) => void;
};

/**
 * 术语目标列表编辑器。
 *
 * @param props 当前 targets 和变更回调。
 * @returns 类型与对象 key 输入行。
 * @throws 不抛出异常。
 */
const TermTargetEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'term-target-row');
  /** 更新指定目标。 */
  const update = (index: number, patch: Partial<DraftTermTarget>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  return (
    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
      {value.length ? (
        value.map((target, index) => (
          <Row gutter={8} key={rowKeys[index]} wrap={false}>
            <Col flex="150px">
              <Select
                options={[
                  { label: '维度', value: 'DIMENSION' },
                  { label: '指标', value: 'METRIC' },
                ]}
                value={target.type}
                onChange={(type) => update(index, { type })}
              />
            </Col>
            <Col flex="auto">
              <Input
                placeholder="草稿对象 key"
                value={target.objectKey}
                onChange={(event) => update(index, { objectKey: event.target.value })}
              />
            </Col>
            <Col flex="36px">
              <Tooltip title="删除映射目标">
                <Button
                  aria-label={`删除第 ${index + 1} 个术语目标`}
                  danger
                  icon={<DeleteOutlined />}
                  title="删除映射目标"
                  onClick={() => onChange(removeArrayItem(value, index))}
                />
              </Tooltip>
            </Col>
          </Row>
        ))
      ) : (
        <Empty description="暂无映射目标" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        size="small"
        type="dashed"
        onClick={() => onChange([...value, { type: 'DIMENSION', objectKey: '' }])}
      >
        新增映射目标
      </Button>
    </Space>
  );
};

export default TermTargetEditor;
