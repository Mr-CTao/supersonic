/**
 * Supersonic 前端运行时入口。
 *
 * 职责：
 * - 初始化用户态、权限码与运行环境；
 * - 配置 Umi ProLayout 的顶栏、内容区与全局浮层；
 * - 为全屏聊天页提供独立的高度和滚动边界，避免页面级滚动影响对话输入区。
 */
import RightContent from '@/components/RightContent';
import BrandLogo from '@/components/BrandLogo';
import { Spin, ConfigProvider } from 'antd';
import ScaleLoader from 'react-spinners/ScaleLoader';
import { history, RunTimeLayoutConfig } from '@umijs/max';
import defaultSettings from '../config/defaultSettings';
import settings from '../config/themeSettings';
import { queryCurrentUser } from './services/user';
import { deleteUrlQuery, isMobile, getToken } from '@/utils/utils';
import { publicPath, basePath } from '../config/defaultSettings';
import type { DefaultSetting } from '../config/defaultSettings';
import { Copilot } from 'supersonic-chat-sdk';
import { configProviderTheme } from '../config/themeSettings';
import type { CSSProperties } from 'react';
export { request } from './services/request';
import { BASE_TITLE } from '@/common/constants';
import { ROUTE_AUTH_CODES } from '../config/routes';
import AppPage from './pages/index';

const replaceRoute = '/';
const CHAT_HEADER_OFFSET = 56;

type LayoutMenuItem = {
  path?: string;
  target?: string;
  isUrl?: boolean;
  children?: unknown;
};

/**
 * 判断菜单点击是否应交给浏览器原生处理。
 *
 * @param event 菜单锚点点击事件。
 * @param target 菜单项声明的打开目标。
 * @returns `true` 表示保留新标签、下载或外链等浏览器默认行为。
 */
const shouldUseNativeMenuNavigation = (
  event: React.MouseEvent<HTMLAnchorElement>,
  target?: string,
) => {
  return (
    event.button !== 0 ||
    event.metaKey ||
    event.altKey ||
    event.ctrlKey ||
    event.shiftKey ||
    (target !== undefined && target !== '_self')
  );
};

/**
 * 将 Umi route path 转成浏览器可见 href。
 *
 * @param path Umi 菜单路由路径。
 * @returns 带 `basePath` 的浏览器 href，便于复制链接和新标签打开。
 */
const getMenuHref = (path: string) => {
  const routePath = path.replace(/\/\*$/, '');
  const normalizedPath = routePath.startsWith('/') ? routePath : `/${routePath}`;
  const normalizedBase = basePath.endsWith('/') ? basePath.slice(0, -1) : basePath;

  if (normalizedPath === normalizedBase || normalizedPath.startsWith(`${normalizedBase}/`)) {
    return normalizedPath;
  }

  return `${normalizedBase}${normalizedPath}`;
};

/**
 * 渲染可接收 ref 的菜单链接。
 *
 * @param menuItemProps ProLayout 传入的菜单元信息。
 * @param defaultDom ProLayout 默认生成的菜单内容。
 * @returns 原生锚点或默认菜单节点。
 */
const renderLayoutMenuItem = (menuItemProps: LayoutMenuItem, defaultDom: React.ReactNode) => {
  if (menuItemProps.isUrl || menuItemProps.children || !menuItemProps.path) {
    return defaultDom;
  }

  const routePath = menuItemProps.path.replace(/\/\*$/, '');

  return (
    <a
      href={getMenuHref(routePath)}
      target={menuItemProps.target}
      onClick={(event) => {
        // Ant Design 6 的菜单 Tooltip 会向子节点注入 ref；原生 a 标签可接收 ref，避免 Umi LinkWithPrefetch 触发 React warning。
        if (shouldUseNativeMenuNavigation(event, menuItemProps.target)) {
          return;
        }

        event.preventDefault();
        history.push(routePath);
      }}
    >
      {defaultDom}
    </a>
  );
};

/**
 * 判断当前路由是否为需要占满视口的聊天页。
 *
 * @param pathname 当前浏览器或 Umi 路由路径。
 * @returns `true` 表示当前页面需要启用聊天页专属高度和滚动约束。
 */
const isFullHeightChatRoute = (pathname: string) => {
  // 本地开源版挂在 /webapp 下运行，统一归一化后再判断，避免全高样式在带 base 路径时失效。
  const normalizedPathname = pathname.replace(/^\/webapp(?=\/|$)/, '');

  return (
    normalizedPathname === '/chat' ||
    normalizedPathname === '/chat/mobile' ||
    normalizedPathname === '/chat/external'
  );
};

