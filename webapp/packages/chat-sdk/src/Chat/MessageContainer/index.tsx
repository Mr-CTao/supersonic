/**
 * 模块说明：聊天消息列表容器。
 * 职责描述：渲染当前会话的用户问题、助手回复和图表结果，并在真实窗口尺寸变化时通知图表重算尺寸。
 */
import Text from '../components/Text';
import { memo, useCallback, useEffect, useRef, useState } from 'react';
import { isEqual } from 'lodash';
import { AgentType, MessageItem, MessageTypeEnum } from '../type';
import { isMobile, updateMessageContainerScroll } from '../../utils/utils';
import styles from './style.module.less';
import AgentTip from '../components/AgentTip';
import classNames from 'classnames';
import { MsgDataType } from '../../common/type';
import ChatItem from '../../components/ChatItem';

const LAYOUT_RESIZE_SETTLE_DELAY = 80;
const RESIZE_PULSE_RESET_DELAY = 60;

type Props = {
  id: string;
  chatId: number;
  messageList: MessageItem[];
  layoutResizeSignal?: number;
  currentAgent?: AgentType;
  chatVisible?: boolean;
  isDeveloper?: boolean;
  integrateSystem?: string;
  isSimpleMode?: boolean;
  isDebugMode?: boolean;
  onMsgDataLoaded: (
    data: MsgDataType,
    questionId: string | number,
    question: string,
    valid: boolean,
    isRefresh?: boolean
  ) => void;
  onSendMsg: (value: string) => void;
};

/**
 * 渲染聊天消息列表，并将窗口尺寸变化同步给下游图表组件。
 *
 * @param props.id 消息容器 DOM id，用于滚动定位。
 * @param props.chatId 当前会话 ID，发送追问和刷新时传给后端。
 * @param props.messageList 当前会话消息列表。
 * @param props.layoutResizeSignal 外部布局动画结束后的稳定态 resize 信号。
 * @param props.currentAgent 当前智能助理信息。
 * @param props.chatVisible 外层聊天容器显隐状态，显隐变化时需要触发图表 resize。
 * @param props.isDeveloper 是否展示开发者调试信息。
 * @param props.integrateSystem 集成系统标识，用于控制局部功能显隐。
 * @param props.isSimpleMode 是否开启精简模式。
 * @param props.isDebugMode 是否开启调试模式。
 * @param props.onMsgDataLoaded 消息结果加载完成回调。
 * @param props.onSendMsg 发送问题回调。
 * @returns 消息列表 React 节点。
 * @throws 不主动抛出异常；子组件请求异常由各自逻辑处理。
 */
