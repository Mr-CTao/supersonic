/**
 * 出入库预测 MVP 前端服务模块。
 *
 * 职责：声明 Forecast 公共类型并封装 Profile、映射、任务、看板和健康接口。
 * 写操作的幂等键由页面在一次操作周期内生成并复用，本模块不保存跨页面共享状态。
 */
import request from 'umi-request';

const FORECAST_BASE_URL = '/api/forecast';

export type ForecastDirection = 'INBOUND' | 'OUTBOUND';
export type ForecastMetric = 'QUANTITY' | 'TASK_COUNT';
export type ForecastDataStatus = 'INSUFFICIENT_DATA' | 'LOW_CONFIDENCE' | 'READY';
export type ForecastJobType =
  | 'INITIAL_SYNC'
  | 'INCREMENTAL_SYNC'
  | 'RECONCILE'
  | 'AGGREGATE'
  | 'FORECAST';
export type ForecastJobStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export type ForecastActivationSummary = {
  jobId: number;
  mappingId: number;
  mappingVersion?: number;
  status: ForecastJobStatus;
  progressPercent: number;
  errorCode?: string;
  errorMessage?: string;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
};

export type ForecastStream = {
  id: number;
  profileId: number;
  name: string;
  enabled: boolean;
  activeMappingId?: number;
  activeMappingVersion?: number;
  latestActivation?: ForecastActivationSummary;
  lockVersion: number;
  lastSyncAt?: string;
};

export type ForecastProfile = {
  id: number;
  name: string;
  sourceDatabaseId: number;
  sourceDatabaseName?: string;
  decisionDatabaseId: number;
  decisionDatabaseName?: string;
  timeZone: string;
  syncCron: string;
  forecastCron: string;
  reconcileCron: string;
  historyDays: number;
  enabled: boolean;
  lockVersion: number;
  lastSyncAt?: string;
  lastForecastAt?: string;
  freshnessStatus?: string;
  streams: ForecastStream[];
};

export type ForecastProfileInput = Omit<
  ForecastProfile,
  | 'id'
  | 'sourceDatabaseName'
  | 'decisionDatabaseName'
  | 'lastSyncAt'
  | 'lastForecastAt'
  | 'freshnessStatus'
  | 'lockVersion'
  | 'streams'
> & { lockVersion?: number };

export type ForecastColumnRef = { tableAlias: string; column: string };
export type ForecastRelationTable = {
  catalog?: string;
  schema?: string;
  table: string;
  alias: string;
};
export type ForecastRelationSource = {
  single?: ForecastRelationTable;
  header?: ForecastRelationTable;
  detail?: ForecastRelationTable;
  join?: {
    type: 'INNER' | 'LEFT';
    left: ForecastColumnRef;
    right: ForecastColumnRef;
  };
};
export type ForecastValueMapping = {
  sourceType: 'COLUMN' | 'CONSTANT';
  column?: ForecastColumnRef;
  secondaryColumn?: ForecastColumnRef;
  constant?: string;
  valueMap?: Record<string, string>;
  transform?: 'NONE' | 'ABS' | 'NEGATE';
};
export type ForecastMappingConfig = {
  relationMode: 'SINGLE' | 'HEADER_DETAIL';
  source: ForecastRelationSource;
  fields: Record<string, ForecastValueMapping | undefined>;
  sourceTimeZone: string;
  syncMode: 'INCREMENTAL' | 'SNAPSHOT_LOOKBACK';
  lookbackDays: number;
};
export type ForecastMapping = {
  id: number;
  streamId: number;
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  config: ForecastMappingConfig;
  configChecksum: string;
  valid: boolean;
  validationSummary?: string;
  createdBy: string;
  createdAt: string;
  publishedAt?: string;
};
export type ForecastMappingValidation = {
  valid: boolean;
  errors: string[];
  warnings: string[];
  samples: Record<string, unknown>[];
};
export type ForecastMetadata = {
  catalogs: string[];
  schemas: string[];
  tables: Array<{
    catalog?: string;
    schema?: string;
    name: string;
    type: string;
    columns: Array<{ name: string; jdbcType: number; typeName: string; nullable: boolean }>;
  }>;
};
export type ForecastJob = {
  id: number;
  parentJobId?: number;
  profileId: number;
  streamId?: number;
  mappingId?: number;
  type: ForecastJobType;
  status: ForecastJobStatus;
  progressPercent: number;
  rowsRead: number;
  rowsWritten: number;
  rowsAggregated: number;
  checkpoint?: { updatedAt?: string; recordId?: string };
  retryCount: number;
  workerId?: string;
  errorCode?: string;
  errorMessage?: string;
  createdBy: string;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  heartbeatAt?: string;
};
export type ForecastSummary = {
  profileId: number;
  metric: ForecastMetric;
  horizon: number;
  predictedTotal: number;
  previousActualTotal: number;
  dataStatus: ForecastDataStatus;
  algorithm?: string;
  wape?: number;
  mae?: number;
  bias?: number;
  lastSyncAt?: string;
  lastForecastAt?: string;
};
export type ForecastSeriesPoint = {
  date: string;
  actual?: number;
  forecast?: number;
  lower?: number;
  upper?: number;
};
export type ForecastBreakdown = {
  warehouseCode: string;
  direction: ForecastDirection;
  predictedTotal: number;
  dataStatus: ForecastDataStatus;
  algorithm?: string;
  wape?: number;
  mae?: number;
  bias?: number;
};
export type ForecastHealth = {
  workerHealthy: boolean;
  activeWorkers: number;
  latestHeartbeatAt?: string;
  latestSyncAt?: string;
  latestForecastAt?: string;
  freshnessStatus: 'FRESH' | 'STALE' | 'NEVER_SYNCED';
};
export type ForecastDatabase = {
  id: number;
  name: string;
  type: string;
  hasEditPermission?: boolean;
};
export type ForecastStreamInput = {
  name: string;
  enabled: boolean;
  lockVersion?: number;
};
export type ForecastJobInput = {
  profileId: number;
  streamId?: number;
  mappingId?: number;
  type: ForecastJobType;
  historyDays?: number;
};

