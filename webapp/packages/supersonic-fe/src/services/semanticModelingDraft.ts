/**
 * AI 语义建模草稿前端服务模块。
 *
 * 职责：
 * - 统一封装阶段 3 草稿创建、重新生成、生成尝试、分页查询、详情保存和版本快照接口；
 * - 封装阶段 4 AI 多轮修订、版本差异、验证报告和提交审批门禁接口；
 * - 描述草稿 JSON 1.0 的前端数据结构，供列表、创建表单和详情工作台复用；
 * - 仅操作独立草稿接口，不调用正式模型、维度、指标、术语或发布接口。
 *
 * 并发说明：
 * - 创建请求由调用方生成并复用 `Idempotency-Key`，避免重复点击产生多个任务；
 * - 保存请求携带 `lockVersion`，后端以乐观锁拒绝过期版本；
 * - 本模块不保存共享状态，因此无需额外的前端锁。
 */
import request from 'umi-request';

const MODELING_DRAFT_BASE_URL = '/api/semantic/modeling/drafts';
const MODELING_VALIDATION_REPORT_BASE_URL = '/api/semantic/modeling/validation-reports';
const SEMANTIC_GAP_BASE_URL = '/api/semantic/gaps';

/** 与后端 ModelingDraftAiReviseReq 的 @Size(max = 2000) 保持一致。 */
export const MODELING_DRAFT_REVISION_INSTRUCTION_MAX_LENGTH = 2000;

export type ModelingDraftSourceType = 'SEMANTIC_GAP' | 'DATA_SOURCE';
export type ModelingDraftStatus = 'GENERATING' | 'DRAFT' | 'GENERATION_FAILED' | 'PENDING_APPROVAL';
export type ModelingDraftAttemptStatus =
  | 'QUEUED'
  | 'GENERATING'
  | 'SUCCEEDED'
  | 'SUCCESS'
  | 'FAILED'
  | 'GENERATION_FAILED'
  | string;

export type DraftDimension = {
  key?: string;
  name: string;
  bizName?: string;
  field: string;
  semanticType?: string;
  aliases?: string[];
  description?: string;
};

export type DraftMetricFilter = {
  field: string;
  operator: string;
  value?: unknown;
  values?: unknown[];
};

export type DraftMetric = {
  key?: string;
  name: string;
  bizName?: string;
  field?: string;
  expression?: string | null;
  aggregation: 'SUM' | 'COUNT' | 'COUNT_DISTINCT' | 'AVG' | 'MAX' | 'MIN';
  description?: string;
  aliases?: string[];
  filters?: DraftMetricFilter[];
};

export type DraftTermTarget = {
  type: 'DIMENSION' | 'METRIC' | string;
  objectKey: string;
};

export type DraftTerm = {
  key?: string;
  name: string;
  aliases?: string[];
  mappingTarget?: string;
  mappingTargetKey?: string;
  mappingType?: 'DIMENSION' | 'METRIC';
  targets?: DraftTermTarget[];
  description?: string;
};

export type DraftSensitiveField = {
  field: string;
  level: 'LOW' | 'MEDIUM' | 'HIGH' | string;
  maskingStrategy?: string;
  reason?: string;
};

export type DraftUncertainty = {
  key?: string;
  modelKey?: string;
  field?: string;
  objectKey?: string;
  type?: string;
  category?: string;
  severity?: string;
  reason: string;
  suggestion?: string;
};

export type DraftTargetDomain = {
  domainId?: number;
  name?: string;
  bizName?: string;
  description?: string;
};

export type SemanticModelDraftModel = {
  key?: string;
  name: string;
  bizName?: string;
  description?: string;
  baseTable: string;
  primaryTimeField?: string;
  dimensions?: DraftDimension[];
  metrics?: DraftMetric[];
  sensitiveFields?: DraftSensitiveField[];
  sampleQuestions?: string[];
};

export type SemanticModelingDraftJson = {
  schemaVersion?: string;
  businessGoal: string;
  targetDomain?: DraftTargetDomain;
  models: SemanticModelDraftModel[];
  terms?: DraftTerm[];
  uncertainties?: DraftUncertainty[];
};

export type SelectedTable = {
  catalogName?: string;
  databaseName: string;
  tableName: string;
};

