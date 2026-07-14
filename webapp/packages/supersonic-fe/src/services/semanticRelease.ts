/**
 * AI 语义建模阶段 5 审批发布服务模块。
 *
 * 职责：
 * - 封装待审批、审批决定、发布、发布审计、知识刷新重试和回滚接口；
 * - 描述发布主状态、独立刷新状态和逐步骤结果；
 * - 发布幂等键由调用方在一次操作周期内生成并复用，本模块不保存共享状态。
 */
import request from 'umi-request';

const SEMANTIC_MODELING_BASE_URL = '/api/semantic/modeling';

export type ApprovalStatus =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'RELEASE_FAILED'
  | string;

export type ReleaseStatus =
  | 'IN_PROGRESS'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'ROLLBACK_IN_PROGRESS'
  | 'ROLLED_BACK'
  | 'ROLLBACK_FAILED'
  | string;

export type RefreshStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | string;

export type SemanticApprovalItem = {
  draftId: number;
  title?: string;
  businessGoal?: string;
  sourceGapId?: number;
  domainId?: number;
  dataSourceId?: number;
  status: ApprovalStatus;
  draftVersionNo?: number;
  validationReportId?: number;
  validationStatus?: string;
  plannedObjectCount?: number;
  submittedBy?: string;
  submittedAt?: string;
  approvedBy?: string;
  approvedAt?: string;
  approvalReason?: string;
};

export type ReleasedObject = {
  type: string;
  key?: string;
  name?: string;
  targetId?: number;
};

export type SemanticReleaseStep = {
  id: number;
  stepKey: string;
  stepType: string;
  targetType: string;
  targetKey?: string;
  targetName?: string;
  targetId?: number;
  status: string;
  attemptCount?: number;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
};

export type SemanticReleaseItem = {
  id: number;
  releaseNo: string;
  draftId: number;
  draftVersionNo?: number;
  validationReportId?: number;
  draftTitle?: string;
  sourceGapId?: number;
  releaseStatus: ReleaseStatus;
  dictReloadStatus: RefreshStatus;
  embeddingReloadStatus: RefreshStatus;
  approvedBy?: string;
  releasedBy?: string;
  releasedAt?: string;
  errorMessage?: string;
  rollbackReason?: string;
  rolledBackBy?: string;
  rolledBackAt?: string;
  releasedObjects?: ReleasedObject[];
  steps?: SemanticReleaseStep[];
};

export type SemanticReleaseQuery = {
  status?: string;
  keyword?: string;
  page?: number;
  pageSize?: number;
};

/**
 * 查询待审批与审批决定列表。
 *
 * @param params 状态、关键词和分页参数。
 * @returns 后端统一响应，data 为 PageInfo。
 * @throws 网络错误或非系统管理员访问时抛出异常。
 */
export function getSemanticApprovals(params: SemanticReleaseQuery): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/approvals`, { method: 'GET', params });
}

/**
 * 审批通过草稿。
 *
 * @param draftId 草稿 ID。
 * @param reason 可选审批备注。
 * @returns 后端统一响应。
 * @throws 状态已变化、验证失效、权限或网络错误。
 */
export function approveSemanticDraft(draftId: number, reason?: string): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/drafts/${draftId}/approve`, {
    method: 'POST',
    data: { reason },
  });
}

/**
 * 拒绝待审批草稿。
 *
 * @param draftId 草稿 ID。
 * @param reason 必填拒绝原因。
 * @returns 后端统一响应。
 * @throws 原因为空、状态已变化、权限或网络错误。
 */
export function rejectSemanticDraft(draftId: number, reason: string): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/drafts/${draftId}/reject`, {
    method: 'POST',
    data: { reason },
  });
}

/**
 * 发布审批通过的 AI 新增对象。
 *
 * @param draftId 草稿 ID。
 * @param idempotencyKey 当前发布操作周期内稳定复用的幂等键。
 * @returns 发布详情，业务失败通过 releaseStatus 明确表达。
 * @throws 审批门禁、权限、并发或网络错误。
 */
export function releaseSemanticDraft(draftId: number, idempotencyKey: string): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/drafts/${draftId}/release`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

/**
 * 查询发布审计列表。
 *
 * @param params 状态、关键词和分页参数。
 * @returns 后端统一响应，data 为 PageInfo。
 * @throws 网络错误或非系统管理员访问时抛出异常。
 */
export function getSemanticReleases(params: SemanticReleaseQuery): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/releases`, { method: 'GET', params });
}

/**
 * 查询发布详情和全部步骤。
 *
 * @param releaseId 发布 ID。
 * @returns 发布详情。
 * @throws 记录不存在、权限或网络错误。
 */
export function getSemanticReleaseDetail(releaseId: number): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/releases/${releaseId}`, { method: 'GET' });
}

/**
 * 只重试失败的 dict/embedding 刷新步骤。
 *
 * @param releaseId 发布 ID。
 * @returns 重试后的发布详情。
 * @throws 对象步骤未完整、状态不允许、权限或网络错误。
 */
export function retrySemanticKnowledge(releaseId: number): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/releases/${releaseId}/knowledge/retry`, {
    method: 'POST',
  });
}

/**
 * 回滚发布记录限定的 AI 新增对象。
 *
 * @param releaseId 发布 ID。
 * @param reason 必填回滚原因；客户端不能传对象 ID。
 * @returns 回滚后的发布详情。
 * @throws 原因为空、发布状态不允许、权限或网络错误。
 */
export function rollbackSemanticRelease(releaseId: number, reason: string): Promise<any> {
  return request(`${SEMANTIC_MODELING_BASE_URL}/releases/${releaseId}/rollback`, {
    method: 'POST',
    data: { reason },
  });
}
