/**
 * Forecast 实际、预测与经验区间 ECharts 组件。
 *
 * 职责：将服务端日期点渲染为实际/预测折线和 P10-P90 经验区间；组件卸载时释放图表、
 * ResizeObserver 和动画帧，避免工作台切页后的监听器泄漏。
 */
import type { ForecastMetric, ForecastSeriesPoint } from '@/services/forecast';
import { formatByThousandSeperator } from '@/utils/utils';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import React, { useEffect, useRef } from 'react';
import { buildForecastTrendWindow } from './forecastTrendChartUtils';

const CHART_GRID_EDGE_PADDING_PX = 16;
const CHART_GRID_SECTION_LABEL_LEFT_PADDING_PX = 72;
const CHART_GRID_RIGHT_PADDING_PX = 48;
const CHART_GRID_TOP_PX = 54;
const AXIS_LABEL_MARGIN_PX = 12;
const AXIS_MAX_DECIMAL_PLACES = 2;
const SECTION_AXIS_OFFSET_PX = 30;
const MAX_LABELED_POINT_COUNT = 16;
const SERIES_SYMBOL_SIZE_PX = 7;
const ACTUAL_SERIES_COLOR = '#f5b400';
const FORECAST_SERIES_COLOR = '#ff4d4f';
const FORECAST_SECTION_COLOR = '#1677ff';
const CONFIDENCE_AREA_COLOR = 'rgba(82,196,26,0.2)';

/** 将数值轴刻度格式化为完整千分位数字，避免科学计数法降低业务可读性。 */
function formatAxisNumber(value: number): string {
  const rounded = Number(value.toFixed(AXIS_MAX_DECIMAL_PLACES));
  return String(formatByThousandSeperator(rounded));
}

/** 将完整 ISO 日期压缩为图二使用的月-日坐标轴格式。 */
function formatAxisDate(value: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) ? value.slice(5) : value;
}

/** 格式化折线点位标签；非数值空点不输出占位文字。 */
function formatSeriesLabel(value?: number): string {
  return typeof value === 'number' && Number.isFinite(value) ? formatAxisNumber(value) : '';
}

type Props = {
  data: ForecastSeriesPoint[];
  height?: number;
  forecastStartDate?: string;
  backtest?: boolean;
  metric?: ForecastMetric;
};

/**
 * 渲染预测趋势图。
 *
 * @param props data 为按日期排序的实际/预测点，height 为图表高度；forecastStartDate 用于标记
 * 预测边界，backtest 决定预测区间内的实际值是否单独作为回测对照展示，metric 决定 Y 轴名称。
 * @returns ECharts 容器。
 * @throws 不主动抛出异常；空数组渲染空坐标系。
 */
