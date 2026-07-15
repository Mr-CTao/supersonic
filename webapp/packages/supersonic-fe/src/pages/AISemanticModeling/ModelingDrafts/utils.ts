/**
 * AI 语义建模草稿页面纯函数工具模块。
 *
 * 职责：
 * - 兼容后端结构化对象和 JSON 字符串两种草稿返回形态；
 * - 将草稿对象转换为稳定的结构树和格式化 JSON；
 * - 统一响应解包、错误文案、冲突识别和选表展示逻辑。
 *
 * 并发说明：
 * - 所有函数均为无副作用纯函数，不访问共享状态，不需要并发保护。
 */
import type { DataNode } from 'antd/es/tree';
import { MODELING_DRAFT_REVISION_INSTRUCTION_MAX_LENGTH } from '@/services/semanticModelingDraft';
import type {
  ModelingDraftAttempt,
  ModelingDraftItem,
  ModelingDraftStatus,
  ModelingDraftVersionDiff,
  ModelingValidationIssue,
  ModelingValidationItem,
  ModelingValidationReport,
  SelectedTable,
  SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';

/** 阶段 3 允许管理员对同一草稿发起的最大手工重新生成次数。 */
export const MAX_MANUAL_REGENERATIONS = 3;

export type DraftParseResult = {
  value?: SemanticModelingDraftJson;
  error?: string;
};

export type RegenerationAvailability = {
  allowed: boolean;
  reason: string;
};

export type ModelingSubmitGateState = {
  allowed: boolean;
  reason: string;
};

export type ModelingSubmitGateInput = {
  draftStatus?: ModelingDraftStatus;
  currentVersionNo?: number;
  dirty: boolean;
  busy?: boolean;
  report?: ModelingValidationReport;
};

/** 阶段 4 服务端固定十项必需检查；顺序与报告展示保持一致。 */
export const MODELING_REQUIRED_VALIDATION_CHECKS = [
  'JSON_SCHEMA',
  'TABLE_FIELD_EXISTENCE',
  'METRIC_EXPRESSION_FIELD',
  'SENSITIVE_FIELD',
  'NAME_CONFLICT',
  'RETRIEVAL_POLLUTION',
  'SAMPLE_QUESTION',
  'SEMANTIC_SQL_GENERATION',
  'SQL_READ_ONLY',
  'PERFORMANCE_RISK',
] as const;

export type ModelingRevisionFailureDisposition = {
  baseVersionConflict: boolean;
  errorCode?: string;
  reuseIdempotencyKey: boolean;
  serverResponded: boolean;
};

const REVISION_RUNNING_ERROR_CODE = 'REVISION_RUNNING';
const REVISION_BASE_CONFLICT_ERROR_CODES = new Set([
  'LOCK_VERSION_CONFLICT',
  'REVISION_BASE_VERSION_CHANGED',
  'REVISION_BASE_VERSION_CONFLICT',
]);

/**
 * 解包 Supersonic 统一响应中的业务数据。
 *
 * @param response `{code,data,msg}` 响应或已经解包的业务对象。
 * @returns 成功响应中的 data；无统一包装时返回原对象。
 * @throws 响应明确携带非 2xx 业务码时抛出包含管理员文案的 Error。
 */
export function unwrapResponseData<T = any>(response: any): T {
  if (!response || !Object.prototype.hasOwnProperty.call(response, 'code')) {
    return response as T;
  }
  const code = Number(response.code);
  if (code >= 200 && code < 300) {
    return response.data as T;
  }
  const error = new Error(response.msg || response.message || '请求失败') as Error & {
    code?: number;
  };
  error.code = code;
  throw error;
}

/**
 * 从请求异常中提取管理员可理解且不包含原始模型内容的文案。
 *
 * @param error umi-request、后端统一响应或普通 Error。
 * @returns 适合 message/Alert 展示的错误文本。
 * @throws 不抛出异常。
 */
export function getRequestErrorText(error: any): string {
  const responseData = error?.data || error?.response?.data;
  // 非 2xx 响应仍会被全局 ResponseAdvice 包装一层，草稿错误对象位于 data 中。
  const data =
    responseData?.data && typeof responseData.data === 'object' ? responseData.data : responseData;
  const issueMessage = Array.isArray(data?.issues)
    ? data.issues.find((issue: any) => typeof issue?.message === 'string')?.message
    : undefined;
  return (
    issueMessage ||
    data?.msg ||
    data?.message ||
    error?.msg ||
    error?.message ||
    '请求失败，请检查后端服务、权限或网络连接'
  );
}

/**
 * 从统一错误包装或 umi-request 异常中提取稳定业务错误码。
 *
 * @param error 请求异常。
 * @returns 优先返回内层业务错误码；只有 HTTP 数字码时返回 undefined。
 * @throws 不抛出异常。
 */
export function getRequestErrorCode(error: any): string | undefined {
  const candidates = [
    error?.data?.data?.code,
    error?.response?.data?.data?.code,
    error?.data?.errorCode,
    error?.response?.data?.errorCode,
    error?.data?.code,
    error?.response?.data?.code,
    error?.errorCode,
    error?.code,
  ];
  return candidates.find(
    (candidate) => typeof candidate === 'string' && !/^\d+$/.test(candidate.trim()),
  );
}

/**
 * 判断异常是否来自已建立的 HTTP 响应，而不是断网、超时或连接被重置。
 *
 * @param error 请求异常。
 * @returns 存在 Response 或有效 HTTP 状态码时返回 true。
 * @throws 不抛出异常。
 */
export function hasServerResponse(error: any): boolean {
  if (error?.response) return true;
  const statusCandidates = [error?.status, error?.data?.status, error?.data?.code, error?.code];
  return statusCandidates.some((candidate) => {
    const status = Number(candidate);
    return Number.isInteger(status) && status >= 100 && status <= 599;
  });
}

/**
 * 决定 AI 修订失败后是否复用原始幂等键和基线版本。
 *
 * @param error umi-request 异常或统一错误响应。
 * @returns 无响应或 REVISION_RUNNING 时复用；其余服务端终态响应创建新业务请求。
 * @throws 不抛出异常。
 */
export function getModelingRevisionFailureDisposition(
  error: any,
): ModelingRevisionFailureDisposition {
  const errorCode = getRequestErrorCode(error);
  const serverResponded = hasServerResponse(error);
  return {
    baseVersionConflict: Boolean(
      serverResponded && errorCode && REVISION_BASE_CONFLICT_ERROR_CODES.has(errorCode),
    ),
    errorCode,
    reuseIdempotencyKey: !serverResponded || errorCode === REVISION_RUNNING_ERROR_CODE,
    serverResponded,
  };
}

/**
 * 判断请求是否由草稿乐观锁冲突导致。
 *
 * @param error 请求异常或带业务码的 Error。
 * @returns HTTP/业务码为 409 时返回 true。
 * @throws 不抛出异常。
 */
export function isOptimisticLockConflict(error: any): boolean {
  const status =
    error?.response?.status ||
    error?.status ||
    error?.code ||
    error?.data?.code ||
    error?.response?.data?.code;
  return Number(status) === 409;
}

/**
 * 将结构化对象或 JSON 字符串解析为草稿 JSON 1.0。
 *
 * @param input 后端 currentDraft、draftJson 或编辑器文本。
 * @returns 解析结果；失败时仅返回脱敏后的格式错误，不回显原文。
 * @throws 不抛出异常，避免无效 JSON 让工作台崩溃。
 */
export function parseDraftJson(input: unknown): DraftParseResult {
  if (!input) {
    return { error: '草稿内容为空' };
  }
  try {
    const value = typeof input === 'string' ? JSON.parse(input) : input;
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { error: '草稿必须是 JSON 对象' };
    }
    return { value: value as SemanticModelingDraftJson };
  } catch (_error) {
    return { error: 'JSON 格式无效，请检查括号、引号和逗号' };
  }
}

