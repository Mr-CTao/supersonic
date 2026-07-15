/**
 * 语义资产路由前端服务契约测试。
 *
 * 职责：锁定分析、轮询和确认的 URL、请求体及幂等请求头，防止页面绕过独立路由治理 API。
 */
import request from 'umi-request';
import {
  confirmSemanticAssetRoute,
  createSemanticAssetRoute,
  getSemanticAssetRoute,
  type CreateSemanticAssetRouteReq,
} from './semanticAssetRouting';

jest.mock('umi-request', () => jest.fn());

const requestMock = request as unknown as jest.Mock;

const analyzeRequest: CreateSemanticAssetRouteReq = {
  sourceType: 'SEMANTIC_GAP',
  sourceId: 3,
  businessGoal: '统计呆滞时长前十的物料',
  domainId: 5,
  dataSourceId: 7,
  catalogName: '',
  databaseName: 'wms',
  selectedTables: ['stock', 'material'],
  chatModelId: 2,
  includeSampleData: false,
};

describe('semanticAssetRouting service', () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockResolvedValue({ code: 202, data: { id: 11, status: 'ANALYZING' } });
  });

  it('creates an analysis with a required stable idempotency key', async () => {
    await createSemanticAssetRoute(analyzeRequest, 'stable-analysis-key');

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/asset-routes', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-analysis-key' },
      data: analyzeRequest,
    });
  });

  it('polls one route detail by its server id', async () => {
    await getSemanticAssetRoute(11);

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/asset-routes/11', {
      method: 'GET',
    });
  });

  it('confirms only the analysis version, action, candidate handle and business answers', async () => {
    const data = {
      analysisVersion: 4,
      action: 'EXTEND_EXISTING' as const,
      candidateHandle: 'candidate_2',
      businessAnswers: { grain: 'MATERIAL_AND_BATCH' },
      overrideReason: undefined,
    };

    await confirmSemanticAssetRoute(11, data, 'stable-confirmation-key');

    expect(requestMock).toHaveBeenCalledWith('/api/semantic/modeling/asset-routes/11/confirm', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'stable-confirmation-key' },
      data,
    });
  });
});
