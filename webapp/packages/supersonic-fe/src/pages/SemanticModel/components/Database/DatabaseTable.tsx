/**
 * 数据库管理列表模块。
 *
 * 负责数据库连接的查询、创建、编辑和删除入口，并提供填满可用区域的统一表格布局。
 */
import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, message, Popconfirm, Space } from 'antd';
import moment from 'moment';
import React, { useEffect, useRef, useState } from 'react';
import DatabaseSettingModal from './DatabaseSettingModal';
import { ISemantic } from '../../data';
import { deleteDatabase, getDatabaseList } from '../../service';
import styles from './style.less';

type Props = {};

const DatabaseTable: React.FC<Props> = ({}) => {
  const [createModalVisible, setCreateModalVisible] = useState<boolean>(false);
  const [databaseItem, setDatabaseItem] = useState<ISemantic.IDatabaseItem>();
  const [dataBaseList, setDataBaseList] = useState<any[]>([]);
  const [deletingId, setDeletingId] = useState<number>();
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20 });

  const actionRef = useRef<ActionType>();

  /**
   * 查询当前用户可见的数据库连接。
   *
   * @returns 数据加载完成后的 Promise。
   * @throws 请求层未捕获的网络异常会继续向上传播，由全局请求拦截器统一处理。
   */
  const queryDatabaseList = async () => {
    const { code, data, msg } = await getDatabaseList();
    if (code === 200) {
      setDataBaseList(data ?? []);
    } else {
      message.error(msg);
    }
  };

  useEffect(() => {
    queryDatabaseList();
  }, []);

  const columns: ProColumns[] = [
    {
      dataIndex: 'sequence',
      title: '序号',
      width: 80,
      search: false,
      render: (_, __, index) => (pagination.current - 1) * pagination.pageSize + index + 1,
    },
    {
      dataIndex: 'name',
      title: '连接名称',
      width: 220,
    },
    {
      dataIndex: 'type',
      title: '类型',
      search: false,
      width: 140,
    },
    {
      dataIndex: 'createdBy',
      title: '创建人',
      search: false,
      width: 140,
    },
    {
      dataIndex: 'description',
      title: '描述',
      search: false,
      width: 500,
    },
    {
      dataIndex: 'updatedAt',
      title: '更新时间',
      search: false,
      width: 220,
      render: (value: any) => {
        return value && value !== '-' ? moment(value).format('YYYY-MM-DD HH:mm:ss') : '-';
      },
    },
    {
      title: '操作',
      dataIndex: 'x',
      valueType: 'option',
      width: 140,
      render: (_, record) => {
        if (!record.hasEditPermission) {
          return <></>;
        }
        return (
          <Space>
            <a
              key="dimensionEditBtn"
              onClick={() => {
                setDatabaseItem(record);
                setCreateModalVisible(true);
              }}
            >
              编辑
            </a>
            <Popconfirm
              title="确认删除？"
              okText="是"
              cancelText="否"
              okButtonProps={{ loading: deletingId === record.id }}
              onConfirm={async () => {
                // 删除期间锁定确认按钮，避免网络延迟导致同一连接被重复提交删除。
                setDeletingId(record.id);
                try {
                  const { code, msg } = await deleteDatabase(record.id);
                  if (code === 200) {
                    setDatabaseItem(undefined);
                    await queryDatabaseList();
                  } else {
                    message.error(msg);
                  }
                } finally {
                  setDeletingId(undefined);
                }
              }}
            >
              <a
                key="dimensionDeleteEditBtn"
                onClick={() => {
                  setDatabaseItem(record);
                }}
              >
                删除
              </a>
            </Popconfirm>
          </Space>
        );
      },
    },
  ].map((column) => ({
    ...column,
    align: 'center',
    ellipsis: column.valueType === 'option' ? false : true,
  }));

  return (
    <div className={styles.databasePage}>
      <ProTable
        className={styles.databaseTable}
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        dataSource={dataBaseList}
        search={false}
        tableAlertRender={() => {
          return false;
        }}
        size="small"
        scroll={{ x: 1440, y: '100%' }}
        pagination={{
          current: pagination.current,
          pageSize: pagination.pageSize,
          showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条/总共 ${total} 条`,
          onChange: (current, pageSize) => {
            setPagination({ current, pageSize });
          },
        }}
        options={{ reload: false, density: false, fullScreen: false }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setDatabaseItem(undefined);
              setCreateModalVisible(true);
            }}
          >
            创建数据库连接
          </Button>,
        ]}
      />
      {createModalVisible && (
        <DatabaseSettingModal
          open={createModalVisible}
          databaseItem={databaseItem}
          onCancel={() => {
            setCreateModalVisible(false);
          }}
          onSubmit={() => {
            setCreateModalVisible(false);
            queryDatabaseList();
          }}
        />
      )}
    </div>
  );
};
export default DatabaseTable;
