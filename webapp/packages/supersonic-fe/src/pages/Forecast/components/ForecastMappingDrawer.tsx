/**
 * 单表/视图或一次主从关联映射、预览、发布与首次同步工作区。
 *
 * 职责：用白名单表单构造 SINGLE/HEADER_DETAIL 映射，不接受任意 SQL；预览最多一百行。
 * 页面在 Profile 主从布局内原位呈现；所有提交按钮分别使用 loading 锁，防止重复创建版本或任务。
 */
import {
  activateForecastMapping,
  createForecastIdempotencyKey,
  createForecastMapping,
  getForecastErrorMessage,
  getForecastMappings,
  getForecastMetadata,
  publishForecastMapping,
  unwrapForecastData,
  validateForecastMapping,
} from '@/services/forecast';
import type {
  ForecastMapping,
  ForecastMappingConfig,
  ForecastMappingValidation,
  ForecastMetadata,
  ForecastJob,
  ForecastProfile,
  ForecastRelationSource,
  ForecastRelationTable,
  ForecastStream,
  ForecastValueMapping,
} from '@/services/forecast';
import {
  buildForecastMappingFormValues,
  serializeForecastRelationTable,
} from './forecastMappingForm';
import type { ForecastMappingFormValues } from './forecastMappingForm';
import { getForecastActivationButtonState } from './forecastActivation';
import { FORECAST_MAPPING_HELP } from './forecastMappingHelp';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  QuestionCircleOutlined,
  SwapRightOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Alert,
  Button,
  Collapse,
  Form,
  Input,
  InputNumber,
  message,
  Progress,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import styles from '../Sources/style.less';

type Props = {
  open: boolean;
  profile?: ForecastProfile;
  stream?: ForecastStream;
  workerHealthy?: boolean;
  onClose: () => void;
  onChanged: () => void | Promise<void>;
};
type SelectedRelationTable = Omit<ForecastRelationTable, 'alias'>;
const FIELD_OPTIONS = [
  ['sourceRecordId', '源记录唯一 ID', true],
  ['taskId', '任务单 ID', false],
  ['quantity', '数量', true],
  ['occurredAt', '业务发生时间', true],
  ['sourceUpdatedAt', '源更新时间', false],
  ['warehouseCode', '仓库编码列', false],
  ['direction', '出入库方向列', false],
  ['status', '任务状态', false],
  ['deleted', '删除标记', false],
] as const;
const PRIMARY_FIELD_OPTIONS = FIELD_OPTIONS.slice(0, 6);
const ADVANCED_FIELD_OPTIONS = FIELD_OPTIONS.slice(6);
const REQUIRED_CORE_FIELD_COUNT = 5;

type PersistedValidationSummary = {
  errors: string[];
  warnings: string[];
};

/** 返回发布按钮的禁用原因；无返回值表示当前草稿已经满足发布条件。 */
const getPublishDisabledReason = (
  mapping?: Pick<ForecastMapping, 'status' | 'valid'>,
): string | undefined => {
  if (!mapping) return '请先保存映射草稿并完成校验。';
  if (mapping.status !== 'DRAFT') return '只有草稿版本可以发布。';
  if (!mapping.valid) return '请先完成校验并确保映射有效。';
  return undefined;
};

/** 将后端持久化的校验摘要拆成可逐条阅读的错误和警告。 */
const parsePersistedValidationSummary = (summary?: string): PersistedValidationSummary => {
  if (!summary?.trim()) return { errors: [], warnings: [] };

  /** 兼容服务端的竖线分隔格式和前端历史版本使用的中文分号格式。 */
  const splitItems = (value?: string) =>
    value
      ?.split(/\s*(?:\||；)\s*/)
      .map((item) => item.trim())
      .filter(Boolean) || [];
  const normalized = summary.trim();
  const errorsMatch = normalized.match(/errors\s*=\s*(.*?)(?=;\s*warnings\s*=|$)/i);
  const warningsMatch = normalized.match(/warnings\s*=\s*(.*)$/i);

  if (errorsMatch || warningsMatch) {
    return {
      errors: splitItems(errorsMatch?.[1]),
      warnings: splitItems(warningsMatch?.[1]),
    };
  }
  return { errors: [], warnings: splitItems(normalized) };
};

/** 将 Select 中的受控 JSON 值还原为列引用。 */
const parseColumn = (value?: string): { tableAlias: string; column: string } | undefined =>
  value ? JSON.parse(value) : undefined;

/** 将选中列构造为受控列映射。 */
const columnMapping = (
  value?: string,
  valueMap?: Record<string, string>,
): ForecastValueMapping | undefined =>
  value
    ? { sourceType: 'COLUMN', column: parseColumn(value), valueMap, transform: 'NONE' }
    : undefined;