export type ModelingDraftItem = {
  id: number;
  sourceType: ModelingDraftSourceType;
  sourceId?: number;
  title?: string;
  businessGoal?: string;
  domainId?: number;
  dataSourceId: number;
  catalogName?: string;
  databaseName?: string;
  selectedTables?: string[] | SelectedTable[] | string;
  chatModelId?: number;
  includeSampleData?: boolean;
  status: ModelingDraftStatus;
  currentDraft?: SemanticModelingDraftJson;
  draftJson?: string;
  errorCode?: string;
  errorMessage?: string;
  currentVersionNo?: number;
  currentVersion?: number;
  lockVersion: number;
  createdBy?: string;
  createdAt?: string;
  updatedBy?: string;
  updatedAt?: string;
  generationStartedAt?: string;
  generationFinishedAt?: string;
  /** 当前生成尝试序号，初始生成为第 1 次。 */
  currentAttemptNo?: number;
  /** 管理员已手工重新生成的次数，不含初始生成。 */
  manualRegenerationCount?: number;
  /** 后端根据上限和当前状态计算的剩余手工重试次数。 */
  remainingManualRegenerations?: number;
  /** 后端复核当前状态、次数和权限后给出的重新生成开关。 */
  canRegenerate?: boolean;
  /** 无法重新生成时可安全展示的阻断原因。 */
  regenerationBlockReason?: string;
  /** 详情接口返回的服务端写权限；false 时页面必须保持只读。 */
  canManage?: boolean;
};

export type ModelingValidationIssue = {
  path?: string;
  code?: string;
  message?: string;
};

/**
 * 一次不可变的 AI 草稿生成尝试摘要。
 *
 * 安全边界：类型刻意不声明 Prompt、样例行、模型原文或修复原文，前端不应请求或展示这些诊断字段。
 */
export type ModelingDraftAttempt = {
  id?: number;
  draftId?: number;
  attemptNo: number;
  triggerType?: string;
  status: ModelingDraftAttemptStatus;
  chatModelId?: number;
  includeSampleData?: boolean;
  llmConversationId?: number;
  generateRequestId?: string;
  repairRequestId?: string;
  failureStage?: string;
  validationIssues?: ModelingValidationIssue[] | string;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  /** 兼容早期契约命名；新接口优先使用 startedAt/finishedAt。 */
  generationStartedAt?: string;
  generationFinishedAt?: string;
  createdBy?: string;
  createdAt?: string;
  updatedBy?: string;
  updatedAt?: string;
};

export type ModelingDraftAttemptPage = {
  list: ModelingDraftAttempt[];
  total: number;
  pageNum?: number;
  pageSize?: number;
};

export type ModelingDraftVersion = {
  id?: number;
  draftId: number;
  versionNo: number;
  changeSource?: string;
  changeSummary?: string;
  snapshot?: SemanticModelingDraftJson;
  currentDraft?: SemanticModelingDraftJson;
  draftJson?: string;
  createdBy?: string;
  createdAt?: string;
};

/** 草稿版本摘要分页参数；版本历史必须显式分页，避免只展示服务端默认前 20 条。 */
export type ModelingDraftVersionQueryParams = {
  page?: number;
  pageSize?: number;
};

/** 阶段 4 AI 修订或版本比较返回的单个结构化差异。 */
export type ModelingDraftChangeItem = {
  path: string;
  changeType: 'ADDED' | 'REMOVED' | 'CHANGED' | 'MODIFIED' | string;
  beforeValue?: unknown;
  afterValue?: unknown;
};

/** AI 多轮修订请求；baseVersionNo 用于拒绝覆盖已变化的草稿。 */
export type AiReviseModelingDraftReq = {
  instruction: string;
  baseVersionNo: number;
};

/** AI 多轮修订结果；完整详情仍应通过详情接口重新读取。 */
export type AiReviseModelingDraftResp = {
  draftId: number;
  baseVersionNo: number;
  newVersionNo: number;
  lockVersion: number;
  draftJson: SemanticModelingDraftJson | string;
  changeSummary?: string;
  changes?: ModelingDraftChangeItem[];
  uncertaintyItems?: DraftUncertainty[];
  idempotentReplay?: boolean;
};

/** 两个不可变草稿版本之间的结构化差异。 */
export type ModelingDraftVersionDiff = {
  draftId: number;
  fromVersionNo: number;
  toVersionNo: number;
  summary?: string;
  items?: ModelingDraftChangeItem[];
  truncated?: boolean;
};

