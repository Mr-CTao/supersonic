/**
 * AI 语义建模阶段 5 发布审计页面。
 *
 * 职责：以待审批和发布记录两个 Tab 提供验证依据、审批决定、发布、逐步骤查看、知识刷新重试和
 * AI 新增对象回滚入口。所有提交按钮使用统一 actionKey 锁定，后端继续提供跨实例幂等保护。
 */
import { EyeOutlined, ReloadOutlined, RollbackOutlined } from '@ant-design/icons';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Input, message, Modal, Space, Tabs, Tag } from 'antd';
import React, { useMemo, useRef, useState } from 'react';
import {
  approveSemanticDraft,
  getSemanticApprovals,
  getSemanticReleases,
  rejectSemanticDraft,
  releaseSemanticDraft,
  retrySemanticKnowledge,
  rollbackSemanticRelease,
  type SemanticApprovalItem,
  type SemanticReleaseItem,
} from '@/services/semanticRelease';
import { getRequestErrorText, unwrapResponseData } from '../ModelingDrafts/utils';
import ApprovalDetailDrawer from './components/ApprovalDetailDrawer';
import ReleaseDetailDrawer from './components/ReleaseDetailDrawer';
import RollbackModal from './components/RollbackModal';

const DEFAULT_PAGE_SIZE = 20;

/** 按治理状态返回统一 Tag 颜色。 */
function statusColor(status?: string): string {
  if (status === 'APPROVED' || status === 'PASSED' || status === 'SUCCEEDED') return 'success';
  if (status === 'REJECTED' || status === 'FAILED' || status === 'ROLLBACK_FAILED') return 'error';
  if (status === 'PENDING_APPROVAL' || status?.includes('PROGRESS')) return 'processing';
  if (status === 'ROLLED_BACK') return 'default';
  return 'warning';
}

/** 仅在发布终态失败且至少一个知识刷新步骤失败时提供知识重试入口。 */
function hasRetryableKnowledgeFailure(release: SemanticReleaseItem): boolean {
  const failedTerminal =
    release.releaseStatus === 'FAILED' || release.releaseStatus === 'ROLLBACK_FAILED';
  return (
    failedTerminal &&
    (release.dictReloadStatus === 'FAILED' || release.embeddingReloadStatus === 'FAILED')
  );
}

/**
 * 创建一次发布操作使用的幂等键。
 *
 * @returns 浏览器支持时使用 UUID，否则使用时间戳与随机数组合。
 * @throws 不抛出异常；后端仍以草稿唯一发布记录作为最终防重边界。
 */