/**
 * 从详情兼容字段中读取当前结构化草稿。
 *
 * @param detail 草稿详情。
 * @returns 优先解析 currentDraft，缺失时回退 draftJson。
 * @throws 不抛出异常，错误通过返回值表达。
 */
export function parseDraftDetail(detail?: ModelingDraftItem): DraftParseResult {
  return parseDraftJson(detail?.currentDraft || detail?.draftJson);
}

/**
 * 将结构化草稿格式化为便于人工编辑的 JSON 文本。
 *
 * @param draft 结构化草稿。
 * @returns 两空格缩进的 JSON 字符串。
 * @throws 草稿包含循环引用时 JSON.stringify 会抛出异常；后端 JSON 对象不会出现该情况。
 */
export function stringifyDraftJson(draft: SemanticModelingDraftJson): string {
  return JSON.stringify(draft, null, 2);
}

/**
 * 构建左侧语义资产结构树。
 *
 * @param draft 结构化草稿。
 * @returns 包含模型维度、指标、敏感字段、示例问法，以及域级术语和不确定项的 Tree 数据。
 * @throws 不抛出异常；缺失数组按空数组处理。
 */
export function buildDraftTreeData(draft?: SemanticModelingDraftJson): DataNode[] {
  if (!draft) {
    return [];
  }
  if (draft.action === 'EXTEND_EXISTING') {
    const additions = draft.additions || {};
    return [
      {
        key: 'target-asset-reference',
        title: `目标资产（只读）· ${draft.targetAsset?.name || '未命名'}`,
      },
      {
        key: 'incremental-additions',
        title: '本次增量',
        children: [
          {
            key: 'incremental-dimensions',
            title: `新增维度（${additions.dimensions?.length || 0}）`,
            children: (additions.dimensions || []).map((item, index) => ({
              key: `incremental-dimension-${item.key || item.field || index}`,
              title: item.name || item.field || `维度 ${index + 1}`,
            })),
          },
          {
            key: 'incremental-metrics',
            title: `新增指标（${additions.metrics?.length || 0}）`,
            children: (additions.metrics || []).map((item, index) => ({
              key: `incremental-metric-${item.key || item.field || index}`,
              title: item.name || item.field || `指标 ${index + 1}`,
            })),
          },
          {
            key: 'incremental-terms',
            title: `新增术语（${additions.terms?.length || 0}）`,
            children: (additions.terms || []).map((item, index) => ({
              key: `incremental-term-${item.key || item.name || index}`,
              title: item.name || `术语 ${index + 1}`,
            })),
          },
          {
            key: 'incremental-modifications',
            title: `受控修改（${draft.modifications?.length || 0}）`,
          },
          {
            key: 'incremental-regression-questions',
            title: `回归问法（${draft.regressionQuestions?.length || 0}）`,
          },
        ],
      },
      {
        key: 'incremental-uncertainties',
        title: `不确定项（${draft.uncertainties?.length || 0}）`,
      },
    ];
  }
  const modelNodes = (draft.models || []).map((model, modelIndex) => {
    const modelKey = `model-${modelIndex}`;
    return {
      key: modelKey,
      title: model.name || `模型 ${modelIndex + 1}`,
      children: [
        {
          key: `${modelKey}-dimensions`,
          title: `维度（${model.dimensions?.length || 0}）`,
          children: (model.dimensions || []).map((item, index) => ({
            key: `${modelKey}-dimension-${index}`,
            title: item.name || item.field || `维度 ${index + 1}`,
          })),
        },
        {
          key: `${modelKey}-metrics`,
          title: `指标（${model.metrics?.length || 0}）`,
          children: (model.metrics || []).map((item, index) => ({
            key: `${modelKey}-metric-${index}`,
            title: item.name || `指标 ${index + 1}`,
          })),
        },
        {
          key: `${modelKey}-sensitive`,
          title: `敏感字段（${model.sensitiveFields?.length || 0}）`,
          children: (model.sensitiveFields || []).map((item, index) => ({
            key: `${modelKey}-sensitive-${index}`,
            title: item.field || `字段 ${index + 1}`,
          })),
        },
        {
          key: `${modelKey}-questions`,
          title: `示例问法（${model.sampleQuestions?.length || 0}）`,
        },
      ],
    } as DataNode;
  });
  return [
    {
      key: 'models',
      title: `模型（${modelNodes.length}）`,
      children: modelNodes,
    },
    {
      key: 'domain-terms',
      title: `域级术语（${draft.terms?.length || 0}）`,
    },
    {
      key: 'domain-uncertainties',
      title: `域级不确定项（${draft.uncertainties?.length || 0}）`,
    },
  ];
}

