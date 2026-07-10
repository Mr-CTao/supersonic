/**
 * 全局请求头合并逻辑单元测试。
 *
 * 职责：验证认证拦截器在追加令牌时不会丢失业务请求头，尤其是草稿创建使用的幂等键。
 */
import { mergeRequestHeaders } from './requestHeaders';

describe('request header interceptor', () => {
  it('preserves idempotency headers while appending authentication headers', () => {
    const headers = mergeRequestHeaders(
      {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'semantic-modeling-test-key',
      },
      'test-token',
    );

    expect(headers['content-type']).toBe('application/json');
    expect(headers['idempotency-key']).toBe('semantic-modeling-test-key');
    expect(headers.Authorization).toBe('Bearer test-token');
    expect(headers.auth).toBe('Bearer test-token');
  });

  it('preserves custom headers when no authentication token exists', () => {
    const headers = mergeRequestHeaders({ 'Idempotency-Key': 'anonymous-key' });

    expect(headers['idempotency-key']).toBe('anonymous-key');
    expect(headers.Authorization).toBeUndefined();
  });
});
