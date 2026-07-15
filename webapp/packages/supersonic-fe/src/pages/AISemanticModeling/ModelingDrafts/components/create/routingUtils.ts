/**
 * 语义资产路由创建流程纯函数。
 *
 * 职责：集中动作文案、范围指纹、业务答案校验、覆盖判定和 Drawer 级同步互斥逻辑，便于无浏览器副作用地测试安全边界。
 *
 * 并发说明：协调器只保护单个 Drawer 会话；服务端幂等键、租约和乐观锁仍是最终一致性边界。
 */
import type {
  CreateSemanticAssetRouteReq,
  SemanticAssetBusinessQuestion,
  SemanticAssetRouteAction,
  SemanticAssetRouteDetail,
} from '@/services/semanticAssetRouting';
import type { CreateDraftFormValues } from './creationTypes';
import type { AssetRoutingOperationCoordinator, AssetRoutingOperationToken } from './routingTypes';

export const ROUTE_ACTION_TEXT: Record<SemanticAssetRouteAction, string> = {
  REUSE_EXISTING: '复用已有资产',
  EXTEND_EXISTING: '增强已有资产',
  CREATE_NEW: '新建语义资产',
  NEEDS_CLARIFICATION: '需要确认业务口径',
};

export const ROUTE_PRIMARY_BUTTON_TEXT: Record<SemanticAssetRouteAction, string> = {
  REUSE_EXISTING: '使用现有资产并重新验证',
  EXTEND_EXISTING: '确认增强并生成增量草稿',
  CREATE_NEW: '确认新建并生成草稿',
  NEEDS_CLARIFICATION: '回答问题后重新分析',
};

/**
 * 将创建表单转换为服务端资产路由请求。
 *
 * @param initialGapId 可选语义缺口 ID。
 * @param values 已通过表单校验的分析范围。
 * @returns 不包含前端推断动作的路由分析请求。
 * @throws 不主动抛出异常；调用方必须先完成表单校验。
 */
export function buildAssetRouteRequest(
  initialGapId: number | undefined,
  values: CreateDraftFormValues,
): CreateSemanticAssetRouteReq {
  return {
    sourceType: initialGapId ? 'SEMANTIC_GAP' : 'DATA_SOURCE',
    sourceId: initialGapId,
    businessGoal: values.businessGoal.trim(),
    domainId: values.domainId,
    dataSourceId: values.dataSourceId,
    catalogName: values.catalogName,
    databaseName: values.databaseName,
    selectedTables: values.selectedTables,
    chatModelId: values.chatModelId,
    includeSampleData: values.includeSampleData ?? false,
  };
}

/** 递归生成属性顺序稳定的 JSON 值，避免同义输入产生不同前端指纹。 */
function normalizeForFingerprint(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizeForFingerprint);
  if (value && typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>)
      .sort()
      .reduce<Record<string, unknown>>((result, key) => {
        const normalized = normalizeForFingerprint((value as Record<string, unknown>)[key]);
        if (normalized !== undefined) result[key] = normalized;
        return result;
      }, {});
  }
  return value;
}

/**
 * 为任意只读请求值生成属性顺序稳定的前端指纹。
 *
 * @param value 需要参与幂等或对账的普通 JSON 值。
 * @returns 属性递归排序后的 JSON 字符串。
 * @throws 输入包含循环引用时由 JSON.stringify 抛出；调用方只应传请求 DTO。
 */
export function buildStableFingerprint(value: unknown): string {
  return JSON.stringify(normalizeForFingerprint(value));
}

/**
 * 计算影响资产路由的前端稳定指纹。
 *
 * @param request 完整或编辑中的分析范围。
 * @returns 规范化 JSON 字符串；选表按集合排序，避免仅选择顺序变化误判。
 * @throws 不抛出异常。
 */
export function buildAssetRouteFingerprint(request: Partial<CreateSemanticAssetRouteReq>): string {
  const normalized = {
    ...request,
    businessGoal: request.businessGoal?.trim() ?? '',
    catalogName: request.catalogName ?? '',
    selectedTables: [...(request.selectedTables ?? [])].sort(),
    includeSampleData: request.includeSampleData ?? false,
  };
  return buildStableFingerprint(normalized);
}

/** 判断业务答案是否真正存在；布尔 false 和数字 0 都是有效答案。 */
function isAnswered(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === 'string') return value.trim().length > 0;
  if (Array.isArray(value)) return value.length > 0;
  return true;
}

/**
 * 校验当前路由的必答业务问题。
 *
 * @param questions 服务端结构化问题。
 * @param answers 当前 Drawer 内答案。
 * @returns 以问题 key 为索引的字段级错误；空对象表示可提交。
 * @throws 不抛出异常。
 */
export function validateBusinessAnswers(
  questions: SemanticAssetBusinessQuestion[] = [],
  answers: Record<string, unknown> = {},
): Record<string, string> {
  return questions.reduce<Record<string, string>>((errors, question) => {
    if (question.required && !isAnswered(answers[question.key])) {
      errors[question.key] = '请先回答该业务问题';
    }
    return errors;
  }, {});
}

