/**
 * Supersonic 前端路由配置模块。
 *
 * 职责：
 * - 声明 Chat BI、语义建模、指标市场、标签市场和系统管理等页面路由；
 * - 根据构建目标和特性开关控制菜单暴露范围；
 * - 保持标签相关页面可被二开部署显式开启，同时避免普通构建默认显示未启用能力。
 */
export const ROUTE_AUTH_CODES = { SYSTEM_ADMIN: 'SYSTEM_ADMIN' };

const ENV_KEY = {
  CHAT: 'chat',
  SEMANTIC: 'semantic',
};

/**
 * 解析路由阶段的布尔特性开关。
 *
 * @param value 环境变量原始值，通常来自 Node.js 构建进程。
 * @param defaultValue 未配置或配置无法识别时的默认值。
 * @returns `true` 表示路由菜单可见，`false` 表示路由菜单默认隐藏。
 * @throws 不抛出异常；错误配置回退默认值，避免本地构建被环境变量拼写阻塞。
 *
 * @example
 * parseRouteFeatureFlag('true', false) // true
 * parseRouteFeatureFlag('false', true) // false
 */
const parseRouteFeatureFlag = (value: unknown, defaultValue = false): boolean => {
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    return value !== 0;
  }
  if (typeof value !== 'string') {
    return defaultValue;
  }

  const normalizedValue = value.trim().toLowerCase();
  if (['1', 'true', 'yes', 'y', 'on'].includes(normalizedValue)) {
    return true;
  }
  if (['0', 'false', 'no', 'n', 'off'].includes(normalizedValue)) {
    return false;
  }

  return defaultValue;
};

// 与 config.ts 的浏览器注入值保持一致，避免菜单可见但页面内部控件仍被隐藏。
const showTagFeatureEnabled = parseRouteFeatureFlag(
  process.env.SHOW_TAG ?? process.env.REACT_APP_SHOW_TAG,
  false,
);