const ForecastTrendChart: React.FC<Props> = ({
  data,
  height = 360,
  forecastStartDate,
  backtest = false,
  metric = 'QUANTITY',
}) => {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return undefined;
    const chart: ECharts = echarts.init(containerRef.current);
    const trendWindow = buildForecastTrendWindow(data, forecastStartDate);
    const visibleData = trendWindow.data;
    const dates = visibleData.map((item) => item.date);
    const lower = visibleData.map((item) => item.lower ?? undefined);
    const interval = visibleData.map((item) =>
      item.lower == null || item.upper == null ? undefined : item.upper - item.lower,
    );
    const historicalActual = visibleData.map((item) =>
      !forecastStartDate || item.date < forecastStartDate ? item.actual ?? undefined : undefined,
    );
    const comparisonActual = visibleData.map((item) =>
      backtest && forecastStartDate && item.date >= forecastStartDate
        ? item.actual ?? undefined
        : undefined,
    );
    const actualSeriesNames = backtest ? ['实际（历史）', '实际（回测区间）'] : ['实际'];
    const showPointLabels = visibleData.length <= MAX_LABELED_POINT_COUNT;
    const historySectionLabel = trendWindow.historyPointCount
      ? `近期历史（训练区间末 ${trendWindow.historyPointCount} 天）`
      : '近期历史';
    const hasSectionLabels =
      trendWindow.forecastStartIndex != null &&
      trendWindow.historyLabelIndex != null &&
      trendWindow.comparisonLabelIndex != null;
    // 只有 1～2 个历史点时，区间说明会落在首个分类刻度上；额外右移绘图区，
    // 为居中的长标签保留完整左半宽度。其他场景继续使用紧凑的通用边距。
    const chartGridLeftPadding =
      hasSectionLabels && trendWindow.historyLabelIndex === 0
        ? CHART_GRID_SECTION_LABEL_LEFT_PADDING_PX
        : CHART_GRID_EDGE_PADDING_PX;
    chart.setOption({
      animationDuration: 300,
      tooltip: { trigger: 'axis' },
      legend: {
        data: [
          ...actualSeriesNames,
          '预测',
          {
            name: '80%经验区间',
            icon: 'roundRect',
            itemStyle: { color: CONFIDENCE_AREA_COLOR },
          },
        ],
      },
      // containLabel 负责容纳坐标轴文字；历史点只有 1 个时，区间说明会以首个分类点居中，
      // 因此左侧额外保留安全间距，避免浏览器缩放后首字“历”越过画布边界被裁切。
      grid: {
        left: chartGridLeftPadding,
        right: CHART_GRID_RIGHT_PADDING_PX,
        top: CHART_GRID_TOP_PX,
        bottom: CHART_GRID_EDGE_PADDING_PX,
        containLabel: true,
      },
      xAxis: [
        {
          type: 'category',
          data: dates,
          boundaryGap: false,
          axisTick: { alignWithLabel: true },
          axisLabel: {
            interval: 0,
            margin: AXIS_LABEL_MARGIN_PX,
            showMinLabel: true,
            showMaxLabel: true,
            hideOverlap: true,
            formatter: formatAxisDate,
            color: (value: string) =>
              forecastStartDate && value >= forecastStartDate ? FORECAST_SECTION_COLOR : '#595959',
          },
        },
        ...(hasSectionLabels
          ? [
              {
                type: 'category',
                data: dates,
                boundaryGap: false,
                position: 'bottom',
                offset: SECTION_AXIS_OFFSET_PX,
                axisLine: { show: false },
                axisTick: { show: false },
                splitLine: { show: false },
                axisLabel: {
                  interval: 0,
                  margin: 0,
                  hideOverlap: false,
                  fontSize: 14,
                  fontWeight: 500,
                  formatter: (_value: string, index: number) => {
                    if (index === trendWindow.historyLabelIndex) {
                      return historySectionLabel;
                    }
                    if (index === trendWindow.comparisonLabelIndex) {
                      return backtest ? '回测区间' : '预测区间';
                    }
                    return '';
                  },
                  color: (_value: string, index: number) =>
                    index >= (trendWindow.forecastStartIndex ?? Number.MAX_SAFE_INTEGER)
                      ? FORECAST_SECTION_COLOR
                      : '#595959',
                },
              },
              {
                type: 'category',
                data: dates,
                boundaryGap: false,
                position: 'bottom',
                axisLine: { show: false },
                axisLabel: { show: false },
                splitLine: { show: false },
                // 独立轴刻度把蓝色分界延伸到区间说明行，形成图二中的完整视觉分区。
                axisTick: {
                  show: true,
                  alignWithLabel: true,
                  interval: (_index: number, value: string) => value === forecastStartDate,
                  length: SECTION_AXIS_OFFSET_PX,
                  lineStyle: {
                    color: FORECAST_SECTION_COLOR,
                    width: 2,
                    type: 'dashed',
                  },
                },
              },
            ]
          : []),
      ],
      yAxis: {
        type: 'value',
        min: 0,
        name: metric === 'TASK_COUNT' ? '任务数' : '数量',
        nameLocation: 'end',
        nameGap: 12,
        nameTextStyle: { color: '#595959', align: 'right' },
        axisLabel: {
          margin: AXIS_LABEL_MARGIN_PX,
          formatter: formatAxisNumber,
        },
        splitLine: { lineStyle: { color: '#f0f0f0' } },
      },
      series: [
        {
          name: '区间下界',
          type: 'line',
          data: lower,
          stack: 'confidence',
          symbol: 'none',
          smooth: 0.25,
          z: 1,
          lineStyle: { opacity: 0 },
        },
        {
          name: '80%经验区间',
          type: 'line',
          data: interval,
          stack: 'confidence',
          symbol: 'none',
          smooth: 0.25,
          z: 1,
          lineStyle: { opacity: 0 },
          areaStyle: { color: CONFIDENCE_AREA_COLOR },
        },
        {
          name: backtest ? '实际（历史）' : '实际',
          type: 'line',
          data: historicalActual,
          symbol: 'circle',
          symbolSize: SERIES_SYMBOL_SIZE_PX,
          showSymbol: showPointLabels,
          smooth: 0.25,
          z: 3,
          itemStyle: { color: ACTUAL_SERIES_COLOR },
          lineStyle: { color: ACTUAL_SERIES_COLOR, width: 2 },
          label: {
            show: showPointLabels,
            position: 'top',
            color: '#262626',
            formatter: ({ value }: { value?: number }) => formatSeriesLabel(value),
          },
          labelLayout: { hideOverlap: true },
        },
        ...(backtest
          ? [
              {
                name: '实际（回测区间）',
                type: 'line',
                data: comparisonActual,
                symbol: 'circle',
                symbolSize: SERIES_SYMBOL_SIZE_PX,
                showSymbol: showPointLabels,
                smooth: 0.25,
                z: 3,
                itemStyle: { color: ACTUAL_SERIES_COLOR },
                lineStyle: { color: ACTUAL_SERIES_COLOR, width: 2 },
                label: {
                  show: showPointLabels,
                  position: 'top',
                  color: '#262626',
                  formatter: ({ value }: { value?: number }) => formatSeriesLabel(value),
                },
                labelLayout: { hideOverlap: true },
              },
            ]
          : []),
        {
          name: '预测',
          type: 'line',
          data: visibleData.map((item) => item.forecast ?? undefined),
          symbol: 'circle',
          symbolSize: SERIES_SYMBOL_SIZE_PX,
          showSymbol: showPointLabels,
          smooth: 0.25,
          z: 3,
          itemStyle: { color: FORECAST_SERIES_COLOR },
          lineStyle: { color: FORECAST_SERIES_COLOR, width: 2, type: 'dashed' },
          label: {
            show: showPointLabels,
            position: 'bottom',
            color: '#262626',
            formatter: ({ value }: { value?: number }) => formatSeriesLabel(value),
          },
          labelLayout: { hideOverlap: true },
          markLine: forecastStartDate
            ? {
                symbol: 'none',
                silent: true,
                lineStyle: { color: '#1677ff', width: 2, type: 'dashed' },
                label: {
                  show: true,
                  formatter: backtest ? '回测起点' : '预测起点',
                  color: FORECAST_SECTION_COLOR,
                  backgroundColor: '#ffffff',
                  borderColor: FORECAST_SECTION_COLOR,
                  borderWidth: 1,
                  borderRadius: 3,
                  padding: [3, 7],
                  position: 'insideEndTop',
                  rotate: 0,
                },
                data: [{ xAxis: forecastStartDate }],
              }
            : undefined,
        },
      ],
    });

    let resizeFrame = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(resizeFrame);
      resizeFrame = requestAnimationFrame(() => chart.resize());
    });
    observer.observe(containerRef.current);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(resizeFrame);
      chart.dispose();
    };
  }, [backtest, data, forecastStartDate, metric]);

  return <div ref={containerRef} style={{ width: '100%', height }} />;
};

export default ForecastTrendChart;