/**
 * 仅提交本次服务端问题集合中的答案，防止旧分析遗留 key 混入确认请求。
 *
 * @param questions 当前问题集合。
 * @param answers Drawer 内保留的所有答案。
 * @returns 当前问题对应的白名单答案。
 * @throws 不抛出异常。
 */
export function pickCurrentBusinessAnswers(
  questions: SemanticAssetBusinessQuestion[] = [],
  answers: Record<string, unknown> = {},
): Record<string, unknown> {
  return questions.reduce<Record<string, unknown>>((result, question) => {
    if (Object.prototype.hasOwnProperty.call(answers, question.key)) {
      result[question.key] = answers[question.key];
    }
    return result;
  }, {});
}

/**
 * 判断管理员选择是否覆盖服务端推荐。
 *
 * @param route 当前成功分析。
 * @param action 管理员选择动作。
 * @param candidateHandle 管理员选择的候选句柄。
 * @returns 动作或候选变化时返回 true。
 * @throws 不抛出异常。
 */
export function isRouteOverride(
  route: SemanticAssetRouteDetail,
  action: SemanticAssetRouteAction,
  candidateHandle?: string,
): boolean {
  if (action !== route.recommendedAction) return true;
  const recommendedHandle = route.primaryCandidate?.candidateHandle;
  return Boolean(candidateHandle && recommendedHandle && candidateHandle !== recommendedHandle);
}

/**
 * 校验覆盖推荐原因。
 *
 * @param reason 管理员输入原因。
 * @returns 合法时返回 undefined，否则返回字段错误。
 * @throws 不抛出异常。
 */
export function validateOverrideReason(reason?: string): string | undefined {
  const normalized = reason?.trim() ?? '';
  if (!normalized) return '更改推荐动作或候选时必须填写原因';
  if (normalized.length > 500) return '覆盖原因不能超过 500 个字符';
  return undefined;
}

/**
 * 判断确认动作是否需要创建草稿。
 *
 * @param action 已确认动作。
 * @returns 仅新建和增强返回 true；复用与待确认永不创建空草稿。
 * @throws 不抛出异常。
 */
export function shouldCreateDraftForAction(action: SemanticAssetRouteAction): boolean {
  return action === 'EXTEND_EXISTING' || action === 'CREATE_NEW';
}

/**
 * 按确认动作安全执行建稿续程。
 *
 * @param action 服务端已确认动作。
 * @param routeAnalysisId 已确认路由 ID。
 * @param idempotencyKey 建稿幂等键。
 * @param createDraft 调用现有建稿接口的回调。
 * @returns 新建/增强时返回建稿响应，复用/待确认返回 undefined。
 * @throws 建稿接口失败时原样抛出，由 Drawer 保留已确认路由供重试对账。
 */
export async function runConfirmedRouteContinuation(
  action: SemanticAssetRouteAction,
  routeAnalysisId: number,
  idempotencyKey: string,
  createDraft: (routeAnalysisId: number, idempotencyKey: string) => Promise<any>,
): Promise<any | undefined> {
  if (!shouldCreateDraftForAction(action)) return undefined;
  return createDraft(routeAnalysisId, idempotencyKey);
}

/**
 * 创建 Drawer 级同步互斥协调器。
 *
 * @param initialSessionKey 初始 Drawer 会话标识。
 * @returns 能阻止重复点击并废弃关闭、切 gap 后迟到响应的协调器。
 * @throws 不抛出异常；已有活动操作时 tryStart 返回 undefined。
 */
export function createAssetRoutingOperationCoordinator(
  initialSessionKey = '',
): AssetRoutingOperationCoordinator {
  let sessionKey = initialSessionKey;
  let sequence = 0;
  let activeToken: AssetRoutingOperationToken | undefined;
  return {
    finish(token) {
      if (token !== activeToken || token.sessionKey !== sessionKey) return false;
      activeToken = undefined;
      return true;
    },
    invalidate(nextSessionKey) {
      sessionKey = nextSessionKey;
      sequence += 1;
      activeToken = undefined;
    },
    isBusy() {
      return Boolean(activeToken);
    },
    isCurrent(token) {
      return token === activeToken && token.sessionKey === sessionKey;
    },
    tryStart(kind) {
      if (!sessionKey || activeToken) return undefined;
      const token = Object.freeze({ kind, sequence: ++sequence, sessionKey });
      activeToken = token;
      return token;
    },
  };
}

/** 将服务端决策来源转换为可读文案，不暴露 Prompt 或评分细节。 */
export function formatRouteDecisionSource(source?: string): string {
  if (source === 'RULE_ONLY') return '规则分析';
  if (source === 'RULE_AND_LLM') return '规则 + AI 分析';
  return '服务端策略分析';
}
