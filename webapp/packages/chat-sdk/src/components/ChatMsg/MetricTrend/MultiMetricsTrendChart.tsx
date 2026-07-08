/**
 * 模块说明：ChatMsg 多指标趋势图组件。
 * 职责描述：将多个指标的时间序列查询结果渲染到同一 ECharts 图表，并规整指标名与数值，避免图例或 series 出现异常值。
 */

import { CHART_SECONDARY_COLOR, CLS_PREFIX, THEME_COLOR_LIST } from '../../../common/constants';
import { getFormattedValue } from '../../../utils/utils';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import React, { useContext, useEffect, useRef } from 'react';
import moment from 'moment';
import { ColumnType } from '../../../common/type';
import { isArray } from 'lodash';
import { ChartItemContext } from '../../ChatItem';
import { useExportByEcharts } from '../../../hooks';
import {
  getSortableChartValue,
  normalizeChartCategoryName,
  normalizeTrendMetricValue,
} from '../chartData';

type Props = {
  dateColumnName: string;
  metricFields: ColumnType[];
  resultList: any[];
  triggerResize?: boolean;
  chartType?: string;
  question: string;
};

/**
 * 渲染多指标趋势图。
 *
 * @param props.dateColumnName 时间字段名。
 * @param props.metricFields 指标字段列表，每个指标对应一个 series。
 * @param props.resultList 查询结果列表。
 * @param props.triggerResize 外部布局变化时触发 ECharts resize 的标记。
 * @param props.chartType ECharts series 类型，通常为 line 或 bar。
 * @param props.question 当前问题文案，用于导出文件名。
 * @returns 多指标趋势图容器。
 * @throws 不主动抛出异常；空指标名会兜底，非法指标值会以断点形式进入趋势图。
 */
const MultiMetricsTrendChart: React.FC<Props> = ({
  dateColumnName,
  metricFields,
  resultList,
  triggerResize,
  chartType,
  question,
}) => {
  const chartRef = useRef<any>();
  const instanceRef = useRef<ECharts>();
  // 将多指标查询结果规整成 ECharts series，非法数值以断点展示，避免 NaN 参与绘制。
  const renderChart = () => {
    let instanceObj: any;
    if (!instanceRef.current) {
      instanceObj = echarts.init(chartRef.current);
      instanceRef.current = instanceObj;
    } else {
      instanceObj = instanceRef.current;
      instanceObj.clear();
    }

    const xData = resultList?.map((item: any) => {
      const date = isArray(item[dateColumnName])
        ? item[dateColumnName].join('-')
        : `${item[dateColumnName]}`;
      return date.length === 10 ? moment(date).format('MM-DD') : date;
    });

    instanceObj.setOption({
      legend: {
        left: 0,
        top: 0,
        icon: 'rect',
        itemWidth: 15,
        itemHeight: 5,
        type: 'scroll',
      },
      xAxis: {
        type: 'category',
        axisTick: {
          alignWithLabel: true,
          lineStyle: {
            color: CHART_SECONDARY_COLOR,
          },
        },
        axisLine: {
          lineStyle: {
            color: CHART_SECONDARY_COLOR,
          },
        },
        axisLabel: {
          showMaxLabel: true,
          color: '#999',
        },
        data: xData,
      },
      yAxis: {
        type: 'value',
        splitLine: {
          lineStyle: {
            opacity: 0.3,
          },
        },
        axisLabel: {
          formatter: function (value: any) {
            return value === 0 ? 0 : getFormattedValue(value);
          },
        },
      },
      tooltip: {
        trigger: 'axis',
        formatter: function (params: any[]) {
          const param = params[0];
          const valueLabels = params
            .sort((a, b) => getSortableChartValue(b.value) - getSortableChartValue(a.value))
            .map(
              (item: any) =>
                `<div style="margin-top: 3px;">${
                  item.marker
                } <span style="display: inline-block; width: 70px; margin-right: 12px;">${
                  item.seriesName
                }</span><span style="display: inline-block; width: 90px; text-align: right; font-weight: 500;">${
                  item.value === '' || item.value === null || item.value === undefined
                    ? '-'
                    : getFormattedValue(item.value)
                }</span></div>`
            )
            .join('');
          return `${param.name}<br />${valueLabels}`;
        },
      },
      grid: {
        left: '1%',
        right: '4%',
        bottom: '3%',
        top: 45,
        containLabel: true,
      },
      series: metricFields.map((metricField, index) => {
        return {
          type: chartType,
          name: normalizeChartCategoryName(metricField.name, metricField.bizName || '指标'),
          symbol: 'circle',
          showSymbol: resultList.length === 1,
          smooth: true,
          data: resultList.map((item: any) => {
            return normalizeTrendMetricValue(item[metricField.bizName], metricField);
          }),
          color: THEME_COLOR_LIST[index],
        };
      }),
    });
    instanceObj.resize();
  };

  const { downloadChartAsImage } = useExportByEcharts({
    instanceRef,
    question,
  });

  const { register } = useContext(ChartItemContext);

  register('downloadChartAsImage', downloadChartAsImage);

  useEffect(() => {
    renderChart();
  }, [resultList, chartType]);

  useEffect(() => {
    if (triggerResize && instanceRef.current) {
      instanceRef.current.resize();
    }
  }, [triggerResize]);

  const prefixCls = `${CLS_PREFIX}-metric-trend`;

  return <div className={`${prefixCls}-flow-trend-chart`} ref={chartRef} />;
};

export default MultiMetricsTrendChart;
