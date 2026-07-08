/**
 * 模块说明：展示当前智能助理下可复用的成功问答案例。
 * 职责描述：分页拉取 showcase 案例、渲染历史问答结果，并在弹窗布局变化后通知下游图表重算尺寸。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import styles from './style.module.less';
import { ShowCaseItemType } from './type';
import { queryShowCase } from './service';
import Text from '../Chat/components/Text';
import ChatItem from '../components/ChatItem';
import { HistoryMsgItemType } from '../common/type';
import { Spin } from 'antd';
import classNames from 'classnames';
import { isMobile } from '../utils/utils';
import { useThrottleFn } from 'ahooks';

const DESKTOP_PAGE_SIZE = 20;
const MOBILE_PAGE_SIZE = 10;
const SCROLL_BOTTOM_TOLERANCE = 1;

type Props = {
  height?: number | string;
  agentId: number;
  onSendMsg?: (msg: string) => void;
};

/**
 * 展示当前 Agent 的高分历史问答案例。
 *
 * @param props.height 外层滚动容器高度，桌面弹窗场景由父组件传入。
 * @param props.agentId 当前智能助理 ID，用于限定 showcase 查询范围。
 * @param props.onSendMsg 点击相似问题后复用父级发送逻辑。
 * @returns showcase 案例列表组件。
 * @throws 不主动抛出异常；接口异常由统一请求层处理，本组件负责释放 loading 状态。
 *
 * @example
 * <ShowCase height="calc(100vh - 140px)" agentId={agentId} onSendMsg={sendMsg} />
 */
const ShowCase: React.FC<Props> = ({ height, agentId, onSendMsg }) => {
  const [showCaseList, setShowCaseList] = useState<ShowCaseItemType[]>([]);
  const [loading, setLoading] = useState(false);
  const [pageNo, setPageNo] = useState(1);
  const [triggerResize, setTriggerResize] = useState(false);

  const showcaseRef = useRef<HTMLDivElement | null>(null);
  const activeAgentIdRef = useRef(agentId);
  const mountedRef = useRef(false);
  const resizeFrameRef = useRef<number>();

  /**
   * 通知下游 ECharts 在容器宽度稳定后 resize。
   *
   * @returns 无返回值。
   * @throws 不主动抛出异常；非浏览器环境直接跳过。
   */
  const requestChartResize = useCallback(() => {
    if (typeof window === 'undefined') {
      return;
    }
    if (resizeFrameRef.current !== undefined) {
      window.cancelAnimationFrame(resizeFrameRef.current);
    }
    // showcase 从固定双列改为自适应网格后，卡片宽度会随案例数量变化，必须等布局提交后再触发图表重排。
    resizeFrameRef.current = window.requestAnimationFrame(() => {
      setTriggerResize(value => !value);
      resizeFrameRef.current = undefined;
    });
  }, []);

  /**
   * 分页加载 showcase 案例。
   *
   * @param pageNoValue 要加载的页码，第一页会覆盖旧数据，后续页追加。
   * @returns 无返回值。
   * @throws 不主动抛出异常；异常会记录到控制台并释放 loading，避免弹窗一直处于加载态。
   */
  const updateData = useCallback(async (pageNoValue: number) => {
    if (pageNoValue === 1) {
      setLoading(true);
    }
    const requestAgentId = agentId;
    try {
      const pageSize = isMobile ? MOBILE_PAGE_SIZE : DESKTOP_PAGE_SIZE;
      const res = await queryShowCase(requestAgentId, pageNoValue, pageSize);
      if (!mountedRef.current || activeAgentIdRef.current !== requestAgentId) {
        return;
      }
      const showCaseMapRes: any = res.data?.showCaseMap || {};
      const list = Object.keys(showCaseMapRes)
        .reduce((result: ShowCaseItemType[], key: string) => {
          result.push({ msgList: showCaseMapRes[key], caseId: key });
          return result;
        }, [])
        .sort((a, b) => {
          return (b.msgList?.[0]?.score || 3) - (a.msgList?.[0]?.score || 3);
        });
      setShowCaseList(prevList => (pageNoValue === 1 ? list : [...prevList, ...list]));
      requestChartResize();
    } catch (error) {
      if (mountedRef.current && activeAgentIdRef.current === requestAgentId) {
        console.error('[ShowCase] queryShowCase failed', error);
      }
    } finally {
      if (mountedRef.current && activeAgentIdRef.current === requestAgentId && pageNoValue === 1) {
        setLoading(false);
      }
    }
  }, [agentId, requestChartResize]);

  const { run: handleScroll } = useThrottleFn(
    (e: Event) => {
      const target = e.target as HTMLElement | null;
      if (!target) {
        return;
      }
      const bottom =
        target.scrollHeight - target.scrollTop - target.clientHeight <= SCROLL_BOTTOM_TOLERANCE;
      if (bottom) {
        updateData(pageNo + 1);
        setPageNo(pageNo + 1);
      }
    },
    {
      leading: true,
      trailing: true,
      wait: 200,
    }
  );

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (resizeFrameRef.current !== undefined && typeof window !== 'undefined') {
        window.cancelAnimationFrame(resizeFrameRef.current);
      }
    };
  }, []);

  useEffect(() => {
    activeAgentIdRef.current = agentId;
  }, [agentId]);

  useEffect(() => {
    if (isMobile) {
      return;
    }
    const el = showcaseRef.current;
    el?.addEventListener('scroll', handleScroll);
    return () => {
      el?.removeEventListener('scroll', handleScroll);
    };
  }, [handleScroll]);

  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') {
      return;
    }
    const el = showcaseRef.current;
    if (!el) {
      return;
    }
    const observer = new ResizeObserver(requestChartResize);
    observer.observe(el);
    return () => {
      observer.disconnect();
    };
  }, [requestChartResize]);

  useEffect(() => {
    if (agentId) {
      setShowCaseList([]);
      updateData(1);
      setPageNo(1);
    }
  }, [agentId, updateData]);

  const showCaseClass = classNames(styles.showCase, { [styles.mobile]: isMobile });

  return (
    <Spin spinning={loading} size="large">
      <div className={showCaseClass} style={{ height }} ref={showcaseRef}>
        <div className={styles.showCaseContent}>
          {showCaseList.map(showCaseItem => {
            return (
              <div key={showCaseItem.caseId} className={styles.showCaseItem}>
                {showCaseItem.msgList
                  .filter((chatItem: HistoryMsgItemType) => !!chatItem.queryResult)
                  .slice(0, 1)
                  .map((chatItem: HistoryMsgItemType) => {
                    return (
                      <div className={styles.showCaseChatItem} key={chatItem.questionId}>
                        <Text position="right" data={chatItem.queryText} anonymousUser />
                        <ChatItem
                          msg={chatItem.queryText}
                          parseInfos={chatItem.parseInfos}
                          msgData={chatItem.queryResult}
                          conversationId={chatItem.chatId}
                          agentId={agentId}
                          integrateSystem="showcase"
                          score={chatItem.score}
                          triggerResize={triggerResize}
                          onSendMsg={onSendMsg}
                        />
                      </div>
                    );
                  })}
              </div>
            );
          })}
        </div>
      </div>
    </Spin>
  );
};

export default ShowCase;
