/**
 * Forecast 映射激活状态机。
 *
 * 职责：将服务端活动映射、最近首次同步任务和 Worker 健康状态转换为统一的按钮与状态文案，
 * 避免数据接入页和映射抽屉各自实现不一致的判断。
 */
import type {
  ForecastActivationSummary,
  ForecastMapping,
  ForecastStream,
} from '@/services/forecast';

const ACTIVE_ACTIVATION_STATUSES = new Set(['QUEUED', 'RUNNING', 'CANCELLING']);

export type ForecastActivationButtonState = {
  disabled: boolean;
  pending: boolean;
  label: string;
  reason?: string;
};

/**
 * 判断最近首次同步任务是否仍占用数据流激活槽。
 *
 * @param activation 服务端返回的最近首次同步摘要。
 * @returns 排队、运行或取消中返回 true；终态或空值返回 false。
 */
export function isForecastActivationPending(activation?: ForecastActivationSummary): boolean {
  return Boolean(activation && ACTIVE_ACTIVATION_STATUSES.has(activation.status));
}

/**
 * 获取首次同步任务的中文状态文案。
 *
 * @param activation 首次同步任务摘要。
 * @returns 可直接展示给管理员的状态文案。
 */
export function getForecastActivationStatusText(activation?: ForecastActivationSummary): string {
  if (!activation) return '尚未提交';
  const version = activation.mappingVersion ? `v${activation.mappingVersion}` : '候选版本';
  switch (activation.status) {
    case 'QUEUED':
      return `${version} 等待 Worker 接单`;
    case 'RUNNING':
      return `${version} 同步中 ${activation.progressPercent ?? 0}%`;
    case 'CANCELLING':
      return `${version} 正在取消`;
    case 'SUCCEEDED':
      return `${version} 已激活`;
    case 'FAILED':
      return `${version} 激活失败`;
    case 'CANCELLED':
      return `${version} 已取消`;
    default:
      return activation.status;
  }
}

/**
 * 计算“首次同步并激活”按钮的锁定状态和文案。
 *
 * @param stream 当前数据流服务端快照。
 * @param mapping 当前查看的不可变映射版本。
 * @param workerHealthy Worker 健康状态；尚未加载时传 undefined，不提前误锁按钮。
 * @returns 按钮禁用、占用和提示信息。
 */
export function getForecastActivationButtonState(
  stream?: Pick<ForecastStream, 'activeMappingId' | 'latestActivation'>,
  mapping?: Pick<ForecastMapping, 'id' | 'status'>,
  workerHealthy?: boolean,
): ForecastActivationButtonState {
  if (!stream || !mapping) {
    return { disabled: true, pending: false, label: '首次同步并激活' };
  }
  if (stream.activeMappingId === mapping.id) {
    return {
      disabled: true,
      pending: false,
      label: '当前版本已激活',
      reason: '该映射已经对外服务，如需重扫请在运行中心创建对账任务。',
    };
  }
  const activation = stream.latestActivation;
  if (isForecastActivationPending(activation)) {
    const sameMapping = activation?.mappingId === mapping.id;
    return {
      disabled: true,
      pending: true,
      label: sameMapping ? getForecastActivationStatusText(activation) : '其他版本正在激活',
      reason: `任务 #${activation?.jobId} 正在占用该数据流，请等待完成或先到运行中心取消。`,
    };
  }
  if (mapping.status !== 'PUBLISHED') {
    return {
      disabled: true,
      pending: false,
      label: '首次同步并激活',
      reason: '只有已发布且尚未激活的映射可以提交首次同步。',
    };
  }
  if (workerHealthy === false) {
    return {
      disabled: true,
      pending: false,
      label: 'Worker 离线，无法激活',
      reason: '请先启动 Forecast Worker；否则任务只会停留在 QUEUED。',
    };
  }
  return { disabled: false, pending: false, label: '首次同步并激活' };
}
