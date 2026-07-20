/**
 * Forecast 运行中心页面。
 *
 * 职责：展示任务状态、进度、水位、吞吐、耗时与脱敏错误，并提供手动任务、取消和重试。
 * 并发说明：ProTable 轮询随组件卸载自动停止；行级 loading 防止重复操作。
 */
import {
  cancelForecastJob,
  createForecastIdempotencyKey,
  createForecastJob,
  getForecastErrorMessage,
  getForecastJobs,
  getForecastProfiles,
  retryForecastJob,
  unwrapForecastData,
} from '@/services/forecast';
import type {
  ForecastJob,
  ForecastJobStatus,
  ForecastJobType,
  ForecastProfile,
} from '@/services/forecast';
import {
  EyeOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useLocation } from '@umijs/max';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ModalForm, PageContainer, ProFormSelect, ProTable } from '@ant-design/pro-components';
import {
  Button,
  Descriptions,
  Drawer,
  message,
  Progress,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';

const STATUS_COLOR: Record<ForecastJobStatus, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  CANCELLING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
};
const TYPE_TEXT: Record<string, string> = {
  INITIAL_SYNC: '首次同步',
  INCREMENTAL_SYNC: '增量同步',
  RECONCILE: '对账',
  AGGREGATE: '聚合',
  FORECAST: '预测',
};
const formatTime = (value?: string) =>
  value && dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';

/** 运行吞吐列标题；同时支持鼠标悬停与键盘聚焦查看统计口径。 */
const TASK_THROUGHPUT_TITLE = (
  <Space size={4}>
    <span>读取 / 写入 / 聚合</span>
    <Tooltip
      title={
        <div style={{ maxWidth: 380 }}>
          <div>读取：从源库查询并标准化得到的事件记录数，不是物料数量。</div>
          <div>写入：决策库标准事件表实际 INSERT 或 UPDATE 的记录数。</div>
          <div>聚合：重算写入的“日期 × 仓库 × 方向”日汇总桶数量，不是预测天数。</div>
        </div>
      }
    >
      <QuestionCircleOutlined
        aria-label="读取、写入和聚合指标说明"
        tabIndex={0}
        style={{ color: '#8c8c8c', cursor: 'help' }}
      />
    </Tooltip>
  </Space>
);

/**
 * 渲染任务运行中心。
 *
 * @returns 可轮询和操作的任务表格。
 * @throws 不主动抛出异常；请求失败显示 message。
 */
