/**
 * AI 语义建模草稿阶段 3/4 服务契约测试。
 *
 * 职责：验证手工重试，以及 AI 修订、版本差异、验证报告和提交审批严格使用约定路径、
 * 请求体与幂等请求头，避免前端绕过治理 API。
 */
import request from 'umi-request';
import {
  aiReviseModelingDraft,
  getModelingDraftAttempts,
  getModelingDraftVersionDiff,
  getModelingDraftVersions,
  getModelingValidationReport,
  getModelingValidationReports,
  regenerateModelingDraft,
  restoreModelingDraftVersion,
  submitModelingDraft,
  validateModelingDraft,
} from './semanticModelingDraft';

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

  it('posts AI revision with a stable idempotency key and explicit base version', async () => {
    await aiReviseModelingDraft(
      7,
      { instruction: '手机号标记为敏感字段', baseVersionNo: 4 },
      'stable-revision-key',
    );

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/ai-revise', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-revision-key' },
      data: { instruction: '手机号标记为敏感字段', baseVersionNo: 4 },
    });
  });

  it('uses the read-only version diff endpoint with both version numbers', async () => {
    await getModelingDraftVersionDiff(7, 3, 4);

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/versions/diff', {
      method: 'GET',
      params: { fromVersionNo: 3, toVersionNo: 4 },
    });
  });

  it('queries version history with explicit bounded pagination', async () => {
    await getModelingDraftVersions(7, { page: 2, pageSize: 50 });

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/versions', {
      method: 'GET',
      params: { page: 2, pageSize: 50 },
    });
  });

  it('restores a historical snapshot through the append-only idempotent endpoint', async () => {
    await restoreModelingDraftVersion(
      7,
      2,
      { currentVersionNo: 4, lockVersion: 9 },
      'stable-restore-key',
    );

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/versions/2/restore', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-restore-key' },
      data: { currentVersionNo: 4, lockVersion: 9 },
    });
  });

  it('validates a concrete version and queries report list/detail', async () => {
    await validateModelingDraft(7, {
      versionNo: 4,
      validationOptions: { sqlPreviewLimit: 100 },
    });
    await getModelingValidationReports(7, { page: 1, pageSize: 20 });
    await getModelingValidationReport(11);

    expect(requestMock).toHaveBeenNthCalledWith(1, '/api/semantic/modeling/drafts/7/validate', {
      method: 'POST',
      data: { versionNo: 4, validationOptions: { sqlPreviewLimit: 100 } },
    });
    expect(requestMock).toHaveBeenNthCalledWith(
      2,
      '/api/semantic/modeling/drafts/7/validation-reports',
      { method: 'GET', params: { page: 1, pageSize: 20 } },
    );
    expect(requestMock).toHaveBeenNthCalledWith(3, '/api/semantic/modeling/validation-reports/11', {
      method: 'GET',
    });
  });

  it('submits only the selected version/report pair with an idempotency key', async () => {
    await submitModelingDraft(7, { versionNo: 4, validationReportId: 11 }, 'stable-submit-key');

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/drafts/7/submit', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-submit-key' },
      data: { versionNo: 4, validationReportId: 11 },
    });
  });
});