/**
 * 从 SuperSonic 统一响应提取业务 data。
 *
 * @param response 原始响应。
 * @returns 业务数据。
 * @throws code 非 200 时抛出带服务端消息的 Error。
 */
export function unwrapForecastData<T>(response: unknown): T {
  if (
    response &&
    typeof response === 'object' &&
    Object.prototype.hasOwnProperty.call(response, 'code')
  ) {
    const envelope = response as { code?: number; msg?: string; data?: unknown };
    if (envelope.code !== 200) throw new Error(envelope.msg || '预测服务请求失败');
    return envelope.data as T;
  }
  return response as T;
}

/**
 * 从未知请求异常中提取可展示消息。
 *
 * @param error catch 捕获的未知值。
 * @param fallback 无安全消息时的业务兜底文案。
 * @returns 不包含对象序列化或堆栈的页面错误文案。
 */
export function getForecastErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

/**
 * 生成一次写操作使用的稳定幂等键。
 *
 * @param scope 便于排查的业务范围，不应包含敏感值。
 * @returns 当前操作周期内唯一的幂等键。
 */
export function createForecastIdempotencyKey(scope: string): string {
  const random = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`;
  return `forecast:${scope}:${random}`;
}

/**
 * 查询 Profile 分页。
 *
 * @param params 页码和页大小。
 * @returns SuperSonic 统一响应 Promise。
 * @throws 网络错误或服务端拒绝时 Promise rejected。
 */
export const getForecastProfiles = (params: { pageNum?: number; pageSize?: number }) =>
  request(`${FORECAST_BASE_URL}/profiles`, { method: 'GET', params });

/**
 * 查询 Profile 详情。
 *
 * @param id Profile ID。
 * @returns Profile 统一响应 Promise。
 * @throws 网络错误或无查看权限时 Promise rejected。
 */
export const getForecastProfile = (id: number) =>
  request(`${FORECAST_BASE_URL}/profiles/${id}`, { method: 'GET' });

/**
 * 创建 Profile。
 *
 * @param data 已校验的 Profile 表单。
 * @param key 本次提交复用的幂等键。
 * @returns 新 Profile 统一响应 Promise。
 * @throws 参数、权限或网络错误时 Promise rejected。
 */
export const createForecastProfile = (data: ForecastProfileInput, key: string) =>
  request(`${FORECAST_BASE_URL}/profiles`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    data,
  });

/**
 * 使用乐观锁更新 Profile。
 *
 * @param id Profile ID。
 * @param data 包含 lockVersion 的表单数据。
 * @param key 本次提交复用的幂等键。
 * @returns 更新后 Profile 统一响应 Promise。
 * @throws 版本冲突、权限或网络错误时 Promise rejected。
 */
export const updateForecastProfile = (id: number, data: ForecastProfileInput, key: string) =>
  request(`${FORECAST_BASE_URL}/profiles/${id}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': key },
    data,
  });

