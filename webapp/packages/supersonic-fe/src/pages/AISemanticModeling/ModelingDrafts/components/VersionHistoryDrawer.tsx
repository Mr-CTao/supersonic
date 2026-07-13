/**
 * AI 语义建模草稿版本历史抽屉。
 *
 * 职责：
 * - 查询并展示草稿不可变版本摘要；
 * - 按需读取指定版本快照并以只读 JSON 展示；
 * - 不提供版本恢复、覆盖、验证或发布操作，避免跨越阶段 3 边界。
 *
 * 并发说明：
 * - 抽屉关闭或组件卸载后通过 active 标记丢弃迟到响应；
 * - 版本快照按钮使用单版本 loading 锁，避免同一版本重复请求。
 */
import { EyeOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  List,
  message,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useRef, useState } from 'react';
import {
  getModelingDraftVersion,
  getModelingDraftVersions,
} from '@/services/semanticModelingDraft';
import type { ModelingDraftVersion } from '@/services/semanticModelingDraft';
import {
  getRequestErrorText,
  parseDraftJson,
  stringifyDraftJson,
  unwrapResponseData,
} from '../utils';
import styles from '../style.less';

const { Text, Title } = Typography;
const VERSION_PAGE_SIZE = 50;

type Props = {
  draftId?: number;
  open: boolean;
  onClose: () => void;
};

/**
 * 格式化版本审计时间。
 *
 * @param value ISO 时间字符串。
 * @returns 本地时间或占位符。
 * @throws 不抛出异常。
 */