/** 将固定业务值构造为不参与 SQL 拼接的常量映射。 */
const constantMapping = (value?: string): ForecastValueMapping | undefined =>
  value?.trim() ? { sourceType: 'CONSTANT', constant: value.trim(), transform: 'NONE' } : undefined;

/** 安全解析管理员输入的字典 JSON。 */
const parseMap = (value?: string): Record<string, string> => {
  if (!value?.trim()) return {};
  const parsed = JSON.parse(value);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object')
    throw new Error('字典必须是 JSON 对象');
  return parsed;
};

/**
 * 渲染原位字段映射工作区。
 *
 * @param props 当前 Profile/Stream、显示状态与刷新回调。
 * @returns 与 Profile 主从页面共享高度的映射工作区。
 * @throws 不主动抛出异常；配置和请求异常展示 message。
 */
const ForecastMappingWorkspace: React.FC<Props> = ({
  open,
  profile,
  stream,
  workerHealthy,
  onClose,
  onChanged,
}) => {
  const [form] = Form.useForm<ForecastMappingFormValues>();
  const watchedValues = Form.useWatch([], form) as ForecastMappingFormValues | undefined;
  const [metadata, setMetadata] = useState<ForecastMetadata>();
  const relationMode = Form.useWatch('relationMode', form) || 'SINGLE';
  const [headerColumns, setHeaderColumns] = useState<string[]>([]);
  const [detailColumns, setDetailColumns] = useState<string[]>([]);
  const [selectedHeader, setSelectedHeader] = useState<SelectedRelationTable>();
  const [selectedDetail, setSelectedDetail] = useState<SelectedRelationTable>();
  const [mappings, setMappings] = useState<ForecastMapping[]>([]);
  const [current, setCurrent] = useState<ForecastMapping>();
  const [validation, setValidation] = useState<ForecastMappingValidation>();
  const [loading, setLoading] = useState<string>();
  const [hydrating, setHydrating] = useState(false);
  // 每个表角色独立递增请求代次，防止管理员快速切换版本时较慢的旧请求覆盖新字段列表。
  const tableLoadVersion = useRef({ header: 0, detail: 0 });
  // 映射 ID 全局唯一；记录已回填版本可避免校验状态更新时无意义地清空管理员正在编辑的副本。
  const hydratedMappingId = useRef<number>();
  // 表单回填也使用代次保护，避免旧版本的异步列加载提前解除新版本的 loading 锁。
  const mappingHydrationVersion = useRef(0);
  // React 状态更新发生在下一次渲染；同步 ref 额外封锁同一事件循环中的快速双击。
  const activationRequestInFlight = useRef(false);
  // 网络结果不确定时复用同一幂等键；切换数据流或映射后才创建新的业务操作键。
  const activationIdempotency = useRef<{ scope: string; key: string }>();
  const profileId = profile?.id;
  const streamId = stream?.id;
  const activeMappingId = stream?.activeMappingId;

  /** 加载映射版本列表，并优先选择调用方指定版本或当前活动版本。 */
  const loadMappings = useCallback(
    async (preferredMappingId?: number) => {
      if (!profileId || !streamId) return;
      const response = await getForecastMappings(profileId, streamId);
      const list = unwrapForecastData<ForecastMapping[]>(response) || [];
      setMappings(list);
      setCurrent((selected) => {
        const targetId = preferredMappingId ?? selected?.id ?? activeMappingId;
        return (
          list.find((item) => item.id === targetId) ||
          list.find((item) => item.id === activeMappingId) ||
          list[0]
        );
      });
    },
    [activeMappingId, profileId, streamId],
  );

  useEffect(() => {
    let active = true;
    if (!open || !profileId || !streamId) return undefined;
    hydratedMappingId.current = undefined;
    mappingHydrationVersion.current += 1;
    tableLoadVersion.current.header += 1;
    tableLoadVersion.current.detail += 1;
    form.resetFields();
    setCurrent(undefined);
    setSelectedHeader(undefined);
    setSelectedDetail(undefined);
    setHeaderColumns([]);
    setDetailColumns([]);
    setValidation(undefined);
    setHydrating(false);
    Promise.all([getForecastMetadata(profileId, {}), loadMappings(activeMappingId)])
      .then(([response]) => active && setMetadata(unwrapForecastData<ForecastMetadata>(response)))
      .catch((error) => message.error(error?.message || '映射上下文加载失败'));
    return () => {
      active = false;
    };
  }, [activeMappingId, form, loadMappings, open, profileId, streamId]);

  const tableOptions = useMemo(
    () =>
      metadata?.tables.map((item) => ({
        label: `${item.schema ? `${item.schema}.` : ''}${item.name} (${item.type})`,
        value: serializeForecastRelationTable({
          catalog: item.catalog,
          schema: item.schema,
          table: item.name,
        }),
      })) || [],
    [metadata],
  );
  const headerAlias = relationMode === 'SINGLE' ? 's' : 'h';
  const headerColumnOptions = headerColumns.map((name) => ({
    label: `${headerAlias}.${name}`,
    value: JSON.stringify({ tableAlias: headerAlias, column: name }),
  }));
  const detailColumnOptions = detailColumns.map((name) => ({
    label: `d.${name}`,
    value: JSON.stringify({ tableAlias: 'd', column: name }),
  }));
  const columnOptions =
    relationMode === 'SINGLE'
      ? headerColumnOptions
      : [...headerColumnOptions, ...detailColumnOptions];

  /**
   * 加载一个已从白名单选择的关系字段，并分别保存主表或明细表状态。
   *
   * @param table 已去除 SQL 运行时别名的受控关系表。
   * @param role 主表或明细表角色。
   * @returns 字段元数据加载完成后结束；旧代次响应会被安全忽略。
   * @throws 不向调用方抛出异常；请求失败统一展示脱敏消息。
   */
  const loadTableColumns = useCallback(
    async (table: SelectedRelationTable, role: 'header' | 'detail') => {
      if (!profileId) return;
      const requestVersion = ++tableLoadVersion.current[role];
      if (role === 'header') {
        setSelectedHeader(table);
        setHeaderColumns([]);
      } else {
        setSelectedDetail(table);
        setDetailColumns([]);
      }
      try {
        const response = await getForecastMetadata(profileId, {
          catalog: table.catalog,
          schema: table.schema,
          tablePattern: table.table,
        });
        const candidates = unwrapForecastData<ForecastMetadata>(response)?.tables || [];
        const matched =
          candidates.find(
            (item) =>
              item.name === table.table &&
              (item.catalog || '') === (table.catalog || '') &&
              (item.schema || '') === (table.schema || ''),
          ) || candidates[0];
        const names = matched?.columns?.map((item) => item.name) || [];
        if (requestVersion !== tableLoadVersion.current[role]) return;
        if (role === 'header') setHeaderColumns(names);
        else setDetailColumns(names);
      } catch (error: unknown) {
        if (requestVersion !== tableLoadVersion.current[role]) return;
        message.error(getForecastErrorMessage(error, '字段加载失败'));
      }
    },
    [profileId],
  );

  /** 将 Select 的受控 JSON 表值转换后加载列元数据。 */
  const selectTable = useCallback(
    async (serialized: string, role: 'header' | 'detail') => {
      await loadTableColumns(JSON.parse(serialized) as SelectedRelationTable, role);
    },
    [loadTableColumns],
  );

  /**
   * 将所选不可变版本回填为“创建下一版本”的可编辑副本。
   *
   * @param mapping 当前查看的历史映射版本。
   * @returns 表单值与关联表字段选项全部加载后结束。
   * @throws 不向调用方抛出异常；字段元数据错误由 loadTableColumns 统一处理。
   */
  const hydrateMappingForm = useCallback(
    async (mapping: ForecastMapping) => {
      if (!profileId) return;
      const requestVersion = ++mappingHydrationVersion.current;
      setHydrating(true);
      form.resetFields();
      form.setFieldsValue(buildForecastMappingFormValues(mapping.config, profile.timeZone));

      const primaryTable =
        mapping.config.relationMode === 'SINGLE'
          ? mapping.config.source.single
          : mapping.config.source.header;
      const detailTable =
        mapping.config.relationMode === 'HEADER_DETAIL' ? mapping.config.source.detail : undefined;
      const loads: Promise<void>[] = [];
      if (primaryTable) {
        loads.push(loadTableColumns(primaryTable, 'header'));
      } else {
        setSelectedHeader(undefined);
        setHeaderColumns([]);
      }
      if (detailTable) {
        loads.push(loadTableColumns(detailTable, 'detail'));
      } else {
        tableLoadVersion.current.detail += 1;
        setSelectedDetail(undefined);
        setDetailColumns([]);
      }
      await Promise.all(loads);
      if (requestVersion === mappingHydrationVersion.current) setHydrating(false);
    },
    [form, loadTableColumns, profileId],
  );

  useEffect(() => {
    if (!open || !current || hydratedMappingId.current === current.id) return;
    hydratedMappingId.current = current.id;
    void hydrateMappingForm(current);
  }, [current, hydrateMappingForm, open]);

  const activationButtonState = useMemo(
    () => getForecastActivationButtonState(stream, current, workerHealthy),
    [current, stream, workerHealthy],
  );
  const latestActivation = stream?.latestActivation;
  const failedActivation =
    !activationButtonState.pending &&
    latestActivation?.status === 'FAILED' &&
    latestActivation.mappingId === current?.id
      ? latestActivation
      : undefined;

  /** 为同一数据流和映射复用激活幂等键，覆盖超时后管理员再次点击的场景。 */
  const getActivationIdempotencyKey = (targetStreamId: number, mappingId: number): string => {
    const scope = `${targetStreamId}:${mappingId}`;
    if (activationIdempotency.current?.scope !== scope) {
      activationIdempotency.current = {
        scope,
        key: createForecastIdempotencyKey(`mapping-activate-${scope}`),
      };
    }
    return activationIdempotency.current.key;
  };

  /** 校验可编辑副本并创建不可变的新映射草稿版本。 */
  const createDraft = async () => {
    if (!profile || !stream) return;
    setLoading('create');
    try {
      const values = await form.validateFields();
      if (!selectedHeader) throw new Error('请选择需要接入的表或视图');
      if (values.relationMode === 'HEADER_DETAIL' && !selectedDetail) {
        throw new Error('主从关联必须选择明细表或视图');
      }
      const fields: Record<string, ForecastValueMapping | undefined> = {};
      FIELD_OPTIONS.forEach(([key]) => {
        fields[key] = columnMapping(values[key]);
      });
      fields.warehouseCode =
        constantMapping(values.warehouseConstant) || columnMapping(values.warehouseCode);
      fields.direction =
        constantMapping(values.directionConstant) ||
        columnMapping(values.direction, parseMap(values.directionMap));
      if (!fields.warehouseCode) throw new Error('仓库编码列与固定仓库编码至少配置一项');
      if (!fields.direction) throw new Error('方向列与固定方向至少配置一项');
      fields.status = columnMapping(values.status, parseMap(values.statusMap));
      if (fields.quantity) fields.quantity.transform = values.quantityTransform || 'NONE';
      if (fields.sourceUpdatedAt && values.sourceUpdatedAtSecondary) {
        fields.sourceUpdatedAt.secondaryColumn = parseColumn(values.sourceUpdatedAtSecondary);
      }
      const source: ForecastRelationSource =
        values.relationMode === 'SINGLE'
          ? { single: { ...selectedHeader, alias: 's' } }
          : {
              header: { ...selectedHeader, alias: 'h' },
              detail: { ...selectedDetail!, alias: 'd' },
              join: {
                type: values.joinType as 'INNER' | 'LEFT',
                left: parseColumn(values.joinLeft)!,
                right: parseColumn(values.joinRight)!,
              },
            };
      const config: ForecastMappingConfig = {
        relationMode: values.relationMode,
        source,
        fields,
        sourceTimeZone: values.sourceTimeZone,
        syncMode: values.syncMode,
        lookbackDays: values.lookbackDays,
      };
      const response = await createForecastMapping(
        profile.id,
        stream.id,
        config,
        createForecastIdempotencyKey('mapping-create'),
      );
      const created = unwrapForecastData<ForecastMapping>(response);
      hydratedMappingId.current = undefined;
      await loadMappings(created.id);
      message.success(`映射草稿 v${created.version} 已创建`);
    } catch (error: unknown) {
      message.error(getForecastErrorMessage(error, '映射草稿创建失败'));
    } finally {
      setLoading(undefined);
    }
  };

  /** 对当前选中版本执行校验、发布或首次同步激活操作。 */
  const runAction = async (action: 'validate' | 'publish' | 'activate') => {
    if (!profile || !stream || !current) return;
    if (action === 'activate') {
      if (activationRequestInFlight.current) return;
      if (activationButtonState.disabled) {
        if (activationButtonState.reason) message.warning(activationButtonState.reason);
        return;
      }
      activationRequestInFlight.current = true;
    }
    setLoading(action);
    try {
      if (action === 'validate') {
        const response = await validateForecastMapping(
          profile.id,
          stream.id,
          current.id,
          createForecastIdempotencyKey('mapping-validate'),
        );
        const result = unwrapForecastData<ForecastMappingValidation>(response);
        setValidation(result);
        setCurrent((selected) =>
          selected
            ? {
                ...selected,
                valid: result.valid,
                validationSummary: [...result.errors, ...result.warnings].join('；'),
              }
            : selected,
        );
      } else if (action === 'publish') {
        await publishForecastMapping(
          profile.id,
          stream.id,
          current.id,
          createForecastIdempotencyKey('mapping-publish'),
        );
        await loadMappings(current.id);
      } else {
        const response = await activateForecastMapping(
          profile.id,
          stream.id,
          current.id,
          getActivationIdempotencyKey(stream.id, current.id),
        );
        const job = unwrapForecastData<ForecastJob>(response);
        message.success(`首次同步任务 #${job.id} 已提交，当前状态 ${job.status}`);
        await onChanged();
      }
    } catch (error: unknown) {
      message.error(getForecastErrorMessage(error, '映射操作失败'));
    } finally {
      if (action === 'activate') activationRequestInFlight.current = false;
      setLoading(undefined);
    }
  };

  const previewColumns = validation?.samples?.[0]
    ? Object.keys(validation.samples[0]).map((key) => ({
        title: key,
        dataIndex: key,
        ellipsis: true,
      }))
    : [];

  /** 切换关系模式后清空依赖表别名的字段，避免旧表列被带入新版本。 */
  const resetRelationFields = () => {
    setSelectedHeader(undefined);
    setSelectedDetail(undefined);
    setHeaderColumns([]);
    setDetailColumns([]);
    form.resetFields([
      'tableSelector',
      'detailTableSelector',
      'joinLeft',
      'joinRight',
      'sourceUpdatedAtSecondary',
      ...FIELD_OPTIONS.map(([key]) => key),
    ]);
  };

  const mappedCoreFieldCount = [
    watchedValues?.sourceRecordId,
    watchedValues?.quantity,
    watchedValues?.occurredAt,
    watchedValues?.warehouseCode || watchedValues?.warehouseConstant,
    watchedValues?.direction || watchedValues?.directionConstant,
  ].filter(Boolean).length;
  const persistedValidation = useMemo(
    () => parsePersistedValidationSummary(current?.validationSummary),
    [current?.validationSummary],
  );
  const validationKnown = Boolean(validation || current?.valid);
  const validationErrors = validation?.errors || persistedValidation.errors;
  const validationWarnings = validation?.warnings || persistedValidation.warnings;
  const lastSavedAt = current?.createdAt
    ? dayjs(current.createdAt).format('YYYY-MM-DD HH:mm')
    : undefined;
  const currentVersionIsActive = Boolean(current && stream?.activeMappingId === current.id);
  const publishDisabledReason = getPublishDisabledReason(current);
  const publishDisabled = Boolean(publishDisabledReason);
  // 底栏只突出当前流程中最接近完成的一项操作，避免校验、发布和激活同时争夺主按钮层级。
  const primaryFooterAction = !activationButtonState.disabled
    ? 'activate'
    : !publishDisabled
    ? 'publish'
    : 'validate';

  if (!open) return null;

  return (
    <div className={styles.mappingWorkspace}>
      <div className={styles.mappingHeader}>
        <Button
          className={styles.mappingBack}
          type="link"
          icon={<ArrowLeftOutlined />}
          onClick={onClose}
        >
          返回数据流
        </Button>
        <div className={styles.mappingTitleRow}>
          <Typography.Title level={4}>字段映射 · {stream?.name || ''}</Typography.Title>
          <Tag color={stream?.enabled ? 'success' : 'default'}>
            {stream?.enabled ? '启用' : '停用'}
          </Tag>
          <Typography.Text className={styles.mappingProfileName} type="secondary">
            {profile?.name}
          </Typography.Text>
        </div>
        <div className={styles.versionBar}>
          <div className={styles.versionSelector}>
            <Select
              value={current?.id}
              placeholder="当前数据流暂无映射版本"
              onChange={(id) => {
                setCurrent(mappings.find((item) => item.id === id));
                setValidation(undefined);
              }}
              options={mappings.map((item) => ({
                value: item.id,
                label:
                  'v' +
                  item.version +
                  ' · ' +
                  item.status +
                  ' · ' +
                  (item.valid ? '有效' : '待校验') +
                  (item.id === stream?.activeMappingId ? ' · 当前活动' : ''),
              }))}
            />
            <Tooltip
              trigger={['hover', 'focus']}
              title={
                current
                  ? `当前编辑基于 v${current.version} 创建新版本，不会覆盖已发布配置。`
                  : '当前数据流没有历史版本，请从空白配置创建首个映射。'
              }
            >
              <Button
                className={styles.helpButton}
                type="text"
                size="small"
                shape="circle"
                icon={<QuestionCircleOutlined />}
                aria-label="查看版本编辑说明"
                title="查看版本编辑说明"
              />
            </Tooltip>
          </div>
          <div className={styles.versionMeta}>
            <Typography.Text type="secondary">配置摘要</Typography.Text>
            <Typography.Text code>{current?.configChecksum?.slice(0, 16) || '-'}</Typography.Text>
          </div>
          <div className={styles.versionState}>
            <CheckCircleOutlined style={{ color: current?.valid ? '#52c41a' : '#bfbfbf' }} />
            <Typography.Text type={current?.valid ? 'success' : 'secondary'}>
              {current?.valid ? '映射有效' : '待校验'}
            </Typography.Text>
          </div>
        </div>
      </div>
      <div className={styles.mappingScroll}>
        {activationButtonState.pending && latestActivation && (
          <Alert
            showIcon
            type={workerHealthy === false ? 'error' : 'info'}
            style={{ marginTop: 16 }}
            title={`激活任务 #${latestActivation.jobId} · ${activationButtonState.label}`}
            description={
              <Space orientation="vertical" size={4} style={{ width: '100%' }}>
                <Typography.Text>
                  当前活动映射不会提前切换；只有首次同步和预测全部成功后，候选版本才会原子生效。
                </Typography.Text>
                {workerHealthy === false && (
                  <Typography.Text type="danger">
                    Worker 当前离线，因此 QUEUED 任务暂时不会开始执行。
                  </Typography.Text>
                )}
                {latestActivation.status === 'RUNNING' && (
                  <Progress percent={latestActivation.progressPercent} size="small" />
                )}
              </Space>
            }
            action={
              <Button
                size="small"
                onClick={() => history.push(`/forecast/runs?profileId=${profileId}`)}
              >
                查看任务
              </Button>
            }
          />
        )}
        {failedActivation && (
          <Alert
            showIcon
            type="error"
            style={{ marginTop: 16 }}
            title={`最近激活任务 #${failedActivation.jobId} 执行失败`}
            description={failedActivation.errorMessage || '请到运行中心查看脱敏错误并重试。'}
            action={
              <Button
                size="small"
                onClick={() => history.push(`/forecast/runs?profileId=${profileId}`)}
              >
                查看任务
              </Button>
            }
          />
        )}
        {workerHealthy === false && !activationButtonState.pending && (
          <Alert
            showIcon
            type="warning"
            style={{ marginTop: 16 }}
            title="Forecast Worker 当前离线"
            description="请先启动 Worker 再提交首次同步，否则任务只能停留在 QUEUED。"
          />
        )}
        {validation && (
          <Alert
            style={{ marginTop: 16 }}
            showIcon
            type={validation.valid ? 'success' : 'error'}
            title={validation.valid ? '映射校验通过' : validation.errors.join('；')}
            description={validation.warnings.join('；')}
          />
        )}
        <div className={styles.mappingBody}>
          <div className={styles.mappingFormCanvas}>
            <Form
              form={form}
              layout="vertical"
              disabled={hydrating}
              initialValues={{
                sourceTimeZone: profile?.timeZone || 'Asia/Shanghai',
                relationMode: 'SINGLE',
                syncMode: 'INCREMENTAL',
                lookbackDays: 7,
                quantityTransform: 'NONE',
                joinType: 'INNER',
              }}
            >
              <section className={styles.formSection}>
                <Typography.Text className={styles.sectionTitle} strong>
                  1. 来源结构
                </Typography.Text>
                <Form.Item
                  className={styles.relationMode}
                  name="relationMode"
                  label="关系模式"
                  tooltip={FORECAST_MAPPING_HELP.relationMode}
                  rules={[{ required: true }]}
                >
                  <Segmented
                    block
                    options={[
                      { value: 'SINGLE', label: '单表 / 视图' },
                      { value: 'HEADER_DETAIL', label: '主表 + 明细表' },
                    ]}
                    onChange={resetRelationFields}
                  />
                </Form.Item>
                <div
                  className={
                    relationMode === 'HEADER_DETAIL' ? styles.relationTables : styles.singleTable
                  }
                >
                  <Form.Item
                    name="tableSelector"
                    label={relationMode === 'SINGLE' ? '表或视图' : '主表或视图'}
                    tooltip={
                      relationMode === 'SINGLE'
                        ? FORECAST_MAPPING_HELP.singleTable
                        : FORECAST_MAPPING_HELP.headerTable
                    }
                    rules={[{ required: true }]}
                  >
                    <Select
                      showSearch
                      optionFilterProp="label"
                      options={tableOptions}
                      onChange={(value) => selectTable(value, 'header')}
                    />
                  </Form.Item>
                  {relationMode === 'HEADER_DETAIL' && (
                    <>
                      <div className={styles.relationConnector} aria-hidden="true">
                        <SwapRightOutlined />
                      </div>
                      <Form.Item
                        name="detailTableSelector"
                        label="明细表或视图"
                        tooltip={FORECAST_MAPPING_HELP.detailTable}
                        rules={[{ required: true }]}
                      >
                        <Select
                          showSearch
                          optionFilterProp="label"
                          options={tableOptions}
                          onChange={(value) => selectTable(value, 'detail')}
                        />
                      </Form.Item>
                    </>
                  )}
                </div>
                {relationMode === 'HEADER_DETAIL' && (
                  <div className={styles.joinGrid}>
                    <Form.Item
                      name="joinType"
                      label="关联类型"
                      tooltip={FORECAST_MAPPING_HELP.joinType}
                      rules={[{ required: true }]}
                    >
                      <Select options={['INNER', 'LEFT'].map((value) => ({ value }))} />
                    </Form.Item>
                    <Form.Item
                      name="joinLeft"
                      label="主表关联键"
                      tooltip={FORECAST_MAPPING_HELP.joinLeft}
                      rules={[{ required: true }]}
                    >
                      <Select showSearch optionFilterProp="label" options={headerColumnOptions} />
                    </Form.Item>
                    <Form.Item
                      name="joinRight"
                      label="明细表关联键"
                      tooltip={FORECAST_MAPPING_HELP.joinRight}
                      rules={[{ required: true }]}
                    >
                      <Select showSearch optionFilterProp="label" options={detailColumnOptions} />
                    </Form.Item>
                  </div>
                )}
              </section>
              <section className={styles.formSection}>
                <Typography.Text className={styles.sectionTitle} strong>
                  2. 同步策略
                </Typography.Text>
                <div className={styles.syncGrid}>
                  <Form.Item
                    name="sourceTimeZone"
                    label="源时区"
                    tooltip={FORECAST_MAPPING_HELP.sourceTimeZone}
                    rules={[{ required: true }]}
                  >
                    <Input />
                  </Form.Item>
                  <Form.Item
                    name="syncMode"
                    label="同步模式"
                    tooltip={FORECAST_MAPPING_HELP.syncMode}
                    rules={[{ required: true }]}
                  >
                    <Select
                      options={[
                        { value: 'INCREMENTAL', label: '复合水位增量' },
                        { value: 'SNAPSHOT_LOOKBACK', label: '最近窗口重扫' },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item
                    name="lookbackDays"
                    label="重扫天数"
                    tooltip={FORECAST_MAPPING_HELP.lookbackDays}
                    extra="用于补偿源库迟到更新"
                  >
                    <InputNumber min={1} max={90} />
                  </Form.Item>
                  <Form.Item
                    name="quantityTransform"
                    label="数量变换"
                    tooltip={FORECAST_MAPPING_HELP.quantityTransform}
                  >
                    <Select options={['NONE', 'ABS', 'NEGATE'].map((value) => ({ value }))} />
                  </Form.Item>
                </div>
              </section>
              <section className={styles.formSection}>
                <Typography.Text className={styles.sectionTitle} strong>
                  3. 标准事件字段
                </Typography.Text>
                <div className={styles.fieldGrid}>
                  {PRIMARY_FIELD_OPTIONS.map(([key, label, required]) => (
                    <Form.Item
                      key={key}
                      name={key}
                      label={label}
                      tooltip={FORECAST_MAPPING_HELP[key]}
                      rules={required ? [{ required: true }] : undefined}
                    >
                      <Select
                        allowClear
                        showSearch
                        optionFilterProp="label"
                        options={columnOptions}
                      />
                    </Form.Item>
                  ))}
                </div>
                <Collapse
                  className={styles.advancedPanel}
                  size="small"
                  items={[
                    {
                      key: 'advanced',
                      label: '高级映射（方向、状态、删除标记与字典）',
                      // 折叠时仍挂载字段，保证读取和保存历史版本时不会丢失高级映射配置。
                      forceRender: true,
                      children: (
                        <>
                          <Typography.Paragraph type="secondary">
                            方向与仓库编码必须分别配置来源列或固定值；状态、删除标记与字典按源库实际语义选填。
                          </Typography.Paragraph>
                          <div className={styles.advancedFieldGrid}>
                            {ADVANCED_FIELD_OPTIONS.map(([key, label, required]) => (
                              <Form.Item
                                key={key}
                                name={key}
                                label={label}
                                tooltip={FORECAST_MAPPING_HELP[key]}
                                rules={required ? [{ required: true }] : undefined}
                              >
                                <Select
                                  allowClear
                                  showSearch
                                  optionFilterProp="label"
                                  options={columnOptions}
                                />
                              </Form.Item>
                            ))}
                            <Form.Item
                              name="warehouseConstant"
                              label="固定仓库编码（可选）"
                              tooltip={FORECAST_MAPPING_HELP.warehouseConstant}
                            >
                              <Input maxLength={255} />
                            </Form.Item>
                            <Form.Item
                              name="directionConstant"
                              label="固定方向（可选）"
                              tooltip={FORECAST_MAPPING_HELP.directionConstant}
                            >
                              <Select
                                allowClear
                                options={[
                                  { value: 'INBOUND', label: '入库 INBOUND' },
                                  { value: 'OUTBOUND', label: '出库 OUTBOUND' },
                                ]}
                              />
                            </Form.Item>
                            {relationMode === 'HEADER_DETAIL' && (
                              <Form.Item
                                name="sourceUpdatedAtSecondary"
                                label="第二更新时间列（可选）"
                                tooltip={FORECAST_MAPPING_HELP.sourceUpdatedAtSecondary}
                              >
                                <Select
                                  allowClear
                                  showSearch
                                  optionFilterProp="label"
                                  options={columnOptions}
                                />
                              </Form.Item>
                            )}
                          </div>
                          <div className={styles.dictionaryGrid}>
                            <Form.Item
                              name="directionMap"
                              label="方向字典 JSON"
                              tooltip={FORECAST_MAPPING_HELP.directionMap}
                            >
                              <Input.TextArea rows={3} />
                            </Form.Item>
                            <Form.Item
                              name="statusMap"
                              label="状态字典 JSON"
                              tooltip={FORECAST_MAPPING_HELP.statusMap}
                            >
                              <Input.TextArea rows={3} />
                            </Form.Item>
                          </div>
                        </>
                      ),
                    },
                  ]}
                />
              </section>
            </Form>

            {validation?.samples?.length ? (
              <section className={styles.previewSection}>
                <Typography.Text className={styles.sectionTitle} strong>
                  校验预览
                </Typography.Text>
                <Table
                  size="small"
                  rowKey="sourceRecordId"
                  columns={previewColumns}
                  dataSource={validation.samples}
                  pagination={{ pageSize: 10 }}
                  scroll={{ x: 'max-content' }}
                />
              </section>
            ) : null}
          </div>

          <aside className={styles.validationRail} aria-label="校验结果">
            <Typography.Title level={5}>校验结果</Typography.Title>
            <div className={styles.validationStats}>
              <div className={styles.validationStat}>
                <Typography.Text type="secondary">errors</Typography.Text>
                <Typography.Title level={4}>
                  {validationKnown ? validationErrors.length : '-'}
                </Typography.Title>
              </div>
              <div className={styles.validationStat}>
                <Typography.Text type="secondary">warnings</Typography.Text>
                <Typography.Title level={4} style={{ color: '#d48806' }}>
                  {validationKnown ? validationWarnings.length : '-'}
                </Typography.Title>
              </div>
              <div className={styles.validationStat}>
                <Typography.Text type="secondary">核心字段</Typography.Text>
                <Typography.Title level={4} style={{ color: '#389e0d' }}>
                  {mappedCoreFieldCount}/{REQUIRED_CORE_FIELD_COUNT}
                </Typography.Title>
              </div>
            </div>
            <div className={styles.validationWarnings}>
              {validationWarnings.length ? (
                validationWarnings.slice(0, 3).map((warning) => (
                  <div className={styles.warningItem} key={warning}>
                    <WarningOutlined />
                    <Typography.Text>{warning}</Typography.Text>
                  </div>
                ))
              ) : (
                <Typography.Text type="secondary">
                  校验后将在这里显示错误、风险提示与预览结果。
                </Typography.Text>
              )}
            </div>
          </aside>
        </div>
      </div>

      <footer className={styles.mappingFooter}>
        <div className={styles.footerMeta}>
          <Typography.Text className={styles.footerTimestamp} type="secondary">
            {lastSavedAt ? '当前版本创建于：' + lastSavedAt : '尚未创建映射版本'}
          </Typography.Text>
          {currentVersionIsActive ? (
            <Tag className={styles.footerStatusTag} color="success" icon={<CheckCircleOutlined />}>
              当前版本已激活
            </Tag>
          ) : (
            <Tag className={styles.footerStatusTag}>
              活动版本 {stream?.activeMappingVersion ? 'v' + stream.activeMappingVersion : '-'}
            </Tag>
          )}
        </div>
        <Space className={styles.footerActions}>
          <Button onClick={onClose}>取消</Button>
          <Button loading={hydrating || loading === 'create'} onClick={createDraft}>
            保存草稿
          </Button>
          <Button
            type={primaryFooterAction === 'validate' ? 'primary' : 'default'}
            disabled={!current}
            loading={loading === 'validate'}
            onClick={() => runAction('validate')}
          >
            校验并预览
          </Button>
          <Tooltip title={publishDisabledReason}>
            <span>
              <Button
                type={primaryFooterAction === 'publish' ? 'primary' : 'default'}
                disabled={publishDisabled}
                loading={loading === 'publish'}
                onClick={() => runAction('publish')}
              >
                发布新版本
              </Button>
            </span>
          </Tooltip>
          {!currentVersionIsActive && (
            <Tooltip title={activationButtonState.reason}>
              <span>
                <Button
                  type={primaryFooterAction === 'activate' ? 'primary' : 'default'}
                  disabled={activationButtonState.disabled}
                  loading={loading === 'activate'}
                  onClick={() => runAction('activate')}
                >
                  {activationButtonState.label}
                </Button>
              </span>
            </Tooltip>
          )}
        </Space>
      </footer>
    </div>
  );
};

export default ForecastMappingWorkspace;
