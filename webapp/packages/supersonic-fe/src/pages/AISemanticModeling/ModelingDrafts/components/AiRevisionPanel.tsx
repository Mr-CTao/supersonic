/**
 * 阶段 4 AI 草稿修订面板。
 *
 * 职责：接收管理员自然语言指令、展示基线版本和修订 loading，并以只读方式展示后端返回的
 * 变更摘要、结构化差异和不确定项。请求、幂等键、版本冲突与详情刷新由工作台控制器负责。
 *
 * 并发说明：组件完全受控，不持有异步请求；父组件必须用 loading 锁防止同一指令重复提交。
 */
import { RobotOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, List, Space, Tag, Tooltip, Typography } from 'antd';
import React from 'react';
import {
  MODELING_DRAFT_REVISION_INSTRUCTION_MAX_LENGTH,
  type AiReviseModelingDraftResp,
} from '@/services/semanticModelingDraft';
import { formatModelingDiffValue } from '../utils';
import styles from '../style.less';

const { Paragraph, Text } = Typography;
const MAX_VISIBLE_CHANGES = 50;

type Props = {
  baseVersionNo?: number;
  disabledReason?: string;
  instruction: string;
  loading: boolean;
  result?: AiReviseModelingDraftResp;
  onInstructionChange: (value: string) => void;
  onSubmit: () => void;
};

/** 根据差异类型选择稳定的管理端标签颜色。 */
function getChangeColor(changeType: string): string {
  if (changeType === 'ADDED') return 'success';
  if (changeType === 'REMOVED') return 'error';
  if (changeType === 'MODIFIED' || changeType === 'CHANGED') return 'processing';
  return 'default';
}

/**
 * 渲染 AI 修订输入与最近一次修订结果。
 *
 * @param props 基线版本、受控指令、禁用原因、请求状态和修订结果。
 * @returns AI 修订面板。
 * @throws 不抛出异常；复杂差异值由有界格式化函数安全降级。
 */
const AiRevisionPanel: React.FC<Props> = ({
  baseVersionNo,
  disabledReason,
  instruction,
  loading,
  result,
  onInstructionChange,
  onSubmit,
}) => {
  const changes = result?.changes || [];
  const disabled = Boolean(disabledReason) || !instruction.trim() || !baseVersionNo;

  return (
    <Space className={styles.stage4Panel} orientation="vertical" size={16}>
      <Alert
        showIcon
        type="info"
        title={`基于版本 ${baseVersionNo || '-'} 进行 AI 修订`}
        description="AI 必须返回完整结构化草稿；每次成功修订都会生成新版本，并使旧版本验证报告失效。"
      />
      {disabledReason ? <Alert showIcon type="warning" title={disabledReason} /> : null}
      <Card size="small" title="管理员修订意见">
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Input.TextArea
            aria-label="AI 修订意见"
            autoSize={{ minRows: 4, maxRows: 10 }}
            disabled={loading}
            maxLength={MODELING_DRAFT_REVISION_INSTRUCTION_MAX_LENGTH}
            placeholder="例如：客户手机号是敏感字段，需要使用 PHONE_MASK；保持其他指标口径不变。"
            showCount
            value={instruction}
            onChange={(event) => onInstructionChange(event.target.value)}
          />
          <Tooltip title={disabled ? disabledReason || '请输入修订意见' : '提交 AI 修订'}>
            <span className={styles.tooltipButtonWrapper}>
              <Button
                disabled={disabled}
                icon={<RobotOutlined />}
                loading={loading}
                type="primary"
                onClick={onSubmit}
              >
                AI 修订并生成新版本
              </Button>
            </span>
          </Tooltip>
        </Space>
      </Card>

      {result ? (
        <Card
          size="small"
          title={
            <Space wrap>
              <Text strong>最近一次修订结果</Text>
              <Tag color="blue">
                版本 {result.baseVersionNo} → {result.newVersionNo}
              </Tag>
              {result.idempotentReplay ? <Tag>幂等重放</Tag> : null}
            </Space>
          }
        >
          <Paragraph>{result.changeSummary || '后端未返回变更摘要'}</Paragraph>
          {changes.length ? (
            <List
              bordered
              dataSource={changes.slice(0, MAX_VISIBLE_CHANGES)}
              renderItem={(item, index) => (
                <List.Item key={`${item.path}-${item.changeType}-${index}`}>
                  <section className={styles.diffItem}>
                    <Space wrap>
                      <Tag color={getChangeColor(item.changeType)}>{item.changeType}</Tag>
                      <Text code>{item.path || '$'}</Text>
                    </Space>
                    <div className={styles.diffGrid}>
                      <div>
                        <Text type="secondary">修订前</Text>
                        <pre className={styles.diffValue}>
                          {formatModelingDiffValue(item.beforeValue)}
                        </pre>
                      </div>
                      <div>
                        <Text type="secondary">修订后</Text>
                        <pre className={styles.diffValue}>
                          {formatModelingDiffValue(item.afterValue)}
                        </pre>
                      </div>
                    </div>
                  </section>
                </List.Item>
              )}
            />
          ) : (
            <Empty description="本次修订没有返回结构化差异" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
          {changes.length > MAX_VISIBLE_CHANGES ? (
            <Alert
              className={styles.stage4InlineAlert}
              showIcon
              type="warning"
              title={`差异较多，当前仅展示前 ${MAX_VISIBLE_CHANGES} 项`}
            />
          ) : null}
          {result.uncertaintyItems?.length ? (
            <Alert
              className={styles.stage4InlineAlert}
              showIcon
              type="warning"
              title={`修订后仍有 ${result.uncertaintyItems.length} 个不确定项`}
              description={result.uncertaintyItems
                .slice(0, 10)
                .map((item) => item.reason)
                .filter(Boolean)
                .join('；')}
            />
          ) : null}
        </Card>
      ) : null}
    </Space>
  );
};

export default AiRevisionPanel;
