/**
 * 模型 SQL 双重校验结果合并工具。
 *
 * 职责：保持数据源执行与语义编译独立呈现，并集中定义任一通道失败即阻断保存的规则。
 */
import type {
  ModelHealth,
  SemanticValidationCheck,
  SemanticValidationResult,
} from '@/pages/SemanticModel/service';

export type DiagnosticTone = 'success' | 'error' | 'warning' | 'processing' | 'default';

export type DiagnosticStatusPresentation = {
  label: string;
  tone: DiagnosticTone;
};

export type ValidationCheckPartition = {
  executedChecks: SemanticValidationCheck[];
  deferredChecks: SemanticValidationCheck[];
};

type HealthStatus =
  | ModelHealth['compileStatus']
  | ModelHealth['schemaCacheStatus']
  | ModelHealth['dictionaryStatus']
  | ModelHealth['embeddingStatus'];

const VALIDATION_STATUS_PRESENTATIONS: Record<
  SemanticValidationCheck['status'],
  DiagnosticStatusPresentation
> = {
  PASSED: { label: '通过', tone: 'success' },
  BLOCKING: { label: '阻断', tone: 'error' },
  WARNING: { label: '警告', tone: 'warning' },
  SKIPPED: { label: '未执行', tone: 'default' },
};

const HEALTH_STATUS_PRESENTATIONS: Record<HealthStatus, DiagnosticStatusPresentation> = {
  PASSED: { label: '通过', tone: 'success' },
  BLOCKING: { label: '阻断', tone: 'error' },
  WARNING: { label: '警告', tone: 'warning' },
  SKIPPED: { label: '未校验', tone: 'default' },
  PENDING: { label: '等待中', tone: 'processing' },
  RUNNING: { label: '刷新中', tone: 'processing' },
  SUCCEEDED: { label: '已刷新', tone: 'success' },
  FAILED: { label: '失败', tone: 'error' },
  UNKNOWN: { label: '待刷新', tone: 'default' },
};

/**
 * 合并独立数据源执行结果与服务端编译报告。
 *
 * @param sourcePassed 数据源执行是否通过。
 * @param sourceMessage 数据源失败时的安全提示。
 * @param compilerResult 编译服务返回的独立检查项。
 * @returns 任一必需通道失败即 BLOCKING 的统一报告。
 */
export function mergeValidationResult(
  sourcePassed: boolean,
  sourceMessage: string,
  compilerResult?: SemanticValidationResult,
): SemanticValidationResult {
  const compilerChecks = compilerResult?.checks || [];
  const checks = compilerChecks
    .filter((check) => check.type !== 'SOURCE_DATABASE')
    .map((check) => ({ ...check }));
  checks.unshift({
    type: 'SOURCE_DATABASE',
    status: sourcePassed ? 'PASSED' : 'BLOCKING',
    message: sourcePassed ? '数据源执行通过' : sourceMessage,
  });
  if (!checks.some((check) => check.type === 'SEMANTIC_COMPILER')) {
    checks.push({
      type: 'SEMANTIC_COMPILER',
      status: 'BLOCKING',
      message: '语义编译校验请求失败，请重试',
    });
  }
  const compilerPassed = checks.some(
    (check) => check.type === 'SEMANTIC_COMPILER' && check.status === 'PASSED',
  );
  return {
    overallStatus: sourcePassed && compilerPassed ? 'PASSED' : 'BLOCKING',
    checks: checks as SemanticValidationCheck[],
    contentDigest: compilerResult?.contentDigest,
    traceId: compilerResult?.traceId,
  };
}

/**
 * 判断异步响应是否仍属于当前 SQL 内容。
 *
 * @param sequence 响应所属请求序号。
 * @param latestSequence 当前最新请求序号。
 * @param aborted 请求是否已取消。
 * @returns 只有最新且未取消的响应可以提交到 UI。
 */
export function isLatestValidation(
  sequence: number,
  latestSequence: number,
  aborted: boolean,
): boolean {
  return sequence === latestSequence && !aborted;
}

/**
 * 获取校验状态的中文展示文案和语义色。
 *
 * @param status 后端返回的校验状态。
 * @returns 可直接用于状态图标和标签的展示元数据。
 */
export function getValidationStatusPresentation(
  status: SemanticValidationCheck['status'],
): DiagnosticStatusPresentation {
  return VALIDATION_STATUS_PRESENTATIONS[status];
}

/**
 * 获取模型健康状态的中文展示文案和语义色。
 *
 * @param status 后端返回的健康状态；首次加载前可以为空。
 * @returns 可直接用于健康状态卡片的展示元数据。
 */
export function getHealthStatusPresentation(status?: HealthStatus): DiagnosticStatusPresentation {
  return status ? HEALTH_STATUS_PRESENTATIONS[status] : HEALTH_STATUS_PRESENTATIONS.UNKNOWN;
}

/**
 * 统计会阻止模型保存的校验项数量。
 *
 * @param result 当前双重校验报告。
 * @returns 状态为 BLOCKING 的检查项数量。
 */
export function countBlockingChecks(result?: SemanticValidationResult): number {
  return result?.checks.filter((check) => check.status === 'BLOCKING').length || 0;
}

/**
 * 将当前阶段实际执行的检查与后续阶段检查分组。
 *
 * @param checks 后端返回的完整结构化检查列表。
 * @returns 已执行检查用于主列表；SKIPPED 检查仅用于生成弱提示，不与成功或失败结果并列。
 */
export function partitionValidationChecks(
  checks: SemanticValidationCheck[],
): ValidationCheckPartition {
  return checks.reduce<ValidationCheckPartition>(
    (partition, check) => {
      if (check.status === 'SKIPPED') {
        partition.deferredChecks.push(check);
      } else {
        partition.executedChecks.push(check);
      }
      return partition;
    },
    { executedChecks: [], deferredChecks: [] },
  );
}
