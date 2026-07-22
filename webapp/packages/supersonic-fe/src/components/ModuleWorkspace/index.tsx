/**
 * 模块级工作台壳层。
 *
 * 职责：为具有多个子页面的一级模块提供统一的左侧导航、紧凑页头、导航显隐记忆和
 * 满高内容区，使模块子菜单不再依赖全局顶栏的悬浮下拉菜单。
 *
 * 并发说明：组件只维护当前浏览器页签内的导航显隐状态；localStorage 写入为同步的
 * 渐进增强操作，不参与业务请求，也不存在需要锁保护的共享服务端状态。
 */
import { MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { history, Outlet, useLocation } from '@umijs/max';
import { Button, Menu, Tooltip, Typography } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import styles from './style.less';

const MODULE_WORKSPACE_SCROLL_LOCK_CLASS_NAME = 'ss-module-workspace-page-scroll-lock';

/** 模块子页面的导航与页头元数据。 */
export interface ModuleWorkspacePage {
  /** 子页面绝对路由。 */
  path: string;
  /** 左侧导航文案。 */
  label: string;
  /** 内容区页头标题。 */
  title: string;
  /** 内容区页头辅助说明。 */
  subTitle: string;
  /** 使用项目现有图标库渲染的导航图标。 */
  icon: React.ReactNode;
}

/** 模块工作台配置。 */
interface ModuleWorkspaceProps {
  /** 左侧导航的无障碍名称。 */
  navigationAriaLabel: string;
  /** 用于隔离不同模块导航偏好的本地存储键。 */
  navigationStorageKey: string;
  /** 当前用户有权访问的模块子页面。 */
  pages: readonly ModuleWorkspacePage[];
}

/**
 * 读取用户上次选择的模块导航状态。
 *
 * @param storageKey 当前模块独立使用的 localStorage 键。
 * @returns `true` 表示导航完全隐藏；服务端渲染、隐私模式或存储异常时回退为展开。
 * @throws 不抛出异常；浏览器存储不可用时使用安全默认值。
 */
const readNavigationHidden = (storageKey: string): boolean => {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(storageKey) === 'true';
  } catch {
    // localStorage 可能被浏览器隐私策略禁用；导航仍可在当前页面内正常切换。
    return false;
  }
};

/**
 * 保存模块导航状态，供模块子页面和下次访问共同复用。
 *
 * @param storageKey 当前模块独立使用的 localStorage 键。
 * @param hidden 是否完全隐藏模块导航。
 * @returns 无返回值。
 * @throws 不抛出异常；存储不可用不会影响当前会话中的显隐状态。
 */
const persistNavigationHidden = (storageKey: string, hidden: boolean): void => {
  try {
    window.localStorage.setItem(storageKey, String(hidden));
  } catch {
    // 状态持久化属于渐进增强，不应阻断页面导航与业务操作。
  }
};

/**
 * 渲染通用模块工作台。
 *
 * @param props 模块导航名称、独立存储键以及当前用户可见的页面元数据。
 * @returns 带可隐藏侧边栏、动态页头和当前子路由内容的工作台。
 * @throws 不主动抛出异常；路由跳转与本地存储失败均有框架或安全回退。
 */
const ModuleWorkspace: React.FC<ModuleWorkspaceProps> = ({
  navigationAriaLabel,
  navigationStorageKey,
  pages,
}) => {
  const location = useLocation();
  const [navigationHidden, setNavigationHidden] = useState(() =>
    readNavigationHidden(navigationStorageKey),
  );
  const navigationRef = useRef<HTMLElement>(null);
  const activePage = useMemo(
    () => pages.find((item) => location.pathname.startsWith(item.path)) ?? pages[0],
    [location.pathname, pages],
  );

  useEffect(() => {
    const rootElement = document.getElementById('root');
    document.body.classList.add(MODULE_WORKSPACE_SCROLL_LOCK_CLASS_NAME);
    // 全局布局含多层滚动容器；进入满高工作台时归零根滚动，避免固定顶栏覆盖模块页头。
    if (rootElement) rootElement.scrollTop = 0;
    return () => document.body.classList.remove(MODULE_WORKSPACE_SCROLL_LOCK_CLASS_NAME);
  }, []);

  useEffect(() => {
    // 收起动画期间侧栏仍保留在 DOM 中；立即启用 inert，避免不可见菜单被鼠标或键盘误操作。
    navigationRef.current?.toggleAttribute('inert', navigationHidden);
  }, [navigationHidden]);

  /** 切换导航显隐并同步当前模块的本地偏好。 */
  const updateNavigationHidden = (hidden: boolean) => {
    setNavigationHidden(hidden);
    persistNavigationHidden(navigationStorageKey, hidden);
  };

  // pages 由模块按权限过滤后传入；空数组属于配置错误，安全返回空内容避免读取未定义元数据。
  if (!activePage) return null;

  return (
    <div className={styles.moduleWorkspace}>
      <aside
        ref={navigationRef}
        id="module-workspace-navigation"
        className={`${styles.moduleNavigation} ${
          navigationHidden ? styles.moduleNavigationHidden : ''
        }`}
        aria-label={navigationAriaLabel}
        aria-hidden={navigationHidden}
      >
        <div className={styles.moduleNavigationInner}>
          <Menu
            className={styles.moduleMenu}
            mode="inline"
            selectedKeys={[activePage.path]}
            items={pages.map((item) => ({
              key: item.path,
              icon: item.icon,
              label: item.label,
            }))}
            onClick={({ key }) => history.push(String(key))}
          />
          <div className={styles.navigationFooter}>
            <Button
              type="text"
              icon={<MenuFoldOutlined />}
              aria-controls="module-workspace-navigation"
              aria-expanded={!navigationHidden}
              tabIndex={navigationHidden ? -1 : 0}
              onClick={() => updateNavigationHidden(true)}
            >
              收起导航
            </Button>
          </div>
        </div>
      </aside>

      <main className={styles.moduleMain}>
        <header className={styles.moduleHeader}>
          <div
            className={`${styles.navigationTriggerSlot} ${
              navigationHidden ? styles.navigationTriggerSlotVisible : ''
            }`}
            aria-hidden={!navigationHidden}
          >
            <Tooltip title="展开模块导航">
              <Button
                className={styles.navigationTrigger}
                icon={<MenuUnfoldOutlined />}
                aria-label={`展开${navigationAriaLabel}`}
                aria-controls="module-workspace-navigation"
                aria-expanded={!navigationHidden}
                tabIndex={navigationHidden ? 0 : -1}
                onClick={() => updateNavigationHidden(false)}
              />
            </Tooltip>
          </div>
          <div className={styles.moduleHeading}>
            <Typography.Title level={4}>{activePage.title}</Typography.Title>
            <Typography.Text type="secondary">{activePage.subTitle}</Typography.Text>
          </div>
        </header>
        <div className={styles.moduleBody}>
          <Outlet />
        </div>
      </main>
    </div>
  );
};

export default ModuleWorkspace;
