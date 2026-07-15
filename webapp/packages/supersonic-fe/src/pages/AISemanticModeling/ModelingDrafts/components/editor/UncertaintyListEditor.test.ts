/**
 * 路由感知不确定项分组纯函数测试。
 *
 * 职责：验证严重级顺序、未知级别 fail-closed 以及稳定业务身份分组。
 */
import { groupUncertainties, normalizeUncertaintySeverity } from './uncertaintyUtils';

describe('UncertaintyListEditor grouping', () => {
  it('未知严重级应按阻断项处理', () => {
    expect(normalizeUncertaintySeverity('UNKNOWN')).toBe('BLOCKING');
  });

  it('应按分类和目标对象分组并保留原索引', () => {
    const grouped = groupUncertainties([
      {
        key: 'grain:stock',
        category: 'GRAIN',
        objectKey: 'stock',
        severity: 'BLOCKING',
        reason: '粒度待确认',
      },
      {
        key: 'alias:stock',
        category: 'ALIAS_CONFLICT',
        objectKey: 'stock',
        severity: 'WARNING',
        reason: '别名冲突',
      },
    ]);

    expect(grouped.get('BLOCKING')?.get('GRAIN::stock')?.[0].originalIndex).toBe(0);
    expect(grouped.get('WARNING')?.get('ALIAS_CONFLICT::stock')?.[0].item.key).toBe(
      'alias:stock',
    );
  });
});
