/**
 * AI 语义建模草稿受控对象编辑器。
 *
 * 职责：
 * - 表单编辑业务目标、目标主题域和模型基础信息；
 * - 组合维度、指标/过滤器、敏感字段、示例问法、域级术语/targets 和不确定项编辑器；
 * - 每次变更返回完整 SemanticModelingDraftJson，由工作台统一序列化到 JSON 高级编辑器。
 *
 * 并发说明：组件没有内部草稿副本，value 是唯一事实源；所有操作通过 onChange 同步父级并触发 dirty。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Form,
  Input,
  InputNumber,
  Row,
  Space,
  Tabs,
  Tooltip,
} from 'antd';
import React from 'react';
import type {
  SemanticModelDraftModel,
  SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editor/editorUtils';
import ModelObjectEditor from './editor/ModelObjectEditor';
import TermListEditor from './editor/TermListEditor';
import UncertaintyListEditor from './editor/UncertaintyListEditor';
import { useStableEditorRowKeys } from './editor/useStableEditorRowKeys';

type Props = {
  value: SemanticModelingDraftJson;
  onChange: (value: SemanticModelingDraftJson) => void;
};

/** 创建一个基础表和业务字段待管理员补充的新模型。 */
function createModel(): SemanticModelDraftModel {
  return {
    key: createDraftObjectKey('model'),
    name: '新模型',
    bizName: '',
    description: '',
    baseTable: '',
    primaryTimeField: '',
    dimensions: [],
    metrics: [],
    sensitiveFields: [],
    sampleQuestions: [],
  };
}

/**
 * 受控草稿对象编辑器。
 *
 * @param props 当前结构化草稿和完整对象变更回调。
 * @returns 分层对象表单。
 * @throws 不抛出异常；后端保存时继续执行 Schema 与业务校验。
 */
const DraftObjectEditor: React.FC<Props> = ({ value, onChange }) => {
  const modelRowKeys = useStableEditorRowKeys(value.models?.length || 0, 'model-row');
  /** 合并草稿顶层字段。 */
  const update = (patch: Partial<SemanticModelingDraftJson>) => {
    onChange({ ...value, ...patch });
  };

  /** 更新目标主题域的单个字段。 */
  const updateTargetDomain = (
    patch: Partial<NonNullable<SemanticModelingDraftJson['targetDomain']>>,
  ) => {
    update({ targetDomain: { ...(value.targetDomain || {}), ...patch } });
  };

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        showIcon
        type="info"
        title="对象表单与 JSON 高级编辑双向同步"
        description="表单修改会立即更新 JSON 并标记未保存；保存时后端仍会校验选表、字段、表达式、对象 key 和术语引用。"
      />

      <Card size="small" title="业务与主题域">
        <Form layout="vertical">
          <Form.Item label="业务目标" required>
            <Input.TextArea
              autoSize={{ minRows: 2, maxRows: 6 }}
              value={value.businessGoal}
              onChange={(event) => update({ businessGoal: event.target.value })}
            />
          </Form.Item>
          <Row gutter={12}>
            <Col span={6}>
              <Form.Item label="主题域 ID">
                <InputNumber
                  min={1}
                  style={{ width: '100%' }}
                  value={value.targetDomain?.domainId}
                  onChange={(domainId) => updateTargetDomain({ domainId: domainId || undefined })}
                />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item label="主题域名称">
                <Input
                  value={value.targetDomain?.name}
                  onChange={(event) => updateTargetDomain({ name: event.target.value })}
                />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item label="主题域英文标识">
                <Input
                  value={value.targetDomain?.bizName}
                  onChange={(event) => updateTargetDomain({ bizName: event.target.value })}
                />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item label="Schema 版本">
                <Input
                  value={value.schemaVersion}
                  onChange={(event) => update({ schemaVersion: event.target.value })}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="主题域说明">
            <Input.TextArea
              autoSize={{ minRows: 1, maxRows: 4 }}
              value={value.targetDomain?.description}
              onChange={(event) => updateTargetDomain({ description: event.target.value })}
            />
          </Form.Item>
        </Form>
      </Card>

      <Card
        size="small"
        title={`模型（${value.models?.length || 0}）`}
        extra={
          <Button
            icon={<PlusOutlined />}
            size="small"
            type="primary"
            onClick={() => update({ models: [...(value.models || []), createModel()] })}
          >
            新增模型
          </Button>
        }
      >
        <Collapse
          items={(value.models || []).map((model, index) => ({
            key: modelRowKeys[index],
            label: `${index + 1}. ${model.name || '未命名模型'} · ${
              model.baseTable || '未指定基础表'
            }`,
            extra: (
              <Tooltip title="删除模型">
                <Button
                  aria-label={`删除模型 ${model.name || index + 1}`}
                  danger
                  icon={<DeleteOutlined />}
                  size="small"
                  title="删除模型"
                  onClick={(event) => {
                    event.stopPropagation();
                    update({ models: removeArrayItem(value.models || [], index) });
                  }}
                />
              </Tooltip>
            ),
            children: (
              <ModelObjectEditor
                value={model}
                onChange={(nextModel) =>
                  update({ models: replaceArrayItem(value.models || [], index, nextModel) })
                }
              />
            ),
          }))}
        />
      </Card>

      <Card size="small" title="域级治理对象">
        <Tabs
          items={[
            {
              key: 'terms',
              label: `术语（${value.terms?.length || 0}）`,
              children: (
                <TermListEditor value={value.terms} onChange={(terms) => update({ terms })} />
              ),
            },
            {
              key: 'uncertainties',
              label: `不确定项（${value.uncertainties?.length || 0}）`,
              children: (
                <UncertaintyListEditor
                  value={value.uncertainties}
                  onChange={(uncertainties) => update({ uncertainties })}
                />
              ),
            },
          ]}
        />
      </Card>
    </Space>
  );
};

export default DraftObjectEditor;
