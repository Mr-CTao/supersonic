/**
 * 出入库预测模块工作台。
 *
 * 职责：声明预测看板、数据接入和运行中心的导航元数据，并复用通用模块工作台渲染
 * 左侧导航、紧凑页头与统一满高内容区。
 *
 * 并发说明：页面仅提供静态导航配置，不持有共享请求状态，无需额外并发保护。
 */
import { AppstoreOutlined, DatabaseOutlined, PlayCircleOutlined } from '@ant-design/icons';
import React from 'react';
import ModuleWorkspace from '@/components/ModuleWorkspace';
import type { ModuleWorkspacePage } from '@/components/ModuleWorkspace';

const FORECAST_NAVIGATION_STORAGE_KEY = 'supersonic.forecast.navigationHidden';

/** Forecast 子页面的导航与紧凑页头元数据。 */
const FORECAST_PAGES: readonly ModuleWorkspacePage[] = [
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
 * 渲染 Forecast 模块壳层。
 *
 * @returns 配置为出入库预测内容的通用模块工作台。
 * @throws 不主动抛出异常。
 */
const Forecast: React.FC = () => {
  return (
    <ModuleWorkspace
      navigationAriaLabel="出入库预测模块导航"
      navigationStorageKey={FORECAST_NAVIGATION_STORAGE_KEY}
      pages={FORECAST_PAGES}
    />
  );
};

export default Forecast;
