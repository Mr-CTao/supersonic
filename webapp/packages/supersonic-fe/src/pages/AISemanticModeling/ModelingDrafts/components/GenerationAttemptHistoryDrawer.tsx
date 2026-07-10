/**
 * AI 语义建模草稿生成尝试历史抽屉。
 *
 * 职责：按需分页读取并倒序展示初始生成和手工重新生成的审计摘要。
 * 安全边界：仅展示状态、模型 ID、请求 ID、脱敏校验问题和时间，不请求也不展示 Prompt、样例行、原始输出或修复输出。
 *
 * 并发说明：只在抽屉打开时请求；关闭或卸载后使用 active 标记忽略迟到响应。
 */
import {
  Alert,
  Descriptions,
  Drawer,
  Empty,
  List,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useState } from 'react';
import {
  getModelingDraftAttempts,
  type ModelingDraftAttempt,
} from '@/services/semanticModelingDraft';
import {
  getRequestErrorText,
  parseValidationIssues,
  sortModelingDraftAttempts,
  unwrapResponseData,
} from '../utils';
import styles from '../style.less';

const { Text } = Typography;
const ATTEMPT_PAGE_SIZE = 20;

const STATUS_TEXT: Record<string, string> = {
  QUEUED: '排队中',
  GENERATING: '生成中',
  SUCCEEDED: '成功',
  SUCCESS: '成功',
  FAILED: '失败',
  GENERATION_FAILED: '失败',
};

const STATUS_COLOR: Record<string, string> = {
  QUEUED: 'default',
  GENERATING: 'processing',
  SUCCEEDED: 'success',
  SUCCESS: 'success',
  FAILED: 'error',
  GENERATION_FAILED: 'error',
};

const TRIGGER_TEXT: Record<string, string> = {
  INITIAL: '初始生成',
  AI_GENERATED: '初始生成',
  MANUAL_REGENERATION: '手工重新生成',
  REGENERATION: '手工重新生成',
};

type Props = {
  draftId?: number;
  open: boolean;
  onClose: () => void;
};

/** 格式化尝试时间，无效值保留原文以便审计。 */
function formatDateTime(value?: string): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/**
 * 草稿生成尝试历史抽屉。
 *
 * @param props 草稿 ID、打开状态和关闭回调。
 * @returns 按 attemptNo 倒序的只读尝试摘要。
 * @throws 不向上抛出异常；加载失败使用 message 告知。
 */
const GenerationAttemptHistoryDrawer: React.FC<Props> = ({ draftId, open, onClose }) => {
  const [loading, setLoading] = useState(false);
  const [attempts, setAttempts] = useState<ModelingDraftAttempt[]>([]);
  const [total, setTotal] = useState(0);

  useEffect(() => {
    if (!open || !draftId) return undefined;
    let active = true;
    setLoading(true);
    setAttempts([]);
    setTotal(0);

    /** 按需读取小数量尝试，即使后端排序变更，页面仍强制最新在前。 */
    const load = async () => {
      try {
        const response = await getModelingDraftAttempts(draftId, {
          page: 1,
          pageSize: ATTEMPT_PAGE_SIZE,
        });
        const data = unwrapResponseData<any>(response) || {};
        const list = Array.isArray(data) ? data : data.list || [];
        if (active) {
          setAttempts(sortModelingDraftAttempts(list));
          setTotal(Number(Array.isArray(data) ? data.length : data.total ?? list.length));
        }
      } catch (error) {
        if (active) message.error(getRequestErrorText(error));
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => {
      active = false;
    };
  }, [draftId, open]);

  return (
    <Drawer
      destroyOnHidden
      open={open}
      size="large"
      title={`生成尝试历史${draftId ? ` · 草稿 #${draftId}` : ''}`}
      onClose={onClose}
    >
      <Alert
        showIcon
        type="info"
        title={`共 ${total} 次生成尝试`}
        description="历史仅用于审计和定位失败阶段；页面不展示 Prompt、样例行或模型原始输出。"
      />
      <Spin spinning={loading}>
        {attempts.length ? (
          <List<ModelingDraftAttempt>
            className={styles.attemptList}
            dataSource={attempts}
            renderItem={(attempt) => {
              const issues = parseValidationIssues(attempt.validationIssues);
              return (
                <List.Item key={attempt.id ?? attempt.attemptNo}>
                  <section className={styles.attemptCard}>
                    <Space className={styles.attemptTitle} wrap>
                      <Tag color="blue">第 {attempt.attemptNo} 次</Tag>
                      <Tag color={STATUS_COLOR[attempt.status] || 'default'}>
                        {STATUS_TEXT[attempt.status] || attempt.status || '-'}
                      </Tag>
                      <Text>
                        {TRIGGER_TEXT[attempt.triggerType || ''] ||
                          attempt.triggerType ||
                          '生成尝试'}
                      </Text>
                    </Space>
                    <Descriptions bordered column={2} size="small">
                      <Descriptions.Item label="生成模型 ID">
                        {attempt.chatModelId ?? '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="脱敏样例">
                        {attempt.includeSampleData ? '已开启' : '已关闭'}
                      </Descriptions.Item>
                      <Descriptions.Item label="Conversation ID">
                        {attempt.llmConversationId ?? '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="失败阶段">
                        {attempt.failureStage || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="生成请求 ID">
                        <Text copyable={Boolean(attempt.generateRequestId)}>
                          {attempt.generateRequestId || '-'}
                        </Text>
                      </Descriptions.Item>
                      <Descriptions.Item label="修复请求 ID">
                        <Text copyable={Boolean(attempt.repairRequestId)}>
                          {attempt.repairRequestId || '-'}
                        </Text>
                      </Descriptions.Item>
                      <Descriptions.Item label="开始时间">
                        {formatDateTime(
                          attempt.startedAt || attempt.generationStartedAt || attempt.createdAt,
                        )}
                      </Descriptions.Item>
                      <Descriptions.Item label="完成时间">
                        {formatDateTime(attempt.finishedAt || attempt.generationFinishedAt)}
                      </Descriptions.Item>
                      <Descriptions.Item label="操作人">
                        {attempt.createdBy || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="错误码">
                        {attempt.errorCode || '-'}
                      </Descriptions.Item>
                      {attempt.errorMessage ? (
                        <Descriptions.Item label="失败摘要" span={2}>
                          {attempt.errorMessage}
                        </Descriptions.Item>
                      ) : null}
                      {issues.length ? (
                        <Descriptions.Item label="校验问题" span={2}>
                          <Space orientation="vertical" size={2}>
                            {issues.map((issue) => (
                              <Text
                                key={`${issue.path || '$'}-${issue.code || ''}-${
                                  issue.message || ''
                                }`}
                              >
                                {[issue.path, issue.code, issue.message]
                                  .filter(Boolean)
                                  .join(' · ')}
                              </Text>
                            ))}
                          </Space>
                        </Descriptions.Item>
                      ) : null}
                    </Descriptions>
                  </section>
                </List.Item>
              );
            }}
          />
        ) : (
          <Empty description={loading ? '正在加载尝试历史' : '暂无生成尝试记录'} />
        )}
      </Spin>
    </Drawer>
  );
};

export default GenerationAttemptHistoryDrawer;
