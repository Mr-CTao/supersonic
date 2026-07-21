/**
 * 出入库预测模块工作台。
 *
 * 职责：为预测看板、数据接入和运行中心提供模块级导航、紧凑页头与统一满高内容区；
 * 导航可以完全收起，并在浏览器本地记住用户选择，避免三个子页面重复渲染大块页头。
 */
import {
  AppstoreOutlined,
  DatabaseOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import { history, Outlet, useLocation } from '@umijs/max';
import { Button, Menu, Tooltip, Typography } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import styles from './style.less';

const FORECAST_NAVIGATION_STORAGE_KEY = 'supersonic.forecast.navigationHidden';
const FORECAST_SCROLL_LOCK_CLASS_NAME = 'ss-forecast-page-scroll-lock';

/** Forecast 子页面的导航与紧凑页头元数据。 */
const FORECAST_PAGES = [
  {
    path: '/forecast/overview',
    label: '预测看板',
    title: '出入库预测看板',
    subTitle: '实际数据、未来预测与模型质量',
    icon: <AppstoreOutlined />,
  },
  {
    path: '/forecast/sources',
    label: '数据接入',
    title: '预测数据接入',
    subTitle: '源库连接、数据流、字段映射与首次同步',
    icon: <DatabaseOutlined />,
  },
  {
    path: '/forecast/runs',
    label: '运行中心',
    title: '预测运行中心',
    subTitle: '任务进度、复合水位、吞吐与故障恢复',
    icon: <PlayCircleOutlined />,
  },
] as const;

/**
 * 读取用户上次选择的模块导航状态。
 *
 * @returns `true` 表示导航完全隐藏；服务端渲染、隐私模式或存储异常时回退为展开。
 * @throws 不抛出异常；浏览器存储不可用时使用安全默认值。
 */
const readNavigationHidden = (): boolean => {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(FORECAST_NAVIGATION_STORAGE_KEY) === 'true';
  } catch {
    // localStorage 可能被浏览器隐私策略禁用；导航仍可在当前页面内正常切换。
    return false;
  }
};

/**
 * 保存模块导航状态，供三个子页面和下次访问共同复用。
 *
 * @param hidden 是否完全隐藏模块导航。
 * @returns 无返回值。
 * @throws 不抛出异常；存储不可用不会影响当前会话中的显隐状态。
 */
const persistNavigationHidden = (hidden: boolean): void => {
  try {
    window.localStorage.setItem(FORECAST_NAVIGATION_STORAGE_KEY, String(hidden));
  } catch {
    // 状态持久化属于渐进增强，不应阻断页面导航与业务操作。
  }
};

/**
 * 渲染 Forecast 模块壳层。
 *
 * @returns 带可隐藏侧边栏、动态页头和当前子路由内容的工作台。
 * @throws 不主动抛出异常；路由跳转与本地存储失败均有框架或安全回退。
 */
const Forecast: React.FC = () => {
  const location = useLocation();
  const [navigationHidden, setNavigationHidden] = useState(readNavigationHidden);
  const navigationRef = useRef<HTMLElement>(null);
  const activePage = useMemo(
    () =>
      FORECAST_PAGES.find((item) => location.pathname.startsWith(item.path)) ?? FORECAST_PAGES[0],
    [location.pathname],
  );

  useEffect(() => {
    const rootElement = document.getElementById('root');
    document.body.classList.add(FORECAST_SCROLL_LOCK_CLASS_NAME);
    // 全局布局含三层水平滚动容器；进入满高工作台时归零根滚动，避免固定顶栏覆盖模块页头。
    if (rootElement) rootElement.scrollTop = 0;
    return () => document.body.classList.remove(FORECAST_SCROLL_LOCK_CLASS_NAME);
  }, []);

  useEffect(() => {
    // 收起动画期间侧栏仍保留在 DOM 中；立即启用 inert，避免不可见菜单被鼠标或键盘误操作。
    navigationRef.current?.toggleAttribute('inert', navigationHidden);
  }, [navigationHidden]);

  /** 切换导航显隐并同步本地偏好；按钮是唯一入口，不存在并发提交。 */
  const updateNavigationHidden = (hidden: boolean) => {
    setNavigationHidden(hidden);
    persistNavigationHidden(hidden);
  };

  return (
    <div className={styles.forecastShell}>
      <aside
        ref={navigationRef}
        id="forecast-module-navigation"
        className={`${styles.moduleNavigation} ${
          navigationHidden ? styles.moduleNavigationHidden : ''
        }`}
        aria-label="出入库预测模块导航"
        aria-hidden={navigationHidden}
      >
        <div className={styles.moduleNavigationInner}>
          <Menu
            className={styles.moduleMenu}
            mode="inline"
            selectedKeys={[activePage.path]}
            items={FORECAST_PAGES.map((item) => ({
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
              aria-controls="forecast-module-navigation"
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
                aria-label="展开出入库预测模块导航"
                aria-controls="forecast-module-navigation"
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

export default Forecast;