/** 追加式恢复请求；两个基线字段共同防止覆盖并发写入。 */
export type RestoreModelingDraftVersionReq = {
  currentVersionNo: number;
  lockVersion: number;
};

/** 恢复只创建新草稿版本，不修改历史快照或正式语义对象。 */
export type RestoreModelingDraftVersionResp = {
  draftId: number;
  targetVersionNo: number;
  baseVersionNo: number;
  newVersionNo: number;
  lockVersion: number;
  currentDraft: SemanticModelingDraftJson;
  idempotentReplay?: boolean;
};

export type ModelingValidationStatus =
  | 'PASSED'
  | 'WARNING'
  | 'FAILED'
  | 'NOT_RUN'
  | 'RUNNING'
  | 'SYSTEM_FAILED';

/** 验证报告中的安全展示问题；服务端应先完成敏感内容脱敏。 */
export type ModelingValidationItem = {
  path?: string;
  code?: string;
  category?: string;
  message?: string;
  detail?: string;
  severity?: string;
  modelKey?: string;
  objectType?: string;
  objectKey?: string;
  blocking?: boolean;
};

/** 验证报告的分类结果保留扩展字段，以兼容不同隔离校验器的结构化明细。 */
export type ModelingValidationCheckResult = {
  category?: string;
  status?: ModelingValidationStatus | string;
  passed?: boolean;
  summary?: string;
  message?: string;
  items?: unknown[];
  issues?: unknown[];
  [key: string]: unknown;
};

/** 与草稿不可变版本绑定的阶段 4 验证报告。 */
export type ModelingValidationReport = {
  id: number;
  draftId: number;
  draftVersionId?: number;
  draftVersionNo: number;
  status: ModelingValidationStatus;
  plannedObjects?: unknown;
  requiredCheckResults?: ModelingValidationCheckResult[];
  sampleQuestionResults?: unknown;
  sqlSafetyResult?: ModelingValidationCheckResult | unknown;
  sensitiveFieldResult?: ModelingValidationCheckResult | unknown;
  conflictResult?: ModelingValidationCheckResult | unknown;
  fieldExistenceResult?: ModelingValidationCheckResult | unknown;
  uncertaintyResult?: ModelingValidationCheckResult | unknown;
  performanceRiskResult?: ModelingValidationCheckResult | unknown;
  blockingItems?: ModelingValidationItem[] | string[] | string;
  warningItems?: ModelingValidationItem[] | string[] | string;
  blockingCount?: number;
  warningCount?: number;
  canSubmit?: boolean;
  submissionBlockReason?: string;
  createdBy?: string;
  createdAt?: string;
  finishedAt?: string;
};

export type ValidateModelingDraftReq = {
  versionNo: number;
  validationOptions?: {
    sqlPreviewLimit?: number;
  };
};

export type ModelingValidationReportQueryParams = {
  page?: number;
  pageSize?: number;
};

export type SubmitModelingDraftReq = {
  versionNo: number;
  validationReportId: number;
};

/** 提交审批只改变草稿治理状态，不代表正式语义资产已经发布。 */
export type SubmitModelingDraftResp = {
  draftId: number;
  versionNo: number;
  validationReportId: number;
  status: 'PENDING_APPROVAL';
  submittedBy?: string;
  submittedAt?: string;
  idempotentReplay?: boolean;
};

export type ModelingDraftQueryParams = {
  sourceType?: ModelingDraftSourceType;
  status?: ModelingDraftStatus;
  dataSourceId?: number;
  keyword?: string;
  page?: number;
  pageSize?: number;
};

export type CreateModelingDraftReq = {
  sourceType: ModelingDraftSourceType;
  sourceId?: number;
  businessGoal: string;
  domainId?: number;
  dataSourceId: number;
  catalogName?: string;
  databaseName: string;
  selectedTables: string[];
  chatModelId: number;
  includeSampleData: boolean;
};

export type UpdateModelingDraftReq = {
  lockVersion: number;
  currentDraft?: SemanticModelingDraftJson;
  draftJson?: string;
  changeSummary?: string;
};

export type RegenerateModelingDraftReq = {
  /** 必须基于页面最后读取的锁版本重试，状态已变化时由后端返回 409。 */
  lockVersion: number;
  chatModelId: number;
  includeSampleData: boolean;
};

export type ModelingDraftAttemptQueryParams = {
  page?: number;
  pageSize?: number;
};

