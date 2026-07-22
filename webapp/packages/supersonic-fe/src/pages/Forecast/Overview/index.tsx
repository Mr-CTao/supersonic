/**
 * 出入库预测看板页面。
 *
 * 职责：按方向、指标和三种时间基准展示一致性快照，包括可自定义训练区间、日期上下文、
 * KPI、实际/预测趋势、经验区间、仓库方向拆分和独立的系统/业务数据新鲜度。
 * 并发说明：请求版本号阻止旧请求覆盖新筛选；历史回测按钮使用 loading 锁；仅正式跟随数据
 * 模式轮询，避免重复执行高成本预览计算；所有定时器和通知在卸载时清理。
 */
import ForecastAnchorBar from './ForecastAnchorBar';
import ForecastTrendChart from '../components/ForecastTrendChart';
import { buildForecastTrendWindow } from '../components/forecastTrendChartUtils';
import {
  getForecastErrorMessage,
  getForecastHealth,
  getForecastOverviewSnapshot,
  getForecastProfiles,
  unwrapForecastData,
} from '@/services/forecast';
import type {
  ForecastAnchorMode,
  ForecastBreakdown,
  ForecastDirection,
  ForecastHealth,
  ForecastMetric,
  ForecastOverviewSnapshot,
  ForecastProfile,
} from '@/services/forecast';
import { QuestionCircleOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Drawer,
  Empty,
  message as antdMessage,
  notification as antdNotification,
  Row,
  Segmented,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type ForecastTrainingRange,
  formatForecastDateRange,
  getBusinessDataFreshness,
  getForecastBiasDirection,
  getRecommendedBacktestStart,
  getTrainingRangeValidationMessage,
  summarizeForecastAlgorithms,
} from './forecastOverview';
import styles from './style.less';

const HORIZONS = [7, 14, 30];
const DASHBOARD_REFRESH_INTERVAL_MS = 30_000;
const STALE_SYNC_NOTIFICATION_KEY = 'forecast-overview-stale-sync-notification';
const STALE_SYNC_NOTIFICATION_DURATION_SECONDS = 3;
const STALE_SYNC_NOTIFICATION_TOP_MARGIN_PX = 48;

type ForecastDirectionScope = 'ALL' | ForecastDirection;

const DIRECTION_SCOPE_LABELS: Record<ForecastDirectionScope, string> = {
  ALL: '入库 + 出库',
  INBOUND: '仅入库',
  OUTBOUND: '仅出库',
};

const HELP_TOOLTIP_MAX_WIDTH_PX = 420;

const ALGORITHM_HELP_CONTENT = (
  <div className={styles.termHelpContent}>
    <div>系统会把几种预测方法放到历史数据上试算，并选择误差较小的一种。</div>
    <div className={styles.termHelpRow}>
      <strong>上周同日参考</strong>（SEASONAL_NAIVE_7）：直接参考 7
      天前同一天的数据，适合每周规律明显的业务。
    </div>
    <div className={styles.termHelpRow}>
      <strong>移动平均</strong>（MOVING_AVERAGE_7/14/28）：用最近 7、14 或 28
      天的平均值预测；天数越多，结果越平稳。
    </div>
    <div className={styles.termHelpRow}>
      <strong>指数平滑</strong>
      （SIMPLE_EXPONENTIAL_SMOOTHING_02/04/06/08）：综合过去数据并更重视近期变化；末尾数字越大，对最新数据越敏感。
    </div>
  </div>
);

const WAPE_HELP_CONTENT = (
  <div className={styles.termHelpContent}>
    <div>表示“预测总体差了多少”，越接近 0% 越准确。</div>
    <div className={styles.termHelpRow}>
      例如 WAPE 为 20%，可以理解为总体误差约占实际总量的 20%。
    </div>
    <div className={styles.termHelpRow}>
      模型 WAPE 来自训练阶段的历史试算；回测 WAPE 来自所选历史回测区间。
    </div>
  </div>
);

const BIAS_HELP_CONTENT = (
  <div className={styles.termHelpContent}>
    <div>表示预测整体是偏高还是偏低，0% 最理想。</div>
    <div className={styles.termHelpRow}>
      正数表示总体预测偏高，负数表示总体预测偏低；绝对值越大，偏离越明显。
    </div>
  </div>
);

type ForecastTermWithHelpProps = {
  label: string;
  help: React.ReactNode;
};