/**
 * 停用 Profile 并保留历史数据。
 *
 * @param id Profile ID。
 * @param lockVersion 当前乐观锁版本。
 * @param key 本次提交复用的幂等键。
 * @returns 停用后 Profile 统一响应 Promise。
 * @throws 版本冲突、权限或网络错误时 Promise rejected。
 */
export const disableForecastProfile = (id: number, lockVersion: number, key: string) =>
  request(`${FORECAST_BASE_URL}/profiles/${id}`, {
    method: 'DELETE',
    params: { lockVersion },
    headers: { 'Idempotency-Key': key },
  });

/**
 * 查询当前用户可见的现有数据库连接。
 *
 * @returns 数据库列表统一响应 Promise。
 * @throws 登录失效或网络错误时 Promise rejected。
 */
export const getForecastDatabases = () =>
  request('/api/semantic/database/getDatabaseList', { method: 'GET' });

/**
 * 创建 Profile 下的数据流。
 *
 * @param profileId Profile ID。
 * @param data 数据流名称和启用状态。
 * @param key 本次提交复用的幂等键。
 * @returns 新数据流统一响应 Promise。
 * @throws 参数、权限或网络错误时 Promise rejected。
 */
export const createForecastStream = (profileId: number, data: ForecastStreamInput, key: string) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/streams`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    data,
  });

/**
 * 发现源库元数据。
 *
 * @param profileId Profile ID。
 * @param params catalog、schema 和表名过滤条件。
 * @returns 表、视图和列元数据统一响应 Promise。
 * @throws 数据源不可用、无权限或网络错误时 Promise rejected。
 */
export const getForecastMetadata = (profileId: number, params: Record<string, unknown>) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/metadata`, { method: 'GET', params });

/**
 * 创建不可变映射草稿。
 *
 * @param profileId Profile ID。
 * @param streamId 数据流 ID。
 * @param config 白名单映射配置。
 * @param key 本次提交复用的幂等键。
 * @returns 新映射版本统一响应 Promise。
 * @throws 映射非法、版本冲突或网络错误时 Promise rejected。
 */
export const createForecastMapping = (
  profileId: number,
  streamId: number,
  config: ForecastMappingConfig,
  key: string,
) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/mappings`, {
    method: 'POST',
    params: { streamId },
    headers: { 'Idempotency-Key': key },
    data: { config },
  });

/**
 * 查询数据流映射版本。
 *
 * @param profileId Profile ID。
 * @param streamId 数据流 ID。
 * @returns 版本降序列表统一响应 Promise。
 * @throws 无权限或网络错误时 Promise rejected。
 */
export const getForecastMappings = (profileId: number, streamId: number) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/mappings`, {
    method: 'GET',
    params: { streamId },
  });

/**
 * 校验映射并读取最多 100 行标准化预览。
 *
 * @param profileId Profile ID。
 * @param streamId 数据流 ID。
 * @param mappingId 映射版本 ID。
 * @param key 本次校验复用的幂等键。
 * @returns 校验错误、警告和预览统一响应 Promise。
 * @throws 数据源读取、权限或网络错误时 Promise rejected。
 */
export const validateForecastMapping = (
  profileId: number,
  streamId: number,
  mappingId: number,
  key: string,
) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/mappings/${mappingId}/validate`, {
    method: 'POST',
    params: { streamId, sampleLimit: 100 },
    headers: { 'Idempotency-Key': key },
  });

/**
 * 发布已验证的映射草稿。
 *
 * @param profileId Profile ID。
 * @param streamId 数据流 ID。
 * @param mappingId 映射版本 ID。
 * @param key 本次发布复用的幂等键。
 * @returns 已发布映射统一响应 Promise。
 * @throws 校验未通过、权限或网络错误时 Promise rejected。
 */
export const publishForecastMapping = (
  profileId: number,
  streamId: number,
  mappingId: number,
  key: string,
) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/mappings/${mappingId}/publish`, {
    method: 'POST',
    params: { streamId },
    headers: { 'Idempotency-Key': key },
  });

