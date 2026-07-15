/**
 * 语义缺口池前端服务封装。
 *
 * 职责：
 * - 集中管理阶段 2 语义缺口池列表、详情、忽略和重新打开接口；
 * - 让页面组件只处理交互状态，避免 HTTP 请求散落在组件内部；
 * - 草稿生成由独立 semanticModelingDraft 服务负责，本模块不发布正式语义资产。
 *
 * 并发说明：
 * - 本文件不维护共享状态，重复点击由页面 loading 状态和后端状态流转共同保护；
 * - 筛选查询由 ProTable 表单提交触发，不需要额外 debounce。
 */
import request from 'umi-request';

const SEMANTIC_GAP_BASE_URL = '/api/semantic/gaps';

export type SemanticGapStatus =
  | 'PENDING_ANALYSIS'
  | 'DRAFTING'
  | 'WAITING_CONFIRMATION'
  | 'RELEASED'
  | 'IGNORED'
  | 'REOPENED';

export type SemanticGapFailureType =
  | 'NO_SELECTED_PARSE'
  | 'PARSER_EXCEPTION'
  | 'WRONG_MODEL_MATCHED'
  | 'LOW_CONFIDENCE'
  | 'SQL_EXECUTION_ERROR'
  | 'EMPTY_RESULT_SUSPECTED'
  | 'USER_NEGATIVE_FEEDBACK'
  | 'FALLBACK_TO_LLM_SQL'
  | 'BUSINESS_DEFINITION_UNCERTAIN'
  | 'SEMANTIC_ASSET_MISSING'
  | 'TECHNICAL_VALIDATION_FAILED'
  | 'UNKNOWN';

export type SemanticGapItem = {
  id: number;
  question?: string;
  normalizedQuestion?: string;
  assistantId?: number;
  userId?: number;
  domainId?: number;
  dataSourceId?: number;
  failureType?: SemanticGapFailureType;
  failureReason?: string;
  matchedModelIds?: string;
  matchedMetricIds?: string;
  matchedDimensionIds?: string;
  generatedSql?: string;
  s2sql?: string;
  feedback?: string;
  occurrenceCount?: number;
  negativeFeedbackCount?: number;
  priorityScore?: number;
  status?: SemanticGapStatus;
  createdAt?: string;
  lastSeenAt?: string;
  createdBy?: string;
  updatedAt?: string;
  updatedBy?: string;
  ignoreReason?: string;
  sourceQueryId?: number;
  sourceChatId?: number;
  recentQuestions?: string;
  diagnosticStage?: string;
  errorCode?: string;
  traceId?: string;
  errorLine?: number;
  errorColumn?: number;
  errorToken?: string;
  suggestion?: string;
};

export type SemanticGapQueryParams = {
  assistantId?: number;
  domainId?: number;
  dataSourceId?: number;
  failureType?: SemanticGapFailureType;
  status?: SemanticGapStatus;
  keyword?: string;
  startTime?: string;
  endTime?: string;
  page?: number;
  pageSize?: number;
};

/**
 * 查询语义缺口列表。
 *
 * @param params 筛选和分页参数。
 * @returns 后端统一响应，data 通常为 PageInfo。
 * @throws 网络异常或请求拦截器异常。
 */
export function getSemanticGaps(params: SemanticGapQueryParams): Promise<any> {
  return request(SEMANTIC_GAP_BASE_URL, {
    method: 'GET',
    params,
  });
}

/**
 * 查询语义缺口详情。
 *
 * @param id 缺口 ID。
 * @returns 后端统一响应，data 为缺口详情。
 * @throws 网络异常或请求拦截器异常。
 */
export function getSemanticGapDetail(id: number): Promise<any> {
  return request(`${SEMANTIC_GAP_BASE_URL}/${id}`, {
    method: 'GET',
  });
}

/**
 * 忽略语义缺口。
 *
 * @param id 缺口 ID。
 * @param reason 忽略原因。
 * @returns 后端统一响应，data 为更新后的缺口。
 * @throws 网络异常或请求拦截器异常。
 */
export function ignoreSemanticGap(id: number, reason?: string): Promise<any> {
  return request(`${SEMANTIC_GAP_BASE_URL}/${id}/ignore`, {
    method: 'POST',
    data: { reason },
  });
}

/**
 * 重新打开语义缺口。
 *
 * @param id 缺口 ID。
 * @returns 后端统一响应，data 为更新后的缺口。
 * @throws 网络异常或请求拦截器异常。
 */
export function reopenSemanticGap(id: number): Promise<any> {
  return request(`${SEMANTIC_GAP_BASE_URL}/${id}/reopen`, {
    method: 'POST',
  });
}
