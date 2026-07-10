/**
 * 建模草稿创建表单元数据 Hook。
 *
 * 职责：加载数据源、可管理主题域、JSON-capable LLM、缺口预填信息，并管理 catalog/database/table 级联选项。
 *
 * 并发说明：级联请求使用递增序号丢弃过期响应；关闭抽屉或卸载后忽略迟到异步结果。
 */
import type { FormInstance } from 'antd';
import { message } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { getLlmCapabilities } from '@/services/llmGateway';
import { getSemanticGapDetail } from '@/services/semanticGap';
import type { SemanticGapItem } from '@/services/semanticGap';
import {
  getCatalogs,
  getDatabaseList,
  getDbNames,
  getDomainList,
  getTables,
} from '@/pages/SemanticModel/service';
import { getRequestErrorText, unwrapResponseData } from '../../utils';
import {
  CATALOG_DATABASE_TYPES,
  type CapabilityOption,
  type CreateDraftFormValues,
  type DatabaseOption,
  type DomainOption,
  type DomainSelectOption,
} from './creationTypes';
import { flattenDomainOptions, normalizeTableName } from './creationUtils';

type Params = {
  form: FormInstance<CreateDraftFormValues>;
  initialGapId?: number;
  open: boolean;
};

export type DraftCreationOptions = {
  initializing: boolean;
  metadataLoading: boolean;
  databaseOptions: DatabaseOption[];
  domainOptions: DomainSelectOption[];
  capabilities: CapabilityOption[];
  catalogOptions: string[];
  databaseNameOptions: string[];
  tableOptions: string[];
  catalogRequired: boolean;
  loadDatabaseChildren: (databaseId: number, databaseType?: string) => Promise<void>;
  loadDatabaseNames: (catalogName: string) => Promise<void>;
  loadTables: (databaseName: string) => Promise<void>;
};

/**
 * 创建表单元数据与级联请求 Hook。
 *
 * @param params 表单实例、可选 gapId 和抽屉状态。
 * @returns 所有下拉选项、loading 状态和级联加载方法。
 * @throws 不向组件抛出请求异常；统一转为 message。
 */
