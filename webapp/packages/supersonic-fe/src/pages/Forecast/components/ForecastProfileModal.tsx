/**
 * Forecast Profile 创建/编辑弹窗。
 *
 * 职责：复用现有数据库连接，约束源库类型与 PostgreSQL 决策库，并提交时锁定按钮防止重复点击。
 */
import {
  createForecastIdempotencyKey,
  createForecastProfile,
  getForecastErrorMessage,
  getForecastDatabases,
  unwrapForecastData,
  updateForecastProfile,
} from '@/services/forecast';
import type { ForecastDatabase, ForecastProfile, ForecastProfileInput } from '@/services/forecast';
import {
  ModalForm,
  ProFormDigit,
  ProFormSelect,
  ProFormSwitch,
  ProFormText,
} from '@ant-design/pro-components';
import { message } from 'antd';
import React, { useEffect, useState } from 'react';

type Props = {
  open: boolean;
  profile?: ForecastProfile;
  onOpenChange: (open: boolean) => void;
  onSaved: () => void;
};

/**
 * 渲染 Profile 配置弹窗。
 *
 * @param props open 控制显示，profile 存在时进入编辑模式，onSaved 通知列表刷新。
 * @returns ModalForm。
 * @throws 不主动抛出异常；请求异常转换为 message。
 */
const ForecastProfileModal: React.FC<Props> = ({ open, profile, onOpenChange, onSaved }) => {
  const [databases, setDatabases] = useState<ForecastDatabase[]>([]);

  useEffect(() => {
    let active = true;
    if (open) {
      getForecastDatabases()
        .then((response) => {
          if (active) setDatabases(unwrapForecastData<ForecastDatabase[]>(response) || []);
        })
        .catch((error) => message.error(error?.message || '数据库连接列表加载失败'));
    }
    return () => {
      active = false;
    };
  }, [open]);

  const initialValues = profile || {
    timeZone: 'Asia/Shanghai',
    historyDays: 180,
    syncCron: '0 0 1 * * *',
    forecastCron: '0 30 2 * * *',
    reconcileCron: '0 30 3 * * SUN',
    enabled: true,
  };
  const sourceOptions = databases
    .filter((item) => ['mysql', 'postgresql', 'sqlserver'].includes(item.type?.toLowerCase()))
    .map((item) => ({ label: `${item.name} (${item.type})`, value: item.id }));
  const decisionOptions = databases
    .filter((item) => item.type?.toLowerCase() === 'postgresql')
    .map((item) => ({ label: `${item.name} (PostgreSQL)`, value: item.id }));

  return (
    <ModalForm<ForecastProfileInput>
      key={profile?.id || 'create'}
      title={profile ? '编辑预测 Profile' : '新建预测 Profile'}
      open={open}
      onOpenChange={onOpenChange}
      initialValues={initialValues}
      modalProps={{ destroyOnHidden: true, mask: { closable: false } }}
      submitter={{ searchConfig: { submitText: profile ? '保存' : '创建' } }}
      onFinish={async (values) => {
        try {
          const data = { ...values, lockVersion: profile?.lockVersion } as ForecastProfileInput;
          const key = createForecastIdempotencyKey(
            profile ? `profile-update-${profile.id}` : 'profile-create',
          );
          if (profile) await updateForecastProfile(profile.id, data, key);
          else await createForecastProfile(data, key);
          message.success(profile ? 'Profile 已更新' : 'Profile 已创建');
          onSaved();
          return true;
        } catch (error: unknown) {
          message.error(getForecastErrorMessage(error, 'Profile 保存失败'));
          return false;
        }
      }}
    >
      <ProFormText name="name" label="名称" rules={[{ required: true }]} />
      <ProFormSelect
        name="sourceDatabaseId"
        label="客户源数据库"
        options={sourceOptions}
        rules={[{ required: true }]}
      />
      <ProFormSelect
        name="decisionDatabaseId"
        label="PostgreSQL 决策库"
        options={decisionOptions}
        rules={[{ required: true }]}
      />
      <ProFormText name="timeZone" label="Profile 时区" rules={[{ required: true }]} />
      <ProFormDigit
        name="historyDays"
        label="首次历史天数"
        min={90}
        max={730}
        rules={[{ required: true }]}
      />
      <ProFormText name="syncCron" label="增量同步 Cron" rules={[{ required: true }]} />
      <ProFormText name="forecastCron" label="预测 Cron" rules={[{ required: true }]} />
      <ProFormText name="reconcileCron" label="对账 Cron" rules={[{ required: true }]} />
      <ProFormSwitch name="enabled" label="启用" />
    </ModalForm>
  );
};

export default ForecastProfileModal;
