/**
 * Forecast 映射表单适配模块的回归测试。
 *
 * 覆盖主从关联、固定方向、列映射、更新时间双列、字典回填及全量表单帮助文案，
 * 防止已发布版本再次显示为空白表单，或新增映射项时遗漏填写/留空说明。
 */
import type { ForecastMappingConfig } from '@/services/forecast';
import {
  buildForecastMappingFormValues,
  serializeForecastRelationTable,
} from './forecastMappingForm';
import { FORECAST_MAPPING_HELP } from './forecastMappingHelp';

/** 字段映射抽屉中应显示问号说明的全部表单项目。 */
const EXPECTED_MAPPING_HELP_KEYS = [
  'relationMode',
  'singleTable',
  'headerTable',
  'detailTable',
  'joinType',
  'joinLeft',
  'joinRight',
  'sourceTimeZone',
  'syncMode',
  'lookbackDays',
  'quantityTransform',
  'sourceRecordId',
  'taskId',
  'quantity',
  'occurredAt',
  'sourceUpdatedAt',
  'warehouseCode',
  'direction',
  'status',
  'deleted',
  'warehouseConstant',
  'directionConstant',
  'sourceUpdatedAtSecondary',
  'directionMap',
  'statusMap',
] as const;

describe('forecastMappingForm', () => {
  it('完整回填已发布的主表明细表映射', () => {
    const config: ForecastMappingConfig = {
      relationMode: 'HEADER_DETAIL',
      source: {
        header: { catalog: 'ajrw', table: 'tb_wms_in', alias: 'h' },
        detail: { catalog: 'ajrw', table: 'tb_wms_in_material_label', alias: 'd' },
        join: {
          type: 'INNER',
          left: { tableAlias: 'h', column: 'Code' },
          right: { tableAlias: 'd', column: 'InCode' },
        },
      },
      fields: {
        sourceRecordId: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'd', column: 'Id' },
        },
        taskId: { sourceType: 'COLUMN', column: { tableAlias: 'h', column: 'Code' } },
        quantity: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'd', column: 'Quantity' },
          transform: 'ABS',
        },
        occurredAt: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'h', column: 'InDate' },
        },
        sourceUpdatedAt: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'h', column: 'UpdateTime' },
          secondaryColumn: { tableAlias: 'd', column: 'UpdateTime' },
        },
        warehouseCode: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'h', column: 'WareHouseCode' },
        },
        direction: { sourceType: 'CONSTANT', constant: 'INBOUND' },
        status: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'h', column: 'Status' },
          valueMap: { Done: 'COMPLETED' },
        },
        deleted: {
          sourceType: 'COLUMN',
          column: { tableAlias: 'd', column: 'IsDeleted' },
        },
      },
      sourceTimeZone: 'Asia/Shanghai',
      syncMode: 'SNAPSHOT_LOOKBACK',
      lookbackDays: 90,
    };

    const values = buildForecastMappingFormValues(config, 'UTC');

    expect(JSON.parse(values.tableSelector!)).toEqual({
      catalog: 'ajrw',
      table: 'tb_wms_in',
    });
    expect(JSON.parse(values.detailTableSelector!)).toEqual({
      catalog: 'ajrw',
      table: 'tb_wms_in_material_label',
    });
    expect(JSON.parse(values.sourceRecordId!)).toEqual({ tableAlias: 'd', column: 'Id' });
    expect(JSON.parse(values.sourceUpdatedAtSecondary!)).toEqual({
      tableAlias: 'd',
      column: 'UpdateTime',
    });
    expect(values).toMatchObject({
      relationMode: 'HEADER_DETAIL',
      joinType: 'INNER',
      sourceTimeZone: 'Asia/Shanghai',
      syncMode: 'SNAPSHOT_LOOKBACK',
      lookbackDays: 90,
      quantityTransform: 'ABS',
      directionConstant: 'INBOUND',
      warehouseConstant: undefined,
      statusMap: '{\n  "Done": "COMPLETED"\n}',
    });
  });

  it('规范化关系表空目录和空 Schema，确保 Select 可以命中选项', () => {
    const value = serializeForecastRelationTable({
      catalog: null as unknown as string,
      schema: '',
      table: 'view_wms_stock',
    });

    expect(value).toBe('{"table":"view_wms_stock"}');
  });

  it('为每个字段映射项目提供填写后与留空时的行为说明', () => {
    expect(Object.keys(FORECAST_MAPPING_HELP).sort()).toEqual(
      [...EXPECTED_MAPPING_HELP_KEYS].sort(),
    );
    EXPECTED_MAPPING_HELP_KEYS.forEach((key) => {
      expect(FORECAST_MAPPING_HELP[key].length).toBeGreaterThan(20);
      expect(FORECAST_MAPPING_HELP[key]).toMatch(/不填|不选择|留空/);
    });
  });
});