/**
 * 计算聊天页外层容器样式。
 *
 * @param pathname 当前浏览器或 Umi 路由路径。
 * @returns 聊天页使用的高度和溢出控制；非聊天页返回 `undefined`。
 */
const getChatPageShellStyle = (pathname: string): CSSProperties | undefined => {
  if (!isFullHeightChatRoute(pathname)) {
    return undefined;
  }

  // /chat 带 ProLayout 顶栏；mobile/external 是 layout:false 页面，不能再扣除顶栏高度。
  const normalizedPathname = pathname.replace(/^\/webapp(?=\/|$)/, '');
  const height =
    normalizedPathname === '/chat' ? `calc(100vh - ${CHAT_HEADER_OFFSET}px)` : '100vh';

  const shellStyle: CSSProperties = {
    height,
    minHeight: 0,
    overflow: 'hidden',
  };

  return shellStyle;
};

const getRunningEnv = async () => {
  try {
    const response = await fetch(`${publicPath}supersonic.config.json`);
    const config = await response.json();
    return config;
  } catch (error) {
    console.warn('无法获取配置文件: 运行时环境将以semantic启动');
  }
};

Spin.setDefaultIndicator(
  <ScaleLoader color={settings['primary-color']} height={25} width={2} radius={2} margin={2} />,
);

const getAuthCodes = (params: any) => {
  const { currentUser } = params;
  const codes = [];
  if (currentUser?.superAdmin) {
    codes.push(ROUTE_AUTH_CODES.SYSTEM_ADMIN);
  }
  return codes;
};

export async function getInitialState(): Promise<{
  settings?: DefaultSetting;
  currentUser?: API.CurrentUser;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
  codeList?: string[];
  authCodes?: string[];
}> {
  const fetchUserInfo = async () => {
    try {
      const { code, data } = await queryCurrentUser();
      if (code === 200) {
        return { ...data, staffName: data.staffName || data.name };
      }
    } catch (error) {}
    return undefined;
  };

  let currentUser: any;
  if (!window.location.pathname.includes('login')) {
    currentUser = await fetchUserInfo();
  }

  if (currentUser) {
    localStorage.setItem('user', currentUser.staffName);
    if (currentUser.orgName) {
      localStorage.setItem('organization', currentUser.orgName);
    }
  }

  const authCodes = getAuthCodes({
    currentUser,
  });

  return {
    fetchUserInfo,
    currentUser,
    settings: defaultSettings,
    authCodes,
  };
}

// export async function patchRoutes({ routes }) {
//   const config = await getRunningEnv();
//   if (config && config.env) {
//     window.RUNNING_ENV = config.env;
//     const { env } = config;
//     const target = routes[0].routes;
//     if (env) {
//       const envRoutes = traverseRoutes(target, env);
//       // 清空原本route;
//       target.splice(0, 99);
//       // 写入根据环境转换过的的route
//       target.push(...envRoutes);
//     }
//   } else {
//     const target = routes[0].routes;
//     // start-standalone模式不存在env，在此模式下不显示chatSetting
//     const envRoutes = target.filter((item: any) => {
//       return !['chatSetting'].includes(item.name);
//     });
//     target.splice(0, 99);
//     target.push(...envRoutes);
//   }
// }

export function onRouteChange() {
  setTimeout(() => {
    let title = window.document.title;
    if (!title.toLowerCase().endsWith(BASE_TITLE.toLowerCase())) {
      window.document.title = `${title}-${BASE_TITLE}`;
    }
  }, 100);
}

export const layout: RunTimeLayoutConfig = (params) => {
  const { initialState } = params as any;
  return {
    onMenuHeaderClick: (e) => {
      e.preventDefault();
      history.push(replaceRoute);
    },
    logo: <BrandLogo size="header" />,
    contentStyle: { ...(initialState?.contentStyle || {}) },
    rightContentRender: () => <RightContent />,
    menuItemRender: renderLayoutMenuItem,
    disableContentMargin: true,
    // menuHeaderRender: undefined,
    childrenRender: (dom) => {
      const pathname = window.location.pathname || history.location.pathname;
      const chatPageShellStyle = getChatPageShellStyle(pathname);

      return (
        <ConfigProvider theme={configProviderTheme}>
          <div style={chatPageShellStyle}>
            {/* <AppPage dom={dom} /> */}
            {dom}
            {!isFullHeightChatRoute(pathname) && !isMobile && (
              <Copilot token={getToken() || ''} isDeveloper />
            )}
          </div>
        </ConfigProvider>
      );
    },
    ...initialState?.settings,
  };
};