/**
 * 提交首次回填，并在回填和预测成功后激活映射。
 *
 * @param profileId Profile ID。
 * @param streamId 数据流 ID。
 * @param mappingId 已发布映射 ID。
 * @param key 本次激活复用的幂等键。
 * @returns INITIAL_SYNC 任务统一响应 Promise。
 * @throws 状态、权限或网络错误时 Promise rejected。
 */
export const activateForecastMapping = (
  profileId: number,
  streamId: number,
  mappingId: number,
  key: string,
) =>
  request(`${FORECAST_BASE_URL}/profiles/${profileId}/mappings/${mappingId}/activate`, {
    method: 'POST',
    params: { streamId },
    headers: { 'Idempotency-Key': key },
  });

/**
 * 分页查询预测任务。
 *
 * @param params Profile、页码和页大小过滤条件。
 * @returns 任务分页统一响应 Promise。
 * @throws 无权限或网络错误时 Promise rejected。
 */
export const getForecastJobs = (params: Record<string, unknown>) =>
  request(`${FORECAST_BASE_URL}/jobs`, { method: 'GET', params });

/**
 * 提交手动同步、对账或预测任务。
 *
 * @param data 任务类型和可选目标。
 * @param key 本次提交复用的幂等键。
 * @returns 新建或幂等复用的任务统一响应 Promise。
 * @throws 幂等键冲突、参数、权限或网络错误时 Promise rejected。
 */
export const createForecastJob = (data: ForecastJobInput, key: string) =>
  request(`${FORECAST_BASE_URL}/jobs`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    data,
  });

/**
 * 请求任务在安全页边界取消。
 *
 * @param id 任务 ID。
 * @param key 本次取消复用的幂等键。
 * @returns 最新任务统一响应 Promise。
 * @throws 状态、权限或网络错误时 Promise rejected。
 */
export const cancelForecastJob = (id: number, key: string) =>
  request(`${FORECAST_BASE_URL}/jobs/${id}/cancel`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
  });

/**
 * 以新任务重试失败或已取消任务。
 *
 * @param id 原任务 ID。
 * @param key 新任务使用的幂等键。
 * @returns 重试任务统一响应 Promise。
 * @throws 状态、权限或网络错误时 Promise rejected。
 */
export const retryForecastJob = (id: number, key: string) =>
  request(`${FORECAST_BASE_URL}/jobs/${id}/retry`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
  });

/**
 * 查询看板汇总。
 *
 * @param params Profile、指标和 7/14/30 天窗口。
 * @returns KPI 与模型质量统一响应 Promise。
 * @throws 参数、权限或网络错误时 Promise rejected。
 */
export const getForecastSummary = (params: Record<string, unknown>) =>
  request(`${FORECAST_BASE_URL}/overview/summary`, { method: 'GET', params });

/**
 * 查询实际与预测曲线。
 *
 * @param params Profile、指标、方向和窗口过滤条件。
 * @returns 日粒度曲线统一响应 Promise。
 * @throws 参数、权限或网络错误时 Promise rejected。
 */
export const getForecastSeries = (params: Record<string, unknown>) =>
  request(`${FORECAST_BASE_URL}/overview/series`, { method: 'GET', params });

/**
 * 查询仓库和方向拆分。
 *
 * @param params Profile、指标和窗口过滤条件。
 * @returns 仓库拆分统一响应 Promise。
 * @throws 参数、权限或网络错误时 Promise rejected。
 */
export const getForecastBreakdown = (params: Record<string, unknown>) =>
  request(`${FORECAST_BASE_URL}/overview/breakdown`, { method: 'GET', params });

/**
 * 查询 Worker 心跳与 Profile 数据新鲜度。
 *
 * @param profileId Profile ID。
 * @returns 健康状态统一响应 Promise。
 * @throws 无权限或网络错误时 Promise rejected。
 */
export const getForecastHealth = (profileId: number) =>
  request(`${FORECAST_BASE_URL}/health`, { method: 'GET', params: { profileId } });
