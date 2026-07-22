/**
 * AI 语义建模模块工作台。
 *
 * 职责：
 * - 为语义缺口池、建模草稿和发布审计提供与出入库预测一致的模块内左侧导航；
 * - 按当前路由切换标题与辅助说明，并保留独立的导航收起偏好；
 * - 在渲染导航前过滤系统管理员专属的发布审计入口。
 *
 * 并发说明：
 * - 容器组件无共享请求状态，无需额外并发保护；权限继续由 Umi 路由 access 做最终校验。
 */
import { AuditOutlined, BulbOutlined, FileTextOutlined } from '@ant-design/icons';
import { useAccess } from '@umijs/max';
import React, { useMemo } from 'react';
import ModuleWorkspace from '@/components/ModuleWorkspace';
import type { ModuleWorkspacePage } from '@/components/ModuleWorkspace';

const AI_SEMANTIC_MODELING_NAVIGATION_STORAGE_KEY =
  'supersonic.aiSemanticModeling.navigationHidden';

/** AI 语义建模子页面的导航、页头与权限元数据。 */
interface AISemanticModelingPage extends ModuleWorkspacePage {
  /** `true` 表示该入口只允许系统管理员看见。 */
  systemAdminOnly?: boolean;
}

/** 当前模块依赖的最小 Umi access 能力，避免耦合其他动态权限键。 */
interface AISemanticModelingAccess {
  /** 系统管理员路由权限。 */
  SYSTEM_ADMIN?: boolean;
}

const AI_SEMANTIC_MODELING_PAGES: readonly AISemanticModelingPage[] = [
  {
    path: '/ai-semantic-modeling/gaps',
    label: '语义缺口池',
    title: '语义缺口池',
    subTitle: '沉淀问答失败与用户反馈，识别待补齐的语义资产',
    icon: <BulbOutlined />,
  },
  {
    path: '/ai-semantic-modeling/drafts',
    label: '建模草稿',
    title: 'AI 建模草稿',
    subTitle: '从缺口或数据源生成、校准并验证语义模型草稿',
    icon: <FileTextOutlined />,
  },
  {
    path: '/ai-semantic-modeling/releases',
    label: '发布审计',
    title: '发布审计',
    subTitle: '审批发布、知识刷新、发布记录与版本回滚',
    icon: <AuditOutlined />,
    systemAdminOnly: true,
  },
] as const;

/**
 * 渲染 AI 语义建模模块工作台。
 *
 * @returns 带权限过滤左侧导航和当前子路由内容的模块工作台。
 * @throws 不抛出异常。
 */
const AISemanticModeling: React.FC = () => {
  const access = useAccess() as AISemanticModelingAccess;
  const visiblePages = useMemo(
    () =>
      AI_SEMANTIC_MODELING_PAGES.filter(
        (page) => !page.systemAdminOnly || Boolean(access.SYSTEM_ADMIN),
      ),
    [access.SYSTEM_ADMIN],
  );

  return (
    <ModuleWorkspace
      navigationAriaLabel="AI 语义建模模块导航"
      navigationStorageKey={AI_SEMANTIC_MODELING_NAVIGATION_STORAGE_KEY}
      pages={visiblePages}
    />
  );
};

export default AISemanticModeling;
