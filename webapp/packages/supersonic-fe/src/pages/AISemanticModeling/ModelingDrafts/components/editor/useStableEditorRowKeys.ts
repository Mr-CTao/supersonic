/**
 * 对象编辑器稳定行 key Hook。
 *
 * 职责：为包含可编辑 key/名称的动态列表提供 UI 专用稳定 key，避免输入过程中 React 因业务 key 改变而重建表单并丢失焦点。
 *
 * 并发说明：key 仅保存在组件实例 ref 中，不写入草稿、不跨页面共享。
 */
import { useRef } from 'react';
import { createDraftObjectKey } from './editorUtils';

/**
 * 根据当前行数维护稳定 UI key。
 *
 * @param length 当前列表长度。
 * @param prefix UI key 前缀。
 * @returns 与当前列表等长的稳定 key 数组。
 * @throws 不抛出异常。
 */
export function useStableEditorRowKeys(length: number, prefix: string): string[] {
  const keysRef = useRef<string[]>([]);
  while (keysRef.current.length < length) {
    keysRef.current.push(createDraftObjectKey(prefix));
  }
  if (keysRef.current.length > length) {
    keysRef.current = keysRef.current.slice(0, length);
  }
  return keysRef.current;
}
