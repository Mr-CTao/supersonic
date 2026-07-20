/**
 * Forecast 映射激活状态机测试。
 *
 * 职责：固定排队、运行、终态、已激活和 Worker 离线时的按钮交互语义。
 */
import type { ForecastMapping, ForecastStream } from '@/services/forecast';
import {
  getForecastActivationButtonState,
  getForecastActivationStatusText,
  isForecastActivationPending,
} from './forecastActivation';

const published = { id: 11, status: 'PUBLISHED' } as ForecastMapping;

/** 创建包含指定首次同步状态的数据流。 */
const streamWithStatus = (status: 'QUEUED' | 'RUNNING' | 'FAILED'): ForecastStream => ({
  id: 7,
  profileId: 3,
  name: '入库任务',
  enabled: true,
  lockVersion: 0,
  latestActivation: {
    jobId: 91,
    mappingId: 11,
    mappingVersion: 2,
    status,
    progressPercent: status === 'RUNNING' ? 37 : 0,
    createdAt: '2026-07-20T03:00:00Z',
  },
});

describe('forecastActivation', () => {
  it('排队和运行任务持续占用激活槽', () => {
    expect(isForecastActivationPending(streamWithStatus('QUEUED').latestActivation)).toBe(true);
    expect(isForecastActivationPending(streamWithStatus('RUNNING').latestActivation)).toBe(true);
    expect(isForecastActivationPending(streamWithStatus('FAILED').latestActivation)).toBe(false);
  });

  it('同映射排队时显示可恢复的服务端状态并禁用按钮', () => {
    const state = getForecastActivationButtonState(streamWithStatus('QUEUED'), published, true);
    expect(state).toMatchObject({
      disabled: true,
      pending: true,
      label: 'v2 等待 Worker 接单',
    });
    expect(state.reason).toContain('任务 #91');
  });

  it('运行中显示真实进度且不同映射不能继续提交', () => {
    expect(getForecastActivationStatusText(streamWithStatus('RUNNING').latestActivation)).toBe(
      'v2 同步中 37%',
    );
    const another = { id: 12, status: 'PUBLISHED' } as ForecastMapping;
    expect(
      getForecastActivationButtonState(streamWithStatus('RUNNING'), another, true),
    ).toMatchObject({ disabled: true, pending: true, label: '其他版本正在激活' });
  });

  it('已激活版本和 Worker 离线都会给出明确禁用原因', () => {
    const activeStream = { ...streamWithStatus('FAILED'), activeMappingId: 11 };
    expect(getForecastActivationButtonState(activeStream, published, true).label).toBe(
      '当前版本已激活',
    );
    expect(
      getForecastActivationButtonState(
        { ...activeStream, activeMappingId: undefined },
        published,
        false,
      ),
    ).toMatchObject({ disabled: true, label: 'Worker 离线，无法激活' });
  });
});
