/**
 * Copilot 悬浮对话模块。
 *
 * 职责：
 * - 提供右下角机器人入口和浮层对话面板；
 * - 在后台管理页面中复用 Chat SDK 的对话能力；
 * - 支持通过标题栏拖拽调整浮层位置，并限制面板不被拖出浏览器视口。
 */
import IconFont from '../components/IconFont';
import { CaretRightOutlined, CloseOutlined } from '@ant-design/icons';
import classNames from 'classnames';
import {
  CSSProperties,
  ForwardRefRenderFunction,
  MouseEvent as ReactMouseEvent,
  useCallback,
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import Chat from '../Chat';
import { AgentType } from '../Chat/type';
import { setToken } from '../utils/utils';
import { SendMsgParamsType } from '../common/type';
import styles from './style.module.less';
import { copilotTitle } from '../common/env';

type Props = {
  token?: string;
  agentIds?: number[];
  noInput?: boolean;
  isDeveloper?: boolean;
  integrateSystem?: string;
  apiUrl?: string;
  onReportMsgEvent?: (msg: string, valid: boolean) => void;
  onOpenChatPage?: (agentId?: number) => void;
};

type DragPosition = {
  left: number;
  top: number;
  width: number;
  height: number;
};

type DragState = {
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
};

const DRAG_VIEWPORT_PADDING = 16;

/**
 * 将拖拽位置限制在浏览器视口内，避免面板被拖到不可恢复的位置。
 *
 * @param left 期望的面板左侧坐标。
 * @param top 期望的面板顶部坐标。
 * @param width 面板当前宽度。
 * @param height 面板当前高度。
 * @returns 修正后的面板坐标。
 */
const getBoundedDragPosition = (left: number, top: number, width: number, height: number) => {
  const maxLeft = Math.max(DRAG_VIEWPORT_PADDING, window.innerWidth - width - DRAG_VIEWPORT_PADDING);
  const maxTop = Math.max(DRAG_VIEWPORT_PADDING, window.innerHeight - height - DRAG_VIEWPORT_PADDING);

  return {
    left: Math.min(Math.max(left, DRAG_VIEWPORT_PADDING), maxLeft),
    top: Math.min(Math.max(top, DRAG_VIEWPORT_PADDING), maxTop),
  };
};

const Copilot: ForwardRefRenderFunction<any, Props> = (
  {
    token,
    agentIds,
    noInput,
    isDeveloper,
    integrateSystem,
    apiUrl,
    onReportMsgEvent,
    onOpenChatPage,
  },
  ref
) => {
  const [chatVisible, setChatVisible] = useState(false);
  const [copilotMinimized, setCopilotMinimized] = useState(false);
  const [currentAgent, setCurrentAgent] = useState<AgentType>();
  const [dragPosition, setDragPosition] = useState<DragPosition>();
  const [dragging, setDragging] = useState(false);

  const chatRef = useRef<any>();
  const chatPopoverRef = useRef<HTMLDivElement>(null);
  const dragStateRef = useRef<DragState>();

  useImperativeHandle(ref, () => ({
    sendCopilotMsg,
  }));

  useEffect(() => {
    if (token) {
      setToken(token);
    }
  }, [token]);

  useEffect(() => {
    if (apiUrl) {
      localStorage.setItem('SUPERSONIC_CHAT_API_URL', apiUrl);
    }
  }, [apiUrl]);

  const sendCopilotMsg = (params: SendMsgParamsType) => {
    chatRef?.current?.sendCopilotMsg(params);
    updateChatVisible(true);
  };

  const updateChatVisible = (visible: boolean) => {
    setChatVisible(visible);
  };

  const onToggleChatVisible = () => {
    updateChatVisible(!chatVisible);
  };

  const onCloseChat = () => {
    updateChatVisible(false);
  };

  const onTransferChat = () => {
    onOpenChatPage?.(currentAgent?.id);
  };

  const onMinimizeCopilot = (e: any) => {
    e.stopPropagation();
    updateChatVisible(false);
    setCopilotMinimized(true);
  };

  /**
   * 开始拖拽 Copilot 面板。
   *
   * @param e 标题栏鼠标按下事件。
   */
  const onStartDrag = useCallback((e: ReactMouseEvent<HTMLDivElement>) => {
    if (e.button !== 0) {
      return;
    }

    const target = e.target as HTMLElement;
    if (target.closest('[data-copilot-header-action="true"]')) {
      return;
    }

    const popoverElement = chatPopoverRef.current;
    if (!popoverElement) {
      return;
    }

    const rect = popoverElement.getBoundingClientRect();
    const boundedPosition = getBoundedDragPosition(rect.left, rect.top, rect.width, rect.height);

    dragStateRef.current = {
      offsetX: e.clientX - rect.left,
      offsetY: e.clientY - rect.top,
      width: rect.width,
      height: rect.height,
    };
    setDragPosition({
      ...boundedPosition,
      width: rect.width,
      height: rect.height,
    });
    setDragging(true);
    e.preventDefault();
  }, []);

  useEffect(() => {
    if (!dragging) {
      return;
    }

    /**
     * 鼠标移动期间持续计算面板位置；使用 document 监听可以避免快速拖拽时丢失事件。
     *
     * @param e 浏览器原生鼠标移动事件。
     */
    const onMouseMove = (e: MouseEvent) => {
      const dragState = dragStateRef.current;
      if (!dragState) {
        return;
      }

      const nextLeft = e.clientX - dragState.offsetX;
      const nextTop = e.clientY - dragState.offsetY;
      const boundedPosition = getBoundedDragPosition(
        nextLeft,
        nextTop,
        dragState.width,
        dragState.height
      );

      setDragPosition({
        ...boundedPosition,
        width: dragState.width,
        height: dragState.height,
      });
    };

    // 鼠标释放即结束拖拽，保留最后位置用于下次打开继续展示。
    const onMouseUp = () => {
      setDragging(false);
      dragStateRef.current = undefined;
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);

    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [dragging]);

  const copilotClass = classNames(styles.copilot, {
    [styles.copilotMinimized]: copilotMinimized,
    [styles.chatOpened]: chatVisible,
  });

  const chatPopoverClass = classNames(styles.chatPopover, {
    [styles.c2System]: integrateSystem === 'c2',
    [styles.dragging]: dragging,
  });
  const rightArrowClass = classNames(styles.rightArrow, {
    [styles.hidden]: !!dragPosition,
  });
  const chatPopoverStyle: CSSProperties | undefined = dragPosition
    ? {
        left: dragPosition.left,
        top: dragPosition.top,
        right: 'auto',
        bottom: 'auto',
        width: dragPosition.width,
        height: dragPosition.height,
      }
    : undefined;

  return (
    <>
      <div
        className={copilotClass}
        onMouseEnter={() => {
          setCopilotMinimized(false);
        }}
        onClick={onToggleChatVisible}
      >
        <IconFont type="icon-copilot-fill" />
        <div className={styles.minimizeWrapper} onClick={onMinimizeCopilot}>
          <div className={styles.minimize}>-</div>
        </div>
      </div>
      <div className={styles.copilotContent} style={{ display: chatVisible ? 'block' : 'none' }}>
        <div className={chatPopoverClass} style={chatPopoverStyle} ref={chatPopoverRef}>
          <div className={styles.header} onMouseDown={onStartDrag}>
            <div className={styles.leftSection} data-copilot-header-action="true">
              <CloseOutlined className={styles.close} onClick={onCloseChat} />
              {onOpenChatPage && (
                <IconFont
                  type="icon-weibiaoti-"
                  className={styles.transfer}
                  onClick={onTransferChat}
                />
              )}
            </div>
            <div className={styles.title}>{copilotTitle}</div>
          </div>
          <div className={styles.chat}>
            <Chat
              chatVisible={chatVisible}
              agentIds={agentIds}
              noInput={noInput}
              isDeveloper={isDeveloper}
              integrateSystem={integrateSystem}
              isCopilot
              onCurrentAgentChange={setCurrentAgent}
              onReportMsgEvent={onReportMsgEvent}
              ref={chatRef}
            />
          </div>
        </div>
        <CaretRightOutlined className={rightArrowClass} />
      </div>
    </>
  );
};

export default forwardRef(Copilot);
