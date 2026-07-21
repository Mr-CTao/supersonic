/**
 * Forecast 数据接入页面。
 *
 * 职责：管理 Profile、复用数据库连接、创建多数据流，并进入字段映射/预览/发布/首次同步闭环。
 * 并发说明：所有提交按钮使用 loading；表格请求由 ProTable 受控分页，不执行逐字搜索请求。
 */
import ForecastMappingDrawer from '../components/ForecastMappingDrawer';
import ForecastProfileModal from '../components/ForecastProfileModal';
import {
  getForecastActivationStatusText,
  isForecastActivationPending,
} from '../components/forecastActivation';
import { formatForecastSchedule } from '../components/forecastSchedule';
import {
  createForecastIdempotencyKey,
  createForecastStream,
  disableForecastProfile,
  getForecastErrorMessage,
  getForecastHealth,
  getForecastProfile,
  getForecastProfiles,
  unwrapForecastData,
} from '@/services/forecast';
import type {
  ForecastHealth,
  ForecastJobStatus,
  ForecastProfile,
  ForecastStream,
} from '@/services/forecast';
import { DatabaseOutlined, EditOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProFormSwitch,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  message,
  Popconfirm,
  Progress,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';

const ACTIVATION_STATUS_COLOR: Record<ForecastJobStatus, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  CANCELLING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
};
const ACTIVATION_POLL_INTERVAL_MS = 5000;

/** 格式化后端 ISO 时间。 */
const formatTime = (value?: string) =>
  value && dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';

/**
 * 渲染数据接入页。
 *
 * @returns Profile 与数据流管理页面。
 * @throws 不主动抛出异常；请求失败展示 message。
 */
