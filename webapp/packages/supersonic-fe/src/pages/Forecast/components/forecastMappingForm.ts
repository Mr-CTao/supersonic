/**
 * Forecast 映射配置与 Ant Design 表单值之间的适配模块。
 *
 * 职责：把不可变的历史映射版本转换为可创建下一版本的表单初始值，并统一关系表、字段引用和字典的序列化格式。
 * 该模块不发起请求且不持有共享状态，因此可安全地在版本快速切换时重复调用。
 */
import type {
  ForecastColumnRef,
  ForecastMappingConfig,
  ForecastRelationTable,
  ForecastValueMapping,
} from '@/services/forecast';

/** 映射创建表单的完整值结构。 */
export type ForecastMappingFormValues = {
  relationMode: 'SINGLE' | 'HEADER_DETAIL';
  tableSelector?: string;
  detailTableSelector?: string;
  joinType: 'INNER' | 'LEFT';
  joinLeft?: string;
  joinRight?: string;
  sourceTimeZone: string;
  syncMode: 'INCREMENTAL' | 'SNAPSHOT_LOOKBACK';
  lookbackDays: number;
  quantityTransform: 'NONE' | 'ABS' | 'NEGATE';
  sourceRecordId?: string;
  taskId?: string;
  quantity?: string;
  occurredAt?: string;
  sourceUpdatedAt?: string;
  sourceUpdatedAtSecondary?: string;
  warehouseCode?: string;
  direction?: string;
  status?: string;
  deleted?: string;
  warehouseConstant?: string;
  directionConstant?: string;
  directionMap?: string;
  statusMap?: string;
};

/** 规范化可选标识符，避免 null 与 undefined 生成不同的 Select value。 */
const optionalIdentifier = (value?: string | null) => value || undefined;

/**
 * 将关系表转换为表选择器使用的稳定 JSON 值。
 *
 * @param table 不含运行时别名也可序列化的关系表。
 * @returns 可供 Select 比较的稳定 JSON；未提供表时返回 undefined。
 * @throws 不主动抛出异常；输入仅包含受控字符串字段。
 */
export const serializeForecastRelationTable = (
  table?: Pick<ForecastRelationTable, 'catalog' | 'schema' | 'table'>,
): string | undefined =>
  table
    ? JSON.stringify({
        catalog: optionalIdentifier(table.catalog),
        schema: optionalIdentifier(table.schema),
        table: table.table,
      })
    : undefined;

/** 将列引用转换为字段选择器使用的稳定 JSON 值。 */
const serializeColumn = (column?: ForecastColumnRef): string | undefined =>
  column ? JSON.stringify({ tableAlias: column.tableAlias, column: column.column }) : undefined;

/** 仅当映射来源是列时返回列选择值。 */
const columnValue = (mapping?: ForecastValueMapping): string | undefined =>
  mapping?.sourceType === 'COLUMN' ? serializeColumn(mapping.column) : undefined;

/** 仅当映射来源是常量时返回常量值。 */
const constantValue = (mapping?: ForecastValueMapping): string | undefined =>
  mapping?.sourceType === 'CONSTANT' ? mapping.constant : undefined;

/** 将非空字典格式化为便于管理员阅读和继续编辑的 JSON。 */
const dictionaryValue = (mapping?: ForecastValueMapping): string | undefined =>
  mapping?.valueMap && Object.keys(mapping.valueMap).length > 0
    ? JSON.stringify(mapping.valueMap, null, 2)
    : undefined;

/**
 * 把一个已保存的映射版本转换成“创建下一版本”表单值。
 *
 * @param config 后端返回的版本化映射配置。
 * @param fallbackTimeZone 配置缺失时使用的 Profile 时区。
 * @returns 可直接传给 Form.setFieldsValue 的完整表单值。
 * @throws 不主动抛出异常；缺失的可选字段会转换为 undefined。
 */
export const buildForecastMappingFormValues = (
  config: ForecastMappingConfig,
  fallbackTimeZone: string,
): ForecastMappingFormValues => {
  const fields = config.fields || {};
  const primaryTable =
    config.relationMode === 'SINGLE' ? config.source.single : config.source.header;
  const detailTable = config.relationMode === 'HEADER_DETAIL' ? config.source.detail : undefined;
  const join = config.relationMode === 'HEADER_DETAIL' ? config.source.join : undefined;

  return {
    relationMode: config.relationMode,
    tableSelector: serializeForecastRelationTable(primaryTable),
    detailTableSelector: serializeForecastRelationTable(detailTable),
    joinType: join?.type || 'INNER',
    joinLeft: serializeColumn(join?.left),
    joinRight: serializeColumn(join?.right),
    sourceTimeZone: config.sourceTimeZone || fallbackTimeZone,
    syncMode: config.syncMode,
    lookbackDays: config.lookbackDays,
    quantityTransform: fields.quantity?.transform || 'NONE',
    sourceRecordId: columnValue(fields.sourceRecordId),
    taskId: columnValue(fields.taskId),
    quantity: columnValue(fields.quantity),
    occurredAt: columnValue(fields.occurredAt),
    sourceUpdatedAt: columnValue(fields.sourceUpdatedAt),
    sourceUpdatedAtSecondary: serializeColumn(fields.sourceUpdatedAt?.secondaryColumn),
    warehouseCode: columnValue(fields.warehouseCode),
    direction: columnValue(fields.direction),
    status: columnValue(fields.status),
    deleted: columnValue(fields.deleted),
    warehouseConstant: constantValue(fields.warehouseCode),
    directionConstant: constantValue(fields.direction),
    directionMap: dictionaryValue(fields.direction),
    statusMap: dictionaryValue(fields.status),
  };
};
