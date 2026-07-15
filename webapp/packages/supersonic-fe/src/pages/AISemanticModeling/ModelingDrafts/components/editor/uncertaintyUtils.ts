/**
 * 路由感知不确定项分组纯函数。
 *
 * 职责：将服务端不确定项按严重级、分类和目标对象稳定分组，供编辑器和测试共用。
 */
import type { DraftUncertainty } from '@/services/semanticModelingDraft';

export const UNCERTAINTY_SEVERITY_ORDER = ['BLOCKING', 'WARNING', 'INFO'] as const;
export type NormalizedUncertaintySeverity = (typeof UNCERTAINTY_SEVERITY_ORDER)[number];

export type UncertaintyEntry = {
  item: DraftUncertainty;
  originalIndex: number;
};

/** 将未知严重级收敛为 BLOCKING，与后端 fail-closed 门禁保持一致。 */
export function normalizeUncertaintySeverity(
  severity?: string,
): NormalizedUncertaintySeverity {
  const normalized = severity?.toUpperCase();
  return UNCERTAINTY_SEVERITY_ORDER.includes(normalized as NormalizedUncertaintySeverity)
    ? (normalized as NormalizedUncertaintySeverity)
    : 'BLOCKING';
}

/**
 * 按严重级与稳定业务身份分组，保留原索引以便受控编辑。
 *
 * @param values 服务端已去重的不确定项。
 * @returns 严重级 -> 分类/对象 -> 项的稳定映射。
 * @throws 不抛出异常，空字段使用安全占位。
 */
export function groupUncertainties(values: DraftUncertainty[]) {
  const grouped = new Map<string, Map<string, UncertaintyEntry[]>>();
  values.forEach((item, originalIndex) => {
    const severity = normalizeUncertaintySeverity(item.severity);
    const target = item.objectKey || item.field || item.modelKey || '未定位对象';
    const groupKey = `${item.category || '未分类'}::${target}`;
    const severityGroups = grouped.get(severity) ?? new Map<string, UncertaintyEntry[]>();
    const entries = severityGroups.get(groupKey) ?? [];
    entries.push({ item, originalIndex });
    severityGroups.set(groupKey, entries);
    grouped.set(severity, severityGroups);
  });
  return grouped;
}