function createIdempotencyKey(): string {
  return (
    globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`
  );
}

/**
 * 阶段 5 发布审计主页面。
 *
 * @returns 待审批、发布记录、详情抽屉和回滚确认弹窗。
 * @throws 不向上抛出异常；接口错误统一通过 message 展示。
 */
const ReleaseAudit: React.FC = () => {
  const approvalActionRef = useRef<ActionType>();
  const releaseActionRef = useRef<ActionType>();
  const [actionKey, setActionKey] = useState<string>();
  const [approvalDetail, setApprovalDetail] = useState<SemanticApprovalItem>();
  const [releaseDetailId, setReleaseDetailId] = useState<number>();
  const [releaseRefreshToken, setReleaseRefreshToken] = useState(0);
  const [rejectApproval, setRejectApproval] = useState<SemanticApprovalItem>();
  const [rejectReason, setRejectReason] = useState('');
  const [rollbackRelease, setRollbackRelease] = useState<SemanticReleaseItem>();

  /** 同时刷新两个列表，使审批状态和发布记录保持一致。 */
  const reloadAll = () => {
    approvalActionRef.current?.reload();
    releaseActionRef.current?.reload();
  };

  /** 执行审批通过，并由 actionKey 锁定当前行。 */
  const approve = async (record: SemanticApprovalItem) => {
    const key = `approve:${record.draftId}`;
    setActionKey(key);
    try {
      await approveSemanticDraft(record.draftId);
      message.success('审批已通过，可继续发布');
      reloadAll();
    } catch (error) {
      message.error(getRequestErrorText(error));
      throw error;
    } finally {
      setActionKey(undefined);
    }
  };

  /** 提交拒绝原因。 */
  const reject = async () => {
    if (!rejectApproval || !rejectReason.trim()) {
      message.warning('请填写拒绝原因');
      return;
    }
    const key = `reject:${rejectApproval.draftId}`;
    setActionKey(key);
    try {
      await rejectSemanticDraft(rejectApproval.draftId, rejectReason.trim());
      message.success('已拒绝该草稿');
      setRejectApproval(undefined);
      setRejectReason('');
      reloadAll();
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setActionKey(undefined);
    }
  };

  /** 发布 AI 新增对象并打开步骤详情。 */
  const release = async (record: SemanticApprovalItem) => {
    const key = `release:${record.draftId}`;
    setActionKey(key);
    try {
      const response = await releaseSemanticDraft(record.draftId, createIdempotencyKey());
      const result = unwrapResponseData<SemanticReleaseItem>(response);
      if (result?.releaseStatus === 'SUCCEEDED') message.success('发布与知识刷新已完成');
      else message.warning('发布存在失败步骤，请在发布详情中处理');
      if (result?.id) setReleaseDetailId(result.id);
      setReleaseRefreshToken((value) => value + 1);
      reloadAll();
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setActionKey(undefined);
    }
  };

  /** 重试失败的知识刷新步骤。 */
  const retryKnowledge = async (record: SemanticReleaseItem) => {
    const key = `retry:${record.id}`;
    setActionKey(key);
    try {
      const response = await retrySemanticKnowledge(record.id);
      const result = unwrapResponseData<SemanticReleaseItem>(response);
      if (result?.releaseStatus === 'SUCCEEDED' || result?.releaseStatus === 'ROLLED_BACK') {
        message.success('知识刷新重试成功');
      } else {
        message.warning('知识刷新仍有失败步骤');
      }
      setReleaseRefreshToken((value) => value + 1);
      reloadAll();
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setActionKey(undefined);
    }
  };

  /** 提交服务端限定范围的 AI 新增对象回滚。 */
  const rollback = async (reason: string) => {
    if (!rollbackRelease) return;
    const key = `rollback:${rollbackRelease.id}`;
    setActionKey(key);
    try {
      const response = await rollbackSemanticRelease(rollbackRelease.id, reason);
      const result = unwrapResponseData<SemanticReleaseItem>(response);
      if (result?.releaseStatus === 'ROLLED_BACK') message.success('AI 新增对象已回滚并刷新知识');
      else message.warning('回滚存在失败步骤，请查看发布详情');
      setRollbackRelease(undefined);
      setReleaseDetailId(rollbackRelease.id);
      setReleaseRefreshToken((value) => value + 1);
      reloadAll();
    } catch (error) {
      message.error(getRequestErrorText(error));
    } finally {
      setActionKey(undefined);
    }
  };

  const approvalColumns = useMemo<ProColumns<SemanticApprovalItem>[]>(
    () => [
      { title: '草稿标题', dataIndex: 'title', width: 180, ellipsis: true },
      { title: '业务目标', dataIndex: 'businessGoal', width: 240, ellipsis: true },
      {
        title: '验证状态',
        dataIndex: 'validationStatus',
        width: 110,
        valueType: 'select',
        render: (_, record) => (
          <Tag color={statusColor(record.validationStatus)}>{record.validationStatus || '-'}</Tag>
        ),
      },
      { title: '计划新增', dataIndex: 'plannedObjectCount', width: 100, search: false },
      { title: '提交人', dataIndex: 'submittedBy', width: 110, search: false },
      { title: '提交时间', dataIndex: 'submittedAt', width: 170, search: false },
      {
        title: '审批状态',
        dataIndex: 'status',
        width: 130,
        valueType: 'select',
        valueEnum: {
          PENDING_APPROVAL: { text: '待审批' },
          APPROVED: { text: '已通过' },
          REJECTED: { text: '已拒绝' },
          RELEASE_FAILED: { text: '发布失败' },
        },
        render: (_, record) => <Tag color={statusColor(record.status)}>{record.status}</Tag>,
      },
      {
        title: '操作',
        valueType: 'option',
        fixed: 'right',
        width: 250,
        render: (_, record) => (
          <Space size={4} wrap={false}>
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              title="查看草稿与验证报告"
              aria-label="查看草稿与验证报告"
              onClick={() => setApprovalDetail(record)}
            />
            {record.status === 'PENDING_APPROVAL' ? (
              <>
                <Button
                  type="link"
                  size="small"
                  loading={actionKey === `approve:${record.draftId}`}
                  onClick={() =>
                    Modal.confirm({
                      title: '确认审批通过？',
                      content: '通过后仍需单独点击发布，系统不会自动写入正式语义资产。',
                      okText: '审批通过',
                      onOk: () => approve(record),
                    })
                  }
                >
                  通过
                </Button>
                <Button type="link" danger size="small" onClick={() => setRejectApproval(record)}>
                  拒绝
                </Button>
              </>
            ) : null}
            {record.status === 'APPROVED' || record.status === 'RELEASE_FAILED' ? (
              <Button
                type="link"
                size="small"
                loading={actionKey === `release:${record.draftId}`}
                onClick={() =>
                  Modal.confirm({
                    title: '确认发布 AI 新增对象？',
                    content: '将调用现有语义管理 API，并依次刷新 dict 与 embedding。',
                    okText: '确认发布',
                    onOk: () => release(record),
                  })
                }
              >
                发布
              </Button>
            ) : null}
          </Space>
        ),
      },
    ],
    [actionKey],
  );

  const releaseColumns = useMemo<ProColumns<SemanticReleaseItem>[]>(
    () => [
      { title: '发布版本', dataIndex: 'releaseNo', width: 210, ellipsis: true },
      { title: '关联草稿', dataIndex: 'draftTitle', width: 180, ellipsis: true },
      {
        title: '发布状态',
        dataIndex: 'releaseStatus',
        width: 150,
        valueType: 'select',
        valueEnum: {
          SUCCEEDED: { text: '发布成功' },
          FAILED: { text: '发布失败' },
          IN_PROGRESS: { text: '发布中' },
          ROLLED_BACK: { text: '已回滚' },
          ROLLBACK_FAILED: { text: '回滚失败' },
        },
        render: (_, record) => (
          <Tag color={statusColor(record.releaseStatus)}>{record.releaseStatus}</Tag>
        ),
      },
      {
        title: 'dict',
        dataIndex: 'dictReloadStatus',
        width: 110,
        search: false,
        render: (_, record) => (
          <Tag color={statusColor(record.dictReloadStatus)}>{record.dictReloadStatus}</Tag>
        ),
      },
      {
        title: 'embedding',
        dataIndex: 'embeddingReloadStatus',
        width: 120,
        search: false,
        render: (_, record) => (
          <Tag color={statusColor(record.embeddingReloadStatus)}>
            {record.embeddingReloadStatus}
          </Tag>
        ),
      },
      { title: '发布人', dataIndex: 'releasedBy', width: 110, search: false },
      { title: '发布时间', dataIndex: 'releasedAt', width: 170, search: false },
      {
        title: '操作',
        valueType: 'option',
        fixed: 'right',
        width: 230,
        render: (_, record) => (
          <Space size={4} wrap={false}>
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              title="查看发布步骤"
              aria-label="查看发布步骤"
              onClick={() => setReleaseDetailId(record.id)}
            />
            {hasRetryableKnowledgeFailure(record) ? (
              <Button
                type="link"
                size="small"
                icon={<ReloadOutlined />}
                loading={actionKey === `retry:${record.id}`}
                onClick={() => retryKnowledge(record)}
              >
                重试
              </Button>
            ) : null}
            {record.releaseStatus === 'SUCCEEDED' ? (
              <Button
                type="link"
                danger
                size="small"
                icon={<RollbackOutlined />}
                onClick={() => setRollbackRelease(record)}
              >
                回滚
              </Button>
            ) : null}
          </Space>
        ),
      },
    ],
    [actionKey],
  );

  return (
    <>
      <Tabs
        defaultActiveKey="approvals"
        items={[
          {
            key: 'approvals',
            label: '待审批',
            children: (
              <ProTable<SemanticApprovalItem>
                actionRef={approvalActionRef}
                rowKey="draftId"
                columns={approvalColumns}
                scroll={{ x: 1360 }}
                options={{ density: false }}
                pagination={{ defaultPageSize: DEFAULT_PAGE_SIZE, showSizeChanger: true }}
                request={async (params) => {
                  try {
                    const response = await getSemanticApprovals({
                      status: params.status,
                      keyword: params.title || params.businessGoal,
                      page: params.current,
                      pageSize: params.pageSize,
                    });
                    const data = unwrapResponseData<any>(response) || {};
                    return { data: data.list || [], total: data.total || 0, success: true };
                  } catch (error) {
                    message.error(getRequestErrorText(error));
                    return { data: [], total: 0, success: false };
                  }
                }}
              />
            ),
          },
          {
            key: 'releases',
            label: '发布记录',
            children: (
              <ProTable<SemanticReleaseItem>
                actionRef={releaseActionRef}
                rowKey="id"
                columns={releaseColumns}
                scroll={{ x: 1300 }}
                options={{ density: false }}
                pagination={{ defaultPageSize: DEFAULT_PAGE_SIZE, showSizeChanger: true }}
                request={async (params) => {
                  try {
                    const response = await getSemanticReleases({
                      status: params.releaseStatus,
                      keyword: params.releaseNo || params.draftTitle,
                      page: params.current,
                      pageSize: params.pageSize,
                    });
                    const data = unwrapResponseData<any>(response) || {};
                    return { data: data.list || [], total: data.total || 0, success: true };
                  } catch (error) {
                    message.error(getRequestErrorText(error));
                    return { data: [], total: 0, success: false };
                  }
                }}
              />
            ),
          },
        ]}
      />
      <ApprovalDetailDrawer
        approval={approvalDetail}
        open={Boolean(approvalDetail)}
        onClose={() => setApprovalDetail(undefined)}
      />
      <ReleaseDetailDrawer
        releaseId={releaseDetailId}
        open={Boolean(releaseDetailId)}
        refreshToken={releaseRefreshToken}
        actionLoading={Boolean(actionKey)}
        onClose={() => setReleaseDetailId(undefined)}
        onRetry={retryKnowledge}
        onRollback={setRollbackRelease}
      />
      <Modal
        title="拒绝审批"
        open={Boolean(rejectApproval)}
        okText="确认拒绝"
        okButtonProps={{ danger: true }}
        confirmLoading={actionKey === `reject:${rejectApproval?.draftId}`}
        onCancel={() => {
          setRejectApproval(undefined);
          setRejectReason('');
        }}
        onOk={reject}
        destroyOnClose
      >
        <Input.TextArea
          rows={4}
          value={rejectReason}
          maxLength={1000}
          showCount
          placeholder="请填写拒绝原因"
          onChange={(event) => setRejectReason(event.target.value)}
        />
      </Modal>
      <RollbackModal
        release={rollbackRelease}
        open={Boolean(rollbackRelease)}
        confirmLoading={actionKey === `rollback:${rollbackRelease?.id}`}
        onCancel={() => setRollbackRelease(undefined)}
        onConfirm={rollback}
      />
    </>
  );
};

export default ReleaseAudit;