/**
 * 创建数据源来源的 AI 建模草稿任务。
 *
 * @param data 业务目标、数据源、选表和 JSON-capable LLM 配置。
 * @param idempotencyKey 当前表单会话内稳定复用的幂等键。
 * @returns 后端统一响应，data 包含新建或重放命中的草稿 ID。
 * @throws 网络错误、鉴权错误或后端拒绝创建时抛出异常。
 */
export function createModelingDraft(
  data: CreateModelingDraftReq,
  idempotencyKey: string,
): Promise<any> {
  return request(MODELING_DRAFT_BASE_URL, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data,
  });
}

/**
 * 从语义缺口创建 AI 建模草稿任务。
 *
 * @param gapId 语义缺口 ID。
 * @param data 管理员补齐的数据源、选表、业务目标和 LLM 配置。
 * @param idempotencyKey 当前表单会话内稳定复用的幂等键。
 * @returns 后端统一响应，data 包含草稿 ID。
 * @throws 网络错误、无权访问缺口或数据源、重复活动草稿等异常。
 */
export function createModelingDraftFromGap(
  gapId: number,
  data: CreateModelingDraftReq,
  idempotencyKey: string,
): Promise<any> {
  return request(`${SEMANTIC_GAP_BASE_URL}/${gapId}/drafts`, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data,
  });
}

/**
 * 分页查询当前用户可访问的建模草稿。
 *
 * @param params 来源、状态、数据源、关键词和分页条件。
 * @returns 后端统一响应，data 为包含 list/total 的分页对象。
 * @throws 网络错误或数据源权限复核失败时抛出异常。
 */
export function getModelingDrafts(params: ModelingDraftQueryParams): Promise<any> {
  return request(MODELING_DRAFT_BASE_URL, {
    method: 'GET',
    params,
  });
}

/**
 * 查询建模草稿最新详情。
 *
 * @param id 草稿 ID。
 * @returns 后端统一响应，data 同时包含结构化 currentDraft 和兼容 draftJson。
 * @throws 草稿不存在、越权或网络失败时抛出异常。
 */
export function getModelingDraftDetail(id: number): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}`, {
    method: 'GET',
  });
}

/**
 * 使用乐观锁保存草稿并生成下一份不可变版本快照。
 *
 * @param id 草稿 ID。
 * @param data 当前锁版本、结构化草稿和变更摘要。
 * @returns 后端统一响应，data 为保存后的最新草稿详情。
 * @throws 锁版本冲突时返回 409；校验失败、越权或网络失败时也会抛出异常。
 */
export function updateModelingDraft(id: number, data: UpdateModelingDraftReq): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}`, {
    method: 'PUT',
    data,
  });
}

/**
 * 对生成失败的原草稿发起一次受限的手工重新生成。
 *
 * @param id 原草稿 ID；成功后仍在该 ID 上轮询，不创建新草稿。
 * @param data 最新锁版本、JSON-capable LLM 和样例开关。
 * @param idempotencyKey 当前弹窗打开周期内稳定复用的幂等键。
 * @returns 202 响应，data 为已回到 GENERATING 的原草稿详情。
 * @throws 锁版本或状态过期时返回 409；超过次数、越权或模型不可用时也会抛出异常。
 */
export function regenerateModelingDraft(
  id: number,
  data: RegenerateModelingDraftReq,
  idempotencyKey: string,
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/regenerations`, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data,
  });
}

/**
 * 分页读取草稿的生成尝试摘要。
 *
 * @param id 草稿 ID。
 * @param params 页码与页大小；页面首次打开历史时才请求。
 * @returns 后端统一响应，data 为 PageInfo，按 attemptNo 倒序展示。
 * @throws 草稿不存在、越权或网络失败时抛出异常。
 */
export function getModelingDraftAttempts(
  id: number,
  params: ModelingDraftAttemptQueryParams = {},
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/attempts`, {
    method: 'GET',
    params,
  });
}

/**
 * 查询草稿的版本摘要列表。
 *
 * @param id 草稿 ID。
 * @param params 可选页码与页大小；阶段 4 页面应显式分页，避免只读取默认前 20 条。
 * @returns 后端统一响应，data 为版本分页对象。
 * @throws 草稿不存在、越权或网络失败时抛出异常。
 */