function formatDateTime(value?: string): string {
  if (!value) {
    return '-';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/**
 * 草稿版本历史抽屉组件。
 *
 * @param props 草稿 ID、开关状态和关闭回调。
 * @returns 版本摘要与选中快照。
 * @throws 不主动抛出异常；接口错误通过 message 展示。
 */
const VersionHistoryDrawer: React.FC<Props> = ({ draftId, open, onClose }) => {
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [versions, setVersions] = useState<ModelingDraftVersion[]>([]);
  const [versionPage, setVersionPage] = useState(0);
  const [versionTotal, setVersionTotal] = useState(0);
  const [snapshotLoadingVersion, setSnapshotLoadingVersion] = useState<number>();
  const [snapshot, setSnapshot] = useState<ModelingDraftVersion>();
  const [snapshotJson, setSnapshotJson] = useState('');
  const [snapshotError, setSnapshotError] = useState('');
  const versionRequestRef = useRef(0);

  useEffect(() => {
    if (!open || !draftId) {
      return undefined;
    }
    let active = true;
    const requestId = ++versionRequestRef.current;
    setLoading(true);
    setLoadingMore(false);
    setVersions([]);
    setVersionPage(0);
    setVersionTotal(0);
    setSnapshot(undefined);
    setSnapshotJson('');
    setSnapshotError('');

    /** 加载版本摘要列表，关闭抽屉后忽略迟到响应。 */
    const loadVersions = async () => {
      try {
        const data =
          unwrapResponseData<any>(
            await getModelingDraftVersions(draftId, { page: 1, pageSize: VERSION_PAGE_SIZE }),
          ) || {};
        const list = (Array.isArray(data) ? data : data.list || []) as ModelingDraftVersion[];
        if (active && requestId === versionRequestRef.current) {
          setVersions(list);
          setVersionPage(1);
          setVersionTotal(Number(Array.isArray(data) ? list.length : data.total ?? list.length));
        }
      } catch (error) {
        if (active && requestId === versionRequestRef.current) {
          message.error(getRequestErrorText(error));
        }
      } finally {
        if (active && requestId === versionRequestRef.current) {
          setLoading(false);
        }
      }
    };

    void loadVersions();
    return () => {
      active = false;
      versionRequestRef.current += 1;
    };
  }, [draftId, open]);

  /** 按页追加更早的版本摘要，避免版本较多时一次性加载全部记录。 */
  const loadMoreVersions = async () => {
    if (!open || !draftId || loading || loadingMore || versions.length >= versionTotal) {
      return;
    }
    const nextPage = versionPage + 1;
    const requestId = ++versionRequestRef.current;
    setLoadingMore(true);
    try {
      const data =
        unwrapResponseData<any>(
          await getModelingDraftVersions(draftId, {
            page: nextPage,
            pageSize: VERSION_PAGE_SIZE,
          }),
        ) || {};
      const list = (Array.isArray(data) ? data : data.list || []) as ModelingDraftVersion[];
      if (requestId !== versionRequestRef.current) return;
      setVersions((previous) => {
        const merged = new Map(previous.map((item) => [item.versionNo, item]));
        list.forEach((item) => merged.set(item.versionNo, item));
        return [...merged.values()].sort((left, right) => right.versionNo - left.versionNo);
      });
      setVersionPage(nextPage);
      setVersionTotal(
        Number(
          Array.isArray(data)
            ? Math.max(versionTotal, versions.length + list.length)
            : data.total ?? versionTotal,
        ),
      );
    } catch (error) {
      if (requestId === versionRequestRef.current) message.error(getRequestErrorText(error));
    } finally {
      if (requestId === versionRequestRef.current) setLoadingMore(false);
    }
  };

  /**
   * 按需读取指定版本结构化快照。
   *
   * @param versionNo 版本号。
   * @returns Promise<void>。
   * @throws 请求和 JSON 解析错误会转为页面提示。
   */
  const openSnapshot = async (versionNo: number) => {
    if (!draftId) {
      return;
    }
    setSnapshotLoadingVersion(versionNo);
    setSnapshotError('');
    try {
      const data = unwrapResponseData<ModelingDraftVersion>(
        await getModelingDraftVersion(draftId, versionNo),
      );
      const parsed = parseDraftJson(data.snapshot || data.currentDraft || data.draftJson);
      setSnapshot(data);
      if (parsed.value) {
        setSnapshotJson(stringifyDraftJson(parsed.value));
      } else {
        setSnapshotJson('');
        setSnapshotError(parsed.error || '版本快照无法解析');
      }
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setSnapshotLoadingVersion(undefined);
    }
  };

  return (
    <Drawer
      destroyOnHidden
      open={open}
      size="large"
      title={`版本历史${draftId ? ` · 草稿 #${draftId}` : ''}`}
      onClose={onClose}
    >
      <Spin spinning={loading}>
        {versions.length ? (
          <List<ModelingDraftVersion>
            bordered
            dataSource={versions}
            footer={
              versions.length < versionTotal ? (
                <Button block loading={loadingMore} onClick={() => void loadMoreVersions()}>
                  加载更早版本（已加载 {versions.length}/{versionTotal}）
                </Button>
              ) : (
                <Text type="secondary">已加载全部 {versionTotal} 个版本</Text>
              )
            }
            renderItem={(item) => (
              <List.Item
                actions={[
                  <Button
                    aria-label={`查看版本 ${item.versionNo}`}
                    icon={<EyeOutlined />}
                    key={`view-${item.versionNo}`}
                    loading={snapshotLoadingVersion === item.versionNo}
                    size="small"
                    title={`查看版本 ${item.versionNo}`}
                    onClick={() => void openSnapshot(item.versionNo)}
                  />,
                ]}
              >
                <List.Item.Meta
                  title={
                    <Space wrap>
                      <Tag color="blue">版本 {item.versionNo}</Tag>
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
          <Empty description={loading ? '正在加载版本' : '暂无版本记录'} />
        )}
      </Spin>

      {snapshot ? (
        <section className={styles.versionSnapshot}>
          <Title level={5}>版本 {snapshot.versionNo} 只读快照</Title>
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="变更摘要">{snapshot.changeSummary || '-'}</Descriptions.Item>
            <Descriptions.Item label="变更来源">{snapshot.changeSource || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建人">{snapshot.createdBy || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {formatDateTime(snapshot.createdAt)}
            </Descriptions.Item>
          </Descriptions>
          {snapshotError ? <Alert showIcon type="error" title={snapshotError} /> : null}
          {snapshotJson ? <pre className={styles.readonlyJson}>{snapshotJson}</pre> : null}
        </section>
      ) : null}
    </Drawer>
  );
};

export default VersionHistoryDrawer;
