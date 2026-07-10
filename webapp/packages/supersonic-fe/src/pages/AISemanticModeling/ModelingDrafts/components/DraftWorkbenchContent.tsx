/**
 * 建模草稿详情工作台内容组件。
 *
 * 职责：展示状态、来源、重试次数与失败操作，并组织结构树、对象表单、分类预览和 JSON 高级编辑；不负责请求、轮询或保存。
 *
 * 并发说明：组件完全受控，所有编辑立即回传父级工作台，不维护异步状态或定时器。
 */
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  Row,
  Space,
  Tabs,
  Tag,
  Tree,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useMemo } from 'react';
import type {
  ModelingDraftItem,
  ModelingDraftStatus,
  SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';
import { buildDraftTreeData, formatSelectedTables, MAX_MANUAL_REGENERATIONS } from '../utils';
import DraftObjectEditor from './DraftObjectEditor';
import DraftStructurePreview from './DraftStructurePreview';
import styles from '../style.less';

const { Paragraph, Text } = Typography;

const STATUS_TEXT: Record<ModelingDraftStatus, string> = {
  GENERATING: '生成中',
  DRAFT: '草稿',
  GENERATION_FAILED: '生成失败',
};

const STATUS_COLOR: Record<ModelingDraftStatus, string> = {
  GENERATING: 'processing',
  DRAFT: 'blue',
  GENERATION_FAILED: 'error',
};

type Props = {
  detail: ModelingDraftItem;
  draft?: SemanticModelingDraftJson;
  jsonText: string;
  jsonError?: string;
  loadParseError?: string;
  dirty: boolean;
  regenerationAllowed: boolean;
  regenerationReason: string;
  regenerating: boolean;
  onDraftChange: (value: SemanticModelingDraftJson) => void;
  onJsonTextChange: (value: string) => void;
  onRegenerate: () => void;
};

/** 格式化审计时间，解析失败时保留原值。 */
function formatDateTime(value?: string): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/** 渲染草稿状态标签。 */
function renderStatus(status: ModelingDraftStatus) {
  return <Tag color={STATUS_COLOR[status]}>{STATUS_TEXT[status]}</Tag>;
}

/**
 * 工作台详情内容。
 *
 * @param props 最新详情、解析后的草稿和受控编辑回调。
 * @returns 生成状态或三层草稿编辑区。
 * @throws 不抛出异常。
 */
const DraftWorkbenchContent: React.FC<Props> = ({
  detail,
  draft,
  jsonText,
  jsonError,
  loadParseError,
  dirty,
  regenerationAllowed,
  regenerationReason,
  regenerating,
  onDraftChange,
  onJsonTextChange,
  onRegenerate,
}) => {
  const treeData = useMemo(() => buildDraftTreeData(draft), [draft]);
  const businessGoal = draft?.businessGoal || detail.businessGoal || '-';

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      {detail.status === 'GENERATING' ? (
        <Alert
          showIcon
          type="info"
          title="AI 正在生成结构化草稿"
          description="页面每 2 秒自动刷新。生成完成后将展示结构树和分类内容。"
        />
      ) : null}
      {detail.status === 'GENERATION_FAILED' ? (
        <Alert
          action={
            <Tooltip title={regenerationReason}>
              <span className={styles.tooltipButtonWrapper}>
                <Button
                  aria-label="重新生成草稿"
                  disabled={!regenerationAllowed}
                  loading={regenerating}
                  title="重新生成草稿"
                  type="primary"
                  onClick={onRegenerate}
                >
                  重新生成
                </Button>
              </span>
            </Tooltip>
          }
          showIcon
          type="error"
          title="草稿生成失败"
          description={
            <Space orientation="vertical" size={2}>
              <Text>
                {detail.errorMessage ||
                  detail.errorCode ||
                  '请检查 LLM、数据源权限和选表后重新发起任务'}
              </Text>
              <Text type="secondary">
                已手工重新生成 {detail.manualRegenerationCount ?? 0}/{MAX_MANUAL_REGENERATIONS} 次
              </Text>
            </Space>
          }
        />
      ) : null}
      {loadParseError && detail.status === 'DRAFT' ? (
        <Alert showIcon type="error" title="结构化草稿无法解析" description={loadParseError} />
      ) : null}

      <Descriptions bordered column={3} size="small">
        <Descriptions.Item label="状态">{renderStatus(detail.status)}</Descriptions.Item>
        <Descriptions.Item label="来源">
          {detail.sourceType === 'SEMANTIC_GAP'
            ? `语义缺口 #${detail.sourceId || '-'}`
            : '数据源选表'}
        </Descriptions.Item>
        <Descriptions.Item label="版本">
          {detail.currentVersionNo ?? detail.currentVersion ?? 0}
        </Descriptions.Item>
        <Descriptions.Item label="当前生成尝试">
          {detail.currentAttemptNo ? `第 ${detail.currentAttemptNo} 次` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="已手工重试">
          {detail.manualRegenerationCount ?? 0}/{MAX_MANUAL_REGENERATIONS}
        </Descriptions.Item>
        <Descriptions.Item label="剩余手工重试">
          {detail.remainingManualRegenerations ?? '-'}
        </Descriptions.Item>
        <Descriptions.Item label="业务目标" span={3}>
          <Paragraph style={{ marginBottom: 0 }}>{businessGoal}</Paragraph>
        </Descriptions.Item>
        <Descriptions.Item label="主题域 ID">{detail.domainId || '-'}</Descriptions.Item>
        <Descriptions.Item label="数据源 ID">{detail.dataSourceId || '-'}</Descriptions.Item>
        <Descriptions.Item label="数据库">
          {[detail.catalogName, detail.databaseName].filter(Boolean).join('.') || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="选表" span={3}>
          {formatSelectedTables(detail.selectedTables)}
        </Descriptions.Item>
        <Descriptions.Item label="创建人">{detail.createdBy || '-'}</Descriptions.Item>
        <Descriptions.Item label="创建时间">{formatDateTime(detail.createdAt)}</Descriptions.Item>
        <Descriptions.Item label="更新时间">{formatDateTime(detail.updatedAt)}</Descriptions.Item>
      </Descriptions>

      {detail.status === 'DRAFT' ? (
        <Row gutter={16} wrap={false}>
          <Col flex="280px">
            <Card className={styles.structureTreeCard} size="small" title="语义资产结构树">
              {treeData.length ? (
                <Tree defaultExpandAll showLine treeData={treeData} />
              ) : (
                <Empty description="暂无结构" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              )}
            </Card>
          </Col>
          <Col className={styles.workbenchContent} flex="auto">
            <Tabs
              items={[
                {
                  key: 'object-editor',
                  label: dirty ? '对象编辑 *' : '对象编辑',
                  children: draft ? (
                    <DraftObjectEditor value={draft} onChange={onDraftChange} />
                  ) : (
                    <Alert
                      showIcon
                      type="error"
                      title="对象表单暂不可用"
                      description="请先在 JSON 高级编辑中修复格式错误。"
                    />
                  ),
                },
                {
                  key: 'preview',
                  label: '分类预览',
                  children: <DraftStructurePreview draft={draft} />,
                },
                {
                  key: 'json',
                  label: dirty ? 'JSON 高级编辑 *' : 'JSON 高级编辑',
                  children: (
                    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
                      <Alert
                        showIcon
                        type="info"
                        title="结构化 JSON 高级编辑"
                        description="保存前后端会重新校验表、字段、指标表达式和术语引用；这里不会接受任意 SQL。"
                      />
                      {jsonError ? <Alert showIcon type="error" title={jsonError} /> : null}
                      <Input.TextArea
                        aria-label="结构化草稿 JSON"
                        className={styles.jsonEditor}
                        spellCheck={false}
                        value={jsonText}
                        onChange={(event) => onJsonTextChange(event.target.value)}
                      />
                      <Text type="secondary">
                        页面不展示 LLM 原始输出，也不展示进入模型上下文的脱敏样例行。
                      </Text>
                    </Space>
                  ),
                },
              ]}
            />
          </Col>
        </Row>
      ) : null}
    </Space>
  );
};

export default DraftWorkbenchContent;
