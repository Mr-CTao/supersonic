/**
 * Forecast 实际、预测与经验区间 ECharts 组件。
 *
 * 职责：将服务端日期点渲染为实际/预测折线和 P10-P90 经验区间；组件卸载时释放图表、
 * ResizeObserver 和动画帧，避免工作台切页后的监听器泄漏。
 */
import type { ForecastSeriesPoint } from '@/services/forecast';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import React, { useEffect, useRef } from 'react';

type Props = { data: ForecastSeriesPoint[]; height?: number };

/**
 * 渲染预测趋势图。
 *
 * @param props data 为按日期排序的实际/预测点，height 为图表高度。
 * @returns ECharts 容器。
 * @throws 不主动抛出异常；空数组渲染空坐标系。
 */
const ForecastTrendChart: React.FC<Props> = ({ data, height = 360 }) => {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return undefined;
    const chart: ECharts = echarts.init(containerRef.current);
    const dates = data.map((item) => item.date);
    const lower = data.map((item) => item.lower ?? undefined);
    const interval = data.map((item) =>
      item.lower == null || item.upper == null ? undefined : item.upper - item.lower,
    );
    chart.setOption({
      animationDuration: 300,
      tooltip: { trigger: 'axis' },
      legend: { data: ['实际', '预测', '80%经验区间'] },
      grid: { left: 56, right: 24, top: 54, bottom: 42 },
      xAxis: { type: 'category', data: dates, boundaryGap: false },
      yAxis: { type: 'value', min: 0, splitLine: { lineStyle: { color: '#f0f0f0' } } },
      series: [
        {
          name: '区间下界',
          type: 'line',
          data: lower,
          stack: 'confidence',
          symbol: 'none',
          lineStyle: { opacity: 0 },
        },
        {
          name: '80%经验区间',
          type: 'line',
          data: interval,
          stack: 'confidence',
          symbol: 'none',
          lineStyle: { opacity: 0 },
          areaStyle: { color: 'rgba(22,119,255,0.16)' },
        },
        {
          name: '实际',
          type: 'line',
          data: data.map((item) => item.actual ?? undefined),
          symbol: 'circle',
          showSymbol: false,
          smooth: true,
          lineStyle: { color: '#52c41a', width: 2 },
        },
        {
          name: '预测',
          type: 'line',
          data: data.map((item) => item.forecast ?? undefined),
          symbol: 'circle',
          showSymbol: false,
          smooth: true,
          lineStyle: { color: '#1677ff', width: 2, type: 'dashed' },
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
  }, [data]);

  return <div ref={containerRef} style={{ width: '100%', height }} />;
};

export default ForecastTrendChart;
