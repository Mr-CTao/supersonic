/**
 * AI 语义建模工作台路由容器。
 *
 * 职责：
 * - 承载阶段 2 语义缺口池和阶段 3 建模草稿等 AI 语义建模管理页面；
 * - 通过 Umi Outlet 渲染子页面，保持“AI 语义建模”下的分阶段菜单结构；
 * - 不在容器层请求后端数据，避免与子页面状态重复。
 *
 * 并发说明：
 * - 容器组件无共享请求状态，无需额外并发保护。
 */
import { Outlet } from '@umijs/max';
import React from 'react';

/**
 * 渲染 AI 语义建模工作台子路由。
 *
 * @returns 子页面 Outlet。
 * @throws 不抛出异常。
 */
const AISemanticModeling: React.FC = () => {
  return <Outlet />;
};

export default AISemanticModeling;