/**
 * 渲染支持鼠标悬停和键盘聚焦的预测术语帮助入口。
 *
 * @param props label 为界面术语，help 为对应的通俗说明。
 * @returns 带问号图标和 Tooltip 的紧凑标题。
 * @throws 不主动抛出异常；Tooltip 内容由静态可信文案构成。
 */
const ForecastTermWithHelp: React.FC<ForecastTermWithHelpProps> = ({ label, help }) => (
  <Space size={4}>
    <span>{label}</span>
    <Tooltip title={help} overlayStyle={{ maxWidth: HELP_TOOLTIP_MAX_WIDTH_PX }}>
      <QuestionCircleOutlined
        aria-label={`查看${label}说明`}
        tabIndex={0}
        className={styles.termHelpIcon}
      />
    </Tooltip>
  </Space>
);

/** 格式化可空数值，避免 NaN 进入图表和 KPI。 */
const formatNumber = (value?: number) =>
  new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value ?? 0));

/**
 * 渲染预测看板。
 *
 * @returns Forecast 看板页面。
 * @throws 不主动抛出异常；请求失败通过 message 和空状态呈现。
 */
const ForecastOverview: React.FC = () => {
  // 使用页面级 Context API，既继承 Ant Design 主题上下文，也避免静态提示方法的控制台告警。
  const [messageApi, messageContextHolder] = antdMessage.useMessage();
  const [notificationApi, notificationContextHolder] = antdNotification.useNotification();
  const [profiles, setProfiles] = useState<ForecastProfile[]>([]);
  const [profileId, setProfileId] = useState<number>();
  const [horizon, setHorizon] = useState(7);
  const [metric, setMetric] = useState<ForecastMetric>('QUANTITY');
  const [directionScope, setDirectionScope] = useState<ForecastDirectionScope>('ALL');
  const [anchorMode, setAnchorMode] = useState<ForecastAnchorMode>('LATEST_DATA');
  const [forecastStartDate, setForecastStartDate] = useState<Dayjs>();
  const [backtestRequestDate, setBacktestRequestDate] = useState<Dayjs>();
  const [trainingRangeOverride, setTrainingRangeOverride] = useState<ForecastTrainingRange>();
  const [snapshot, setSnapshot] = useState<ForecastOverviewSnapshot>();
  const [health, setHealth] = useState<ForecastHealth>();
  const [loading, setLoading] = useState(false);
  const [breakdownDrawerOpen, setBreakdownDrawerOpen] = useState(false);
  const requestVersion = useRef(0);
  const staleSyncNotificationShown = useRef(false);

  /** 加载可见 Profile，并默认选中首项。 */
  const loadProfiles = useCallback(async () => {
    try {
      const response = await getForecastProfiles({ pageNum: 1, pageSize: 100 });
      const page = unwrapForecastData<{ list?: ForecastProfile[] }>(response);
      const available = page?.list || [];
      setProfiles(available);
      setProfileId((current) => current ?? available[0]?.id);
    } catch (error: unknown) {
      messageApi.error(getForecastErrorMessage(error, '预测 Profile 加载失败'));
    }
  }, [messageApi]);

  /** 请求一次同版本看板快照和系统健康状态。 */
  const loadDashboard = useCallback(
    async (mode: ForecastAnchorMode, startDate?: Dayjs, trainingRange?: ForecastTrainingRange) => {
      if (!profileId) return;
      const version = ++requestVersion.current;
      setLoading(true);
      try {
        const params: Record<string, unknown> = {
          profileId,
          horizon,
          metric,
          anchorMode: mode,
        };
        if (directionScope !== 'ALL') {
          params.direction = directionScope;
        }
        if (mode === 'BACKTEST' && startDate) {
          params.forecastStartDate = startDate.format('YYYY-MM-DD');
        }
        if (trainingRange) {
          params.trainingStartDate = trainingRange[0].format('YYYY-MM-DD');
          params.trainingEndDate = trainingRange[1].format('YYYY-MM-DD');
        }
        const [snapshotResp, healthResp] = await Promise.all([
          getForecastOverviewSnapshot(params),
          getForecastHealth(profileId),
        ]);
        if (version !== requestVersion.current) return;
        setSnapshot(unwrapForecastData<ForecastOverviewSnapshot>(snapshotResp));
        setHealth(unwrapForecastData<ForecastHealth>(healthResp));
      } catch (error: unknown) {
        if (version === requestVersion.current) {
          messageApi.error(getForecastErrorMessage(error, '预测看板加载失败'));
        }
      } finally {
        if (version === requestVersion.current) setLoading(false);
      }
    },
    [directionScope, horizon, messageApi, metric, profileId],
  );

  useEffect(() => {
    loadProfiles();
  }, [loadProfiles]);

  useEffect(() => {
    const requestedDate = anchorMode === 'BACKTEST' ? backtestRequestDate : undefined;
    if (anchorMode === 'BACKTEST' && !requestedDate) return undefined;
    loadDashboard(anchorMode, requestedDate, trainingRangeOverride);
    const canPollPublishedSnapshot =
      anchorMode === 'LATEST_DATA' && directionScope === 'ALL' && !trainingRangeOverride;
    if (!canPollPublishedSnapshot) return undefined;
    const timer = window.setInterval(
      () => loadDashboard('LATEST_DATA'),
      DASHBOARD_REFRESH_INTERVAL_MS,
    );
    return () => {
      window.clearInterval(timer);
      requestVersion.current += 1;
    };
  }, [anchorMode, backtestRequestDate, directionScope, loadDashboard, trainingRangeOverride]);

  useEffect(() => {
    if (health?.freshnessStatus !== 'STALE' || staleSyncNotificationShown.current) return;
    // 轮询可能持续返回 STALE；组件生命周期内只提示一次，避免遮挡基准设置。
    staleSyncNotificationShown.current = true;
    notificationApi.warning({
      key: STALE_SYNC_NOTIFICATION_KEY,
      title: '同步已延迟',
      description: '请到运行中心检查同步任务；业务数据日期请以预测基准状态条为准。',
      duration: STALE_SYNC_NOTIFICATION_DURATION_SECONDS,
      placement: 'topRight',
      style: { marginTop: STALE_SYNC_NOTIFICATION_TOP_MARGIN_PX },
    });
  }, [health?.freshnessStatus, notificationApi]);

  useEffect(
    () => () => {
      notificationApi.destroy(STALE_SYNC_NOTIFICATION_KEY);
      requestVersion.current += 1;
    },
    [notificationApi],
  );

  /** 切换 Profile 时回到安全默认模式，避免沿用另一数据集的历史日期。 */
  const handleProfileChange = (value: number) => {
    staleSyncNotificationShown.current = false;
    setAnchorMode('LATEST_DATA');
    setForecastStartDate(undefined);
    setBacktestRequestDate(undefined);
    setTrainingRangeOverride(undefined);
    setSnapshot(undefined);
    setBreakdownDrawerOpen(false);
    setProfileId(value);
  };

  /** 切换预测天数，并在回测模式下同步选择最近完整窗口。 */
  const handleHorizonChange = (value: number) => {
    if (anchorMode !== 'BACKTEST') {
      setHorizon(value);
      return;
    }
    const recommended = getRecommendedBacktestStart(
      snapshot?.latestActualDate,
      value,
      snapshot?.dataStartDate,
    );
    if (!recommended) {
      messageApi.warning(`当前业务数据无法同时满足至少 14 天训练和 ${value} 天完整回测`);
      return;
    }
    setHorizon(value);
    setForecastStartDate(recommended);
    setBacktestRequestDate(recommended);
    setTrainingRangeOverride(undefined);
  };

  /** 切换预测基准；进入回测时自动展示最近一个完整可验证窗口。 */
  const handleAnchorModeChange = (mode: ForecastAnchorMode) => {
    if (mode === 'BACKTEST') {
      const recommended = getRecommendedBacktestStart(
        snapshot?.latestActualDate,
        horizon,
        snapshot?.dataStartDate,
      );
      if (!recommended) {
        messageApi.warning('当前 Profile 尚无可用于历史回测的完整业务数据');
        return;
      }
      setForecastStartDate(recommended);
      setBacktestRequestDate(recommended);
    } else {
      setForecastStartDate(undefined);
      setBacktestRequestDate(undefined);
    }
    // 不同基准拥有不同预测起点；确认能够切换后再恢复自动窗口，避免旧截止日越过新起点。
    setTrainingRangeOverride(undefined);
    setAnchorMode(mode);
  };

  /** 切换方向时回到跟随数据并重取该方向自己的日期边界。 */
  const handleDirectionScopeChange = (scope: ForecastDirectionScope) => {
    setDirectionScope(scope);
    setAnchorMode('LATEST_DATA');
    setForecastStartDate(undefined);
    setBacktestRequestDate(undefined);
    setTrainingRangeOverride(undefined);
    setSnapshot(undefined);
    setBreakdownDrawerOpen(false);
  };

  /** 校验并应用自定义训练区间；回测草稿日期与区间作为同一请求一起生效。 */
  const handleTrainingRangeChange = (range?: ForecastTrainingRange) => {
    if (!range) return;
    let effectiveForecastStart = forecastStartDate;
    if (anchorMode !== 'BACKTEST') {
      effectiveForecastStart = snapshot?.forecastStartDate
        ? dayjs(snapshot.forecastStartDate)
        : undefined;
    }
    const validationMessage = getTrainingRangeValidationMessage(
      range,
      snapshot,
      effectiveForecastStart,
    );
    if (validationMessage) {
      messageApi.warning(validationMessage);
      return;
    }
    setTrainingRangeOverride(range);
    if (anchorMode === 'BACKTEST' && forecastStartDate) {
      setBacktestRequestDate(forecastStartDate);
    }
  };

  /** 恢复 Profile 自动训练天数，并立即刷新当前基准快照。 */
  const handleResetTrainingRange = () => {
    setTrainingRangeOverride(undefined);
    if (anchorMode === 'BACKTEST' && forecastStartDate) {
      setBacktestRequestDate(forecastStartDate);
    }
  };

  /** 提交历史回测；相同日期也允许显式刷新，但 loading 期间按钮会被锁定。 */
  const handleGenerateBacktest = () => {
    if (!forecastStartDate || loading) return;
    if (backtestRequestDate?.isSame(forecastStartDate, 'day')) {
      loadDashboard('BACKTEST', forecastStartDate, trainingRangeOverride);
      return;
    }
    setBacktestRequestDate(forecastStartDate);
  };

  const columns: ColumnsType<ForecastBreakdown> = useMemo(
    () => [
      { title: '仓库', dataIndex: 'warehouseCode', width: 110 },
      {
        title: '方向',
        dataIndex: 'direction',
        width: 90,
        render: (value) => (
          <Tag color={value === 'INBOUND' ? 'blue' : 'orange'}>
            {value === 'INBOUND' ? '入库' : '出库'}
          </Tag>
        ),
      },
      {
        title: `${horizon}天预测`,
        dataIndex: 'predictedTotal',
        align: 'right',
        width: 120,
        render: formatNumber,
      },
      {
        title: <ForecastTermWithHelp label="算法" help={ALGORITHM_HELP_CONTENT} />,
        dataIndex: 'algorithm',
        width: 210,
        render: (value) => {
          if (!value) return '-';
          const algorithm = summarizeForecastAlgorithms(value);
          return (
            <Tooltip title={`系统算法标识：${value}`}>
              <Tag className={styles.tableAlgorithmTag}>{algorithm.label}</Tag>
            </Tooltip>
          );
        },
      },
      {
        title: (
          <ForecastTermWithHelp
            label={anchorMode === 'BACKTEST' ? '回测 WAPE' : '模型 WAPE'}
            help={WAPE_HELP_CONTENT}
          />
        ),
        dataIndex: 'wape',
        width: 130,
        render: (value) => (value == null ? '-' : `${(value * 100).toFixed(2)}%`),
      },
      {
        title: <ForecastTermWithHelp label="Bias" help={BIAS_HELP_CONTENT} />,
        dataIndex: 'bias',
        width: 110,
        render: (value) => (value == null ? '-' : `${(value * 100).toFixed(2)}%`),
      },
    ],
    [anchorMode, horizon],
  );

  const isBacktest = snapshot?.actualComparisonType === 'FORECAST_PERIOD';
  const forecastRange = formatForecastDateRange(
    snapshot?.forecastStartDate,
    snapshot?.forecastEndDate,
  );
  const actualRange = formatForecastDateRange(snapshot?.actualStartDate, snapshot?.actualEndDate);
  const trainingRange = formatForecastDateRange(
    snapshot?.trainingStartDate,
    snapshot?.trainingEndDate,
  );
  const trendWindow = buildForecastTrendWindow(snapshot?.series ?? [], snapshot?.forecastStartDate);
  const trendWindowHint =
    trainingRange !== '-' && trendWindow.historyPointCount
      ? `模型训练区间：${trainingRange}；图表仅展示训练区间末 ${trendWindow.historyPointCount} 天`
      : undefined;
  const businessFreshness = getBusinessDataFreshness(snapshot?.businessDataLagDays);
  const breakdownData = useMemo(() => {
    return [...(snapshot?.breakdown ?? [])].sort((left, right) => {
      if (left.wape == null) return right.wape == null ? 0 : 1;
      if (right.wape == null) return -1;
      return right.wape - left.wape;
    });
  }, [snapshot?.breakdown]);
  const algorithmSummary = useMemo(
    () =>
      summarizeForecastAlgorithms(
        snapshot?.algorithm,
        snapshot?.breakdown?.map((item) => item.algorithm),
      ),
    [snapshot?.algorithm, snapshot?.breakdown],
  );
  const algorithmRawTitle = algorithmSummary.rawNames.length
    ? `系统算法标识：${algorithmSummary.rawNames.join('、')}`
    : '暂无可用算法';
  const wapeValue = snapshot?.wape == null ? '-' : `${(snapshot.wape * 100).toFixed(2)}%`;
  const biasValue = snapshot?.bias == null ? '-' : `${(snapshot.bias * 100).toFixed(2)}%`;
  const biasDirection = getForecastBiasDirection(snapshot?.bias);

  return (
    <div className={styles.overviewPage}>
      {messageContextHolder}
      {notificationContextHolder}
      <Space wrap className={styles.primaryFilters}>
        <Select
          className={styles.profileSelect}
          placeholder="选择预测 Profile"
          value={profileId}
          options={profiles.map((item) => ({ value: item.id, label: item.name }))}
          onChange={handleProfileChange}
        />
        <Segmented
          aria-label="选择预测天数"
          options={HORIZONS.map((value) => ({ label: `${value}天`, value }))}
          value={horizon}
          onChange={(value) => handleHorizonChange(Number(value))}
        />
        <Segmented
          aria-label="选择预测指标"
          options={[
            { label: '数量', value: 'QUANTITY' },
            { label: '任务数', value: 'TASK_COUNT' },
          ]}
          value={metric}
          onChange={(value) => setMetric(value as ForecastMetric)}
        />
        <Segmented
          aria-label="选择出入库统计口径"
          options={[
            { label: '全部', value: 'ALL' },
            { label: '入库', value: 'INBOUND' },
            { label: '出库', value: 'OUTBOUND' },
          ]}
          value={directionScope}
          onChange={(value) => handleDirectionScopeChange(value as ForecastDirectionScope)}
        />
        <Space size={6} className={styles.primaryStatusTags} aria-label="预测运行状态">
          <Tag color={health?.workerHealthy ? 'success' : 'error'}>
            {health?.workerHealthy ? 'Worker 正常' : 'Worker 离线'}
          </Tag>
          <Tag color={health?.freshnessStatus === 'FRESH' ? 'success' : 'warning'}>
            {health?.freshnessStatus === 'FRESH' ? '同步正常' : '同步延迟'}
          </Tag>
          <Tag color={businessFreshness.color}>{businessFreshness.label}</Tag>
        </Space>
      </Space>

      <ForecastAnchorBar
        anchorMode={anchorMode}
        forecastStartDate={forecastStartDate}
        trainingRangeOverride={trainingRangeOverride}
        horizon={horizon}
        snapshot={snapshot}
        loading={loading}
        onAnchorModeChange={handleAnchorModeChange}
        onForecastStartDateChange={setForecastStartDate}
        onTrainingRangeChange={handleTrainingRangeChange}
        onResetTrainingRange={handleResetTrainingRange}
        onGenerateBacktest={handleGenerateBacktest}
      />

      {anchorMode === 'TODAY' && Number(snapshot?.businessDataLagDays ?? 0) > 1 && (
        <Alert
          showIcon
          type="warning"
          title={`业务数据仅更新到 ${snapshot?.latestActualDate}，从今天预测的可靠性较低`}
          description="建议切换到“跟随数据”，或选择历史日期查看可与实际数据对比的回测结果。"
          className={styles.statusAlert}
        />
      )}
      {snapshot?.dataStatus === 'INSUFFICIENT_DATA' && (
        <Alert
          showIcon
          type="warning"
          title="历史数据少于 14 天，暂不生成预测"
          className={styles.statusAlert}
        />
      )}
      {snapshot?.dataStatus === 'LOW_CONFIDENCE' && (
        <Alert
          showIcon
          type="info"
          title="历史数据不足 28 天，当前使用低置信度 7 日移动平均"
          className={styles.statusAlert}
        />
      )}

      <Spin spinning={loading}>
        <Row className={styles.dashboardGrid} gutter={[16, 16]}>
          <Col xs={24} lg={12} xxl={6}>
            <Card className={styles.summaryCard}>
              <Statistic
                title={`${isBacktest ? '回测预测总量' : '预测总量'}（${forecastRange}）`}
                value={formatNumber(snapshot?.predictedTotal)}
                suffix={metric === 'QUANTITY' ? '数量' : '任务'}
              />
            </Card>
          </Col>
          <Col xs={24} lg={12} xxl={6}>
            <Card className={styles.summaryCard}>
              <Statistic
                title={`${isBacktest ? '同期实际总量' : '前一周期实际'}（${actualRange}）`}
                value={formatNumber(snapshot?.actualTotal)}
              />
            </Card>
          </Col>
          <Col xs={24} lg={24} xxl={12}>
            <Card className={styles.summaryCard}>
              <div className={styles.performanceHeader}>
                <span className={styles.performanceTitle}>
                  {isBacktest ? '回测表现' : '模型表现'}
                </span>
                {breakdownData.length > 0 && (
                  <Button type="link" size="small" onClick={() => setBreakdownDrawerOpen(true)}>
                    查看明细（{breakdownData.length}）
                  </Button>
                )}
              </div>
              <div className={styles.performanceGrid}>
                <div className={styles.performanceMetric}>
                  <div className={styles.performanceLabel}>
                    <ForecastTermWithHelp label="算法" help={ALGORITHM_HELP_CONTENT} />
                  </div>
                  <Tooltip title={algorithmRawTitle}>
                    <Tag
                      color={algorithmSummary.rawNames.length > 1 ? 'purple' : 'blue'}
                      className={styles.performanceAlgorithmTag}
                    >
                      {algorithmSummary.label}
                    </Tag>
                  </Tooltip>
                </div>
                <div className={styles.performanceMetric}>
                  <div className={styles.performanceLabel}>
                    <ForecastTermWithHelp
                      label={isBacktest ? '回测 WAPE' : '模型 WAPE'}
                      help={WAPE_HELP_CONTENT}
                    />
                  </div>
                  <div className={styles.performanceValue}>{wapeValue}</div>
                </div>
                <div className={styles.performanceMetric}>
                  <div className={styles.performanceLabel}>
                    <ForecastTermWithHelp label="Bias" help={BIAS_HELP_CONTENT} />
                  </div>
                  <div className={styles.performanceValueRow}>
                    <span className={styles.performanceValue}>{biasValue}</span>
                    {biasDirection && <span className={styles.biasDirection}>{biasDirection}</span>}
                  </div>
                </div>
              </div>
            </Card>
          </Col>
          <Col span={24}>
            <Card
              title={
                <div className={styles.trendCardTitle}>
                  <Space size={8} wrap>
                    <span>实际 / 预测趋势</span>
                    <Tag color={snapshot?.direction ? 'blue' : 'default'}>
                      统计口径：
                      {DIRECTION_SCOPE_LABELS[snapshot?.direction ?? 'ALL']}
                    </Tag>
                  </Space>
                  {trendWindowHint && (
                    <span className={styles.trendWindowHint}>{trendWindowHint}</span>
                  )}
                </div>
              }
            >
              {snapshot?.series?.length ? (
                <ForecastTrendChart
                  data={snapshot.series}
                  forecastStartDate={snapshot.forecastStartDate}
                  backtest={isBacktest}
                  metric={snapshot.metric}
                />
              ) : (
                <Empty description="尚无可展示序列" />
              )}
            </Card>
          </Col>
        </Row>
      </Spin>
      <Drawer
        title={`仓库与方向明细（${breakdownData.length}）`}
        placement="right"
        width="min(900px, 94vw)"
        open={breakdownDrawerOpen}
        onClose={() => setBreakdownDrawerOpen(false)}
      >
        <div className={styles.breakdownDrawerHint}>
          默认按 WAPE 从高到低排列，便于优先发现预测偏差较大的仓库和方向。
        </div>
        <Table
          rowKey={(row) => `${row.warehouseCode}-${row.direction}`}
          columns={columns}
          dataSource={breakdownData}
          size="small"
          pagination={breakdownData.length > 10 ? { pageSize: 10 } : false}
          scroll={{ x: 800 }}
        />
      </Drawer>
    </div>
  );
};

export default ForecastOverview;
