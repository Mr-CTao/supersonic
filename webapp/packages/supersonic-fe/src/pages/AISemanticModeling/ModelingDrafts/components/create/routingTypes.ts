/**
 * 语义资产路由创建流程共享类型。
 *
 * 职责：声明两步 Drawer 的步骤、互斥操作、管理员选择和完成结果，避免容器、Hook 与子组件重复定义。
 */
import type {
  CreateSemanticAssetRouteReq,
  SemanticAssetRouteAction,
  SemanticAssetRouteDetail,
} from '@/services/semanticAssetRouting';

export type AssetRoutingStep = 'SCOPE' | 'DECISION';

export type AssetRoutingBusyKind = 'ANALYZE' | 'CONFIRM' | 'CREATE_DRAFT';

export type AssetRoutingDecision = {
  action: SemanticAssetRouteAction;
  candidateHandle?: string;
  overrideReason?: string;
};

export type AssetRoutingCompletion =
  | { kind: 'REANALYZED'; route: SemanticAssetRouteDetail }
  | { kind: 'REUSED'; route: SemanticAssetRouteDetail }
  | { kind: 'DRAFT_CREATED'; route: SemanticAssetRouteDetail; draftResponse: any };

export type AssetRoutingOperationToken = Readonly<{
  kind: AssetRoutingBusyKind;
  sequence: number;
  sessionKey: string;
}>;

export type AssetRoutingOperationCoordinator = {
  finish: (token: AssetRoutingOperationToken) => boolean;
  invalidate: (sessionKey: string) => void;
  isBusy: () => boolean;
  isCurrent: (token: AssetRoutingOperationToken) => boolean;
  tryStart: (kind: AssetRoutingBusyKind) => AssetRoutingOperationToken | undefined;
};

export type AssetRoutingAnalyzedScope = {
  fingerprint: string;
  request: CreateSemanticAssetRouteReq;
};
