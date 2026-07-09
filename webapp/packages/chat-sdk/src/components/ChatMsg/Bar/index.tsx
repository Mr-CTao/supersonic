/**
 * 模块说明：ChatMsg 柱状图组件。
 * 职责描述：将单指标聚合查询结果渲染为 ECharts 柱状图，并在渲染前规整分类名和指标值，避免异常数据影响图表展示。
 */

import { CHART_BLUE_COLOR, CHART_SECONDARY_COLOR, PREFIX_CLS } from '../../../common/constants';
import { MsgDataType } from '../../../common/type';
import {
  formatByDataFormatType,
  getChartLightenColor,
  getFormattedValue,
} from '../../../utils/utils';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import { useContext, useEffect, useRef } from 'react';
import NoPermissionChart from '../NoPermissionChart';
import { ColumnType } from '../../../common/type';
import { Spin } from 'antd';
import { ChartItemContext } from '../../ChatItem';
import { useExportByEcharts } from '../../../hooks';
import { renderChartWhenContainerReady } from '../chartContainer';
import { normalizeChartCategoryName, toFiniteChartNumber } from '../chartData';

type Props = {
  data: MsgDataType;
  question?: string;
  triggerResize?: boolean;
  loading: boolean;
  metricField: ColumnType;
  onApplyAuth?: (model: string) => void;
};

/**
 * 渲染聚合结果柱状图。
 *
 * @param props.data 问答查询结果，包含 queryColumns 和 queryResults。
 * @param props.question 当前问题文案，用于图表标题和导出文件名。
 * @param props.triggerResize 外部布局变化时触发 ECharts resize 的标记。
 * @param props.loading 查询或二次加载状态。
 * @param props.metricField 柱状图数值字段。
 * @param props.onApplyAuth 无权限时申请权限的回调。
 * @returns 柱状图或无权限占位图。
 * @throws 不主动抛出异常；非法指标值会被过滤，空分类会显示为“未知”。
 */
const BarChart: React.FC<Props> = ({
  data,
  question = "",
  triggerResize,
  loading,
  metricField,
  onApplyAuth,
}) => {
  const chartRef = useRef<any>();
  const instanceRef = useRef<ECharts>();

  const { queryColumns, queryResults, entityInfo } = data;

  const categoryColumnName =
    queryColumns?.find(column => column.showType === 'CATEGORY')?.bizName || '';
  const metricColumn = queryColumns?.find(column => column.showType === 'NUMBER');
  const metricColumnName = metricColumn?.bizName || '';

  // 将查询结果规整成 ECharts 柱状图数据，非法指标值不参与图表渲染但仍保留在表格视图中。
  const renderChart = () => {
    if (!chartRef.current) {
      return;
    }
    let instanceObj: any;
    if (!instanceRef.current) {
      instanceObj = echarts.init(chartRef.current);
      instanceRef.current = instanceObj;
    } else {
      instanceObj = instanceRef.current;
    }
    const data = queryResults || [];
    const chartData = data.reduce<any[]>((result, item) => {
      const value = toFiniteChartNumber(item[metricColumnName]);
      if (value === undefined) {
        return result;
      }
      result.push({
        name: normalizeChartCategoryName(item[categoryColumnName]),
        value,
      });
      return result;
    }, []);
    const xData = chartData.map(item => item.name);
    instanceObj.setOption({
      xAxis: {
        type: 'category',
        axisTick: {
          show: false,
        },
        axisLine: {
          lineStyle: {
            color: CHART_SECONDARY_COLOR,
          },
        },
        axisLabel: {
          width: 200,
          overflow: 'truncate',
          showMaxLabel: true,
          hideOverlap: false,
          interval: 0,
          color: '#333',
          rotate: 30,
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
            .map(
              (item: any) =>
                `<div style="margin-top: 3px;">${
                  item.marker
                } <span style="display: inline-block; width: 70px; margin-right: 12px;">${
                  item.seriesName
                }</span><span style="display: inline-block; width: 90px; text-align: right; font-weight: 500;">${
                  item.value === ''
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
        left: '2%',
        right: '1%',
        bottom: '3%',
        top: 20,
        containLabel: true,
      },
      series: {
        type: 'bar',
        name: metricColumn?.name,
        barWidth: 20,
        itemStyle: {
          borderRadius: [10, 10, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: CHART_BLUE_COLOR },
            { offset: 1, color: getChartLightenColor(CHART_BLUE_COLOR) },
          ]),
        },
        label: {
          show: true,
          position: 'top',
          formatter: function ({ value }: any) {
            return value === 0
              ? 0
              : metricField.dataFormatType === 'percent'
              ? formatByDataFormatType(value, metricField.dataFormatType, metricField.dataFormat)
              : getFormattedValue(value);
          },
        },
        data: chartData.map(item => item.value),
      },
    });
    instanceObj.resize();
  };

  useEffect(() => {
    if (queryResults && queryResults.length > 0 && metricColumn?.authorized) {
      return renderChartWhenContainerReady(() => chartRef.current, renderChart);
    }
  }, [queryResults]);

  useEffect(() => {
    if (triggerResize && instanceRef.current) {
      instanceRef.current.resize();
    }
  }, [triggerResize]);

  const prefixCls = `${PREFIX_CLS}-bar`;

  const { downloadChartAsImage } = useExportByEcharts({
    instanceRef,
    question,
  });

  const { register } = useContext(ChartItemContext);

  register('downloadChartAsImage', downloadChartAsImage);

  if (metricColumn && !metricColumn?.authorized) {
    return (
      <NoPermissionChart
        model={entityInfo?.dataSetInfo.name || ''}
        chartType="barChart"
        onApplyAuth={onApplyAuth}
      />
    );
  }

  return (
    <div>
      <div className={`${prefixCls}-top-bar`}>
        <div className={`${prefixCls}-indicator-name`}>{question}</div>
      </div>
      <Spin spinning={loading}>
        <div className={`${prefixCls}-chart`} ref={chartRef} />
      </Spin>
    </div>
  );
};

export default BarChart;
