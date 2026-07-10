/**
 * 建模草稿创建流程纯函数工具。
 *
 * 职责：生成表单会话幂等键、压平可管理主题域和兼容不同表元数据返回格式。
 *
 * 并发说明：方法无共享可变状态；幂等键只由创建容器在一次打开周期内持有。
 */
import type { DomainOption, DomainSelectOption } from './creationTypes';

/**
 * 生成当前创建表单会话的幂等键。
 *
 * @returns 时间与随机 UUID 组合的稳定键。
 * @throws 不抛出异常；旧浏览器不支持 randomUUID 时回退随机字符串。
 */
export function createIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined;
  const randomPart =
    typeof cryptoApi?.randomUUID === 'function'
      ? cryptoApi.randomUUID()
      : Math.random().toString(36).slice(2);
  return `semantic-modeling-${Date.now()}-${randomPart}`;
}

/**
 * 压平主题域树并过滤无编辑权限节点。
 *
 * @param domains 主题域树或扁平列表。
 * @param depth 当前递归深度。
 * @returns 带层级缩进的 Select 选项。
 * @throws 不抛出异常。
 */
export function flattenDomainOptions(
  domains: DomainOption[] = [],
  depth = 0,
): DomainSelectOption[] {
  return domains.flatMap((domain) => {
    const current =
      domain.hasEditPermission === false
        ? []
        : [{ label: `${'　'.repeat(depth)}${domain.name}`, value: domain.id }];
    return current.concat(flattenDomainOptions(domain.children || [], depth + 1));
  });
}

/**
 * 兼容字符串和对象两种表元数据格式。
 *
 * @param table 表名或表元数据对象。
 * @returns 可提交表名，无法识别时返回空字符串。
 * @throws 不抛出异常。
 */
export function normalizeTableName(table: any): string {
  return typeof table === 'string'
    ? table
    : String(table?.tableName || table?.name || table?.table || '');
}
