/**
 * AI 语义建模草稿重新生成服务契约测试。
 *
 * 职责：验证手工重试使用原草稿路径、稳定幂等请求头和必填 lockVersion，以及尝试历史只读分页路径。
 */
import request from 'umi-request';
import { getModelingDraftAttempts, regenerateModelingDraft } from './semanticModelingDraft';

jest.mock('umi-request', () => jest.fn());

const requestMock = request as unknown as jest.Mock;

describe('semanticModelingDraft regeneration service', () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockResolvedValue({ code: 202, data: { id: 7, status: 'GENERATING' } });
  });

  it('posts regeneration to the same draft with lock version and idempotency key', async () => {
    await regenerateModelingDraft(
      7,
      { lockVersion: 4, chatModelId: 2, includeSampleData: false },
      'stable-regeneration-key',
    );

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/regenerations', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-regeneration-key' },
      data: { lockVersion: 4, chatModelId: 2, includeSampleData: false },
    });
  });

  it('loads attempt summaries on demand through PageInfo query parameters', async () => {
    await getModelingDraftAttempts(7, { page: 1, pageSize: 20 });

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/attempts', {
      method: 'GET',
      params: { page: 1, pageSize: 20 },
    });
  });
});