/**
 * 将后端多种选表存储形态转换为安全的展示文本。
 *
 * @param selectedTables 字符串数组、结构化数组或序列化 JSON。
 * @returns 逗号分隔的 catalog/database/table 路径；无法解析时返回短占位符。
 * @throws 不抛出异常，避免旧数据格式破坏详情页面。
 */
export function formatSelectedTables(selectedTables?: string[] | SelectedTable[] | string): string {
  if (!selectedTables) {
    return '-';
  }
  let values: string[] | SelectedTable[] = selectedTables as string[] | SelectedTable[];
  if (typeof selectedTables === 'string') {
    try {
      const parsed = JSON.parse(selectedTables);
      values = Array.isArray(parsed) ? parsed : [selectedTables];
    } catch (_error) {
      values = [selectedTables];
    }
  }
  if (!Array.isArray(values) || values.length === 0) {
    return '-';
  }
  return values
    .map((item) => {
      if (typeof item === 'string') {
        return item;
      }
      return [item.catalogName, item.databaseName, item.tableName].filter(Boolean).join('.');
    })
    .join('、');
}

/**
 * 从创建响应中提取草稿 ID。
 *
 * @param data 已解包的响应数据，可能是数字或包含 draftId/id 的对象。
 * @returns 草稿 ID，无法识别时返回 undefined。
 * @throws 不抛出异常。
 */
