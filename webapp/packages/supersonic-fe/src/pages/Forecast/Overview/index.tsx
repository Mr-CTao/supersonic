/**
 * 出入库预测看板页面。
 *
 * 职责：展示 7/14/30 天 KPI、实际/预测曲线、经验区间、仓库方向拆分、模型质量与 Worker 新鲜度。
 * 并发说明：请求序号阻止旧请求覆盖新筛选；轮询定时器在卸载时清理。
 */
import ForecastTrendChart from '../components/ForecastTrendChart';
import {
  getForecastBreakdown,
  getForecastErrorMessage,
  getForecastHealth,
  getForecastProfiles,
  getForecastSeries,
  getForecastSummary,
  unwrapForecastData,
} from '@/services/forecast';
import type {
  ForecastBreakdown,
  ForecastHealth,
  ForecastMetric,
  ForecastProfile,
  ForecastSeriesPoint,
  ForecastSummary,
} from '@/services/forecast';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Card,
  Col,
  Empty,
  message,
  Row,
  Segmented,
  Select,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';

const HORIZONS = [7, 14, 30];

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
  const [profiles, setProfiles] = useState<ForecastProfile[]>([]);
  const [profileId, setProfileId] = useState<number>();
  const [horizon, setHorizon] = useState(7);
  const [metric, setMetric] = useState<ForecastMetric>('QUANTITY');
  const [summary, setSummary] = useState<ForecastSummary>();
  const [series, setSeries] = useState<ForecastSeriesPoint[]>([]);
  const [breakdown, setBreakdown] = useState<ForecastBreakdown[]>([]);
  const [health, setHealth] = useState<ForecastHealth>();
  const [loading, setLoading] = useState(false);
  const requestVersion = useRef(0);

  const loadProfiles = useCallback(async () => {
    try {
      const response = await getForecastProfiles({ pageNum: 1, pageSize: 100 });
      const page = unwrapForecastData<{ list?: ForecastProfile[] }>(response);
      const available = page?.list || [];
      setProfiles(available);
      setProfileId((current) => current ?? available[0]?.id);
    } catch (error: unknown) {
      message.error(getForecastErrorMessage(error, '预测 Profile 加载失败'));
    }
  }, []);

  const loadDashboard = useCallback(async () => {
    if (!profileId) return;
    const version = ++requestVersion.current;
    setLoading(true);
    const params = { profileId, horizon, metric };
    try {
      const [summaryResp, seriesResp, breakdownResp, healthResp] = await Promise.all([
        getForecastSummary(params),
        getForecastSeries(params),
        getForecastBreakdown(params),
        getForecastHealth(profileId),
      ]);
      if (version !== requestVersion.current) return;
      setSummary(unwrapForecastData<ForecastSummary>(summaryResp));
      setSeries(unwrapForecastData<ForecastSeriesPoint[]>(seriesResp) || []);
      setBreakdown(unwrapForecastData<ForecastBreakdown[]>(breakdownResp) || []);
      setHealth(unwrapForecastData<ForecastHealth>(healthResp));
    } catch (error: unknown) {
      if (version === requestVersion.current) {
        message.error(getForecastErrorMessage(error, '预测看板加载失败'));
      }
    } finally {
      if (version === requestVersion.current) setLoading(false);
    }
  }, [horizon, metric, profileId]);

  useEffect(() => {
    loadProfiles();
  }, [loadProfiles]);

  useEffect(() => {
    loadDashboard();
    const timer = window.setInterval(loadDashboard, 30_000);
    return () => {
      window.clearInterval(timer);
      requestVersion.current += 1;
    };
  }, [loadDashboard]);

  const columns: ColumnsType<ForecastBreakdown> = useMemo(
    () => [
      { title: '仓库', dataIndex: 'warehouseCode' },
      {
        title: '方向',
        dataIndex: 'direction',
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
        render: formatNumber,
      },
      { title: '算法', dataIndex: 'algorithm', render: (value) => value || '-' },
      {
        title: 'WAPE',
        dataIndex: 'wape',
        render: (value) => (value == null ? '-' : `${(value * 100).toFixed(2)}%`),
      },
      {
        title: 'Bias',
        dataIndex: 'bias',
        render: (value) => (value == null ? '-' : `${(value * 100).toFixed(2)}%`),
      },
    ],
    [horizon],
  );

  return (
    <PageContainer title="出入库预测看板" subTitle="实际数据、未来预测与模型质量">
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          style={{ width: 260 }}
          placeholder="选择预测 Profile"
          value={profileId}
          options={profiles.map((item) => ({ value: item.id, label: item.name }))}
          onChange={setProfileId}
        />
        <Segmented
          options={HORIZONS.map((value) => ({ label: `${value}天`, value }))}
          value={horizon}
          onChange={(value) => setHorizon(Number(value))}
        />
        <Segmented
          options={[
            { label: '数量', value: 'QUANTITY' },
            { label: '任务数', value: 'TASK_COUNT' },
          ]}
          value={metric}
          onChange={(value) => setMetric(value as ForecastMetric)}
        />
        <Tag color={health?.workerHealthy ? 'success' : 'error'}>
          {health?.workerHealthy ? `${health.activeWorkers} 个 Worker 在线` : 'Worker 离线'}
        </Tag>
        <Tag color={health?.freshnessStatus === 'FRESH' ? 'success' : 'warning'}>
          数据：{health?.freshnessStatus || '-'}
        </Tag>
      </Space>

      {summary?.dataStatus === 'INSUFFICIENT_DATA' && (
        <Alert
          showIcon
          type="warning"
          title="历史数据少于 14 天，暂不生成预测"
          style={{ marginBottom: 16 }}
        />
      )}
      {summary?.dataStatus === 'LOW_CONFIDENCE' && (
        <Alert
          showIcon
          type="info"
          title="历史数据不足 28 天，当前使用低置信度 7 日移动平均"
          style={{ marginBottom: 16 }}
        />
      )}
      {health?.freshnessStatus === 'STALE' && (
        <Alert
          showIcon
          type="warning"
          title="数据已过期，请到运行中心检查同步任务"
          style={{ marginBottom: 16 }}
        />
      )}

      <Spin spinning={loading}>
        <Row gutter={[16, 16]}>
          <Col xs={24} md={8}>
            <Card>
              <Statistic
                title={`未来 ${horizon} 天预测`}
                value={formatNumber(summary?.predictedTotal)}
                suffix={metric === 'QUANTITY' ? '数量' : '任务'}
              />
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card>
              <Statistic
                title={`过去 ${horizon} 天实际`}
                value={formatNumber(summary?.previousActualTotal)}
              />
            </Card>
          </Col>
          <Col xs={24} md={8}>
            <Card>
              <Statistic
                title="模型 WAPE"
                value={summary?.wape == null ? '-' : (summary.wape * 100).toFixed(2)}
                suffix={summary?.wape == null ? undefined : '%'}
              />
            </Card>
          </Col>
          <Col span={24}>
            <Card title="实际 / 预测趋势">
              {series.length ? (
                <ForecastTrendChart data={series} />
              ) : (
                <Empty description="尚无可展示序列" />
              )}
            </Card>
          </Col>
          <Col span={24}>
            <Card title="仓库与方向分解">
              <Table
                rowKey={(row) => `${row.warehouseCode}-${row.direction}`}
                columns={columns}
                dataSource={breakdown}
                pagination={{ pageSize: 10 }}
                scroll={{ x: 800 }}
              />
            </Card>
          </Col>
        </Row>
      </Spin>
    </PageContainer>
  );
};

export default ForecastOverview;
