/**
 * Chat 结构化语义诊断的纯函数工具。
 *
 * 职责：集中管理阶段标题和脱敏复制文本，避免多个组件按错误字符串猜测根因。
 */
import { SemanticDiagnosticType } from '../../common/type';

const TITLE_BY_STAGE: Record<string, string> = {
  MODEL_SQL_COMPILE: '模型校验失败',
  METRIC_EXPRESSION_COMPILE: '指标表达式校验失败',
  SEMANTIC_TRANSLATION: '语义翻译失败',
  PHYSICAL_SQL_EXECUTION: '查询执行失败',
  SCHEMA_MAPPING: '语义资产匹配失败',
};

/**
 * 根据稳定阶段码返回用户可理解的标题。
 *
 * @param stage 结构化诊断阶段。
 * @returns 阶段对应标题；未知阶段返回通用标题。
 */
export function diagnosticTitle(stage?: string): string {
  return TITLE_BY_STAGE[stage || ''] || '语义查询失败';
}

/**
 * 生成长度受限的开发者复制文本，不包含 SQL、用户问题和异常堆栈。
 *
 * @param diagnostic 后端返回的脱敏结构化诊断。
 * @returns 最多 2000 字符的键值文本。
 */
export function diagnosticCopyText(diagnostic: SemanticDiagnosticType): string {
  return [
    `errorCode=${diagnostic.code || '-'}`,
    `stage=${diagnostic.stage || '-'}`,
    `model=${diagnostic.modelName || '-'}(${diagnostic.modelId || '-'})`,
    `dataSetId=${diagnostic.dataSetId || '-'}`,
    `position=${diagnostic.line || '-'}:${diagnostic.column || '-'}`,
    `token=${diagnostic.token || '-'}`,
    `suggestion=${diagnostic.suggestion || '-'}`,
    `traceId=${diagnostic.traceId || '-'}`,
  ]
    .join('\n')
    .slice(0, 2000);
}
