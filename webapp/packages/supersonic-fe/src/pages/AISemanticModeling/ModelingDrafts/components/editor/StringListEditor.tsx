/**
 * 建模草稿字符串列表编辑器。
 *
 * 职责：以受控列表编辑示例问法等可重复文本，并提供新增、删除和逐项修改操作。
 *
 * 并发说明：组件不保存内部副本，每次修改立即通过 onChange 返回新数组，不存在跨组件共享状态竞争。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Empty, Input, Space, Tooltip } from 'antd';
import React from 'react';
import { removeArrayItem, replaceArrayItem } from './editorUtils';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

type Props = {
  value?: string[];
  addText: string;
  placeholder?: string;
  onChange: (value: string[]) => void;
};

/**
 * 受控字符串列表编辑器。
 *
 * @param props 当前数组、文案和变更回调。
 * @returns 可增删的输入框列表。
 * @throws 不抛出异常。
 */
const StringListEditor: React.FC<Props> = ({ value = [], addText, placeholder, onChange }) => {
  const rowKeys = useStableEditorRowKeys(value.length, 'text-row');

  return (
    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
      {value.length ? (
        value.map((item, index) => (
          <Space key={rowKeys[index]} align="start" style={{ width: '100%' }}>
            <Input.TextArea
              autoSize={{ minRows: 1, maxRows: 3 }}
              placeholder={placeholder}
              value={item}
              onChange={(event) => onChange(replaceArrayItem(value, index, event.target.value))}
            />
            <Tooltip title="删除">
              <Button
                aria-label={`删除第 ${index + 1} 项`}
                danger
                icon={<DeleteOutlined />}
                title="删除"
                onClick={() => onChange(removeArrayItem(value, index))}
              />
            </Tooltip>
          </Space>
        ))
      ) : (
        <Empty description="暂无内容" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button icon={<PlusOutlined />} type="dashed" onClick={() => onChange([...value, ''])}>
        {addText}
      </Button>
    </Space>
  );
};

export default StringListEditor;
