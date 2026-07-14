/**
 * 阶段 5 发布详情抽屉。
 *
 * 职责：展示发布对象、dict/embedding 独立状态和逐步骤结果，并提供刷新重试与回滚入口。
 * 请求切换时忽略过期响应；提交 loading 由父页面统一协调，防止重复操作。
 */
import { ReloadOutlined, RollbackOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Space, Spin, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useEffect, useState } from 'react';
import {
  getSemanticReleaseDetail,
  type SemanticReleaseItem,
  type SemanticReleaseStep,
} from '@/services/semanticRelease';
import { getRequestErrorText, unwrapResponseData } from '../../ModelingDrafts/utils';

type ReleaseDetailDrawerProps = {
  releaseId?: number;
  open: boolean;
  refreshToken?: number;
  actionLoading?: boolean;
  onClose: () => void;
  onRetry: (release: SemanticReleaseItem) => void;
  onRollback: (release: SemanticReleaseItem) => void;
};

/** 按步骤状态选择稳定颜色。 */
function statusColor(status?: string): string {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED' || status === 'ROLLBACK_FAILED') return 'error';
  if (status?.includes('PROGRESS') || status === 'PENDING') return 'processing';
  if (status === 'ROLLED_BACK') return 'default';
  return 'warning';
}

const stepColumns: ColumnsType<SemanticReleaseStep> = [
  { title: '步骤', dataIndex: 'stepType', width: 150 },
  { title: '对象类型', dataIndex: 'targetType', width: 110 },
  { title: '对象', dataIndex: 'targetName', width: 160, ellipsis: true },
  { title: '正式 ID', dataIndex: 'targetId', width: 100 },
  {
    title: '结果',
    dataIndex: 'status',
    width: 110,
    render: (value) => <Tag color={statusColor(value)}>{value}</Tag>,
  },
  { title: '次数', dataIndex: 'attemptCount', width: 70 },
  { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
];

/**
 * 展示发布详情。
 *
 * @param props 发布 ID、刷新令牌、操作回调和 loading。
 * @returns 发布步骤抽屉。
 * @throws 不向上抛出异常；请求错误在抽屉中显示。
 */
const ReleaseDetailDrawer: React.FC<ReleaseDetailDrawerProps> = ({
  releaseId,
  open,
  refreshToken,
  actionLoading,
  onClose,
  onRetry,
  onRollback,
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [release, setRelease] = useState<SemanticReleaseItem>();

  useEffect(() => {
    let cancelled = false;
    if (!open || !releaseId) return () => undefined;
    setLoading(true);
    setError(undefined);
    getSemanticReleaseDetail(releaseId)
      .then((response) => {
        if (!cancelled) setRelease(unwrapResponseData<SemanticReleaseItem>(response));
      })
      .catch((requestError) => {
        if (!cancelled) setError(getRequestErrorText(requestError));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, refreshToken, releaseId]);

  const failedTerminal =
    release?.releaseStatus === 'FAILED' || release?.releaseStatus === 'ROLLBACK_FAILED';
  const canRetry =
    failedTerminal &&
    (release?.dictReloadStatus === 'FAILED' || release?.embeddingReloadStatus === 'FAILED');
  const canRollback = release?.releaseStatus === 'SUCCEEDED';

  return (
    <Drawer
      title={release ? `发布详情 · ${release.releaseNo}` : '发布详情'}
      width={980}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        release ? (
          <Space wrap={false}>
            {canRetry ? (
              <Button
                icon={<ReloadOutlined />}
                loading={actionLoading}
                onClick={() => onRetry(release)}
              >
                重试知识刷新
              </Button>
            ) : null}
            {canRollback ? (
              <Button
                danger
                icon={<RollbackOutlined />}
                loading={actionLoading}
                onClick={() => onRollback(release)}
              >
                回滚
              </Button>
            ) : null}
          </Space>
        ) : null
      }
    >
      <Spin spinning={loading}>
        {error ? <Alert type="error" showIcon message={error} /> : null}
        {release ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            {release.errorMessage ? (
              <Alert
                type="warning"
                showIcon
                message="发布存在失败步骤"
                description={release.errorMessage}
              />
            ) : null}
            <Descriptions bordered size="small" column={3}>
              <Descriptions.Item label="发布状态">
                <Tag color={statusColor(release.releaseStatus)}>{release.releaseStatus}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="dict">
                <Tag color={statusColor(release.dictReloadStatus)}>{release.dictReloadStatus}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="embedding">
                <Tag color={statusColor(release.embeddingReloadStatus)}>
                  {release.embeddingReloadStatus}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="关联草稿">
                {release.draftTitle || release.draftId}
              </Descriptions.Item>
              <Descriptions.Item label="审批人">{release.approvedBy || '-'}</Descriptions.Item>
              <Descriptions.Item label="发布人">{release.releasedBy || '-'}</Descriptions.Item>
              <Descriptions.Item label="发布时间" span={3}>
                {release.releasedAt || '-'}
              </Descriptions.Item>
              {release.rollbackReason ? (
                <Descriptions.Item label="回滚原因" span={3}>
                  {release.rollbackReason}
                </Descriptions.Item>
              ) : null}
            </Descriptions>
            <div>
              <Typography.Title level={5}>发布步骤</Typography.Title>
              <Table<SemanticReleaseStep>
                rowKey="id"
                size="small"
                pagination={false}
                scroll={{ x: 900 }}
                columns={stepColumns}
                dataSource={release.steps || []}
              />
            </div>
          </Space>
        ) : null}
      </Spin>
    </Drawer>
  );
};

export default ReleaseDetailDrawer;
