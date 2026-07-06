/**
 * 聊天页模块。
 *
 * 职责：
 * - 从 URL 查询参数中读取初始智能助理；
 * - 注入当前登录 token；
 * - 承接运行时布局传入的全高容器，确保 Chat SDK 只在自身消息区滚动。
 */
import { useLocation } from '@umijs/max';
import { useEffect } from 'react';
import { getToken } from '@/utils/utils';
import queryString from 'query-string';
import { Chat } from 'supersonic-chat-sdk';
import styles from './style.module.less';

const CHAT_PAGE_SCROLL_LOCK_CLASS = 'ss-chat-page-scroll-lock';

const ChatPage = () => {
  const location = useLocation();
  const query = queryString.parse(location.search) || {};
  const { agentId } = query;

  useEffect(() => {
    // ProLayout 外层的 #root 默认允许纵向滚动，聊天页需要把滚动边界收敛到消息列表内部。
    document.body.classList.add(CHAT_PAGE_SCROLL_LOCK_CLASS);

    return () => {
      document.body.classList.remove(CHAT_PAGE_SCROLL_LOCK_CLASS);
    };
  }, []);

  return (
    <div className={styles.chatPage}>
      <Chat initialAgentId={agentId ? +agentId : undefined} token={getToken() || ''} isDeveloper />
    </div>
  );
};

export default ChatPage;
