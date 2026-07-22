/**
 * 预测基准状态条组件。
 *
 * 职责：集中呈现三种预测基准、历史回测日期和 Tag 化的训练/预测时间范围；提交按钮使用
 * loading 锁避免重复触发昂贵回测计算。Worker、同步和业务日期状态由看板顶部筛选区呈现。
 */
import type { ForecastAnchorMode, ForecastOverviewSnapshot } from '@/services/forecast';
import { SyncOutlined } from '@ant-design/icons';
import { Button, DatePicker, Segmented, Space, Tag, Typography } from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import React, { useMemo } from 'react';
import {
  type ForecastTrainingRange,
  formatCompactForecastDateRange,
  isBacktestDateDisabled,
  isTrainingDateDisabled,
} from './forecastOverview';
import styles from './style.less';

const { Text } = Typography;
const { RangePicker } = DatePicker;

/** 单个日期上下文 Tag，key 保证稳定渲染，color 用于突出预测或回测区间。 */
type ForecastDateContextItem = {
  key: 'training' | 'data' | 'forecast' | 'backtest' | 'actual';
  label: string;
  color?: string;
};

type Props = {
  anchorMode: ForecastAnchorMode;
  forecastStartDate?: Dayjs;
  trainingRangeOverride?: ForecastTrainingRange;
  horizon: number;
  snapshot?: ForecastOverviewSnapshot;
  loading: boolean;
  onAnchorModeChange: (mode: ForecastAnchorMode) => void;
  onForecastStartDateChange: (date?: Dayjs) => void;
  onTrainingRangeChange: (range?: ForecastTrainingRange) => void;
  onResetTrainingRange: () => void;
  onGenerateBacktest: () => void;
};

/**
 * 渲染方案 2 的预测基准上下文条。
 *
 * @param props 当前模式、日期、快照、健康状态和交互回调。
 * @returns 可交互的基准选择与日期语义区域。
 */
const ForecastAnchorBar: React.FC<Props> = ({
  anchorMode,
  forecastStartDate,
  trainingRangeOverride,
  horizon,
  snapshot,
  loading,
  onAnchorModeChange,
  onForecastStartDateChange,
  onTrainingRangeChange,
  onResetTrainingRange,
  onGenerateBacktest,
}) => {
  const draftForecastEnd = forecastStartDate?.add(horizon - 1, 'day');
  const automaticTrainingRange = useMemo<ForecastTrainingRange | undefined>(() => {
    if (!snapshot?.trainingStartDate || !snapshot.trainingEndDate) return undefined;
    return [dayjs(snapshot.trainingStartDate), dayjs(snapshot.trainingEndDate)];
  }, [snapshot?.trainingEndDate, snapshot?.trainingStartDate]);
  const effectiveTrainingRange = trainingRangeOverride ?? automaticTrainingRange;
  let effectiveForecastStart = forecastStartDate;
  if (anchorMode !== 'BACKTEST') {
    effectiveForecastStart = snapshot?.forecastStartDate
      ? dayjs(snapshot.forecastStartDate)
      : undefined;
  }
  const trainingRangeText = effectiveTrainingRange
    ? formatCompactForecastDateRange(
        effectiveTrainingRange[0].format('YYYY-MM-DD'),
        effectiveTrainingRange[1].format('YYYY-MM-DD'),
      )
    : '-';

  const contextItems = useMemo<ForecastDateContextItem[]>(() => {
    const trainingItem: ForecastDateContextItem = {
      key: 'training',
      label: `训练：${trainingRangeText}`,
    };
    if (anchorMode === 'BACKTEST' && forecastStartDate && draftForecastEnd) {
      return [
        trainingItem,
        {
          key: 'backtest',
          label: `回测：${forecastStartDate.format('MM-DD')}～${draftForecastEnd.format('MM-DD')}`,
          color: 'blue',
        },
        {
          key: 'actual',
          label: `实际截至：${snapshot?.latestActualDate?.slice(5) || '-'}`,
        },
      ];
    }
    return [
      trainingItem,
      { key: 'data', label: `数据截至：${snapshot?.latestActualDate || '-'}` },
      {
        key: 'forecast',
        label: `预测：${formatCompactForecastDateRange(
          snapshot?.forecastStartDate,
          snapshot?.forecastEndDate,
        )}`,
        color: 'blue',
      },
    ];
  }, [anchorMode, draftForecastEnd, forecastStartDate, snapshot, trainingRangeText]);

  return (
    <div className={styles.anchorBar} aria-label="预测基准与日期上下文">
      <div className={styles.anchorControls}>
        <Text strong>预测基准</Text>
        <Segmented
          aria-label="选择预测基准"
          options={[
            { label: '跟随数据', value: 'LATEST_DATA' },
            { label: '历史回测', value: 'BACKTEST' },
            { label: '今天', value: 'TODAY' },
          ]}
          value={anchorMode}
          onChange={(value) => onAnchorModeChange(value as ForecastAnchorMode)}
        />
        {anchorMode === 'BACKTEST' && (
          <Space size={8}>
            <Text type="secondary">预测起始日</Text>
            <DatePicker
              aria-label="历史回测预测起始日"
              allowClear={false}
              disabled={loading}
              value={forecastStartDate}
              disabledDate={(date) =>
                isBacktestDateDisabled(date, snapshot, horizon, trainingRangeOverride?.[1])
              }
              onChange={(date) => onForecastStartDateChange(date || undefined)}
            />
          </Space>
        )}
        <Space size={8} wrap className={styles.trainingControls}>
          <Text type="secondary">训练区间</Text>
          <RangePicker
            aria-label="自定义历史训练区间"
            allowClear={false}
            disabled={loading || !snapshot?.dataStartDate || !snapshot.latestActualDate}
            disabledDate={(date) => isTrainingDateDisabled(date, snapshot, effectiveForecastStart)}
            value={effectiveTrainingRange}
            onChange={(dates) => {
              if (dates?.[0] && dates[1]) {
                onTrainingRangeChange([dates[0], dates[1]]);
              }
            }}
          />
          <Tag color={trainingRangeOverride ? 'blue' : 'default'}>
            {trainingRangeOverride ? '自定义' : '自动'}
          </Tag>
          {trainingRangeOverride && (
            <Button type="link" size="small" onClick={onResetTrainingRange}>
              恢复自动
            </Button>
          )}
        </Space>
      </div>

      <div
        className={styles.anchorContext}
        aria-label={contextItems.map((item) => item.label).join('，')}
      >
        <div className={styles.dateContextTags}>
          {contextItems.map((item) => (
            <Tag
              key={item.key}
              color={item.color}
              className={styles.dateContextTag}
              title={item.label}
            >
              {item.label}
            </Tag>
          ))}
        </div>
      </div>

      <div className={styles.anchorActions}>
        {anchorMode === 'BACKTEST' && (
          <Button
            type="primary"
            icon={<SyncOutlined />}
            loading={loading}
            disabled={!forecastStartDate}
            onClick={onGenerateBacktest}
          >
            生成回测
          </Button>
        )}
      </div>
    </div>
  );
};

export default ForecastAnchorBar;
