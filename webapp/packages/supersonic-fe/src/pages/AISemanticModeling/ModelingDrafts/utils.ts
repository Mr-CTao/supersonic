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
import type {
  ModelingDraftAttempt,
  ModelingDraftItem,
  ModelingValidationIssue,
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