export function extractDraftId(data: any): number | undefined {
  const value = typeof data === 'number' ? data : data?.draftId ?? data?.id;
  const id = Number(value);
  return Number.isFinite(id) && id > 0 ? id : undefined;
}

/**
 * 根据后端最新状态和前端硬上限判断是否可以重新生成。
 *
 * @param draft 列表或详情返回的草稿摘要。
 * @returns allowed 为 false 时 reason 可直接用于禁用按钮 Tooltip。
 * @throws 不抛出异常；缺少后端扩展字段时仍保留向后兼容。
 */
export function getRegenerationAvailability(draft?: ModelingDraftItem): RegenerationAvailability {
  if (!draft || draft.status !== 'GENERATION_FAILED') {
    return { allowed: false, reason: '仅生成失败的草稿可以重新生成' };
  }
  const reachedLimit =
    Number(draft.manualRegenerationCount ?? 0) >= MAX_MANUAL_REGENERATIONS ||
    draft.remainingManualRegenerations === 0;
  if (reachedLimit) {
    return {
      allowed: false,
      reason:
        draft.regenerationBlockReason ||
        `已达到最多 ${MAX_MANUAL_REGENERATIONS} 次手工重新生成上限`,
    };
  }
  if (draft.canRegenerate === false) {
    return {
      allowed: false,
      reason: draft.regenerationBlockReason || '当前草稿状态已不允许重新生成',
    };
  }
  if (!Number.isInteger(draft.lockVersion) || draft.lockVersion < 0) {
    return { allowed: false, reason: '草稿锁版本缺失，请重新加载后再试' };
  }
  return { allowed: true, reason: '重新生成草稿' };
}

/**
 * 将生成尝试复制并按 attemptNo 倒序排列。
 *
 * @param attempts 后端 PageInfo.list 或空数组。
 * @returns 不修改原数组的倒序结果。
 * @throws 不抛出异常。
 */
export function sortModelingDraftAttempts(
  attempts: ModelingDraftAttempt[] = [],
): ModelingDraftAttempt[] {
  return [...attempts].sort((left, right) => Number(right.attemptNo) - Number(left.attemptNo));
}

/**
 * 解析后端脱敏的校验问题，拒绝回显无法识别的任意字符串。
 *
 * @param issues 结构化问题数组或兼容的 JSON 字符串。
 * @returns 仅包含 path/code/message 的安全问题列表。
 * @throws 不抛出异常；非法 JSON 返回空数组。
 */
export function parseValidationIssues(
  issues?: ModelingValidationIssue[] | string,
): ModelingValidationIssue[] {
  try {
    const parsed = typeof issues === 'string' ? JSON.parse(issues) : issues;
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        path: typeof item.path === 'string' ? item.path : undefined,
        code: typeof item.code === 'string' ? item.code : undefined,
        message: typeof item.message === 'string' ? item.message : undefined,
      }))
      .filter((item) => item.path || item.code || item.message);
  } catch (_error) {
    return [];
  }
}

/**
 * 将验证报告阻塞项或警告项归一化为安全、有限的展示对象。
 *
 * @param input 后端结构化数组、字符串数组或兼容 JSON 字符串。
 * @returns 最多 100 个仅含稳定分类、路径、消息、严重级别和模型 key 的问题对象。
 * @throws 不抛出异常；无法识别的对象和非法 JSON 会被忽略。
 */
