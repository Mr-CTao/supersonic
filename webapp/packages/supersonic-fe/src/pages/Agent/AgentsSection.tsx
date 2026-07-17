/**
 * 助理列表模块。
 *
 * 职责：
 * - 展示智能助理列表、状态和更新时间；
 * - 提供进入编辑、新建助理、删除助理和启停助理的入口；
 * - 为表格指定稳定 rowKey，避免 React 在渲染行时产生 key 警告。
 */
import { PlusOutlined } from '@ant-design/icons';
import { Button, Popconfirm, Switch, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import moment from 'moment';
import { useEffect, useState } from 'react';
import styles from './style.less';
import { AgentType } from './type';

const DEFAULT_PAGE_SIZE = 10;
const TABLE_SCROLL_X = 1000;

type Props = {
  agents: AgentType[];
  loading: boolean;
  onSelectAgent: (agent: AgentType) => void;
  onDeleteAgent: (id: number) => void;

  onSaveAgent: (agent: AgentType, noTip?: boolean) => Promise<void>;
  onCreatBtnClick?: () => void;
};

const AgentsSection: React.FC<Props> = ({
  agents,
  loading,
  onSelectAgent,
  onDeleteAgent,
  onSaveAgent,
  onCreatBtnClick,
}) => {
  const [showAgents, setShowAgents] = useState<AgentType[]>([]);
  const [pageInfo, setPageInfo] = useState({ current: 1, pageSize: DEFAULT_PAGE_SIZE });
  const [savingAgentId, setSavingAgentId] = useState<number>();

  useEffect(() => {
    setShowAgents(agents);
  }, [agents]);

  const columns: ColumnsType<AgentType> = [
    {
      title: '序号',
      key: 'sequence',
      width: 72,
      align: 'center',
      render: (_value, _agent, index) => (pageInfo.current - 1) * pageInfo.pageSize + index + 1,
    },
    {
      title: '助理名称',
      dataIndex: 'name',
      key: 'name',
      width: 160,
      align: 'center',
      render: (value: string, agent: AgentType) => {
        return (
          <a
            onClick={() => {
              onSelectAgent(agent);
            }}
          >
            {value}
          </a>
        );
      },
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      width: 320,
      align: 'center',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 130,
      align: 'center',
      render: (status: number, agent: AgentType) => {
        return (
          <div className={styles.toggleStatus}>
            {status === 0 ? '已禁用' : <span className={styles.online}>已启用</span>}
            <span
              onClick={(e) => {
                e.stopPropagation();
              }}
            >
              <Switch
                key={agent.id}
                size="small"
                defaultChecked={status === 1}
                loading={savingAgentId === agent.id}
                disabled={savingAgentId === agent.id}
                onChange={async (value) => {
                  // 状态切换会立即写入后端，行级锁可避免连续点击造成请求乱序。
                  setSavingAgentId(agent.id);
                  try {
                    await onSaveAgent({ ...agent, status: value ? 1 : 0 }, true);
                  } finally {
                    setSavingAgentId(undefined);
                  }
                }}
              />
            </span>
          </div>
        );
      },
    },
    {
      title: '更新人',
      dataIndex: 'updatedBy',
      key: 'updatedBy',
      width: 100,
      align: 'center',
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 170,
      align: 'center',
      render: (value: string) => {
        return moment(value).format('YYYY-MM-DD HH:mm:ss');
      },
    },
    {
      title: '操作',
      dataIndex: 'x',
      key: 'x',
      width: 100,
      align: 'center',
      fixed: 'right',
      render: (_: any, agent: AgentType) => {
        return (
          <div className={styles.operateIcons}>
            <a
              onClick={() => {
                onSelectAgent(agent);
              }}
            >
              编辑
            </a>
            <Popconfirm
              title="确定删除吗？"
              onCancel={(e) => {
                e?.stopPropagation();
              }}
              onConfirm={() => {
                onDeleteAgent(agent.id!);
              }}
            >
              <a>删除</a>
            </Popconfirm>
          </div>
        );
      },
    },
  ];

  return (
    <div className={styles.agentsSection}>
      <div className={styles.content}>
        <div className={styles.searchBar}>
          <Button
            type="primary"
            onClick={() => {
              onCreatBtnClick?.();
            }}
          >
            <PlusOutlined />
            新建助理
          </Button>
        </div>
        <Table<AgentType>
          className={styles.agentsTable}
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={showAgents}
          tableLayout="fixed"
          scroll={{ x: TABLE_SCROLL_X }}
          pagination={{
            current: pageInfo.current,
            pageSize: pageInfo.pageSize,
            showSizeChanger: true,
            onChange: (current, pageSize) => {
              setPageInfo({ current, pageSize });
            },
          }}
        />
      </div>
    </div>
  );
};

export default AgentsSection;
