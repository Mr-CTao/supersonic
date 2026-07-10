/**
 * 建模草稿指标结构化过滤器编辑器。
 *
 * 职责：编辑指标过滤字段、受控操作符和值列表，不接受或拼接任意 SQL。
 *
 * 并发说明：组件无异步状态，每次修改都以新数组同步父级指标对象。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Col, Empty, Input, Row, Select, Space, Tooltip } from 'antd';
import React from 'react';
import type { DraftMetricFilter } from '@/services/semanticModelingDraft';
import { removeArrayItem, replaceArrayItem } from './editorUtils';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

const FILTER_OPERATORS = [
  'EQ',
  'NE',
  'IN',
  'NOT_IN',
  'GT',
  'GTE',
  'LT',
  'LTE',
  'IS_NULL',
  'IS_NOT_NULL',
];

type Props = {
  value?: DraftMetricFilter[];
  onChange: (value: DraftMetricFilter[]) => void;
};

/** 创建一条待补全的结构化过滤器。 */
function createFilter(): DraftMetricFilter {
  return { field: '', operator: 'EQ', values: [] };
}

/**
 * 指标结构化过滤器编辑器。
 *
 * @param props 当前过滤器数组和变更回调。
 * @returns 过滤条件行。
 * @throws 不抛出异常。
 */
const MetricFilterEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'metric-filter-row');
  /** 更新一条过滤器。 */
  const update = (index: number, patch: Partial<DraftMetricFilter>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  return (
    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
      {value.length ? (
        value.map((filter, index) => (
          <Row gutter={8} key={rowKeys[index]} wrap={false}>
            <Col flex="180px">
              <Input
                placeholder="字段"
                value={filter.field}
                onChange={(event) => update(index, { field: event.target.value })}
              />
            </Col>
            <Col flex="140px">
              <Select
                options={FILTER_OPERATORS.map((operator) => ({ label: operator, value: operator }))}
                value={filter.operator}
                onChange={(operator) => update(index, { operator })}
              />
            </Col>
            <Col flex="auto">
              <Select
                mode="tags"
                open={false}
                placeholder="值，输入后回车"
                tokenSeparators={[',', '，']}
                value={(filter.values || (filter.value === undefined ? [] : [filter.value])).map(
                  String,
                )}
                onChange={(values) => update(index, { values, value: undefined })}
              />
            </Col>
            <Col flex="36px">
              <Tooltip title="删除过滤条件">
                <Button
                  aria-label={`删除第 ${index + 1} 条过滤条件`}
                  danger
                  icon={<DeleteOutlined />}
                  title="删除过滤条件"
                  onClick={() => onChange(removeArrayItem(value, index))}
                />
              </Tooltip>
            </Col>
          </Row>
        ))
      ) : (
        <Empty description="无结构化过滤条件" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        size="small"
        type="dashed"
        onClick={() => onChange([...value, createFilter()])}
      >
        新增过滤条件
      </Button>
    </Space>
  );
};

export default MetricFilterEditor;
