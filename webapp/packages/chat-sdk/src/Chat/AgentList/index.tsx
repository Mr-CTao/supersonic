/**
 * 模块说明：智能助理列表侧栏组件。
 * 职责描述：展示可切换的智能助理，并通过可见态样式配合父级完成侧栏展开/收起动画。
 */
import { PlusCircleOutlined } from '@ant-design/icons';
import { AgentType } from '../type';
import styles from './style.module.less';
import classNames from 'classnames';
import { message } from 'antd';
import IconFont from '../../components/IconFont';
import { AGENT_ICONS } from '../constants';

type Props = {
  agentList: AgentType[];
  currentAgent?: AgentType;
  visible?: boolean;
  onSelectAgent: (agent: AgentType) => void;
  onLayoutTransitionEnd?: () => void;
};

/**
 * 渲染桌面端智能助理侧栏。
 *
 * @param props.agentList 可用智能助理列表。
 * @param props.currentAgent 当前选中的智能助理。
 * @param props.visible 是否展示侧栏；隐藏时组件保持挂载以便播放收起动画。
 * @param props.onSelectAgent 选择智能助理时的回调。
 * @param props.onLayoutTransitionEnd 侧栏宽度动画结束后的布局稳定回调。
 * @returns 智能助理侧栏节点。
 * @throws 不主动抛出异常。
 */
const AgentList: React.FC<Props> = ({
  agentList,
  currentAgent,
  visible = true,
  onSelectAgent,
  onLayoutTransitionEnd,
}) => {
  // 该入口暂未开放真实创建逻辑，保留提示以避免用户误以为点击无反馈。
  const onAddAgent = () => {
    message.info('正在开发中，敬请期待');
  };

  // 只响应外层占位宽度动画结束，内层 transform/opacity 结束不触发布局恢复。
  const onAgentListTransitionEnd = (e: React.TransitionEvent<HTMLDivElement>) => {
    if (
      e.currentTarget !== e.target ||
      (e.propertyName !== 'width' && e.propertyName !== 'flex-basis')
    ) {
      return;
    }
    onLayoutTransitionEnd?.();
  };

  const agentListClass = classNames(styles.agentList, {
    [styles.visible]: visible,
  });

  return (
    <div className={agentListClass} onTransitionEnd={onAgentListTransitionEnd}>
      <div className={styles.agentListPanel}>
        <div className={styles.header}>
          <div className={styles.headerTitle}>智能助理</div>
          <PlusCircleOutlined className={styles.plusIcon} onClick={onAddAgent} />
        </div>
        <div className={styles.agentListContent}>
          {agentList.map((agent, index) => {
            const agentItemClass = classNames(styles.agentItem, {
              [styles.active]: currentAgent?.id === agent.id,
            });
            return (
              <div
                key={agent.id}
                className={agentItemClass}
                onClick={() => {
                  onSelectAgent(agent);
                }}
              >
                <IconFont
                  type={AGENT_ICONS[index % AGENT_ICONS.length]}
                  className={styles.avatar}
                />
                <div className={styles.agentInfo}>
                  <div className={styles.agentName}>{agent.name}</div>
                  <div className={styles.agentDesc}>{agent.description}</div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default AgentList;
