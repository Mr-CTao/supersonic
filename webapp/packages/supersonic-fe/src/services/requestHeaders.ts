/**
 * 全局请求头合并纯函数模块。
 *
 * 职责：保留业务调用方请求头并追加认证信息，避免认证拦截器覆盖幂等键等协议字段。
 * 并发说明：函数只操作请求级局部对象，没有共享可变状态，不需要并发保护。
 */

/**
 * 合并调用方请求头与当前认证头。
 *
 * @param originalHeaders 调用方显式传入的请求头，例如 Idempotency-Key。
 * @param token 当前登录令牌；为空时只保留调用方请求头。
 * @returns 可交给 umi-request 的普通请求头对象。
 * @throws Headers 构造器仅在传入非法请求头名称或值时抛出 TypeError。
 */
export const mergeRequestHeaders = (
  originalHeaders: HeadersInit | undefined,
  token?: string | null,
): Record<string, string> => {
  const headers: Record<string, string> = {};
  new Headers(originalHeaders).forEach((value, key) => {
    headers[key] = value;
  });
  if (token) {
    headers.Authorization = `Bearer ${token}`;
    headers.auth = `Bearer ${token}`;
  }
  return headers;
};
