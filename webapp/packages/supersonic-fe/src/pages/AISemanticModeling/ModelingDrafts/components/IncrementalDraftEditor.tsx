/**
 * 路由感知的增量建模草稿编辑器。
 *
 * 职责：
 * - 只读展示已确认的目标资产、基线版本和业务口径；
 * - 仅编辑 additions、受控 modifications、回归问法与不确定项；
 * - 不把正式目标模型复制成可编辑对象，也不提供删除正式对象的入口。
 *
 * 并发说明：组件是纯受控组件，不持有请求、轮询或共享缓存；保存互斥和乐观锁由工作台统一处理。
 */
import { Alert, Card, Descriptions, List, Space, Tabs, Tag, Typography } from 'antd';
import React from 'react';
import type { SemanticModelingDraftJson } from '@/services/semanticModelingDraft';
import DimensionListEditor from './editor/DimensionListEditor';
import MetricListEditor from './editor/MetricListEditor';
import SensitiveAndQuestionsEditor from './editor/SensitiveAndQuestionsEditor';
import StringListEditor from './editor/StringListEditor';
import TermListEditor from './editor/TermListEditor';
import UncertaintyListEditor from './editor/UncertaintyListEditor';

const { Paragraph, Text } = Typography;

type Props = {
  value: SemanticModelingDraftJson;
  onChange: (value: SemanticModelingDraftJson) => void;
};

/** 将 before/after 值转换为 React 转义的短文本，不使用不可信 HTML。 */
function formatValue(value: unknown): string {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch (_error) {
    return '[无法展示]';
  }
}

/**
 * 渲染只包含目标资产增量的编辑界面。
 *
 * @param props 已解析的 2.0 草稿和受控变更回调。
 * @returns 目标快照、增量对象和治理信息。
 * @throws 不抛出异常；结构异常由后端 Schema 校验和工作台 JSON 错误共同处理。
 */
const IncrementalDraftEditor: React.FC<Props> = ({ value, onChange }) => {
  const additions = value.additions || {};

  /** 合并顶层字段且保留不可编辑的路由快照。 */
  const update = (patch: Partial<SemanticModelingDraftJson>) => {
    onChange({ ...value, ...patch });
  };

  /** 只更新 additions 内的增量集合，避免误写 models。 */
  const updateAdditions = (patch: Partial<NonNullable<SemanticModelingDraftJson['additions']>>) => {
    update({ additions: { ...additions, ...patch } });
  };

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        showIcon
        type="info"
        title="增强已有资产"
        description="本草稿只保存新增和受控修改建议；目标资产保持只读，正式变更仍须走后续审批与发布 API。"
      />

      <Card size="small" title="已确认目标与业务口径">
        <Descriptions bordered column={3} size="small">
          <Descriptions.Item label="目标资产">
            {value.targetAsset?.name || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="基线版本">
            {value.targetAsset?.baseVersion ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label="基础表">
            {value.targetAsset?.baseTable || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="决策来源">
            {value.routeSummary?.decisionSource === 'RULE_ONLY' ? '规则分析' : '规则 + AI 分析'}
          </Descriptions.Item>
          <Descriptions.Item label="路由分析" span={2}>
            #{value.routeSummary?.routeAnalysisId || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="推荐说明" span={3}>
            {value.routeSummary?.explanation || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="已确认业务口径" span={3}>
            {Object.entries(value.routeSummary?.businessAnswers || {}).length ? (
              <Space wrap>
                {Object.entries(value.routeSummary?.businessAnswers || {}).map(([key, answer]) => (
                  <Tag key={key}>{`${key}: ${formatValue(answer)}`}</Tag>
                ))}
              </Space>
            ) : (
              '-'
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card size="small" title="业务目标">
        <Paragraph style={{ marginBottom: 4 }}>{value.businessGoal || '-'}</Paragraph>
        <Text type="secondary">来自已确认路由，如需修改请返回语义缺口重新分析。</Text>
      </Card>

      <Card size="small" title="本次增量对象">
        <Tabs
          items={[
            {
              key: 'dimensions',
              label: `新增维度（${additions.dimensions?.length || 0}）`,
              children: (
                <DimensionListEditor
                  value={additions.dimensions}
                  onChange={(dimensions) => updateAdditions({ dimensions })}
                />
              ),
            },
            {
              key: 'metrics',
              label: `新增指标（${additions.metrics?.length || 0}）`,
              children: (
                <MetricListEditor
                  value={additions.metrics}
                  onChange={(metrics) => updateAdditions({ metrics })}
                />
              ),
            },
            {
              key: 'terms',
              label: `新增术语（${additions.terms?.length || 0}）`,
              children: (
                <TermListEditor
                  value={additions.terms}
                  onChange={(terms) => updateAdditions({ terms })}
                />
              ),
            },
            {
              key: 'governance',
              label: '敏感字段与样例',
              children: (
                <SensitiveAndQuestionsEditor
                  sensitiveFields={additions.sensitiveFields}
                  sampleQuestions={additions.sampleQuestions}
                  onSensitiveFieldsChange={(sensitiveFields) =>
                    updateAdditions({ sensitiveFields })
                  }
                  onSampleQuestionsChange={(sampleQuestions) =>
                    updateAdditions({ sampleQuestions })
                  }
                />
              ),
            },
            {
              key: 'aliases',
              label: `新增别名（${additions.aliases?.length || 0}）`,
              children: (
                <StringListEditor
                  addText="新增别名"
                  placeholder="输入目标资产的新别名"
                  value={additions.aliases}
                  onChange={(aliases) => updateAdditions({ aliases })}
                />
              ),
            },
          ]}
        />
      </Card>

      <Card size="small" title={`受控修改（${value.modifications?.length || 0}）`}>
        <List
          dataSource={value.modifications || []}
          locale={{ emptyText: '无既有对象修改' }}
          rowKey={(item) => `${item.objectType}:${item.objectKey}:${item.field}`}
          renderItem={(item) => (
            <List.Item>
              <Space orientation="vertical" size={2}>
                <Text strong>{`${item.objectType} · ${item.objectKey} · ${item.field}`}</Text>
                <Text>{`修改前：${formatValue(item.beforeValue)}`}</Text>
                <Text>{`修改后：${formatValue(item.afterValue)}`}</Text>
                {item.reason ? <Text type="secondary">原因：{item.reason}</Text> : null}
              </Space>
            </List.Item>
          )}
        />
      </Card>

      <Card size="small" title="回归问法与不确定项">
        <Tabs
          items={[
            {
              key: 'regression-questions',
              label: `回归问法（${value.regressionQuestions?.length || 0}）`,
              children: (
                <StringListEditor
                  addText="新增回归问法"
                  placeholder="输入增强后必须验证的业务问法"
                  value={value.regressionQuestions}
                  onChange={(regressionQuestions) => update({ regressionQuestions })}
                />
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

      <Paragraph type="secondary" style={{ marginBottom: 0 }}>
        目标模型现有对象仅作只读参考；本页面不会执行 SQL、删除正式对象或直接发布变更。
      </Paragraph>
    </Space>
  );
};

export default IncrementalDraftEditor;
