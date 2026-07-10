/**
 * 建模草稿对象编辑器纯函数工具模块。
 *
 * 职责：生成草稿本地对象 key，并提供不可变数组替换、删除和追加方法，确保对象表单与 JSON 编辑器共享同一份可追踪状态。
 *
 * 并发说明：所有方法均为无副作用纯函数；key 只用于当前草稿对象关联，不承担后端幂等职责。
 */

/**
 * 生成草稿对象本地 key。
 *
 * @param prefix 对象类型前缀。
 * @returns 包含时间和随机段的可读 key。
 * @throws 不抛出异常。
 */
export function createDraftObjectKey(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * 不可变替换数组指定项。
 *
 * @param values 原数组。
 * @param index 待替换索引。
 * @param next 新值。
 * @returns 替换后的新数组。
 * @throws 不抛出异常；索引越界时返回原数组浅拷贝。
 */
export function replaceArrayItem<T>(values: T[], index: number, next: T): T[] {
  return values.map((item, itemIndex) => (itemIndex === index ? next : item));
}

/**
 * 不可变删除数组指定项。
 *
 * @param values 原数组。
 * @param index 待删除索引。
 * @returns 删除后的新数组。
 * @throws 不抛出异常。
 */
export function removeArrayItem<T>(values: T[], index: number): T[] {
  return values.filter((_, itemIndex) => itemIndex !== index);
}