const ForecastSources: React.FC = () => {
  const actionRef = useRef<ActionType>();
  const [profileModalOpen, setProfileModalOpen] = useState(false);
  const [editingProfile, setEditingProfile] = useState<ForecastProfile>();
  const [selectedProfile, setSelectedProfile] = useState<ForecastProfile>();
  const [streamModalOpen, setStreamModalOpen] = useState(false);
  const [mappingOpen, setMappingOpen] = useState(false);
  const [selectedStreamId, setSelectedStreamId] = useState<number>();
  const [health, setHealth] = useState<ForecastHealth>();
  const [disableLoading, setDisableLoading] = useState<number>();
  const detailRequestVersion = useRef(0);
  const pollingInFlight = useRef(false);

  const selectedStream = useMemo(
    () => selectedProfile?.streams?.find((stream) => stream.id === selectedStreamId),
    [selectedProfile?.streams, selectedStreamId],
  );

  /** 同批刷新 Profile 与 Worker 健康状态；请求代次防止切换 Profile 后旧响应回写。 */
  const reloadProfileDetail = useCallback(async (profileId?: number, silent = false) => {
    if (!profileId) return;
    const requestVersion = ++detailRequestVersion.current;
    const [profileResult, healthResult] = await Promise.allSettled([
      getForecastProfile(profileId),
      getForecastHealth(profileId),
    ]);
    if (requestVersion !== detailRequestVersion.current) return;

    if (profileResult.status === 'fulfilled') {
      setSelectedProfile(unwrapForecastData<ForecastProfile>(profileResult.value));
    } else if (!silent) {
      message.error(getForecastErrorMessage(profileResult.reason, 'Profile 详情加载失败'));
    }
    if (healthResult.status === 'fulfilled') {
      setHealth(unwrapForecastData<ForecastHealth>(healthResult.value));
    } else if (!silent) {
      message.warning(getForecastErrorMessage(healthResult.reason, 'Worker 健康状态加载失败'));
    }
  }, []);

  const hasPendingActivation = useMemo(
    () =>
      Boolean(
        selectedProfile?.streams?.some((stream) =>
          isForecastActivationPending(stream.latestActivation),
        ),
      ),
    [selectedProfile?.streams],
  );

  useEffect(() => {
    if (!selectedProfile?.id || !hasPendingActivation) return undefined;
    const profileId = selectedProfile.id;
    const timer = window.setInterval(() => {
      if (pollingInFlight.current) return;
      pollingInFlight.current = true;
      void reloadProfileDetail(profileId, true).finally(() => {
        pollingInFlight.current = false;
      });
    }, ACTIVATION_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [hasPendingActivation, reloadProfileDetail, selectedProfile?.id]);

  useEffect(
    () => () => {
      // 组件卸载后让所有未完成详情请求失效，避免异步回写已释放页面。
      detailRequestVersion.current += 1;
    },
    [],
  );

  const columns: ProColumns<ForecastProfile>[] = useMemo(
    () => [
      { title: 'Profile', dataIndex: 'name', ellipsis: true },
      { title: '源数据库', dataIndex: 'sourceDatabaseName', search: false, ellipsis: true },
      { title: '决策库', dataIndex: 'decisionDatabaseName', search: false, ellipsis: true },
      {
        title: '历史窗口',
        dataIndex: 'historyDays',
        search: false,
        width: 90,
        render: (_, row) => `${row.historyDays} 天`,
      },
      {
        title: '状态',
        dataIndex: 'enabled',
        search: false,
        width: 90,
        render: (_, row) => (
          <Tag color={row.enabled ? 'success' : 'default'}>{row.enabled ? '启用' : '停用'}</Tag>
        ),
      },
      {
        title: '最近同步',
        dataIndex: 'lastSyncAt',
        search: false,
        width: 170,
        render: (_, row) => formatTime(row.lastSyncAt),
      },
      {
        title: '操作',
        valueType: 'option',
        width: 132,
        fixed: 'right',
        render: (_, row) => [
          <Tooltip title="管理数据流" key="manage">
            <Button
              type="text"
              icon={<DatabaseOutlined />}
              aria-label="管理数据流"
              onClick={() => {
                setMappingOpen(false);
                setSelectedStreamId(undefined);
                setHealth(undefined);
                setSelectedProfile(row);
                void reloadProfileDetail(row.id);
              }}
            />
          </Tooltip>,
          <Tooltip title="编辑" key="edit">
            <Button
              type="text"
              icon={<EditOutlined />}
              aria-label="编辑 Profile"
              onClick={() => {
                setEditingProfile(row);
                setProfileModalOpen(true);
              }}
            />
          </Tooltip>,
          <Popconfirm
            key="disable"
            title="停用后定时任务将不再创建，确认继续？"
            disabled={!row.enabled}
            onConfirm={async () => {
              setDisableLoading(row.id);
              try {
                await disableForecastProfile(
                  row.id,
                  row.lockVersion,
                  createForecastIdempotencyKey(`profile-disable-${row.id}`),
                );
                message.success('Profile 已停用');
                actionRef.current?.reload();
                if (selectedProfile?.id === row.id) reloadProfileDetail(row.id);
              } catch (error: unknown) {
                message.error(getForecastErrorMessage(error, '停用失败'));
              } finally {
                setDisableLoading(undefined);
              }
            }}
          >
            <Tooltip title="停用">
              <Button
                type="text"
                danger
                disabled={!row.enabled}
                loading={disableLoading === row.id}
                icon={<StopOutlined />}
                aria-label="停用 Profile"
              />
            </Tooltip>
          </Popconfirm>,
        ],
      },
    ],
    [disableLoading, reloadProfileDetail, selectedProfile?.id],
  );

  const streamColumns: ProColumns<ForecastStream>[] = useMemo(
    () => [
      { title: '数据流', dataIndex: 'name' },
      {
        title: '状态',
        dataIndex: 'enabled',
        width: 90,
        render: (_, row) => (
          <Tag color={row.enabled ? 'success' : 'default'}>{row.enabled ? '启用' : '停用'}</Tag>
        ),
      },
      {
        title: '当前活动映射',
        width: 150,
        render: (_, row) =>
          row.activeMappingVersion ? (
            <Tag color="success">v{row.activeMappingVersion} · 当前服务</Tag>
          ) : (
            <Space orientation="vertical" size={0}>
              <Tag color="warning">尚未激活</Tag>
              {isForecastActivationPending(row.latestActivation) && (
                <Typography.Text type="secondary">
                  候选 v{row.latestActivation?.mappingVersion || '-'} 处理中
                </Typography.Text>
              )}
            </Space>
          ),
      },
      {
        title: '最近激活任务',
        width: 260,
        render: (_, row) => {
          const activation = row.latestActivation;
          if (!activation) return '-';
          return (
            <Space orientation="vertical" size={2} style={{ width: '100%' }}>
              <Space size={4} wrap>
                <Tag color={ACTIVATION_STATUS_COLOR[activation.status]}>{activation.status}</Tag>
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => history.push(`/forecast/runs?profileId=${row.profileId}`)}
                >
                  任务 #{activation.jobId}
                </Button>
                <Typography.Text type="secondary">
                  {getForecastActivationStatusText(activation)}
                </Typography.Text>
              </Space>
              {activation.status === 'RUNNING' && (
                <Progress percent={activation.progressPercent} size="small" />
              )}
              {activation.status === 'FAILED' && activation.errorMessage && (
                <Typography.Text type="danger" ellipsis={{ tooltip: activation.errorMessage }}>
                  {activation.errorMessage}
                </Typography.Text>
              )}
            </Space>
          );
        },
      },
      { title: '最近同步', width: 170, render: (_, row) => formatTime(row.lastSyncAt) },
      {
        title: '操作',
        valueType: 'option',
        width: 100,
        fixed: 'right',
        render: (_, row) => [
          <Button
            key="mapping"
            type="link"
            onClick={() => {
              setSelectedStreamId(row.id);
              setMappingOpen(true);
            }}
          >
            字段映射
          </Button>,
        ],
      },
    ],
    [],
  );

  return (
    <PageContainer title="预测数据接入" subTitle="源库连接、数据流、字段映射与首次同步">
      <Alert
        showIcon
        type="info"
        title="Forecast 不复制数据库密码；源库只读扫描，标准事件和预测结果写入独立 PostgreSQL 决策库。"
        style={{ marginBottom: 16 }}
      />
      <ProTable<ForecastProfile>
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={false}
        scroll={{ x: 980 }}
        pagination={{ defaultPageSize: 20 }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditingProfile(undefined);
              setProfileModalOpen(true);
            }}
          >
            新建 Profile
          </Button>,
        ]}
        request={async (params) => {
          try {
            const response = await getForecastProfiles({
              pageNum: params.current,
              pageSize: params.pageSize,
            });
            const page = unwrapForecastData<{ list: ForecastProfile[]; total: number }>(response);
            return { data: page?.list || [], total: page?.total || 0, success: true };
          } catch (error: unknown) {
            message.error(getForecastErrorMessage(error, 'Profile 列表加载失败'));
            return { data: [], total: 0, success: false };
          }
        }}
      />

      {selectedProfile && (
        <Card
          title={`数据流 · ${selectedProfile.name}`}
          style={{ marginTop: 16 }}
          extra={
            <Button icon={<PlusOutlined />} onClick={() => setStreamModalOpen(true)}>
              新增数据流
            </Button>
          }
        >
          <Descriptions
            size="small"
            column={3}
            style={{ marginBottom: 16 }}
            items={[
              { key: 'timezone', label: '时区', children: selectedProfile.timeZone },
              {
                key: 'sync',
                label: '同步计划',
                children: formatForecastSchedule(selectedProfile.syncCron),
              },
              {
                key: 'forecast',
                label: '预测计划',
                children: formatForecastSchedule(selectedProfile.forecastCron),
              },
            ]}
          />
          {health?.workerHealthy === false && (
            <Alert
              showIcon
              type={hasPendingActivation ? 'error' : 'warning'}
              title="Forecast Worker 当前离线"
              description={
                hasPendingActivation
                  ? '已有首次同步任务停留在 QUEUED；请启动 Worker 后等待页面自动刷新，活动映射只会在同步和预测全部成功后切换。'
                  : '请先启动 Worker 再提交首次同步，否则任务无法被接单。'
              }
              action={
                <Button
                  size="small"
                  onClick={() => history.push(`/forecast/runs?profileId=${selectedProfile.id}`)}
                >
                  查看运行中心
                </Button>
              }
              style={{ marginBottom: 16 }}
            />
          )}
          <ProTable<ForecastStream>
            rowKey="id"
            search={false}
            options={false}
            pagination={false}
            columns={streamColumns}
            dataSource={selectedProfile.streams || []}
            scroll={{ x: 1100 }}
          />
        </Card>
      )}

      <ForecastProfileModal
        open={profileModalOpen}
        profile={editingProfile}
        onOpenChange={setProfileModalOpen}
        onSaved={() => {
          setProfileModalOpen(false);
          actionRef.current?.reload();
          if (editingProfile) reloadProfileDetail(editingProfile.id);
        }}
      />
      <ModalForm<{ name: string; enabled: boolean }>
        title="新增数据流"
        open={streamModalOpen}
        onOpenChange={setStreamModalOpen}
        modalProps={{ destroyOnHidden: true }}
        initialValues={{ enabled: true }}
        onFinish={async (values) => {
          if (!selectedProfile) return false;
          try {
            await createForecastStream(
              selectedProfile.id,
              values,
              createForecastIdempotencyKey(`stream-create-${selectedProfile.id}`),
            );
            message.success('数据流已创建');
            await reloadProfileDetail(selectedProfile.id);
            return true;
          } catch (error: unknown) {
            message.error(getForecastErrorMessage(error, '数据流创建失败'));
            return false;
          }
        }}
      >
        <ProFormText name="name" label="数据流名称" rules={[{ required: true }]} />
        <ProFormSwitch name="enabled" label="启用" />
      </ModalForm>
      <ForecastMappingDrawer
        open={mappingOpen}
        profile={selectedProfile}
        stream={selectedStream}
        workerHealthy={health?.workerHealthy}
        onClose={() => setMappingOpen(false)}
        onChanged={() => reloadProfileDetail(selectedProfile?.id)}
      />
    </PageContainer>
  );
};

export default ForecastSources;
