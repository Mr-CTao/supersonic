/**
 * 标签洞察详情表格模块。
 *
 * 负责展示标签查询结果，并在渲染前为结果行补充内部唯一 key，
 * 避免接口数据缺少默认 `key` 字段时触发 React 列表 key 警告。
 */
import { Table } from 'antd';
import type { TableColumnsType } from 'antd';
import React, { useMemo } from 'react';
import moment from 'moment';

type Props = {
  columnConfig?: ColumnConfig[];
  dataSource: any;
  loading?: boolean;
};

const TAG_TABLE_ROW_KEY = '__tagTableRowKey';

/**
 * 生成标签查询结果表格的数据源。
 *
 * @param dataSource 原始查询结果。
 * @returns 带内部行 key 的表格数据源。
 * @throws 不主动抛出异常；非数组数据会规整为空数组。
 */
const getTagTableDataSource = (dataSource: any) => {
  if (!Array.isArray(dataSource)) {
    return [];
  }

  return dataSource.map((item, index) => {
    const record = typeof item === 'object' && item !== null ? item : { value: item };
    const stableId = record.id ?? record.key ?? record[TAG_TABLE_ROW_KEY];
    const rowKey = stableId !== undefined && stableId !== null && stableId !== ''
      ? stableId
      : `${index}-${JSON.stringify(record)}`;

    return {
      ...record,
      [TAG_TABLE_ROW_KEY]: String(rowKey),
    };
  });
};

/**
 * 渲染标签查询结果表格。
 *
 * @param props 表格列配置、数据源和加载态。
 * @returns 标签结果表格。
 */
const TagTable: React.FC<Props> = ({ columnConfig, dataSource, loading = false }) => {
  const tableDataSource = useMemo(() => getTagTableDataSource(dataSource), [dataSource]);

  return (
    <div style={{ height: '100%' }}>
      {/* {Array.isArray(columns) && columns.length > 0 && ( */}
      <Table
        rowKey={TAG_TABLE_ROW_KEY}
        columns={columnConfig}
        dataSource={tableDataSource}
        scroll={{ x: 200, y: 700 }}
        loading={loading}
        onChange={() => {}}
      />
      {/* )} */}
    </div>
  );
};

export default TagTable;