export function useDraftCreationOptions({
  form,
  initialGapId,
  open,
}: Params): DraftCreationOptions {
  const [initializing, setInitializing] = useState(false);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [databaseOptions, setDatabaseOptions] = useState<DatabaseOption[]>([]);
  const [domainOptions, setDomainOptions] = useState<DomainSelectOption[]>([]);
  const [capabilities, setCapabilities] = useState<CapabilityOption[]>([]);
  const [catalogOptions, setCatalogOptions] = useState<string[]>([]);
  const [databaseNameOptions, setDatabaseNameOptions] = useState<string[]>([]);
  const [tableOptions, setTableOptions] = useState<string[]>([]);
  const [catalogRequired, setCatalogRequired] = useState(false);
  const requestRef = useRef(0);
  const mountedRef = useRef(true);

  useEffect(
    () => () => {
      mountedRef.current = false;
      requestRef.current += 1;
    },
    [],
  );

  /** 加载当前数据源第一层 catalog 或 database 选项。 */
  const loadDatabaseChildren = async (databaseId: number, databaseType?: string) => {
    const requestId = ++requestRef.current;
    const requiresCatalog = CATALOG_DATABASE_TYPES.has(databaseType || '');
    setCatalogRequired(requiresCatalog);
    setCatalogOptions([]);
    setDatabaseNameOptions([]);
    setTableOptions([]);
    form.setFieldsValue({
      catalogName: undefined,
      databaseName: undefined,
      selectedTables: undefined,
    });
    setMetadataLoading(true);
    try {
      if (requiresCatalog) {
        const values = unwrapResponseData<string[]>(await getCatalogs(databaseId)) || [];
        if (requestId === requestRef.current && mountedRef.current) setCatalogOptions(values);
      } else {
        const values = unwrapResponseData<string[]>(await getDbNames(databaseId, '')) || [];
        if (requestId === requestRef.current && mountedRef.current) setDatabaseNameOptions(values);
      }
    } catch (error) {
      if (requestId === requestRef.current && mountedRef.current) {
        message.error(getRequestErrorText(error));
      }
    } finally {
      if (requestId === requestRef.current && mountedRef.current) setMetadataLoading(false);
    }
  };

  /** 选择 catalog 后加载 database 名称。 */
  const loadDatabaseNames = async (catalogName: string) => {
    const databaseId = form.getFieldValue('dataSourceId');
    if (!databaseId) return;
    const requestId = ++requestRef.current;
    setDatabaseNameOptions([]);
    setTableOptions([]);
    form.setFieldsValue({ databaseName: undefined, selectedTables: undefined });
    setMetadataLoading(true);
    try {
      const values = unwrapResponseData<string[]>(await getDbNames(databaseId, catalogName)) || [];
      if (requestId === requestRef.current && mountedRef.current) setDatabaseNameOptions(values);
    } catch (error) {
      if (requestId === requestRef.current && mountedRef.current) {
        message.error(getRequestErrorText(error));
      }
    } finally {
      if (requestId === requestRef.current && mountedRef.current) setMetadataLoading(false);
    }
  };

  /** 选择 database 后加载可建模表和视图。 */
  const loadTables = async (databaseName: string) => {
    const databaseId = form.getFieldValue('dataSourceId');
    if (!databaseId) return;
    const requestId = ++requestRef.current;
    setTableOptions([]);
    form.setFieldValue('selectedTables', undefined);
    setMetadataLoading(true);
    try {
      const data =
        unwrapResponseData<any[]>(
          await getTables(databaseId, form.getFieldValue('catalogName') || '', databaseName),
        ) || [];
      if (requestId === requestRef.current && mountedRef.current) {
        setTableOptions(data.map(normalizeTableName).filter(Boolean));
      }
    } catch (error) {
      if (requestId === requestRef.current && mountedRef.current) {
        message.error(getRequestErrorText(error));
      }
    } finally {
      if (requestId === requestRef.current && mountedRef.current) setMetadataLoading(false);
    }
  };

  useEffect(() => {
    if (!open) return undefined;
    let active = true;
    requestRef.current += 1;
    form.resetFields();
    form.setFieldsValue({ includeSampleData: false });
    setCatalogOptions([]);
    setDatabaseNameOptions([]);
    setTableOptions([]);
    setCatalogRequired(false);
    setInitializing(true);

    /** 并行加载基础选项，并使用缺口中的可信 ID 预填表单。 */
    const initialize = async () => {
      try {
        const [databaseResponse, domainResponse, capabilityResponse, gapResponse] =
          await Promise.all([
            getDatabaseList(),
            getDomainList(),
            getLlmCapabilities(),
            initialGapId ? getSemanticGapDetail(initialGapId) : Promise.resolve(undefined),
          ]);
        if (!active) return;
        const databases = unwrapResponseData<DatabaseOption[]>(databaseResponse) || [];
        const domains = unwrapResponseData<DomainOption[]>(domainResponse) || [];
        const capabilityData = unwrapResponseData<any>(capabilityResponse) || [];
        const jsonCapabilities = (
          Array.isArray(capabilityData) ? capabilityData : capabilityData.list || []
        ).filter(
          (item: CapabilityOption) => item.enabled !== false && item.supportJsonMode === true,
        );
        setDatabaseOptions(databases.filter((item) => item.hasUsePermission !== false));
        setDomainOptions(flattenDomainOptions(domains));
        setCapabilities(jsonCapabilities);

        if (initialGapId && gapResponse) {
          const gap = unwrapResponseData<SemanticGapItem>(gapResponse);
          form.setFieldsValue({
            businessGoal: gap.question || gap.normalizedQuestion || '',
            dataSourceId: gap.dataSourceId,
            domainId: gap.domainId,
            includeSampleData: false,
          });
          if (gap.dataSourceId) {
            const database = databases.find((item) => item.id === gap.dataSourceId);
            await loadDatabaseChildren(gap.dataSourceId, database?.type);
          }
        }
      } catch (error) {
        if (active) message.error(getRequestErrorText(error));
      } finally {
        if (active) setInitializing(false);
      }
    };
    void initialize();
    return () => {
      active = false;
      requestRef.current += 1;
    };
  }, [form, initialGapId, open]);

  return {
    initializing,
    metadataLoading,
    databaseOptions,
    domainOptions,
    capabilities,
    catalogOptions,
    databaseNameOptions,
    tableOptions,
    catalogRequired,
    loadDatabaseChildren,
    loadDatabaseNames,
    loadTables,
  };
}
