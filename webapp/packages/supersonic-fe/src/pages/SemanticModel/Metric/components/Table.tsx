/**
 * 指标详情数据表格模块。
 *
 * 负责把指标趋势或明细查询结果渲染为 antd Table，并为查询结果补充内部行 key，
 * 避免后端明细数据没有 `key` 字段时在 React 18 下产生列表 key 警告。
 */
import { Table } from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import moment from 'moment';
import { ColumnConfig } from '../data';

type Props = {
  columnConfig?: ColumnConfig[];
  dataSource: any;
  metricFieldName: string;
  dateFieldName?: string;
  loading?: boolean;
};

const METRIC_TABLE_ROW_KEY = '__metricTableRowKey';

/**
 * 生成指标查询结果表格的数据源。
 *
 * @param dataSource 原始查询结果。
 * @returns 带内部行 key 的表格数据源。
 * @throws 不主动抛出异常；非数组数据会规整为空数组。
 */
const getMetricTableDataSource = (dataSource: any) => {
  if (!Array.isArray(dataSource)) {
    return [];
  }

  return dataSource.map((item, index) => {
    const record = typeof item === 'object' && item !== null ? item : { value: item };
    const stableId = record.id ?? record.key ?? record[METRIC_TABLE_ROW_KEY];
    const rowKey = stableId !== undefined && stableId !== null && stableId !== ''
      ? stableId
      : `${index}-${JSON.stringify(record)}`;

    return {
      ...record,
      [METRIC_TABLE_ROW_KEY]: String(rowKey),
    };
  });
};

/**
 * 渲染指标查询结果表格。
 *
 * @param props 表格列配置、数据源、日期字段、指标字段和加载态。
 * @returns 指标结果表格。
 */
const MetricTable: React.FC<Props> = ({
  columnConfig,
  dataSource,
  dateFieldName = 'sys_imp_date',
  metricFieldName,
  loading = false,
}) => {
  const [columns, setColumns] = useState<TableColumnsType<any>>([]);
  const tableDataSource = useMemo(() => getMetricTableDataSource(dataSource), [dataSource]);

  useEffect(() => {
    if (Array.isArray(columnConfig)) {
      const config: TableColumnsType<any> = columnConfig.map((item: ColumnConfig) => {
        const { name, nameEn } = item;
        if (nameEn === dateFieldName) {
          return {
            title: '日期',
            dataIndex: nameEn,
            key: nameEn,
            width: 120,
            fixed: 'left',
            defaultSortOrder: 'descend',
            sorter: (a, b) => moment(a[nameEn]).valueOf() - moment(b[nameEn]).valueOf(),
          };
        }
        if (nameEn === metricFieldName) {
          return {
            title: name,
            dataIndex: nameEn,
            key: nameEn,
            sortDirections: ['descend'],
            sorter: (a, b) => a[nameEn] - b[nameEn],
          };
        }
        return {
          title: name,
          key: nameEn,
          dataIndex: nameEn,
        };
      });
      setColumns(config);
    }
  }, [columnConfig]);

  return (
    <div style={{ height: '100%' }}>
      {/* {Array.isArray(columns) && columns.length > 0 && ( */}
      <Table
        rowKey={METRIC_TABLE_ROW_KEY}
        columns={columns}
        dataSource={tableDataSource}
        scroll={{ x: 200, y: 700 }}
        loading={loading}
        onChange={() => {}}
      />
      {/* )} */}
    </div>
  );
};

export default MetricTable;
