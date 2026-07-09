/**
 * 聊天消息结果表格模块。
 *
 * 负责把服务端返回的查询列与查询结果渲染为 Ant Design Table，并补齐行 key、日期排序、
 * 权限申请入口等与聊天结果展示相关的 UI 兼容逻辑。
 */
import { formatByDataFormatType, formatByThousandSeperator } from '../../../utils/utils';
import { Table as AntTable } from 'antd';
import { MsgDataType } from '../../../common/type';
import { CLS_PREFIX } from '../../../common/constants';
import ApplyAuth from '../ApplyAuth';
import type { ConfigProviderProps } from 'antd';
import moment from 'moment';

type Props = {
  data: MsgDataType;
  size?: NonNullable<ConfigProviderProps['componentSize']>;
  question?: string;
  loading?: boolean;
  onApplyAuth?: (model: string) => void;
};

type TableRecord = Record<string, unknown> & {
  __supersonicTableRowKey: string;
};

/**
 * 渲染聊天查询结果表格。
 *
 * @param props 聊天结果表格渲染参数，包含数据、尺寸、加载态和权限申请回调。
 * @returns 用于展示查询结果的 React 节点。
 */
const Table: React.FC<Props> = ({ data, size, loading, question, onApplyAuth }) => {
  const { entityInfo, queryColumns, queryResults } = data;

  const prefixCls = `${CLS_PREFIX}-table`;
  const tableColumns: any[] = queryColumns.map(
    ({ name, bizName, showType, dataFormatType, dataFormat, authorized }) => {
      return {
        dataIndex: bizName,
        key: bizName,
        title: name || bizName,
        sorter:
          showType === 'NUMBER'
            ? (a, b) => {
                return a[bizName] - b[bizName];
              }
            : undefined,
        render: (value: string | number) => {
          if (!authorized) {
            return (
              <ApplyAuth model={entityInfo?.dataSetInfo.name || ''} onApplyAuth={onApplyAuth} />
            );
          }
          if (dataFormatType === 'percent') {
            return (
              <div className={`${prefixCls}-formatted-value`}>
                {`${
                  value
                    ? formatByDataFormatType(value, dataFormatType, dataFormat)
                    : '0%'
                }`}
              </div>
            );
          }
          if (showType === 'NUMBER') {
            return (
              <div className={`${prefixCls}-formatted-value`}>
                {/* {getFormattedValue(value as number)} */}
                {formatByThousandSeperator(value)}
              </div>
            );
          }
          if (bizName.includes('photo')) {
            return (
              <div className={`${prefixCls}-photo`}>
                <img width={40} height={40} src={value as string} alt="" />
              </div>
            );
          }
          return value;
        },
      };
    }
  );

  const getRowClassName = (_: any, index: number) => {
    return index % 2 !== 0 ? `${prefixCls}-even-row` : '';
  };

  const dateColumn = queryColumns.find(column => column.type === 'DATE' || column.showType === 'DATE');
  const sortedQueryResults = dateColumn
    ? [...queryResults].sort((a, b) =>
        moment(a[dateColumn.bizName]).diff(moment(b[dateColumn.bizName]))
      )
    : queryResults;
  const baseDataSource = dateColumn
    ? sortedQueryResults
    : queryResults;

  const getTableRowKey = (record: Record<string, unknown>, index: number) => {
    const stableId = record.id ?? record.key ?? record.__rowKey;
    if (stableId !== undefined && stableId !== null && stableId !== '') {
      return String(stableId);
    }

    // 聊天查询结果通常没有后端行 id，按当前列值组合兜底，避免 React 在 antd 6 表格 Body 中重复报 key warning。
    const rowValues = queryColumns.map(({ bizName }) => record[bizName] ?? '').join('|');
    return `${index}-${rowValues}`;
  };

  const dataSource: TableRecord[] = baseDataSource.map((record, index) => ({
    ...record,
    __supersonicTableRowKey: getTableRowKey(record, index),
  }));

  return (
    <div className={prefixCls}>
      {question && (
        <div className={`${prefixCls}-top-bar`}>
          <div className={`${prefixCls}-indicator-name`}>{question}</div>
        </div>
      )}

      <AntTable
        pagination={
          queryResults.length <= 10 ? false : { defaultPageSize: 10, position: ['bottomCenter'] }
        }
        columns={tableColumns}
        dataSource={dataSource}
        style={{ width: '100%', overflowX: 'auto', overflowY: 'hidden' }}
        rowClassName={getRowClassName}
        rowKey="__supersonicTableRowKey"
        size={size}
        loading={loading}
      />
    </div>
  );
};

export default Table;
