/**
 * 建模草稿对象编辑器不可变更新工具测试。
 *
 * 覆盖本地对象 key、数组替换和删除，保证对象表单不会原地修改 JSON 编辑器正在使用的草稿引用。
 */
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editorUtils';

describe('DraftObjectEditor editorUtils', () => {
  it('creates readable local keys with the requested prefix', () => {
    expect(createDraftObjectKey('metric')).toMatch(/^metric_\d+_[a-z0-9]+$/);
  });

  it('replaces an array item without mutating the source array', () => {
    const source = [{ name: 'old' }, { name: 'keep' }];
    const next = replaceArrayItem(source, 0, { name: 'new' });

    expect(next).toEqual([{ name: 'new' }, { name: 'keep' }]);
    expect(source).toEqual([{ name: 'old' }, { name: 'keep' }]);
    expect(next).not.toBe(source);
  });

  it('removes only the requested array item', () => {
    expect(removeArrayItem(['a', 'b', 'c'], 1)).toEqual(['a', 'c']);
  });
});
