/**
 * Forecast 数据接入页面。
 *
 * 职责：管理 Profile、复用数据库连接、创建多数据流，并进入字段映射/预览/发布/首次同步闭环。
 * 并发说明：所有提交按钮使用 loading；表格请求由 ProTable 受控分页，不执行逐字搜索请求。
 */
import ForecastMappingWorkspace from '../components/ForecastMappingDrawer';
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
import {
  DatabaseOutlined,
  EditOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  SettingOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import type { ProColumns } from '@ant-design/pro-components';
import { ModalForm, ProFormSwitch, ProFormText, ProTable } from '@ant-design/pro-components';
import {
  Alert,
  Badge,
  Button,
  Empty,
  message,
  Pagination,
  Popconfirm,
  Progress,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import styles from './style.less';

const ACTIVATION_STATUS_COLOR: Record<ForecastJobStatus, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  CANCELLING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
};
const ACTIVATION_POLL_INTERVAL_MS = 5000;
const PROFILE_PAGE_SIZE = 20;

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
  const [profiles, setProfiles] = useState<ForecastProfile[]>([]);
  const [profilesLoading, setProfilesLoading] = useState(false);
  const [profilePage, setProfilePage] = useState(1);
  const [profileTotal, setProfileTotal] = useState(0);
  const [profileModalOpen, setProfileModalOpen] = useState(false);
  const [editingProfile, setEditingProfile] = useState<ForecastProfile>();
  const [selectedProfile, setSelectedProfile] = useState<ForecastProfile>();
  const [streamModalOpen, setStreamModalOpen] = useState(false);
  const [mappingOpen, setMappingOpen] = useState(false);
  const [selectedStreamId, setSelectedStreamId] = useState<number>();
  const [health, setHealth] = useState<ForecastHealth>();
  const [disableLoading, setDisableLoading] = useState<number>();
  // Profile 列表与详情分别维护请求代次，防止分页、刷新和快速切换产生的旧响应覆盖新状态。
  const profileListRequestVersion = useRef(0);
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

  /**
   * 加载一个 Profile 分页，并选中指定项或当前页首项。
   *
   * @param page 目标页码。
   * @param preferredProfileId 刷新后优先保持选中的 Profile ID。
   * @returns 列表与首个可用详情均完成刷新后结束。
   * @throws 不向调用方抛出异常；请求失败展示统一错误提示。
   */
  const loadProfiles = useCallback(
    async (page = 1, preferredProfileId?: number) => {
      const requestVersion = ++profileListRequestVersion.current;
      setProfilesLoading(true);
      try {
        const response = await getForecastProfiles({ pageNum: page, pageSize: PROFILE_PAGE_SIZE });
        const result = unwrapForecastData<{ list: ForecastProfile[]; total: number }>(response);
        if (requestVersion !== profileListRequestVersion.current) return;

        const nextProfiles = result?.list || [];
        const nextSelected =
          nextProfiles.find((item) => item.id === preferredProfileId) || nextProfiles[0];
        setProfiles(nextProfiles);
        setProfilePage(page);
        setProfileTotal(result?.total || 0);
        setMappingOpen(false);
        setSelectedStreamId(undefined);
        setHealth(undefined);
        setSelectedProfile(nextSelected);
        if (nextSelected) await reloadProfileDetail(nextSelected.id);
      } catch (error: unknown) {
        if (requestVersion !== profileListRequestVersion.current) return;
        message.error(getForecastErrorMessage(error, 'Profile 列表加载失败'));
      } finally {
        if (requestVersion === profileListRequestVersion.current) setProfilesLoading(false);
      }
    },
    [reloadProfileDetail],
  );

  /** 切换 Profile 时关闭子级映射工作区，并加载与新选择匹配的最新详情。 */
  const selectProfile = useCallback(
    (profile: ForecastProfile) => {
      if (profile.id === selectedProfile?.id && !mappingOpen) return;
      setMappingOpen(false);
      setSelectedStreamId(undefined);
      setHealth(undefined);
      setSelectedProfile(profile);
      void reloadProfileDetail(profile.id);
    },
    [mappingOpen, reloadProfileDetail, selectedProfile?.id],
  );

  useEffect(() => {
    void loadProfiles(1);
  }, [loadProfiles]);

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
      // 组件卸载后让所有未完成列表与详情请求失效，避免异步回写已释放页面。
      profileListRequestVersion.current += 1;
      detailRequestVersion.current += 1;
    },
    [],
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

  /** 停用 Profile，并在写操作完成后同步刷新主列表与当前详情。 */
  const disableProfile = async (profile: ForecastProfile) => {
    setDisableLoading(profile.id);
    try {
      await disableForecastProfile(
        profile.id,
        profile.lockVersion,
        createForecastIdempotencyKey(`profile-disable-${profile.id}`),
      );
      message.success('Profile 已停用');
      await loadProfiles(profilePage, profile.id);
    } catch (error: unknown) {
      message.error(getForecastErrorMessage(error, '停用失败'));
    } finally {
      setDisableLoading(undefined);
    }
  };

  return (
    <div className={styles.sourcesPage}>
      <div className={styles.sourcesWorkspace}>
        <aside className={styles.profilePane} aria-label="Profile 列表">
          <div className={styles.profilePaneHeader}>
            <Typography.Title level={5}>Profiles</Typography.Title>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditingProfile(undefined);
                setProfileModalOpen(true);
              }}
            >
              新建 Profile
            </Button>
          </div>
          <div className={styles.profileList}>
            {profilesLoading && profiles.length === 0 ? (
              <div className={styles.profileLoading}>
                <Spin />
              </div>
            ) : profiles.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Profile" />
            ) : (
              profiles.map((profile) => {
                const active = selectedProfile?.id === profile.id;
                return (
                  <div
                    key={profile.id}
                    className={[styles.profileItem, active && styles.profileItemActive]
                      .filter(Boolean)
                      .join(' ')}
                  >
                    <button
                      type="button"
                      className={styles.profileSelection}
                      aria-pressed={active}
                      onClick={() => selectProfile(profile)}
                    >
                      <Typography.Text strong ellipsis={{ tooltip: profile.name }}>
                        {profile.name}
                      </Typography.Text>
                      <Typography.Text type="secondary" ellipsis>
                        源库：{profile.sourceDatabaseName || '-'}
                      </Typography.Text>
                      <span className={styles.profileMeta}>
                        <Tag color={profile.enabled ? 'success' : 'default'}>
                          {profile.enabled ? '启用' : '停用'}
                        </Tag>
                        <Typography.Text type="secondary">
                          最近同步：{formatTime(profile.lastSyncAt)}
                        </Typography.Text>
                      </span>
                    </button>
                    <div className={styles.profileActions}>
                      <Tooltip title="管理数据流">
                        <Button
                          type="text"
                          size="small"
                          icon={<DatabaseOutlined />}
                          aria-label={'管理 ' + profile.name + ' 的数据流'}
                          onClick={() => selectProfile(profile)}
                        />
                      </Tooltip>
                      <Tooltip title="编辑 Profile">
                        <Button
                          type="text"
                          size="small"
                          icon={<EditOutlined />}
                          aria-label={'编辑 ' + profile.name}
                          onClick={() => {
                            setEditingProfile(profile);
                            setProfileModalOpen(true);
                          }}
                        />
                      </Tooltip>
                      <Popconfirm
                        title="停用后定时任务将不再创建，确认继续？"
                        disabled={!profile.enabled}
                        onConfirm={() => disableProfile(profile)}
                      >
                        <Tooltip title={profile.enabled ? '停用 Profile' : 'Profile 已停用'}>
                          <Button
                            type="text"
                            size="small"
                            danger
                            disabled={!profile.enabled}
                            loading={disableLoading === profile.id}
                            icon={<StopOutlined />}
                            aria-label={'停用 ' + profile.name}
                          />
                        </Tooltip>
                      </Popconfirm>
                    </div>
                  </div>
                );
              })
            )}
          </div>
          <div className={styles.profilePagination}>
            <Pagination
              size="small"
              current={profilePage}
              pageSize={PROFILE_PAGE_SIZE}
              total={profileTotal}
              showSizeChanger={false}
              showTotal={(total, range) =>
                '第 ' + range[0] + '-' + range[1] + ' 条/总共 ' + total + ' 条'
              }
              onChange={(page) => void loadProfiles(page)}
            />
          </div>
        </aside>

        <section className={styles.detailPane} aria-label="Profile 详情">
          {!selectedProfile ? (
            <Empty
              className={styles.detailEmpty}
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="请选择或创建 Profile"
            />
          ) : mappingOpen && selectedStream ? (
            <ForecastMappingWorkspace
              open
              profile={selectedProfile}
              stream={selectedStream}
              workerHealthy={health?.workerHealthy}
              onClose={() => setMappingOpen(false)}
              onChanged={() => reloadProfileDetail(selectedProfile.id)}
            />
          ) : (
            <div className={styles.streamsView}>
              <div className={styles.detailHeader}>
                <div className={styles.detailTitle}>
                  <Typography.Title level={4}>{selectedProfile.name}</Typography.Title>
                  <Tag color={selectedProfile.enabled ? 'success' : 'default'}>
                    {selectedProfile.enabled ? '启用' : '停用'}
                  </Tag>
                  <Badge
                    status={
                      health?.workerHealthy === undefined
                        ? 'processing'
                        : health.workerHealthy
                        ? 'success'
                        : 'error'
                    }
                    text={
                      health?.workerHealthy === undefined
                        ? '状态检测中'
                        : health.workerHealthy
                        ? '运行正常'
                        : 'Worker 离线'
                    }
                  />
                </div>
                <Space className={styles.detailActions} size={8}>
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => setStreamModalOpen(true)}
                  >
                    新增数据流
                  </Button>
                  <Tooltip title="刷新 Profile">
                    <Button
                      icon={<ReloadOutlined />}
                      aria-label="刷新 Profile"
                      onClick={() => void reloadProfileDetail(selectedProfile.id)}
                    />
                  </Tooltip>
                  <Tooltip title="编辑 Profile">
                    <Button
                      icon={<SettingOutlined />}
                      aria-label="编辑 Profile"
                      onClick={() => {
                        setEditingProfile(selectedProfile);
                        setProfileModalOpen(true);
                      }}
                    />
                  </Tooltip>
                </Space>
              </div>

              <div className={styles.profileSummary}>
                {[
                  ['源数据库', selectedProfile.sourceDatabaseName || '-'],
                  ['决策库', selectedProfile.decisionDatabaseName || '-'],
                  ['时区', selectedProfile.timeZone],
                  ['同步计划', formatForecastSchedule(selectedProfile.syncCron)],
                  ['预测计划', formatForecastSchedule(selectedProfile.forecastCron)],
                  ['历史窗口', selectedProfile.historyDays + ' 天'],
                ].map(([label, value]) => (
                  <div className={styles.summaryItem} key={label}>
                    <Typography.Text type="secondary">{label}</Typography.Text>
                    <Typography.Text ellipsis={{ tooltip: value }}>{value}</Typography.Text>
                  </div>
                ))}
              </div>

              {health?.workerHealthy === false && (
                <Alert
                  className={styles.workerNotice}
                  showIcon
                  type={hasPendingActivation ? 'error' : 'warning'}
                  title="Forecast Worker 当前离线"
                  description={
                    hasPendingActivation
                      ? '已有首次同步任务停留在 QUEUED；Worker 恢复后页面会自动刷新，活动映射仅在同步和预测全部成功后切换。'
                      : '请先启动 Worker 再提交首次同步，否则任务无法被接单。'
                  }
                  action={
                    <Button
                      size="small"
                      onClick={() => history.push('/forecast/runs?profileId=' + selectedProfile.id)}
                    >
                      查看运行中心
                    </Button>
                  }
                />
              )}

              <div className={styles.streamsSection}>
                <div className={styles.sectionHeading}>
                  <Typography.Text strong>数据流管理</Typography.Text>
                  <Tooltip
                    title="源库只读扫描，凭据不复制；标准事件与预测结果写入独立决策库。"
                    trigger={['hover', 'focus']}
                  >
                    <Button
                      className={styles.helpButton}
                      type="text"
                      size="small"
                      shape="circle"
                      icon={<QuestionCircleOutlined />}
                      aria-label="查看数据流安全说明"
                      title="查看数据流安全说明"
                    />
                  </Tooltip>
                </div>
                <ProTable<ForecastStream>
                  className={styles.streamsTable}
                  rowKey="id"
                  search={false}
                  options={false}
                  pagination={false}
                  columns={streamColumns}
                  dataSource={selectedProfile.streams || []}
                  scroll={{ x: 980 }}
                  toolBarRender={false}
                />
              </div>
            </div>
          )}
        </section>
      </div>

      <ForecastProfileModal
        open={profileModalOpen}
        profile={editingProfile}
        onOpenChange={setProfileModalOpen}
        onSaved={() => {
          setProfileModalOpen(false);
          void loadProfiles(profilePage, editingProfile?.id || selectedProfile?.id);
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
    </div>
  );
};

export default ForecastSources;
