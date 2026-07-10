/**
 * 建模草稿单模型对象编辑器。
 *
 * 职责：编辑模型基础信息，并组合维度、指标、敏感字段和示例问法的受控子编辑器。
 *
 * 并发说明：所有子编辑器共享父级 model 值，不保留独立草稿副本；每次修改都返回完整的新模型对象。
 */
import { Col, Form, Input, Row, Tabs } from 'antd';
import React from 'react';
import type { SemanticModelDraftModel } from '@/services/semanticModelingDraft';
import DimensionListEditor from './DimensionListEditor';
import MetricListEditor from './MetricListEditor';
import SensitiveAndQuestionsEditor from './SensitiveAndQuestionsEditor';

type Props = {
  value: SemanticModelDraftModel;
  onChange: (value: SemanticModelDraftModel) => void;
};

/**
 * 单模型受控对象编辑器。
 *
 * @param props 当前模型和变更回调。
 * @returns 模型基础表单及分类子编辑器。
 * @throws 不抛出异常。
 */
const ModelObjectEditor: React.FC<Props> = ({ value, onChange }) => {
  /** 合并模型基础字段或子数组修改。 */
  const update = (patch: Partial<SemanticModelDraftModel>) => {
    onChange({ ...value, ...patch });
  };

  return (
    <>
      <Form layout="vertical">
        <Row gutter={12}>
          <Col span={8}>
            <Form.Item label="模型 Key" required>
              <Input value={value.key} onChange={(event) => update({ key: event.target.value })} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item label="模型名称" required>
              <Input
                value={value.name}
                onChange={(event) => update({ name: event.target.value })}
              />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item label="英文标识" required>
              <Input
                value={value.bizName}
                onChange={(event) => update({ bizName: event.target.value })}
              />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item label="基础表" required extra="阶段 3 第一版每个模型只能绑定一张基础表。">
              <Input
                value={value.baseTable}
                onChange={(event) => update({ baseTable: event.target.value })}
              />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item label="主时间字段">
              <Input
                value={value.primaryTimeField}
                onChange={(event) => update({ primaryTimeField: event.target.value })}
              />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item label="模型说明">
          <Input.TextArea
            autoSize={{ minRows: 2, maxRows: 5 }}
            value={value.description}
            onChange={(event) => update({ description: event.target.value })}
          />
        </Form.Item>
      </Form>

      <Tabs
        items={[
          {
            key: 'dimensions',
            label: `维度（${value.dimensions?.length || 0}）`,
            children: (
              <DimensionListEditor
                value={value.dimensions}
                onChange={(dimensions) => update({ dimensions })}
              />
            ),
          },
          {
            key: 'metrics',
            label: `指标（${value.metrics?.length || 0}）`,
            children: (
              <MetricListEditor value={value.metrics} onChange={(metrics) => update({ metrics })} />
            ),
          },
          {
            key: 'sensitive-and-questions',
            label: '敏感字段与问法',
            children: (
              <SensitiveAndQuestionsEditor
                sampleQuestions={value.sampleQuestions}
                sensitiveFields={value.sensitiveFields}
                onSampleQuestionsChange={(sampleQuestions) => update({ sampleQuestions })}
                onSensitiveFieldsChange={(sensitiveFields) => update({ sensitiveFields })}
              />
            ),
          },
        ]}
      />
    </>
  );
};

export default ModelObjectEditor;
