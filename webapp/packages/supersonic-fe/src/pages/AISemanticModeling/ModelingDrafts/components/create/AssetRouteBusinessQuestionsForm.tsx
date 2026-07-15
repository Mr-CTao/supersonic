/**
 * 语义资产路由业务问题表单。
 *
 * 职责：根据服务端结构化题型渲染单选、布尔或受限文本输入，并在问题旁展示必填错误；不把业务问题混入草稿不确定项。
 */
import { Form, Input, Radio, Space, Typography } from 'antd';
import React from 'react';
import type { SemanticAssetBusinessQuestion } from '@/services/semanticAssetRouting';

const { Text } = Typography;

type Props = {
  answers: Record<string, unknown>;
  disabled?: boolean;
  errors: Record<string, string>;
  questions?: SemanticAssetBusinessQuestion[];
  onAnswerChange: (key: string, value: unknown) => void;
};

/** 根据服务端题型渲染受控输入控件。 */
function renderQuestionInput(
  question: SemanticAssetBusinessQuestion,
  value: unknown,
  disabled: boolean,
  onChange: (value: unknown) => void,
) {
  if (question.answerType === 'BOOLEAN') {
    return (
      <Radio.Group
        aria-label={question.question}
        disabled={disabled}
        options={[
          { label: '是', value: true },
          { label: '否', value: false },
        ]}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    );
  }
  if (question.answerType === 'SINGLE_SELECT' || question.options?.length) {
    return (
      <Radio.Group
        aria-label={question.question}
        disabled={disabled}
        options={(question.options ?? []).map((option) => ({
          label: option.label,
          value: option.key,
        }))}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    );
  }
  return (
    <Input.TextArea
      aria-label={question.question}
      disabled={disabled}
      maxLength={question.maxLength || 500}
      rows={3}
      showCount
      value={typeof value === 'string' ? value : ''}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

/**
 * 渲染路由业务问题表单。
 *
 * @param props 服务端问题、当前答案、字段错误和更新回调。
 * @returns 结构化、字段级校验的业务问题区；无问题时返回 null。
 * @throws 不抛出异常。
 */
const AssetRouteBusinessQuestionsForm: React.FC<Props> = ({
  answers,
  disabled = false,
  errors,
  questions = [],
  onAnswerChange,
}) => {
  if (!questions.length) return null;
  return (
    <Space orientation="vertical" size={4} style={{ width: '100%' }}>
      <Text strong>需要确认的业务口径</Text>
      <Text type="secondary">这些答案会随路由快照保存；影响推荐时系统会重新评估。</Text>
      {questions.map((question) => (
        <Form.Item
          key={question.key}
          help={errors[question.key]}
          label={question.question}
          required={question.required}
          validateStatus={errors[question.key] ? 'error' : undefined}
        >
          {renderQuestionInput(question, answers[question.key], disabled, (value) =>
            onAnswerChange(question.key, value),
          )}
        </Form.Item>
      ))}
    </Space>
  );
};

export default AssetRouteBusinessQuestionsForm;
