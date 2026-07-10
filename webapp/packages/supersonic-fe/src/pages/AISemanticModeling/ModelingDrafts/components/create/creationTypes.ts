/**
 * 建模草稿创建表单共享类型与常量。
 *
 * 职责：集中声明数据源、主题域、模型能力、表单值和第一版选表约束，避免创建容器、表单和元数据 Hook 重复定义。
 */

export const MAX_SELECTED_TABLES = 10;
export const CATALOG_DATABASE_TYPES = new Set(['STARROCKS', 'KYUUBI', 'PRESTO', 'TRINO']);

export type DatabaseOption = {
  id: number;
  name: string;
  type?: string;
  hasUsePermission?: boolean;
};

export type DomainOption = {
  id: number;
  name: string;
  children?: DomainOption[];
  hasEditPermission?: boolean;
};

export type DomainSelectOption = {
  label: string;
  value: number;
};

export type CapabilityOption = {
  chatModelId: number;
  modelName: string;
  providerType?: string;
  supportJsonMode?: boolean;
  enabled?: boolean;
};

export type CreateDraftFormValues = {
  businessGoal: string;
  dataSourceId: number;
  catalogName?: string;
  databaseName: string;
  selectedTables: string[];
  domainId?: number;
  chatModelId: number;
  includeSampleData: boolean;
};