const ForecastRuns: React.FC = () => {
  const location = useLocation();
  const requestedProfileId = useMemo(() => {
    const raw = new URLSearchParams(location.search).get('profileId');
    const parsed = raw ? Number(raw) : undefined;
    return parsed && Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
  }, [location.search]);
  const actionRef = useRef<ActionType>();
  const [profiles, setProfiles] = useState<ForecastProfile[]>([]);
  const [profileId, setProfileId] = useState<number | undefined>(requestedProfileId);
  const [detail, setDetail] = useState<ForecastJob>();
  const [createOpen, setCreateOpen] = useState(false);
  const [actionLoadingId, setActionLoadingId] = useState<number>();

  const loadProfiles = useCallback(async () => {
    try {
      const response = await getForecastProfiles({ pageNum: 1, pageSize: 100 });
      const list = unwrapForecastData<{ list: ForecastProfile[] }>(response)?.list || [];
      setProfiles(list);
      setProfileId((current) => {
        if (current && list.some((profile) => profile.id === current)) return current;
        if (requestedProfileId && list.some((profile) => profile.id === requestedProfileId)) {
          return requestedProfileId;
        }
        return list[0]?.id;
      });
    } catch (error: unknown) {
      message.error(getForecastErrorMessage(error, 'Profile 加载失败'));
    }
  }, [requestedProfileId]);

  useEffect(() => {
    loadProfiles();
  }, [loadProfiles]);
  useEffect(() => {
    if (requestedProfileId) setProfileId(requestedProfileId);
  }, [requestedProfileId]);
  useEffect(() => {
    actionRef.current?.reload();
  }, [profileId]);

  const operate = async (job: ForecastJob, action: 'cancel' | 'retry') => {
    setActionLoadingId(job.id);
    try {
      const key = createForecastIdempotencyKey(`job-${action}-${job.id}`);
      if (action === 'cancel') await cancelForecastJob(job.id, key);
      else await retryForecastJob(job.id, key);
      message.success(action === 'cancel' ? '已提交取消请求' : '重试任务已创建');
      actionRef.current?.reload();
    } catch (error: unknown) {
      message.error(getForecastErrorMessage(error, '任务操作失败'));
    } finally {
      setActionLoadingId(undefined);
    }
  };

  const columns: ProColumns<ForecastJob>[] = useMemo(
    () => [
      { title: '任务 ID', dataIndex: 'id', width: 90, search: false },
      {
        title: '类型',
        dataIndex: 'type',
        width: 110,
        search: false,
        render: (_, row) => TYPE_TEXT[row.type] || row.type,
      },
      {
        title: '状态',
        dataIndex: 'status',
        width: 110,
        search: false,
        render: (_, row) => <Tag color={STATUS_COLOR[row.status]}>{row.status}</Tag>,
      },
      {
        title: '进度',
        dataIndex: 'progressPercent',
        width: 150,
        search: false,
        render: (_, row) => (
          <Progress
            percent={row.progressPercent}
            size="small"
            status={
              row.status === 'FAILED'
                ? 'exception'
                : row.status === 'SUCCEEDED'
                ? 'success'
                : 'active'
            }
          />
        ),
      },
      {
        title: TASK_THROUGHPUT_TITLE,
        width: 180,
        search: false,
        render: (_, row) => `${row.rowsRead} / ${row.rowsWritten} / ${row.rowsAggregated}`,
      },
      { title: '重试', dataIndex: 'retryCount', width: 70, search: false },
      {
        title: '创建时间',
        dataIndex: 'createdAt',
        width: 170,
        search: false,
        render: (_, row) => formatTime(row.createdAt),
      },
      {
        title: '耗时',
        width: 100,
        search: false,
        render: (_, row) =>
          row.startedAt
            ? `${Math.max(
                0,
                dayjs(row.finishedAt || undefined).diff(dayjs(row.startedAt), 'second'),
              )} 秒`
            : '-',
      },
      {
        title: '操作',
        valueType: 'option',
        width: 132,
        fixed: 'right',
        render: (_, row) => [
          <Tooltip title="查看详情" key="detail">
            <Button
              type="text"
              icon={<EyeOutlined />}
              aria-label="查看任务详情"
              onClick={() => setDetail(row)}
            />
          </Tooltip>,
          <Tooltip title="取消" key="cancel">
            <Button
              type="text"
              danger
              icon={<StopOutlined />}
              aria-label="取消任务"
              disabled={!['QUEUED', 'RUNNING'].includes(row.status)}
              loading={actionLoadingId === row.id}
              onClick={() => operate(row, 'cancel')}
            />
          </Tooltip>,
          <Tooltip title="重试" key="retry">
            <Button
              type="text"
              icon={<ReloadOutlined />}
              aria-label="重试任务"
              disabled={!['FAILED', 'CANCELLED'].includes(row.status)}
              loading={actionLoadingId === row.id}
              onClick={() => operate(row, 'retry')}
            />
          </Tooltip>,
        ],
      },
    ],
    [actionLoadingId],
  );

  const selectedProfile = profiles.find((item) => item.id === profileId);
  return (
    <PageContainer title="预测运行中心" subTitle="任务进度、复合水位、吞吐与故障恢复">
      <ProTable<ForecastJob>
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={false}
        polling={5000}
        scroll={{ x: 1150 }}
        pagination={{ defaultPageSize: 20 }}
        toolbar={{
          title: (
            <Select
              style={{ width: 260 }}
              placeholder="选择 Profile"
              value={profileId}
              options={profiles.map((item) => ({ value: item.id, label: item.name }))}
              onChange={setProfileId}
            />
          ),
        }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            icon={<PlusOutlined />}
            disabled={!profileId}
            onClick={() => setCreateOpen(true)}
          >
            手动任务
          </Button>,
        ]}
        request={async (params) => {
          if (!profileId) return { data: [], total: 0, success: true };
          try {
            const response = await getForecastJobs({
              profileId,
              pageNum: params.current,
              pageSize: params.pageSize,
            });
            const page = unwrapForecastData<{ list: ForecastJob[]; total: number }>(response);
            return { data: page?.list || [], total: page?.total || 0, success: true };
          } catch (error: unknown) {
            message.error(getForecastErrorMessage(error, '任务列表加载失败'));
            return { data: [], total: 0, success: false };
          }
        }}
      />

      <ModalForm<{ type: ForecastJobType; streamId?: number }>
        title="创建手动任务"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnHidden: true }}
        initialValues={{ type: 'INCREMENTAL_SYNC' }}
        onFinish={async (values) => {
          if (!profileId) return false;
          try {
            await createForecastJob(
              {
                profileId,
                streamId: values.streamId,
                type: values.type,
                historyDays: selectedProfile?.historyDays,
              },
              createForecastIdempotencyKey(`manual-${values.type}-${profileId}`),
            );
            message.success('任务已入队');
            actionRef.current?.reload();
            return true;
          } catch (error: unknown) {
            message.error(getForecastErrorMessage(error, '任务创建失败'));
            return false;
          }
        }}
      >
        <ProFormSelect
          name="type"
          label="任务类型"
          rules={[{ required: true }]}
          options={[
            { value: 'INCREMENTAL_SYNC', label: '增量同步' },
            { value: 'RECONCILE', label: '最近窗口对账' },
            { value: 'FORECAST', label: '重新预测' },
          ]}
        />
        <ProFormSelect
          name="streamId"
          label="数据流（留空表示全部）"
          options={(selectedProfile?.streams || []).map((stream) => ({
            value: stream.id,
            label: stream.name,
          }))}
        />
      </ModalForm>

      <Drawer
        size={640}
        open={Boolean(detail)}
        onClose={() => setDetail(undefined)}
        title={`任务详情 #${detail?.id || ''}`}
      >
        {detail && (
          <Descriptions
            bordered
            size="small"
            column={1}
            items={[
              {
                key: 'type',
                label: '类型 / 状态',
                children: (
                  <Space>
                    <span>{TYPE_TEXT[detail.type] || detail.type}</span>
                    <Tag color={STATUS_COLOR[detail.status]}>{detail.status}</Tag>
                  </Space>
                ),
              },
              {
                key: 'progress',
                label: '进度',
                children: <Progress percent={detail.progressPercent} />,
              },
              {
                key: 'rows',
                label: '读取 / 写入 / 聚合',
                children: `${detail.rowsRead} / ${detail.rowsWritten} / ${detail.rowsAggregated}`,
              },
              {
                key: 'checkpoint',
                label: '复合水位',
                children: (
                  <Typography.Text copyable>{`${detail.checkpoint?.updatedAt || '-'} | ${
                    detail.checkpoint?.recordId || '-'
                  }`}</Typography.Text>
                ),
              },
              { key: 'worker', label: 'Worker', children: detail.workerId || '-' },
              { key: 'heartbeat', label: '最后心跳', children: formatTime(detail.heartbeatAt) },
              {
                key: 'error',
                label: '错误',
                children: detail.errorCode
                  ? `${detail.errorCode}：${detail.errorMessage || ''}`
                  : '-',
              },
              {
                key: 'time',
                label: '开始 / 完成',
                children: `${formatTime(detail.startedAt)} / ${formatTime(detail.finishedAt)}`,
              },
            ]}
          />
        )}
      </Drawer>
    </PageContainer>
  );
};

export default ForecastRuns;