export function normalizeModelingValidationItems(input: unknown): ModelingValidationItem[] {
  let values: unknown = input;
  if (typeof input === 'string') {
    try {
      const parsed = JSON.parse(input);
      values = Array.isArray(parsed) ? parsed : [input];
    } catch (_error) {
      values = [input];
    }
  }
  if (values && typeof values === 'object' && !Array.isArray(values)) {
    values = [values];
  }
  if (!Array.isArray(values)) return [];
  return values
    .slice(0, 100)
    .map((item): ModelingValidationItem | undefined => {
      if (typeof item === 'string') return { message: item.slice(0, 1000) };
      if (!item || typeof item !== 'object') return undefined;
      const value = item as Record<string, unknown>;
      const text = (key: string) =>
        typeof value[key] === 'string' ? String(value[key]).slice(0, 1000) : undefined;
      const normalized = {
        path: text('path'),
        code: text('code'),
        category: text('category') || text('type'),
        message: text('message') || text('description') || text('reason') || text('title'),
        detail: text('detail') || text('suggestion'),
        severity: text('severity'),
        modelKey: text('modelKey'),
        objectType: text('objectType'),
        objectKey: text('objectKey'),
        blocking: typeof value.blocking === 'boolean' ? value.blocking : undefined,
      };
      // 未知结构仍代表后端返回了一项门禁结果；宁可阻塞并提示复核，也不能静默放行。
      return normalized.path ||
        normalized.code ||
        normalized.category ||
        normalized.message ||
        normalized.detail ||
        normalized.severity ||
        normalized.modelKey
        ? normalized
        : { message: '存在未识别的校验项，请查看后端日志或刷新报告', blocking: true };
    })
    .filter((item): item is ModelingValidationItem =>
      Boolean(item && (item.path || item.code || item.category || item.message || item.detail)),
    );
}

/**
 * 返回验证报告中缺失、重复、NOT_RUN、失败、空状态或未知状态的必需检查 ID。
 *
 * @param report 当前版本验证报告。
 * @returns 不完整检查 ID；历史报告缺少集合时返回全部十项。
 * @throws 不抛出异常，非数组结构按全部不完整处理。
 */
export function getIncompleteRequiredValidationChecks(report?: ModelingValidationReport): string[] {
  if (!Array.isArray(report?.requiredCheckResults)) {
    return [...MODELING_REQUIRED_VALIDATION_CHECKS];
  }
  const completedStatuses = new Set(['PASSED', 'WARNING']);
  return MODELING_REQUIRED_VALIDATION_CHECKS.filter((checkId) => {
    const matches = report.requiredCheckResults!.filter(
      (result) => String(result?.category || '').toUpperCase() === checkId,
    );
    if (matches.length !== 1) return true;
    return !completedStatuses.has(String(matches[0]?.status || '').toUpperCase());
  });
}

/**
 * 判断详情响应是否明确授予草稿写权限。
 *
 * @param detail 当前草稿详情；加载中或缺少 canManage 时按只读处理。
 * @returns 仅 canManage === true 时返回 true。
 * @throws 不抛出异常。
 */
export function hasModelingManagePermission(detail?: ModelingDraftItem): boolean {
  return detail?.canManage === true;
}

/**
 * 计算“提交审批”按钮的前端展示门禁。
 *
 * @param input 草稿状态、当前版本、本地脏状态、忙状态和最新验证报告。
 * @returns allowed 为 false 时 reason 可直接用于按钮 Tooltip；服务端仍必须独立复核。
 * @throws 不抛出异常。
 */
