/**
 * 模块说明：ChatMsg 饼图组件。
 * 职责描述：将单指标、单分类的查询结果渲染为 ECharts 饼图，并在渲染前规整空分类和非法数值，避免异常数据击穿图表运行时。
 */

import { PREFIX_CLS, THEME_COLOR_LIST } from '../../../common/constants';
import { MsgDataType } from '../../../common/type';
import { formatByDataFormatType, getFormattedValue } from '../../../utils/utils';
import type { ECharts } from 'echarts';
import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { ColumnType } from '../../../common/type';
import { normalizeChartCategoryName, toFiniteChartNumber } from '../chartData';

type Props = {
  data: MsgDataType;
  metricField: ColumnType;
  categoryField: ColumnType;
  triggerResize?: boolean;
};

/**
 * 渲染聚合结果饼图。
 *
 * @param props.data 问答查询结果，包含 queryResults。
 * @param props.metricField 饼图扇区数值字段。
 * @param props.categoryField 饼图扇区分类字段。
 * @param props.triggerResize 外部布局变化时触发 ECharts resize 的标记。
 * @returns 饼图容器节点。
 * @throws 不主动抛出异常；空分类和非法指标值会在进入 ECharts 前被规整或过滤。
 */
const PieChart: React.FC<Props> = ({
  data,
  metricField,
  categoryField,
  triggerResize,
}) => {
  const chartRef = useRef<any>();
  const instanceRef = useRef<ECharts>();

  const { queryResults } = data;
  const categoryColumnName = categoryField?.bizName || '';
  const metricColumnName = metricField?.bizName || '';

  // 将查询结果规整成 ECharts 饼图数据，防止空分类或非法数值进入 legend / series。
  const renderChart = () => {
    let instanceObj: any;
    if (!instanceRef.current) {
      instanceObj = echarts.init(chartRef.current);
      instanceRef.current = instanceObj;
    } else {
      instanceObj = instanceRef.current;
    }

    const data = queryResults || [];
    const seriesData = data.reduce<any[]>((result, item, index) => {
      const value = toFiniteChartNumber(item[metricColumnName]);
      if (value === undefined || value < 0) {
        return result;
      }
      const name = normalizeChartCategoryName(item[categoryColumnName]);
      // ECharts 的 scroll legend 会读取每个图例项的 name，不能让 null 分类值穿透到配置中。
      result.push({
        name,
        value,
        itemStyle: {
          color: THEME_COLOR_LIST[index % THEME_COLOR_LIST.length],
        },
      });
      return result;
    }, []);

    instanceObj.setOption({
      tooltip: {
        trigger: 'item',
        formatter: function (params: any) {
          const value = params.value;
          return `${params.name}: ${
            metricField.dataFormatType === 'percent'
              ? formatByDataFormatType(value, metricField.dataFormatType, metricField.dataFormat)
              : getFormattedValue(value)
          }`;
        },
      },
      legend: {
        orient: 'vertical',
        left: 'left',
        type: 'scroll',
        data: seriesData.map(item => item.name),
        selectedMode: true,
        textStyle: {
          color: '#666',
        },
      },
      series: [
        {
          name: '占比',
          type: 'pie',
          radius: ['40%', '70%'],
          avoidLabelOverlap: false,
          itemStyle: {
            borderRadius: 10,
            borderColor: '#fff',
            borderWidth: 2,
          },
          label: {
            show: false,
            position: 'center',
          },
          emphasis: {
            label: {
              show: true,
              fontSize: '14',
              fontWeight: 'bold',
            },
          },
          labelLine: {
            show: false,
          },
          data: seriesData,
        },
      ],
    });
    instanceObj.resize();
  };

  useEffect(() => {
    if (queryResults && queryResults.length > 0) {
      renderChart();
    }
  }, [queryResults, metricField, categoryField]);

  useEffect(() => {
    if (triggerResize && instanceRef.current) {
      instanceRef.current.resize();
    }
  }, [triggerResize]);

  return <div className={`${PREFIX_CLS}-pie-chart`} ref={chartRef} />;
};

export default PieChart;
