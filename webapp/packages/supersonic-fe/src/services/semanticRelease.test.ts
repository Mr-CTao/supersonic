/**
 * AI 语义建模阶段 5 审批发布服务契约测试。
 *
 * 职责：验证发布操作携带稳定幂等键，知识重试和回滚只使用发布记录 ID，且客户端不能
 * 提交任意正式对象 ID，从前端请求层锁定阶段 5 的安全边界。
 */
import request from 'umi-request';
import {
  approveSemanticDraft,
  getSemanticApprovals,
  getSemanticReleaseDetail,
  getSemanticReleases,
  rejectSemanticDraft,
  releaseSemanticDraft,
  retrySemanticKnowledge,
  rollbackSemanticRelease,
} from './semanticRelease';

jest.mock('umi-request', () => jest.fn());

const requestMock = request as unknown as jest.Mock;

describe('semanticRelease service', () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockResolvedValue({ code: 200, data: {} });
  });

  it('queries approval and release audit through bounded PageInfo parameters', async () => {
    await getSemanticApprovals({
      status: 'PENDING_APPROVAL',
      keyword: '库存',
      page: 1,
      pageSize: 20,
    });
    await getSemanticReleases({ status: 'FAILED', page: 2, pageSize: 20 });
    await getSemanticReleaseDetail(31);

    expect(requestMock).toHaveBeenNthCalledWith(1, '/api/semantic/modeling/approvals', {
      method: 'GET',
      params: { status: 'PENDING_APPROVAL', keyword: '库存', page: 1, pageSize: 20 },
    });
    expect(requestMock).toHaveBeenNthCalledWith(2, '/api/semantic/modeling/releases', {
      method: 'GET',
      params: { status: 'FAILED', page: 2, pageSize: 20 },
    });
    expect(requestMock).toHaveBeenNthCalledWith(3, '/api/semantic/modeling/releases/31', {
      method: 'GET',
    });
  });

  it('posts approval decisions and a stable release idempotency key', async () => {
    await approveSemanticDraft(7, '验证通过');
    await rejectSemanticDraft(8, '指标口径不完整');
    await releaseSemanticDraft(7, 'stable-release-key');

    expect(requestMock).toHaveBeenNthCalledWith(1, '/api/semantic/modeling/drafts/7/approve', {
      method: 'POST',
      data: { reason: '验证通过' },
    });
    expect(requestMock).toHaveBeenNthCalledWith(2, '/api/semantic/modeling/drafts/8/reject', {
      method: 'POST',
      data: { reason: '指标口径不完整' },
    });
    expect(requestMock).toHaveBeenNthCalledWith(3, '/api/semantic/modeling/drafts/7/release', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-release-key' },
    });
  });

  it('retries knowledge and rolls back only by server-owned release scope', async () => {
    await retrySemanticKnowledge(31);
    await rollbackSemanticRelease(31, '发布内容需要废弃');

    expect(requestMock).toHaveBeenNthCalledWith(
      1,
      '/api/semantic/modeling/releases/31/knowledge/retry',
      { method: 'POST' },
    );
    expect(requestMock).toHaveBeenNthCalledWith(2, '/api/semantic/modeling/releases/31/rollback', {
      method: 'POST',
      data: { reason: '发布内容需要废弃' },
    });
  });
});