const MessageContainer: React.FC<Props> = ({
  id,
  chatId,
  messageList,
  layoutResizeSignal,
  currentAgent,
  chatVisible,
  isDeveloper,
  integrateSystem,
  isSimpleMode,
  isDebugMode,
  onMsgDataLoaded,
  onSendMsg,
}) => {
  const [triggerResize, setTriggerResize] = useState(false);
  const messageContainerRef = useRef<HTMLDivElement | null>(null);
  const resizeObserverWidthRef = useRef<number>();
  const resizeQueueTimerRef = useRef<number>();
  const resizeFrameRef = useRef<number>();
  const resizeResetTimerRef = useRef<number>();

  /**
   * 发送一次可被图表组件稳定感知的 resize 脉冲。
   *
   * @returns 无返回值。
   * @throws 不主动抛出异常；非浏览器环境直接跳过。
   */
  const pulseChartResize = useCallback(() => {
    if (typeof window === 'undefined') {
      return;
    }
    if (resizeFrameRef.current !== undefined) {
      window.cancelAnimationFrame(resizeFrameRef.current);
    }
    if (resizeResetTimerRef.current !== undefined) {
      window.clearTimeout(resizeResetTimerRef.current);
    }
    // 先回落到 false，再在下一帧置 true，确保子图表的 useEffect 一定能观察到 true 边沿。
    setTriggerResize(false);
    resizeFrameRef.current = window.requestAnimationFrame(() => {
      setTriggerResize(true);
      resizeFrameRef.current = undefined;
      resizeResetTimerRef.current = window.setTimeout(() => {
        setTriggerResize(false);
        resizeResetTimerRef.current = undefined;
      }, RESIZE_PULSE_RESET_DELAY);
    });
  }, []);

  /**
   * 延迟到布局稳定后再触发图表 resize。
   *
   * @param delay 等待布局停止变化的毫秒数。
   * @returns 无返回值。
   * @throws 不主动抛出异常；非浏览器环境直接跳过。
   */
  const scheduleChartResize = useCallback((delay = 0) => {
    if (typeof window === 'undefined') {
      return;
    }
    if (resizeQueueTimerRef.current !== undefined) {
      window.clearTimeout(resizeQueueTimerRef.current);
    }
    resizeQueueTimerRef.current = window.setTimeout(() => {
      pulseChartResize();
      resizeQueueTimerRef.current = undefined;
    }, delay);
  }, [pulseChartResize]);

  /**
   * 通知图表在真实视口变化后重算尺寸。
   *
   * @returns 无返回值。
   * @throws 不主动抛出异常。
   */
  const onResize = useCallback(() => {
    scheduleChartResize();
  }, [scheduleChartResize]);

  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') {
      return;
    }
    const ele = messageContainerRef.current;
    if (!ele) {
      return;
    }
    const observer = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width;
      if (width === undefined) {
        return;
      }
      if (resizeObserverWidthRef.current === undefined) {
        resizeObserverWidthRef.current = width;
        return;
      }
      if (Math.abs(width - resizeObserverWidthRef.current) < 0.5) {
        return;
      }
      resizeObserverWidthRef.current = width;
      // 历史侧栏宽度动画期间会连续改变消息区宽度，等最后一次变化后再 resize 图表。
      scheduleChartResize(LAYOUT_RESIZE_SETTLE_DELAY);
    });
    observer.observe(ele);
    return () => {
      observer.disconnect();
    };
  }, [scheduleChartResize]);

  useEffect(() => {
    return () => {
      if (typeof window === 'undefined') {
        return;
      }
      if (resizeQueueTimerRef.current !== undefined) {
        window.clearTimeout(resizeQueueTimerRef.current);
      }
      if (resizeFrameRef.current !== undefined) {
        window.cancelAnimationFrame(resizeFrameRef.current);
      }
      if (resizeResetTimerRef.current !== undefined) {
        window.clearTimeout(resizeResetTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      if (resizeResetTimerRef.current !== undefined) {
        window.clearTimeout(resizeResetTimerRef.current);
      }
    };
  }, [onResize]);

  useEffect(() => {
    onResize();
  }, [chatVisible, layoutResizeSignal, onResize]);

  const messageContainerClass = classNames(styles.messageContainer, { [styles.mobile]: isMobile });
  return (
    <div id={id} className={messageContainerClass} ref={messageContainerRef}>
      <div className={styles.messageList}>
        {messageList.map((msgItem: MessageItem, index: number) => {
          const {
            id: msgId,
            questionId,
            modelId,
            agentId,
            type,
            msg,
            msgValue,
            score,
            identityMsg,
            parseInfos,
            parseTimeCost,
            msgData,
            filters,
          } = msgItem;

          return (
            <div key={msgId} id={`${msgId}`} className={styles.messageItem}>
              {type === MessageTypeEnum.TEXT && <Text position="left" data={msg} />}
              {type === MessageTypeEnum.AGENT_LIST && (
                <AgentTip currentAgent={currentAgent} onSendMsg={onSendMsg} />
              )}
              {type === MessageTypeEnum.QUESTION && (
                <>
                  <Text position="right" data={msg} />
                  {identityMsg && <Text position="left" data={identityMsg} />}
                  <ChatItem
                    questionId={questionId}
                    currentAgent={currentAgent}
                    isSimpleMode={isSimpleMode}
                    isDebugMode={isDebugMode}
                    msg={msgValue || msg || ''}
                    parseInfos={parseInfos}
                    parseTimeCostValue={parseTimeCost}
                    msgData={msgData}
                    conversationId={chatId}
                    modelId={modelId}
                    agentId={agentId}
                    score={score}
                    filter={filters}
                    triggerResize={triggerResize}
                    isDeveloper={isDeveloper}
                    integrateSystem={integrateSystem}
                    onMsgDataLoaded={(data: MsgDataType, valid: boolean, isRefresh) => {
                      onMsgDataLoaded(data, msgId, msgValue || msg || '', valid, isRefresh);
                    }}
                    onUpdateMessageScroll={updateMessageContainerScroll}
                    onSendMsg={onSendMsg}
                    isLastMessage={index === messageList.length - 1}
                  />
                </>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

function areEqual(prevProps: Props, nextProps: Props) {
  if (
    prevProps.id === nextProps.id &&
    isEqual(prevProps.messageList, nextProps.messageList) &&
    prevProps.layoutResizeSignal === nextProps.layoutResizeSignal &&
    prevProps.currentAgent === nextProps.currentAgent &&
    prevProps.chatVisible === nextProps.chatVisible &&
    prevProps.isSimpleMode === nextProps.isSimpleMode
  ) {
    return true;
  }
  return false;
}

export default memo(MessageContainer, areEqual);
