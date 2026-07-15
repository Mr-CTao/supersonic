/**
 * AI 语义资产路由前端服务模块。
 *
 * 职责：
 * - 声明服务端资产路由动作、状态、候选、业务问题和确认快照契约；
 * - 统一封装分析创建、详情轮询和管理员确认接口；
 * - 仅传递候选句柄，不在前端接收或提交可执行 SQL、正式资产 ID 变更或发布指令。
 *
 * 并发说明：本模块不保存共享状态；幂等键、轮询互斥和迟到响应隔离由 Drawer 工作流 Hook 负责。
 */
import request from 'umi-request';

const SEMANTIC_ASSET_ROUTE_BASE_URL = '/api/semantic/modeling/asset-routes';

/** 服务端稳定资产路由动作；页面只能消费该枚举，不根据分数自行推断动作。 */
export const SEMANTIC_ASSET_ROUTE_ACTIONS = [
  'REUSE_EXISTING',
  'EXTEND_EXISTING',
  'CREATE_NEW',
  'NEEDS_CLARIFICATION',
] as const;

export type SemanticAssetRouteAction = (typeof SEMANTIC_ASSET_ROUTE_ACTIONS)[number];

/** 路由分析异步状态。 */
export type SemanticAssetRouteStatus = 'PENDING' | 'ANALYZING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';

/** 路由决策来源，用于向管理员解释规则与 AI 的协作边界。 */
export type SemanticAssetRouteDecisionSource = 'RULE_ONLY' | 'RULE_AND_LLM' | string;

/** 服务端已过滤权限后的单个候选资产摘要。 */
export type SemanticAssetRouteCandidate = {
  candidateHandle: string;
  assetType: string;
  assetVersion?: number;
  name: string;
  bizName?: string;
  description?: string;
  grain?: string | string[];
  coveredCapabilities?: string[];
  missingCapabilities?: Array<SemanticAssetMissingCapability | string>;
  coverageDescription?: string;
  manageable?: boolean;
  evidenceSources?: string[];
};

/** 候选未覆盖的增量能力。 */
export type SemanticAssetMissingCapability = {
  type: string;
  name: string;
  reason?: string;
};

/** 业务问题的稳定枚举选项。 */
export type SemanticAssetBusinessQuestionOption = {
  key: string;
  label: string;
};

/** 路由阶段需要管理员回答的结构化业务问题。 */
export type SemanticAssetBusinessQuestion = {
  key: string;
  question: string;
  required: boolean;
  answerType?: 'SINGLE_SELECT' | 'BOOLEAN' | 'TEXT' | string;
  options?: SemanticAssetBusinessQuestionOption[];
  maxLength?: number;
  affectsRecommendation?: boolean;
};

/** 可安全展示的技术证据摘要；不包含敏感值、完整 Prompt 或可执行 SQL。 */
export type SemanticAssetRouteEvidence = {
  key?: string;
  label?: string;
  summary?: string;
  source?: string;
};

/** 创建一次路由分析的完整、可指纹化输入。 */
export type CreateSemanticAssetRouteReq = {
  sourceType: 'SEMANTIC_GAP' | 'DATA_SOURCE';
  sourceId?: number;
  businessGoal: string;
  domainId?: number;
  dataSourceId: number;
  catalogName?: string;
  databaseName: string;
  selectedTables: string[];
  chatModelId: number;
  includeSampleData: boolean;
  businessAnswers?: Record<string, unknown>;
};

/** 管理员接受或覆盖路由推荐的请求。 */
export type ConfirmSemanticAssetRouteReq = {
  analysisVersion: number;
  action: SemanticAssetRouteAction;
  candidateHandle?: string;
  businessAnswers: Record<string, unknown>;
  overrideReason?: string;
};

/** 单个可审计路由分析及确认快照。 */
export type SemanticAssetRouteDetail = {
  id: number;
  status: SemanticAssetRouteStatus;
  recommendedAction?: SemanticAssetRouteAction;
  recommendedActionLabel?: string;
  explanation?: string;
  decisionSource?: SemanticAssetRouteDecisionSource;
  primaryCandidate?: SemanticAssetRouteCandidate;
  alternativeCandidates?: SemanticAssetRouteCandidate[];
  coveredCapabilities?: string[];
  missingCapabilities?: SemanticAssetMissingCapability[];
  resultOperations?: string[];
  businessQuestions?: SemanticAssetBusinessQuestion[];
  allowedActions?: SemanticAssetRouteAction[];
  canConfirm?: boolean;
  confirmDisabledReason?: string;
  technicalEvidence?: Array<SemanticAssetRouteEvidence | string>;
  analysisVersion: number;
  lockVersion?: number;
  confirmedAction?: SemanticAssetRouteAction;
  confirmedCandidateHandle?: string;
  businessAnswers?: Record<string, unknown>;
  overrideReason?: string;
  confirmedBy?: string;
  confirmedAt?: string;
  failureCode?: string;
  failureMessage?: string;
  expiresAt?: string;
  idempotentReplay?: boolean;
};

/**
 * 发起一次幂等的语义资产路由分析。
 *
 * @param data 完整分析范围，不包含业务样例行或正式资产 ID。
 * @param idempotencyKey 当前范围指纹对应的稳定幂等键。
 * @returns 后端统一响应，data 为初始路由详情。
 * @throws 网络错误、权限不足、输入非法或幂等键冲突时抛出异常。
 */
export function createSemanticAssetRoute(
  data: CreateSemanticAssetRouteReq,
  idempotencyKey: string,
): Promise<any> {
  return request(SEMANTIC_ASSET_ROUTE_BASE_URL, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    data,
  });
}

/**
 * 查询一次路由分析的最新服务端快照。
 *
 * @param id 路由分析 ID。
 * @returns 后端统一响应，data 为最新路由详情。
 * @throws 路由不存在、无读取权限或网络失败时抛出异常。
 */
export function getSemanticAssetRoute(id: number): Promise<any> {
  return request(`${SEMANTIC_ASSET_ROUTE_BASE_URL}/${id}`, { method: 'GET' });
}

/**
 * 幂等确认推荐、覆盖推荐或提交澄清答案。
 *
 * @param id 路由分析 ID。
 * @param data 分析版本、动作、候选句柄、业务答案和可选覆盖原因。
 * @param idempotencyKey 当前确认指纹对应的稳定幂等键。
 * @returns 后端统一响应，data 为确认后或重新分析中的最新路由详情。
 * @throws 版本漂移、权限不足、必答问题缺失、未知候选或网络失败时抛出异常。
 */
export function confirmSemanticAssetRoute(
  id: number,
  data: ConfirmSemanticAssetRouteReq,
  idempotencyKey: string,
): Promise<any> {
  return request(`${SEMANTIC_ASSET_ROUTE_BASE_URL}/${id}/confirm`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    data,
  });
}
