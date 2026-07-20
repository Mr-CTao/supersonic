/**
 * 出入库预测工作台路由容器。
 *
 * 职责：承载预测看板、数据接入和运行中心三个子页面；容器本身不持有请求状态。
 */
import { Outlet } from '@umijs/max';
import React from 'react';

/**
 * 渲染 Forecast 子路由。
 *
 * @returns Umi Outlet。
 * @throws 不抛出异常。
 */
const Forecast: React.FC = () => <Outlet />;

export default Forecast;
