/**
 * 阶段 4 草稿版本差异与历史面板。
 *
 * 职责：按需读取不可变版本摘要、选择两个版本并查询服务端结构化 diff，同时展示最近一次 AI
 * 修订返回的即时差异。具有管理权限的用户可以把历史快照追加恢复为新版本；正式对象始终只读。
 *
 * 并发说明：版本和 diff 请求都使用请求序号/active 标记丢弃迟到响应；比较按钮使用 loading
 * 锁，切换版本不会让旧响应覆盖新选择。
 */
import { DiffOutlined, ReloadOutlined, RollbackOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  List,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React from 'react';
import type { AiReviseModelingDraftResp } from '@/services/semanticModelingDraft';
import { formatModelingDiffValue } from '../utils';
import { useDraftVersionDiff } from './useDraftVersionDiff';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';
import styles from '../style.less';

const { Paragraph, Text } = Typography;
const MAX_VISIBLE_DIFF_ITEMS = 200;

type Props = {
  active: boolean;
  currentVersionNo?: number;
  draftId: number;
  lockVersion?: number;
  canRestore?: boolean;
  mutationCoordinator?: DraftMutationCoordinator;
  onVersionRestored?: () => Promise<void> | void;
  recentRevision?: AiReviseModelingDraftResp;
  refreshToken?: number;
};

/** 格式化版本创建时间，解析失败时保留原值。 */
function formatDateTime(value?: string): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/** 根据差异类型返回稳定标签颜色。 */
function getChangeColor(changeType: string): string {
  if (changeType === 'ADDED') return 'success';
  if (changeType === 'REMOVED') return 'error';
  if (changeType === 'MODIFIED' || changeType === 'CHANGED') return 'processing';
  return 'default';
}

/**
 * 展示只读版本历史与结构化版本差异。
 *
 * @param props 草稿 ID、当前版本、激活状态、最近修订和刷新令牌。
 * @returns 版本选择器、差异列表和历史摘要。
 * @throws 不向上抛出异常；接口错误通过 message 展示。
 */
const VersionDiffPanel: React.FC<Props> = ({
  active,
  currentVersionNo,
  draftId,
  lockVersion,
  canRestore,
  mutationCoordinator,
  onVersionRestored,
  recentRevision,
  refreshToken,
}) => {
  const workflow = useDraftVersionDiff({
    active,
    currentVersionNo,
    draftId,
    lockVersion,
    canRestore,
    mutationCoordinator,
    onVersionRestored,
    recentRevision,
    refreshToken,
  });
  const {
    compareDisabled,
    diffItems,
    diffLoading,
    fromVersionNo,
    hasMoreVersions,
    toVersionNo,
    versionOptions,
    versionTotal,
    versions,
    versionsLoading,
    versionsLoadingMore,
    restoringVersionNo,
    visibleDiff,
    loadDiff,
    loadMoreVersions,
    refreshVersions,
    restoreVersion,
    selectFromVersion,
    selectToVersion,
  } = workflow;

  return (
    <Space className={styles.stage4Panel} orientation="vertical" size={16}>
      <Alert
        showIcon
        type="info"
        title="历史快照不可修改"
        description="管理员可将历史快照追加恢复为新草稿版本；该操作不会覆盖历史版本，也不会发布或回滚正式语义对象。"
      />
      <Card size="small" title="选择比较版本">
        <Space wrap>
          <Text>从</Text>
          <Select
            aria-label="差异起始版本"
            loading={versionsLoading}
            options={versionOptions}
            placeholder="选择起始版本"
            style={{ minWidth: 240 }}
            value={fromVersionNo}
            onChange={selectFromVersion}
          />
          <Text>到</Text>
          <Select
            aria-label="差异目标版本"
            loading={versionsLoading}
            options={versionOptions}
            placeholder="选择目标版本"
            style={{ minWidth: 240 }}
            value={toVersionNo}
            onChange={selectToVersion}
          />
          <Button
            disabled={compareDisabled}
            icon={<DiffOutlined />}
            loading={diffLoading}
            type="primary"
            onClick={() => void loadDiff()}
          >
            比较版本
          </Button>
          <Tooltip title="刷新版本历史">
            <Button
              aria-label="刷新版本历史"
              icon={<ReloadOutlined />}
              loading={versionsLoading}
              title="刷新版本历史"
              onClick={refreshVersions}
            />
          </Tooltip>
        </Space>
      </Card>

      <Card loading={diffLoading} size="small" title="结构化差异">
        {visibleDiff ? (
          <>
            <Space wrap>
              <Tag color="blue">
                版本 {visibleDiff.fromVersionNo} → {visibleDiff.toVersionNo}
              </Tag>
              {visibleDiff.truncated ? <Tag color="warning">差异已截断</Tag> : null}
            </Space>
            <Paragraph className={styles.diffSummary}>
              {visibleDiff.summary || '后端未返回差异摘要'}
            </Paragraph>
            {diffItems.length ? (
              <List
                bordered
                dataSource={diffItems.slice(0, MAX_VISIBLE_DIFF_ITEMS)}
                renderItem={(item, index) => (
                  <List.Item key={`${item.path}-${item.changeType}-${index}`}>
                    <section className={styles.diffItem}>
                      <Space wrap>
                        <Tag color={getChangeColor(item.changeType)}>{item.changeType}</Tag>
                        <Text code>{item.path || '$'}</Text>
                      </Space>
                      <div className={styles.diffGrid}>
                        <div>
                          <Text type="secondary">变更前</Text>
                          <pre className={styles.diffValue}>
                            {formatModelingDiffValue(item.beforeValue)}
                          </pre>
                        </div>
                        <div>
                          <Text type="secondary">变更后</Text>
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
              <Empty description="两个版本没有结构化差异" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
            {diffItems.length > MAX_VISIBLE_DIFF_ITEMS ? (
              <Alert
                className={styles.stage4InlineAlert}
                showIcon
                type="warning"
                title={`差异共 ${diffItems.length} 项，当前仅展示前 ${MAX_VISIBLE_DIFF_ITEMS} 项`}
                description="请缩小版本跨度后继续比较，避免遗漏未展示的变更。"
              />
            ) : null}
          </>
        ) : (
          <Empty description="请选择两个不同版本进行比较" />
        )}
      </Card>

      <Card size="small" title="版本历史">
        <Spin spinning={versionsLoading}>
          {versions.length ? (
            <List
              bordered
              dataSource={versions}
              footer={
                hasMoreVersions ? (
                  <Button
                    block
                    loading={versionsLoadingMore}
                    onClick={() => void loadMoreVersions()}
                  >
                    加载更早版本（已加载 {versions.length}/{versionTotal}）
                  </Button>
                ) : (
                  <Text type="secondary">已加载全部 {versionTotal} 个版本</Text>
                )
              }
              renderItem={(item) => (
                <List.Item
                  key={item.versionNo}
                  actions={
                    canRestore && item.versionNo !== currentVersionNo
                      ? [
                          <Button
                            key="restore"
                            disabled={restoringVersionNo !== undefined}
                            icon={<RollbackOutlined />}
                            loading={restoringVersionNo === item.versionNo}
                            onClick={() =>
                              Modal.confirm({
                                title: `将版本 ${item.versionNo} 恢复为新版本？`,
                                content: `当前版本为 ${
                                  currentVersionNo ?? '-'
                                }。恢复会追加一个连续的新版本，不会修改历史版本，也不会影响正式语义对象。`,
                                okText: '恢复为新版本',
                                cancelText: '取消',
                                onOk: () => restoreVersion(item.versionNo),
                              })
                            }
                          >
                            恢复为新版本
                          </Button>,
                        ]
                      : undefined
                  }
                >
                  <List.Item.Meta
                    title={
                      <Space wrap>
                        <Tag color={item.versionNo === currentVersionNo ? 'blue' : 'default'}>
                          版本 {item.versionNo}
                        </Tag>
                        <Text>{item.changeSummary || '未填写变更摘要'}</Text>
                      </Space>
                    }
                    description={`${item.createdBy || '-'} · ${formatDateTime(item.createdAt)} · ${
                      item.changeSource || '-'
                    }`}
                  />
                </List.Item>
              )}
            />
          ) : (
            <Empty description={versionsLoading ? '正在加载版本' : '暂无版本记录'} />
          )}
        </Spin>
      </Card>
    </Space>
  );
};

export default VersionDiffPanel;