export function getModelingSubmitGateState(
  input: ModelingSubmitGateInput,
): ModelingSubmitGateState {
  const { busy, currentVersionNo, dirty, draftStatus, report } = input;
  if (draftStatus === 'PENDING_APPROVAL') {
    return { allowed: false, reason: '当前草稿已经提交审批' };
  }
  if (draftStatus !== 'DRAFT') {
    return { allowed: false, reason: '仅草稿状态可以提交审批' };
  }
  if (dirty) {
    return { allowed: false, reason: '请先保存当前修改并重新执行验证' };
  }
  if (busy) {
    return { allowed: false, reason: '当前操作尚未完成，请稍候' };
  }
  if (!Number.isInteger(currentVersionNo) || Number(currentVersionNo) <= 0) {
    return { allowed: false, reason: '当前草稿版本无效，请重新加载' };
  }
  if (!report) {
    return { allowed: false, reason: '请先对当前版本执行验证' };
  }
  if (Number(report.draftVersionNo) !== Number(currentVersionNo)) {
    return { allowed: false, reason: '验证报告属于旧版本，请重新验证当前版本' };
  }
  const incompleteChecks = getIncompleteRequiredValidationChecks(report);
  if (incompleteChecks.length > 0) {
    return {
      allowed: false,
      reason: `验证报告必需检查未完成：${incompleteChecks.join('、')}`,
    };
  }
  if (report.canSubmit !== true) {
    return {
      allowed: false,
      reason: report.submissionBlockReason || '服务端报告快照不允许提交审批',
    };
  }
  if (report.status === 'RUNNING') {
    return { allowed: false, reason: '当前版本仍在验证中' };
  }
  if (report.status === 'FAILED') {
    return { allowed: false, reason: '验证失败，请先修复阻塞项' };
  }
  if (report.status === 'SYSTEM_FAILED') {
    return { allowed: false, reason: '验证任务异常，请重新执行验证' };
  }
  if (!['PASSED', 'WARNING'].includes(report.status)) {
    return { allowed: false, reason: '验证报告状态不允许提交审批' };
  }
  if (normalizeModelingValidationItems(report.blockingItems).length > 0) {
    return { allowed: false, reason: '验证报告仍包含阻塞项' };
  }
  return {
    allowed: true,
    reason: report.status === 'WARNING' ? '验证包含警告，确认后可提交审批' : '验证已通过',
  };
}

/**
 * 将验证阻塞项转换为可编辑的 AI 修订指令。
 *
 * @param input 验证报告或阻塞项集合。
 * @returns 有界的自然语言修订指令；无有效阻塞项时返回空字符串。
 * @throws 不抛出异常。
 *
 * @example
 * buildValidationRepairInstruction([{ code: 'UNKNOWN_FIELD', message: '字段不存在' }]);
 */
export function buildValidationRepairInstruction(
  input: ModelingValidationReport | ModelingValidationReport['blockingItems'],
): string {
  const rawItems =
    input && typeof input === 'object' && !Array.isArray(input) && 'blockingItems' in input
      ? input.blockingItems
      : input;
  const items = normalizeModelingValidationItems(rawItems).slice(0, 20);
  if (!items.length) return '';
  const lines = items.map((item, index) => {
    const location = [item.path, item.code, item.category].filter(Boolean).join(' · ');
    const detail = item.message || item.detail || '请修复该阻塞项';
    return `${index + 1}. ${location ? `[${location}] ` : ''}${detail}`;
  });
  const instruction = [
    '请修复以下验证阻塞项，并保持其他已经确认的语义对象和业务口径不变：',
    ...lines,
    '修订后请返回完整结构化草稿；无法确认的业务口径请继续保留为不确定项。',
  ].join('\n');
  if (instruction.length <= MODELING_DRAFT_REVISION_INSTRUCTION_MAX_LENGTH) return instruction;
  const suffix = '\n…其余阻塞项请在验证报告中继续复核。';
  return `${instruction.slice(
    0,
    MODELING_DRAFT_REVISION_INSTRUCTION_MAX_LENGTH - suffix.length,
  )}${suffix}`;
}

/**
 * 校验版本差异响应是否仍属于当前草稿和所选版本对。
 *
 * @param diff 服务端返回的差异响应。
 * @param draftId 当前草稿 ID。
 * @param fromVersionNo 当前起始版本号。
 * @param toVersionNo 当前目标版本号。
 * @returns 三个标识均一致时返回 true，防止迟到响应覆盖新选择。
 * @throws 不抛出异常。
 */
export function isModelingVersionDiffForSelection(
  diff: ModelingDraftVersionDiff | undefined,
  draftId: number,
  fromVersionNo?: number,
  toVersionNo?: number,
): boolean {
  return Boolean(
    diff &&
      Number(diff.draftId) === Number(draftId) &&
      Number(diff.fromVersionNo) === Number(fromVersionNo) &&
      Number(diff.toVersionNo) === Number(toVersionNo),
  );
}

/**
 * 将差异值转换为有界的只读文本，避免超大对象拖慢管理页面。
 *
 * @param value 差异前值或后值。
 * @returns 最多 2000 字符的 JSON/文本。
 * @throws 不抛出异常；循环引用回退为占位符。
 */
export function formatModelingDiffValue(value: unknown): string {
  if (value === undefined) return '-';
  if (value === null) return 'null';
  try {
    const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    return text.length > 2000 ? `${text.slice(0, 2000)}…` : text;
  } catch (_error) {
    return '[无法展示的复杂值]';
  }
}