const ROUTES = [
  {
    path: '/chat/mobile',
    name: 'chat',
    component: './ChatPage',
    hideInMenu: true,
    layout: false,
    envEnableList: [ENV_KEY.CHAT],
  },
  {
    path: '/chat/external',
    name: 'chat',
    component: './ChatPage',
    hideInMenu: true,
    layout: false,
    envEnableList: [ENV_KEY.CHAT],
  },
  {
    path: '/chat',
    name: 'chat',
    component: './ChatPage',
    envEnableList: [ENV_KEY.CHAT],
  },
  // {
  //   path: '/chatSetting/model/:domainId?/:modelId?/:menuKey?',
  //   component: './SemanticModel/ChatSetting/ChatSetting',
  //   name: 'chatSetting',
  //   envEnableList: [ENV_KEY.CHAT],
  // },
  {
    path: '/agent',
    name: 'agent',
    component: './Agent',
    envEnableList: [ENV_KEY.CHAT],
  },
  {
    path: '/plugin',
    name: 'plugin',
    component: './ChatPlugin',
    envEnableList: [ENV_KEY.CHAT],
  },
  {
    path: '/model/metric/edit/:metricId',
    name: 'metricEdit',
    hideInMenu: true,
    component: './SemanticModel/Metric/Edit',
    envEnableList: [ENV_KEY.SEMANTIC],
  },
  {
    path: '/model/',
    component: './SemanticModel/',
    name: 'semanticModel',
    envEnableList: [ENV_KEY.SEMANTIC],
    routes: [
      {
        path: '/model/',
        redirect: '/model/domain',
      },
      {
        path: '/model/domain/',
        component: './SemanticModel/OverviewContainer',
        routes: [
          {
            path: '/model/domain/:domainId',
            component: './SemanticModel/DomainManager',
            routes: [
              {
                path: '/model/domain/:domainId/:menuKey',
                component: './SemanticModel/DomainManager',
              },
            ],
          },
          {
            path: '/model/domain/manager/:domainId/:modelId',
            component: './SemanticModel/ModelManager',
            routes: [
              {
                path: '/model/domain/manager/:domainId/:modelId/:menuKey',
                component: './SemanticModel/ModelManager',
              },
            ],
          },
        ],
      },
      {
        path: '/model/dataset/:domainId/:datasetId',
        component: './SemanticModel/View/components/Detail',
        envEnableList: [ENV_KEY.SEMANTIC],
        routes: [
          {
            path: '/model/dataset/:domainId/:datasetId/:menuKey',
            component: './SemanticModel/View/components/Detail',
          },
        ],
      },
      {
        path: '/model/metric/:domainId/:modelId/:metricId',
        component: './SemanticModel/Metric/Edit',
        envEnableList: [ENV_KEY.SEMANTIC],
        // routes: [
        //   {
        //     path: '/model/manager/:domainId/:modelId/:menuKey',
        //     component: './SemanticModel/ModelManager',
        //   },
        // ],
      },
      {
        path: '/model/dimension/:domainId/:modelId/:dimensionId',
        component: './SemanticModel/Dimension/Detail',
        envEnableList: [ENV_KEY.SEMANTIC],
        // routes: [
        //   {
        //     path: '/model/manager/:domainId/:modelId/:menuKey',
        //     component: './SemanticModel/ModelManager',
        //   },
        // ],
      },
    ],
  },

  {
    path: '/metric',
    name: 'metric',
    component: './SemanticModel/Metric',
    envEnableList: [ENV_KEY.SEMANTIC],
    routes: [
      {
        path: '/metric',
        redirect: '/metric/market',
      },
      {
        path: '/metric/market',
        component: './SemanticModel/Metric/Market',
        hideInMenu: true,
        envEnableList: [ENV_KEY.SEMANTIC],
      },
      {
        path: '/metric/detail/:metricId',
        name: 'metricDetail',
        hideInMenu: true,
        component: './SemanticModel/Metric/Detail',
        envEnableList: [ENV_KEY.SEMANTIC],
      },
      {
        path: '/metric/detail/edit/:metricId',
        name: 'metricDetail',
        hideInMenu: true,
        component: './SemanticModel/Metric/Edit',
        envEnableList: [ENV_KEY.SEMANTIC],
      },
    ],
  },
  {
    path: '/tag',
    name: 'tag',
    component: './SemanticModel/Insights',
    envEnableList: [ENV_KEY.SEMANTIC],
    hideInMenu: !showTagFeatureEnabled,
    routes: [
      {
        path: '/tag',
        redirect: '/tag/market',
      },
      {
        path: '/tag/market',
        component: './SemanticModel/Insights/Market',
        hideInMenu: true,
        envEnableList: [ENV_KEY.SEMANTIC],
      },
      {
        path: '/tag/detail/:tagId',
        name: 'tagDetail',
        hideInMenu: true,
        component: './SemanticModel/Insights/Detail',
        envEnableList: [ENV_KEY.SEMANTIC],
      },
    ],
  },

  {
    path: '/login',
    name: 'login',
    layout: false,
    hideInMenu: true,
    component: './Login',
  },
  {
    path: '/database',
    name: 'database',
    component: './SemanticModel/components/Database/DatabaseTable',
    envEnableList: [ENV_KEY.SEMANTIC],
  },
  {
    path: '/llm',
    name: 'llm',
    component: './SemanticModel/components/LLM/LlmTable',
    envEnableList: [ENV_KEY.SEMANTIC],
  },
  {
    path: '/ai-semantic-modeling',
    name: 'aiSemanticModeling',
    component: './AISemanticModeling',
    envEnableList: [ENV_KEY.SEMANTIC],
    routes: [
      {
        path: '/ai-semantic-modeling',
        redirect: '/ai-semantic-modeling/gaps',
      },
      {
        path: '/ai-semantic-modeling/gaps',
        name: 'semanticGapPool',
        component: './AISemanticModeling/SemanticGapPool',
        envEnableList: [ENV_KEY.SEMANTIC],
      },
    ],
  },
  {
    path: '/system',
    name: 'system',
    component: './System',
    access: ROUTE_AUTH_CODES.SYSTEM_ADMIN,
  },
  {
    path: '/',
    redirect: '/model',
  },
  {
    path: '/401',
    component: './401',
  },
];

export default ROUTES;
