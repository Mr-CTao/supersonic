/**
 * 阶段 4 必需检查与分类结果展示区。
 *
 * 职责：按固定十项契约展示检查完成状态，并提供有界、只读的分类结果折叠区。缺失、NOT_RUN、
 * FAILED、空状态和未知状态统一显示为“未完成/阻塞”，绝不在前端伪装为通过。
 */
import { Alert, Collapse, List, Space, Tag, Typography } from 'antd';
import React from 'react';
import type {
  ModelingValidationCheckResult,
  ModelingValidationReport,
} from '@/services/semanticModelingDraft';
import {
  getIncompleteRequiredValidationChecks,
  MODELING_REQUIRED_VALIDATION_CHECKS,
} from '../utils';
import styles from '../style.less';

const { Text } = Typography;
const MAX_REPORT_TEXT_LENGTH = 12000;
const CHECK_LABELS: Record<string, string> = {
  JSON_SCHEMA: 'JSON Schema',
  TABLE_FIELD_EXISTENCE: '表字段存在性',
  METRIC_EXPRESSION_FIELD: '指标表达式字段引用',
  SENSITIVE_FIELD: '敏感字段',
  NAME_CONFLICT: '命名与术语冲突',
  RETRIEVAL_POLLUTION: '高频词污染风险',
  SAMPLE_QUESTION: '样例问法命中',
  SEMANTIC_SQL_GENERATION: 'S2SQL/SQL 生成',
  SQL_READ_ONLY: 'SQL 只读',
  PERFORMANCE_RISK: '大查询风险',
};

type Props = {
  report: ModelingValidationReport;
};

/** 将后端已脱敏的分类结果格式化为有界只读文本。 */
function formatReportValue(value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  try {
    const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    return text.length > MAX_REPORT_TEXT_LENGTH
      ? `${text.slice(0, MAX_REPORT_TEXT_LENGTH)}\n…内容已截断`
      : text;
  } catch (_error) {
    return '[无法展示的复杂结果]';
  }
}

/** 未知、空和 NOT_RUN 状态统一按阻塞展示。 */
function requiredStatus(result?: ModelingValidationCheckResult): {
  color: string;
  text: string;
} {
  const status = String(result?.status || '').toUpperCase();
  if (status === 'PASSED') return { color: 'success', text: '通过' };
  if (status === 'WARNING') return { color: 'warning', text: '警告' };
  if (status === 'FAILED') return { color: 'error', text: '失败' };
  if (status === 'NOT_RUN') return { color: 'error', text: '未运行' };
  return { color: 'error', text: status ? `未知状态：${status}` : '结果缺失' };
}

/** 从分类结果中提取状态标签；未知结构保持中性且不显示为通过。 */
function sectionStatus(value: unknown): { color: string; text: string } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { color: 'default', text: '查看明细' };
  }
  const record = value as Record<string, unknown>;
  const status = String(record.status || '').toUpperCase();
  if (status === 'PASSED' || status === 'SUCCESS') return { color: 'success', text: '通过' };
  if (status === 'WARNING') return { color: 'warning', text: '警告' };
  if (status === 'FAILED' || status === 'BLOCKED' || status === 'NOT_RUN') {
    return { color: 'error', text: status === 'NOT_RUN' ? '未运行' : '失败' };
  }
  return { color: 'default', text: '查看明细' };
}

/**
 * 展示必需检查清单与兼容分类明细。
 *
 * @param props 当前验证报告。
 * @returns 检查列表和折叠明细。
 * @throws 不抛出异常；非标准结构按有界 JSON 文本降级。
 */
const ValidationCheckSections: React.FC<Props> = ({ report }) => {
  const incompleteChecks = getIncompleteRequiredValidationChecks(report);
  const requiredResults = Array.isArray(report.requiredCheckResults)
    ? report.requiredCheckResults
    : [];
  const sections = [
    { key: 'field', label: '字段存在性', value: report.fieldExistenceResult },
    { key: 'conflict', label: '命名与术语冲突', value: report.conflictResult },
    { key: 'sensitive', label: '敏感字段', value: report.sensitiveFieldResult },
    {
      key: 'questions',
      label: '样例问法命中（隔离语义链路）',
      value: report.sampleQuestionResults,
    },
    { key: 'sql', label: 'SQL 只读检查（不执行）', value: report.sqlSafetyResult },
    { key: 'performance', label: '性能风险', value: report.performanceRiskResult },
    { key: 'uncertainty', label: '不确定项', value: report.uncertaintyResult },
    { key: 'objects', label: '计划新增语义对象', value: report.plannedObjects },
  ];

  return (
    <>
      {incompleteChecks.length ? (
        <Alert
          className={styles.stage4InlineAlert}
          showIcon
          type="error"
          title="必需检查未完整执行，当前报告不能提交审批"
          description={incompleteChecks.map((item) => CHECK_LABELS[item] || item).join('、')}
        />
      ) : null}
      <List
        bordered
        size="small"
        dataSource={[...MODELING_REQUIRED_VALIDATION_CHECKS]}
        renderItem={(checkId) => {
          const matches = requiredResults.filter(
            (item) => String(item?.category || '').toUpperCase() === checkId,
          );
          const result = matches.length === 1 ? matches[0] : undefined;
          const status = requiredStatus(result);
          return (
            <List.Item key={checkId} extra={<Tag color={status.color}>{status.text}</Tag>}>
              <List.Item.Meta
                title={CHECK_LABELS[checkId] || checkId}
                description={result?.summary || '服务端未返回该必需检查的完整结果'}
              />
            </List.Item>
          );
        }}
      />
      <Collapse
        className={styles.validationSections}
        items={sections.map((section) => {
          const status = sectionStatus(section.value);
          return {
            key: section.key,
            label: (
              <Space>
                <Text>{section.label}</Text>
                <Tag color={status.color}>{status.text}</Tag>
              </Space>
            ),
            children: (
              <>
                {section.key === 'sql' ? (
                  <Alert
                    showIcon
                    type="info"
                    title="这里只展示后端隔离语义翻译和只读 AST 检查结果，不会执行 SQL"
                  />
                ) : null}
                <pre className={styles.validationResult}>{formatReportValue(section.value)}</pre>
              </>
            ),
          };
        })}
      />
    </>
  );
};

export default ValidationCheckSections;
