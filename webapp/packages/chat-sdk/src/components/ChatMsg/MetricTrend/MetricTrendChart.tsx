/**
 * 模块说明：ChatMsg 单指标趋势图组件。
 * 职责描述：将时间序列查询结果渲染为 ECharts 折线图或柱状图，并统一规整图例分类、时间轴数据和指标值。
 */

import { CHART_SECONDARY_COLOR, CLS_PREFIX, THEME_COLOR_LIST } from '../../../common/constants';
import {
  formatByDataFormatType,
  getFormattedValue,
  getMinMaxDate,
  groupByColumn,
  normalizeTrendData,
} from '../../../utils/utils';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import React, { useContext, useEffect, useRef } from 'react';
import moment from 'moment';
import { ColumnType } from '../../../common/type';
import NoPermissionChart from '../NoPermissionChart';
import classNames from 'classnames';
import { isArray } from 'lodash';
import { useExportByEcharts } from '../../../hooks';
import { ChartItemContext } from '../../ChatItem';
import {
  getSortableChartValue,
  normalizeChartCategoryName,
  normalizeTrendMetricValue,
} from '../chartData';

type Props = {
  model?: string;
  dateColumnName: string;
  categoryColumnName: string;
  metricField: ColumnType;
  resultList: any[];
  triggerResize?: boolean;
  onApplyAuth?: (model: string) => void;
  chartType?: string;
};

const DEFAULT_TREND_CATEGORY_FIELD = '__chartTrendCategory';

/**
 * 渲染单指标趋势图。
 *
 * @param props.model 当前数据模型名称，用于无权限提示。
 * @param props.dateColumnName 时间字段名。
 * @param props.categoryColumnName 可选分类字段名；为空时渲染单序列趋势。
 * @param props.metricField 指标字段配置。
 * @param props.resultList 查询结果列表。
 * @param props.triggerResize 外部布局变化时触发 ECharts resize 的标记。
 * @param props.onApplyAuth 无权限时申请权限的回调。
 * @param props.chartType ECharts series 类型，通常为 line 或 bar。
 * @returns 趋势图或无权限占位图。
 * @throws 不主动抛出异常；空分类会兜底，非法指标值会以断点形式进入趋势图。
 */
const MetricTrendChart: React.FC<Props> = ({
  model,
  dateColumnName,
  categoryColumnName,
  metricField,
  resultList,
  triggerResize,
  onApplyAuth,
  chartType,
}) => {
  const chartRef = useRef<any>();
  const instanceRef = useRef<ECharts>();

  // 将查询结果规整成 ECharts 趋势图数据，空分类统一兜底，非法数值以断点展示。
  const renderChart = () => {
    let instanceObj: any;
    if (!instanceRef.current) {
      instanceObj = echarts.init(chartRef.current);
      instanceRef.current = instanceObj;
    } else {
      instanceObj = instanceRef.current;
      instanceObj.clear();
    }

    const valueColumnName = metricField.bizName;
    const groupColumnName = categoryColumnName || DEFAULT_TREND_CATEGORY_FIELD;
    const dataSource = resultList.map((item: any) => {
      return {
        ...item,
        [groupColumnName]: categoryColumnName
          ? normalizeChartCategoryName(item[categoryColumnName])
          : normalizeChartCategoryName(metricField.name, metricField.bizName || '指标'),
        [dateColumnName]: Array.isArray(item[dateColumnName])
          ? moment(item[dateColumnName].join('')).format('MM-DD')
          : item[dateColumnName],
      };
    });

    const groupDataValue = groupByColumn(dataSource, groupColumnName);
    const [startDate, endDate] = getMinMaxDate(dataSource, dateColumnName);
    const groupData = Object.keys(groupDataValue).reduce((result: any, key) => {
      result[key] =
        startDate &&
        endDate &&
        (dateColumnName.includes('date') || dateColumnName.includes('month'))
          ? normalizeTrendData(
              groupDataValue[key],
              dateColumnName,
              valueColumnName,
              startDate,
              endDate,
              dateColumnName.includes('month') ? 'months' : 'days'
            )
          : groupDataValue[key];
      return result;
    }, {});

    const sortedGroupKeys = Object.keys(groupData).sort((a, b) => {
      const aLastValue = groupData[a]?.[groupData[a].length - 1]?.[valueColumnName];
      const bLastValue = groupData[b]?.[groupData[b].length - 1]?.[valueColumnName];
      return getSortableChartValue(bLastValue) - getSortableChartValue(aLastValue);
    });

    const xData = groupData[sortedGroupKeys[0]]?.map((item: any) => {
      const date = isArray(item[dateColumnName])
        ? item[dateColumnName].join('-')
        : `${item[dateColumnName]}`;
      return date.length === 10 ? moment(date).format('MM-DD') : date;
    });

    instanceObj.setOption({
      legend: categoryColumnName
        ? {
          left: 0,
          top: 0,
          icon: 'rect',
          itemWidth: 15,
          itemHeight: 5,
          type: 'scroll',
          data: sortedGroupKeys.slice(0, 20),
        }
        : undefined,
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
            return value === 0
              ? 0
              : metricField.dataFormatType === 'percent'
              ? formatByDataFormatType(value, metricField.dataFormatType, metricField.dataFormat)
              : getFormattedValue(value);
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
                    : metricField.dataFormatType === 'percent' ||
                      metricField.dataFormatType === 'decimal'
                    ? formatByDataFormatType(item.value, metricField.dataFormatType, metricField.dataFormat)
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
        top: categoryColumnName ? 45 : 20,
        containLabel: true,
      },
      series: sortedGroupKeys.slice(0, 20).map((category, index) => {
        const data = groupData[category];
        return {
          type: chartType,
          name: categoryColumnName
            ? category
            : normalizeChartCategoryName(metricField.name, metricField.bizName || '指标'),
          symbol: 'circle',
          showSymbol: data.length === 1,
          smooth: true,
          data: data.map((item: any) => {
            return normalizeTrendMetricValue(item[valueColumnName], metricField);
          }),
          color: THEME_COLOR_LIST[index],
        };
      }),
    });
    instanceObj.resize();
  };

  const { downloadChartAsImage } = useExportByEcharts({
    instanceRef,
    question: metricField.name,
  });

  const { register } = useContext(ChartItemContext);

  register('downloadChartAsImage', downloadChartAsImage);

  useEffect(() => {
    if (metricField.authorized) {
      renderChart();
    }
  }, [resultList, metricField, chartType]);

  useEffect(() => {
    if (triggerResize && instanceRef.current) {
      instanceRef.current.resize();
    }
  }, [triggerResize]);

  const prefixCls = `${CLS_PREFIX}-metric-trend`;

  const flowTrendChartClass = classNames(`${prefixCls}-flow-trend-chart`, {
    [`${prefixCls}-flow-trend-chart-single`]: !categoryColumnName,
  });

  return (
    <div>
      {!metricField.authorized ? (
        <NoPermissionChart model={model || ''} onApplyAuth={onApplyAuth} />
      ) : (
        <div className={flowTrendChartClass} ref={chartRef} />
      )}
    </div>
  );
};

export default MetricTrendChart;
