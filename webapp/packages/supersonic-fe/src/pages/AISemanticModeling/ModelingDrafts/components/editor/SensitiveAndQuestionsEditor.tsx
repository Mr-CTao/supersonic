/**
 * 建模草稿敏感字段与示例问法编辑器。
 *
 * 职责：受控编辑模型敏感字段、敏感等级、脱敏策略、识别原因和示例问法。
 *
 * 并发说明：组件无异步请求，所有修改立即生成新数组回传父模型。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  Row,
  Select,
  Space,
  Tooltip,
  Typography,
} from 'antd';
import React from 'react';
import type { DraftSensitiveField } from '@/services/semanticModelingDraft';
import { removeArrayItem, replaceArrayItem } from './editorUtils';
import StringListEditor from './StringListEditor';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

const { Title } = Typography;

type Props = {
  sensitiveFields?: DraftSensitiveField[];
  sampleQuestions?: string[];
  onSensitiveFieldsChange: (value: DraftSensitiveField[]) => void;
  onSampleQuestionsChange: (value: string[]) => void;
};

/** 创建一条默认高敏感、策略待确认的字段建议。 */
function createSensitiveField(): DraftSensitiveField {
  return { field: '', level: 'HIGH', maskingStrategy: '', reason: '' };
}

/**
 * 敏感字段与示例问法编辑器。
 *
 * @param props 当前敏感字段、示例问法和两个受控回调。
 * @returns 两个分类编辑区。
 * @throws 不抛出异常。
 */
const SensitiveAndQuestionsEditor: React.FC<Props> = ({
  sensitiveFields = [],
  sampleQuestions = [],
  onSensitiveFieldsChange,
  onSampleQuestionsChange,
}) => {
  const rowKeys = useStableEditorRowKeys(sensitiveFields.length, 'sensitive-row');
  /** 更新指定敏感字段。 */
  const updateSensitiveField = (index: number, patch: Partial<DraftSensitiveField>) => {
    onSensitiveFieldsChange(
      replaceArrayItem(sensitiveFields, index, { ...sensitiveFields[index], ...patch }),
    );
  };

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <section>
        <Title level={5}>敏感字段</Title>
        <Space orientation="vertical" size={10} style={{ width: '100%' }}>
          {sensitiveFields.length ? (
            sensitiveFields.map((field, index) => (
              <Card
                key={rowKeys[index]}
                size="small"
                title={`敏感字段 ${index + 1}`}
                extra={
                  <Tooltip title="删除敏感字段">
                    <Button
                      aria-label={`删除第 ${index + 1} 个敏感字段`}
                      danger
                      icon={<DeleteOutlined />}
                      size="small"
                      title="删除敏感字段"
                      onClick={() =>
                        onSensitiveFieldsChange(removeArrayItem(sensitiveFields, index))
                      }
                    />
                  </Tooltip>
                }
              >
                <Form layout="vertical">
                  <Row gutter={12}>
                    <Col span={8}>
                      <Form.Item label="字段" required>
                        <Input
                          value={field.field}
                          onChange={(event) =>
                            updateSensitiveField(index, { field: event.target.value })
                          }
                        />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item label="敏感等级" required>
                        <Select
                          options={['LOW', 'MEDIUM', 'HIGH'].map((level) => ({
                            label: level,
                            value: level,
                          }))}
                          value={field.level}
                          onChange={(level) => updateSensitiveField(index, { level })}
                        />
                      </Form.Item>
                    </Col>
                    <Col span={8}>
                      <Form.Item label="脱敏策略">
                        <Input
                          placeholder="例如 PHONE_MASK"
                          value={field.maskingStrategy}
                          onChange={(event) =>
                            updateSensitiveField(index, { maskingStrategy: event.target.value })
                          }
                        />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Form.Item label="识别原因">
                    <Input.TextArea
                      autoSize={{ minRows: 1, maxRows: 3 }}
                      value={field.reason}
                      onChange={(event) =>
                        updateSensitiveField(index, { reason: event.target.value })
                      }
                    />
                  </Form.Item>
                </Form>
              </Card>
            ))
          ) : (
            <Empty description="暂无敏感字段" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
          <Button
            icon={<PlusOutlined />}
            type="dashed"
            onClick={() => onSensitiveFieldsChange([...sensitiveFields, createSensitiveField()])}
          >
            新增敏感字段
          </Button>
        </Space>
      </section>

      <section>
        <Title level={5}>示例问法</Title>
        <StringListEditor
          addText="新增示例问法"
          placeholder="输入管理员用于验证语义覆盖的自然语言问法"
          value={sampleQuestions}
          onChange={onSampleQuestionsChange}
        />
      </section>
    </Space>
  );
};

export default SensitiveAndQuestionsEditor;