export function getModelingDraftVersions(
  id: number,
  params?: ModelingDraftVersionQueryParams,
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/versions`, {
    method: 'GET',
    ...(params ? { params } : {}),
  });
}

/**
 * 查询指定版本的只读快照。
 *
 * @param id 草稿 ID。
 * @param versionNo 版本号。
 * @returns 后端统一响应，data 为不可变版本快照。
 * @throws 版本不存在、越权或网络失败时抛出异常。
 */
export function getModelingDraftVersion(id: number, versionNo: number): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/versions/${versionNo}`, {
    method: 'GET',
  });
}

/**
 * 把历史快照追加为新的当前草稿版本。
 *
 * @param id 草稿 ID。
 * @param versionNo 目标历史版本号。
 * @param data 客户端确认的当前版本和锁版本。
 * @param idempotencyKey 当前恢复动作重试期间稳定复用的幂等键。
 * @returns 新版本、锁版本和恢复后的完整草稿。
 * @throws viewer/public 无权限、状态只读、活动写操作或基线过期时抛出异常。
 */
export function restoreModelingDraftVersion(
  id: number,
  versionNo: number,
  data: RestoreModelingDraftVersionReq,
  idempotencyKey: string,
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/versions/${versionNo}/restore`, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data,
  });
}

/**
 * 基于指定草稿版本执行一次 AI 对话修订。
 *
 * @param id 草稿 ID。
 * @param data 管理员自然语言指令和基线版本号。
 * @param idempotencyKey 当前指令重试期间稳定复用的幂等键。
 * @returns 修订后的新版本、结构化差异和不确定项。
 * @throws 基线版本过期时返回 409；权限、模型或结构校验失败时也会抛出异常。
 */
export function aiReviseModelingDraft(
  id: number,
  data: AiReviseModelingDraftReq,
  idempotencyKey: string,
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/ai-revise`, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data,
  });
}

/**
 * 查询两个不可变草稿版本之间的结构化差异。
 *
 * @param id 草稿 ID。
 * @param fromVersionNo 起始版本号。
 * @param toVersionNo 目标版本号。
 * @returns 差异摘要、明细和是否截断标记。
 * @throws 版本不存在、越权或网络失败时抛出异常。
 */
export function getModelingDraftVersionDiff(
  id: number,
  fromVersionNo: number,
  toVersionNo: number,
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/versions/diff`, {
    method: 'GET',
    params: { fromVersionNo, toVersionNo },
  });
}

/**
 * 对指定草稿版本执行发布前验证门禁。
 *
 * @param id 草稿 ID。
 * @param data 不可变版本号和可选隔离语义翻译 SQL 预览限制。
 * @returns 与该版本绑定的验证报告。
 * @throws 同版本已有验证运行、版本过期、越权或系统校验失败时抛出异常。
 */
export function validateModelingDraft(id: number, data: ValidateModelingDraftReq): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/validate`, {
    method: 'POST',
    data,
  });
}

/**
 * 分页查询草稿验证报告。
 *
 * @param id 草稿 ID。
 * @param params 页码和页大小。
 * @returns 验证报告分页对象，通常按创建时间倒序。
 * @throws 草稿不存在、越权或网络失败时抛出异常。
 */
export function getModelingValidationReports(
  id: number,
  params: ModelingValidationReportQueryParams = {},
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/validation-reports`, {
    method: 'GET',
    params,
  });
}

/**
 * 查询单份验证报告详情。
 *
 * @param reportId 验证报告 ID。
 * @returns 完整验证分类结果与阻塞项。
 * @throws 报告不存在、越权或网络失败时抛出异常。
 */
export function getModelingValidationReport(reportId: number): Promise<any> {
  return request(`${MODELING_VALIDATION_REPORT_BASE_URL}/${reportId}`, {
    method: 'GET',
  });
}

/**
 * 在服务端重新执行版本和验证报告门禁后提交审批。
 *
 * @param id 草稿 ID。
 * @param data 当前版本号和通过门禁的报告 ID。
 * @param idempotencyKey 当前版本与报告组合稳定复用的幂等键。
 * @returns PENDING_APPROVAL 提交摘要；不代表已发布正式语义资产。
 * @throws 旧报告、阻塞项、重复流程、越权或网络失败时抛出异常。
 */
export function submitModelingDraft(
  id: number,
  data: SubmitModelingDraftReq,
  idempotencyKey: string,
): Promise<any> {
  return request(`${MODELING_DRAFT_BASE_URL}/${id}/submit`, {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data,
  });
}
