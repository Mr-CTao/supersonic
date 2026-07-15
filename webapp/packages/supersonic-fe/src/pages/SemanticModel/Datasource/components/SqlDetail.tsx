/**
 * 模型 SQL 编辑与双重校验组件。
 *
 * 职责：编辑模型 SQL、受控执行数据源预览，并使用运行时同源 Calcite 配置展示语义编译结果。
 * 并发说明：请求序号与 AbortController 共同阻止迟到响应覆盖新 SQL；按钮 loading 防止重复提交，
 * 卸载时取消请求和解锁定时器。
 */
import React, { useState, useEffect, useRef } from 'react';
import { Button, Table, message, Tooltip, Space, Dropdown, Tag, Spin } from 'antd';
import SplitPane from 'react-split-pane';
import Pane from 'react-split-pane/lib/Pane';
import { useModel } from '@umijs/max';
import { format } from 'sql-formatter';
import {
  FullscreenOutlined,
  WarningOutlined,
  EditOutlined,
  PlayCircleTwoTone,
  SwapOutlined,
  PlayCircleOutlined,
  CloudServerOutlined,
  ApiOutlined,
  AimOutlined,
  CheckCircleFilled,
  CloseCircleFilled,
  DoubleLeftOutlined,
  DoubleRightOutlined,
  ExclamationCircleFilled,
  InfoCircleOutlined,
  LoadingOutlined,
  MinusCircleFilled,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { isFunction } from 'lodash';
import FullScreen from '@/components/FullScreen';
import SqlEditor from '@/components/SqlEditor';
import type { TaskResultItem, TaskResultColumn } from '../data';
import {
  executeSql,
  validateModelSql,
  getModelHealth,
  revalidateModel,
  ModelHealth,
  SemanticDiagnostic,
  SemanticValidationCheck,
  SemanticValidationResult,
} from '@/pages/SemanticModel/service';

import SqlParams from './SqlParams';
import {
  countBlockingChecks,
  DiagnosticTone,
  getHealthStatusPresentation,
  getValidationStatusPresentation,
  isLatestValidation,
  mergeValidationResult,
  partitionValidationChecks,
} from './validationUtils';
import styles from '../style.less';
import 'ace-builds/src-min-noconflict/ext-searchbox';
import 'ace-builds/src-min-noconflict/theme-sqlserver';
import 'ace-builds/src-min-noconflict/theme-monokai';
import 'ace-builds/src-min-noconflict/mode-sql';
import { IDataSource, ISemantic } from '../../data';

export type DataSourceSubmitData = {
  sql: string;
  databaseId: number;
  columns: any[];
  sqlParams: any[];
};

type IProps = {
  dataSourceItem: IDataSource.IDataSourceItem;
  onUpdateSql?: (sql: string) => void;
  sql?: string;
  onSubmitSuccess?: (dataSourceInfo: DataSourceSubmitData) => void;
};

type ResultTableItem = Record<string, any>;

type ResultColItem = {
  key: string;
  title: string;
  dataIndex: string;
  width: number;
  ellipsis: { showTitle: boolean };
};

type ScreenSize = 'small' | 'middle' | 'large';

type DatabaseItem = {
  label: string;
  key: number;
};

type HealthItem = {
  key: string;
  label: string;
  status?:
    | ModelHealth['compileStatus']
    | ModelHealth['schemaCacheStatus']
    | ModelHealth['dictionaryStatus']
    | ModelHealth['embeddingStatus'];
};

const RESULT_COLUMN_MIN_WIDTH = 120;
const RESULT_COLUMN_MAX_WIDTH = 320;
const RESULT_COLUMN_HORIZONTAL_PADDING = 32;

const VALIDATION_CHECK_LABELS: Record<SemanticValidationCheck['type'], string> = {
  SOURCE_DATABASE: '数据源执行',
  SEMANTIC_COMPILER: '语义编译',
  FIELD_EXPRESSION: '字段表达式',
  SEMANTIC_QUERY_SMOKE: '语义冒烟',
};

/** 根据被推迟的检查类型生成与当前验证阶段一致的说明，避免把“未执行”误读为失败。 */
const getDeferredValidationHint = (checks: SemanticValidationCheck[]): string => {
  const hints: string[] = [];
  const completeModelChecks = checks.filter(
    (check) => check.type === 'FIELD_EXPRESSION' || check.type === 'SEMANTIC_QUERY_SMOKE',
  );
  if (checks.some((check) => check.type === 'SOURCE_DATABASE')) {
    hints.push('本次健康刷新未重复执行数据源查询');
  }
  if (completeModelChecks.length > 0) {
    const labels = completeModelChecks.map((check) => VALIDATION_CHECK_LABELS[check.type]);
    hints.push(`${labels.join('、')}将在完整模型验证阶段执行`);
  }
  if (checks.some((check) => check.type === 'SEMANTIC_COMPILER')) {
    hints.push('语义编译本次未执行');
  }
  return `${hints.join('；')}。以上不影响当前阶段的校验结果。`;
};

// react-split-pane 的旧版类型未声明 React 18 children；运行时组件能力完整，集中兼容避免散落忽略注释。
const CompatibleSplitPane = SplitPane as React.ComponentType<any>;

const SqlDetail: React.FC<IProps> = ({
  dataSourceItem,
  onSubmitSuccess,
  sql = '',
  onUpdateSql,
}) => {
  const databaseModel = useModel('SemanticModel.databaseData');
  const { databaseConfigList } = databaseModel;

  const [resultTable, setResultTable] = useState<ResultTableItem[]>([]);
  const [resultTableLoading, setResultTableLoading] = useState(false);
  const [resultCols, setResultCols] = useState<ResultColItem[]>([]);
  const [pagination, setPagination] = useState({
    current: 1,
    pageSize: 20,
    total: 0,
  });
  const [dataBaseItems, setDataBaseItems] = useState<DatabaseItem[]>([]);
  const [currentDatabaseItem, setCurrentDatabaseItem] = useState<DatabaseItem>();

  const [tableScroll, setTableScroll] = useState({
    scrollToFirstRowOnChange: true,
    x: 'max-content',
    y: 200,
  });

  const [runState, setRunState] = useState<boolean | undefined>();
  const [validationResult, setValidationResult] = useState<SemanticValidationResult>();
  const [validationLoading, setValidationLoading] = useState(false);
  const [modelHealth, setModelHealth] = useState<ModelHealth>();
  const [healthLoading, setHealthLoading] = useState(false);
  const [diagnosticPanelVisible, setDiagnosticPanelVisible] = useState(true);

  const [taskLog, setTaskLog] = useState('');
  const [isSqlExcLocked, setIsSqlExcLocked] = useState<boolean>(false);
  const [screenSize, setScreenSize] = useState<ScreenSize>('middle');

  const [isSqlIdeFullScreen, setIsSqlIdeFullScreen] = useState<boolean>(false);
  const [isSqlResFullScreen, setIsSqlResFullScreen] = useState<boolean>(false);

  const resultInnerWrap = useRef<HTMLDivElement>();
  const sqlEditorRef = useRef<any>();
  const validationSequenceRef = useRef(0);
  const validationAbortRef = useRef<AbortController>();
  const unlockTimerRef = useRef<ReturnType<typeof setTimeout>>();

  const [editorSize, setEditorSize] = useState<number>(0);
  const DEFAULT_FULLSCREEN_TOP = 0;

  const [partialSql, setPartialSql] = useState('');
  const [isPartial, setIsPartial] = useState<boolean>(false);
  const [isRight, setIsRight] = useState<boolean>(false);

  const [variableCollapsed, setVariableCollapsed] = useState<boolean>(true);
  const [sqlParams, setSqlParams] = useState<IDataSource.ISqlParamsItem[]>([]);

  const [scriptColumns, setScriptColumns] = useState<TaskResultColumn[]>([]);

  useEffect(() => {
    const list = databaseConfigList.map((item: ISemantic.IDatabaseItem) => {
      return {
        label: item.name,
        key: item.id,
        disabled: !item.hasUsePermission,
      };
    });
    setDataBaseItems(list);
    let targetDataBase = list[0];
    if (dataSourceItem?.id) {
      const { databaseId } = dataSourceItem;
      const target = list.find((item) => item.key === databaseId);
      if (target) {
        targetDataBase = target;
      }
    }
    setCurrentDatabaseItem(targetDataBase);
  }, [dataSourceItem, databaseConfigList]);

  useEffect(() => {
    setSqlParams(dataSourceItem?.modelDetail?.sqlVariables || []);
  }, [dataSourceItem]);

  useEffect(() => {
    const modelId = dataSourceItem?.id;
    if (!modelId) {
      setModelHealth(undefined);
      return;
    }
    let active = true;
    setHealthLoading(true);
    getModelHealth(modelId)
      .then((response) => {
        if (active) setModelHealth(response?.data || response);
      })
      .catch(() => {
        if (active) setModelHealth(undefined);
      })
      .finally(() => {
        if (active) setHealthLoading(false);
      });
    return () => {
      active = false;
    };
  }, [dataSourceItem?.id]);

  useEffect(() => {
    setRunState(undefined);
    setValidationResult(undefined);
    validationSequenceRef.current += 1;
    validationAbortRef.current?.abort();
  }, [currentDatabaseItem, sql]);

  useEffect(() => {
    // 阻断结果必须主动呈现，避免用户在收起侧栏后只看到“完成”按钮不可用却不知道原因。
    if (validationResult?.overallStatus === 'BLOCKING') {
      setDiagnosticPanelVisible(true);
    }
  }, [validationResult]);

  useEffect(() => {
    return () => {
      validationAbortRef.current?.abort();
      if (unlockTimerRef.current) {
        clearTimeout(unlockTimerRef.current);
      }
    };
  }, []);

  function creatCalcItem(key: string, data: string) {
    const line = document.createElement('div'); // 需要每条数据一行，这样避免数据换行的时候获得的宽度不准确
    const child = document.createElement('span');
    child.classList.add(`resultCalcItem_${key}`);
    child.innerText = data;
    line.appendChild(child);
    return line;
  }

  const handleVariable = () => {
    const collapsedValue = !variableCollapsed;
    setVariableCollapsed(collapsedValue);
  };

  // 计算每列的宽度，通过容器插入文档中动态得到该列数据(包括表头)的最长宽度，设为列宽度，保证每列的数据都能一行展示完
  function getKeyWidthMap(list: TaskResultItem[]): Record<string, number> {
    const widthMap: Record<string, number> = {};
    const container = document.createElement('div');
    container.id = 'resultCalcWrap';
    container.style.position = 'fixed';
    container.style.left = '-99999px';
    container.style.top = '-99999px';
    container.style.width = '19999px';
    container.style.fontSize = '12px';
    list.forEach((item, index) => {
      if (index === 0) {
        Object.keys(item).forEach((key, keyIndex) => {
          // 因为key可能存在一些特殊字符，导致querySelectorAll获取的时候报错，所以用keyIndex(而不用key)拼接className
          container.appendChild(creatCalcItem(`${keyIndex}`, key));
          container.appendChild(creatCalcItem(`${keyIndex}`, `${item[key]}`));
        });
      } else {
        Object.keys(item).forEach((key, keyIndex) => {
          container.appendChild(creatCalcItem(`${keyIndex}`, `${item[key]}`));
        });
      }
    });
    document.body.appendChild(container);
    Object.keys(list[0]).forEach((key, keyIndex) => {
      // 因为key可能存在一些特殊字符，导致querySelectorAll获取的时候报错，所以用keyIndex(而不用key)拼接className
      const widthArr = Array.from(container.querySelectorAll(`.resultCalcItem_${keyIndex}`)).map(
        (node: any) => node.offsetWidth,
      );
      widthMap[key] = Math.max(...widthArr);
    });
    document.body.removeChild(container);
    return widthMap;
  }

  const updateResultCols = (list: TaskResultItem[], columns: TaskResultColumn[]) => {
    if (list.length) {
      const widthMap = getKeyWidthMap(list);
      const cols = columns.map(({ columnName }) => {
        const measuredWidth = (widthMap[columnName] as number) + RESULT_COLUMN_HORIZONTAL_PADDING;
        return {
          key: columnName,
          title: columnName,
          dataIndex: columnName,
          // 设置稳定的最小宽度并限制超长字段，避免短字段逐字换行或单列撑满结果区。
          width: Math.min(
            Math.max(measuredWidth, RESULT_COLUMN_MIN_WIDTH),
            RESULT_COLUMN_MAX_WIDTH,
          ),
          ellipsis: { showTitle: true },
        };
      });
      setResultCols(cols);
    }
  };

  const fetchTaskResult = (params: any, columnData = []) => {
    setResultTable(
      params.resultList.map((item: Record<string, string>, index: number) => {
        return {
          ...item,
          index,
        };
      }),
    );
    setPagination({
      current: 1,
      pageSize: 20,
      total: params.resultList.length,
    });
    setScriptColumns(columnData);
    updateResultCols(params.resultList, columnData);
  };

  const changePaging = (paging: Pagination) => {
    setPagination({
      ...pagination,
      ...paging,
    });
  };

  const onSqlChange = (sqlString: string) => {
    if (onUpdateSql && isFunction(onUpdateSql)) {
      onUpdateSql(sqlString);
    }
  };

  const formatSQL = () => {
    const sqlvalue = format(sql);
    if (onUpdateSql && isFunction(onUpdateSql)) {
      onUpdateSql(sqlvalue);
    }
    // eslint-disable-next-line no-param-reassign
    sql = sqlvalue;
  };

  const separateSql = async (value: string) => {
    if (!currentDatabaseItem?.key) {
      return;
    }
    const sequence = validationSequenceRef.current + 1;
    validationSequenceRef.current = sequence;
    validationAbortRef.current?.abort();
    const controller = new AbortController();
    validationAbortRef.current = controller;
    setResultTableLoading(true);
    setValidationLoading(true);
    const [sourceResponse, compilerResponse] = await Promise.allSettled([
      executeSql({ sql: value, id: currentDatabaseItem.key, sqlVariables: sqlParams }),
      validateModelSql(
        {
          databaseId: currentDatabaseItem.key,
          modelId: dataSourceItem?.id,
          modelName: dataSourceItem?.name,
          sql: value,
          sqlVariables: sqlParams,
          executeSource: false,
        },
        controller.signal,
      ),
    ]);
    if (!isLatestValidation(sequence, validationSequenceRef.current, controller.signal.aborted)) {
      return;
    }
    setResultTableLoading(false);
    setValidationLoading(false);

    const source = sourceResponse.status === 'fulfilled' ? sourceResponse.value : undefined;
    const sourcePassed = source?.code === 200;
    if (sourcePassed) {
      const { data } = source;
      const columnData = (data.columns || []).map((item: any) => {
        return {
          ...item,
          columnName: item.nameEn,
        };
      });
      fetchTaskResult(data, columnData);
      setRunState(true);
    } else {
      setRunState(false);
      setTaskLog(source?.msg || '数据源执行失败，请检查连接或 SQL');
    }

    const compiler = compilerResponse.status === 'fulfilled' ? compilerResponse.value : undefined;
    const compilerResult: SemanticValidationResult | undefined =
      compiler?.code === 200 ? compiler.data : undefined;
    setValidationResult(
      mergeValidationResult(
        sourcePassed,
        source?.msg || '数据源执行失败，请检查连接或 SQL',
        compilerResult,
      ),
    );
  };

  const onSelect = (value: string) => {
    if (value) {
      setIsPartial(true);
      setPartialSql(value);
    } else {
      setIsPartial(false);
    }
  };

  const excuteScript = () => {
    if (!sql) {
      return message.error('SQL查询语句不可以为空！');
    }
    if (isSqlExcLocked) {
      return message.warning('请间隔5s再重新执行！');
    }
    const waitTime = 5000;
    setIsSqlExcLocked(true); // 加锁，5s后再解锁
    unlockTimerRef.current = setTimeout(() => {
      setIsSqlExcLocked(false);
    }, waitTime);

    return isPartial ? separateSql(partialSql) : separateSql(sql);
  };

  // const showDataSetModal = () => {
  //   setDataSourceModalVisible(true);
  // };

  // const startCreatDataSource = async () => {
  //   showDataSetModal();
  // };

  const updateNormalResScroll = () => {
    const node = resultInnerWrap?.current;
    if (node) {
      setTableScroll({
        scrollToFirstRowOnChange: true,
        x: 'max-content',
        y: node.clientHeight - 120,
      });
    }
  };

  const updateFullScreenResScroll = () => {
    const windowHeight = window.innerHeight;
    const paginationHeight = 96;
    setTableScroll({
      scrollToFirstRowOnChange: true,
      x: 'max-content',
      y: windowHeight - DEFAULT_FULLSCREEN_TOP - paginationHeight - 30, // 30为退出全屏按钮的高度
    });
  };

  const handleFullScreenSqlIde = () => {
    setIsSqlIdeFullScreen(true);
  };

  const handleNormalScreenSqlIde = () => {
    setIsSqlIdeFullScreen(false);
  };

  const handleFullScreenSqlResult = () => {
    setIsSqlResFullScreen(true);
  };

  const handleNormalScreenSqlResult = () => {
    setIsSqlResFullScreen(false);
  };

  const handleThemeChange = () => {
    setIsRight(!isRight);
  };

  const renderResult = () => {
    if (runState === false) {
      return (
        <>
          {
            <div className={styles.taskFailed}>
              <WarningOutlined className={styles.resultFailIcon} />
              任务执行失败
            </div>
          }
          <pre className={styles.sqlResultLog}>{taskLog}</pre>
        </>
      );
    }

    if (runState) {
      return (
        <>
          <div className={styles.detail} />
          <Table<TaskResultItem>
            loading={resultTableLoading}
            dataSource={resultTable}
            columns={resultCols}
            onChange={changePaging}
            pagination={pagination}
            scroll={tableScroll}
            className={styles.resultTable}
            rowClassName="resultTableRow"
            rowKey="index"
          />
        </>
      );
    }
    return <div className={styles.sqlResultContent}>请点击左侧任务列表查看执行详情</div>;
  };

  /** 根据语义色渲染统一状态图标，状态变化不只依赖颜色表达。 */
  const renderStatusIcon = (tone: DiagnosticTone) => {
    if (tone === 'success') return <CheckCircleFilled className={styles.statusSuccess} />;
    if (tone === 'error') return <CloseCircleFilled className={styles.statusError} />;
    if (tone === 'warning') return <ExclamationCircleFilled className={styles.statusWarning} />;
    if (tone === 'processing') return <LoadingOutlined spin className={styles.statusProcessing} />;
    return <MinusCircleFilled className={styles.statusDefault} />;
  };

  /** 将编辑器滚动并聚焦到服务端返回的 SQL 错误位置。 */
  const locateDiagnostic = (diagnostic?: SemanticDiagnostic) => {
    if (!diagnostic?.line || !sqlEditorRef.current) return;
    const column = Math.max((diagnostic.column || 1) - 1, 0);
    sqlEditorRef.current.gotoLine(diagnostic.line, column, true);
    sqlEditorRef.current.focus();
  };

  /** 渲染当前阶段已执行的检查，并将后续阶段检查收敛为弱提示。 */
  const renderValidation = () => {
    const checks = validationResult?.checks || [];
    const { executedChecks, deferredChecks } = partitionValidationChecks(checks);
    const overallPresentation = validationResult
      ? getValidationStatusPresentation(validationResult.overallStatus)
      : undefined;
    return (
      <section className={styles.diagnosticSection} aria-labelledby="sql-validation-title">
        <div className={styles.diagnosticSectionHeader}>
          <div>
            <div id="sql-validation-title" className={styles.diagnosticSectionTitle}>
              SQL 双重校验
            </div>
            <div className={styles.diagnosticSectionSubtitle}>数据源执行与语义编译独立检查</div>
          </div>
          {validationLoading ? (
            <Tag color="processing">校验中</Tag>
          ) : overallPresentation ? (
            <Tag color={overallPresentation.tone}>{overallPresentation.label}</Tag>
          ) : (
            <Tag>等待运行</Tag>
          )}
        </div>
        {validationLoading && checks.length === 0 ? (
          <div className={styles.diagnosticLoading}>
            <Spin size="small" /> 正在并行检查数据源与语义模型…
          </div>
        ) : checks.length > 0 ? (
          <div className={styles.validationList}>
            {executedChecks.map((check) => {
              const presentation = getValidationStatusPresentation(check.status);
              const diagnostic = check.diagnostic;
              return (
                <div
                  key={check.type}
                  className={`${styles.validationItem} ${
                    check.status === 'BLOCKING' ? styles.validationItemBlocking : ''
                  }`}
                >
                  <div className={styles.validationItemHeader}>
                    <Space size={8}>
                      {renderStatusIcon(presentation.tone)}
                      <span className={styles.validationItemTitle}>
                        {VALIDATION_CHECK_LABELS[check.type]}
                      </span>
                    </Space>
                    <Tag color={presentation.tone}>{presentation.label}</Tag>
                  </div>
                  <div className={styles.validationMessage}>{check.message || '检查已完成'}</div>
                  {diagnostic?.line ? (
                    <div className={styles.validationLocation}>
                      <span>
                        第 {diagnostic.line} 行，第 {diagnostic.column || 1} 列
                        {diagnostic.token ? ` · ${diagnostic.token}` : ''}
                      </span>
                      <Button
                        type="link"
                        size="small"
                        icon={<AimOutlined />}
                        onClick={() => locateDiagnostic(diagnostic)}
                        title={`定位到第 ${diagnostic.line} 行`}
                        aria-label={`定位到 SQL 第 ${diagnostic.line} 行第 ${
                          diagnostic.column || 1
                        } 列`}
                      >
                        定位
                      </Button>
                    </div>
                  ) : null}
                  {diagnostic?.suggestion ? (
                    <div className={styles.validationSuggestion}>{diagnostic.suggestion}</div>
                  ) : null}
                </div>
              );
            })}
            {deferredChecks.length > 0 ? (
              <div className={styles.validationDeferredNote}>
                <InfoCircleOutlined aria-hidden="true" />
                <span>{getDeferredValidationHint(deferredChecks)}</span>
              </div>
            ) : null}
          </div>
        ) : (
          <div className={styles.diagnosticEmpty}>
            运行 SQL 后，这里会显示数据源执行与语义编译结果。
          </div>
        )}
      </section>
    );
  };

  /** 对已保存模型重新执行同源编译校验，并刷新健康摘要。 */
  const handleRevalidate = async () => {
    if (!dataSourceItem?.id || healthLoading) return;
    setHealthLoading(true);
    try {
      const validationResponse = await revalidateModel(dataSourceItem.id);
      setValidationResult(validationResponse?.data || validationResponse);
      const healthResponse = await getModelHealth(dataSourceItem.id);
      setModelHealth(healthResponse?.data || healthResponse);
    } catch (error: any) {
      message.error(error?.message || '重新校验失败');
    } finally {
      setHealthLoading(false);
    }
  };

  /** 渲染保存后编译、缓存和知识索引状态；FAILED 不被伪装成完全可用。 */
  const renderModelHealth = () => {
    if (!dataSourceItem?.id) return null;
    const healthItems: HealthItem[] = [
      { key: 'compile', label: '语义编译', status: modelHealth?.compileStatus || 'SKIPPED' },
      {
        key: 'schema',
        label: 'Schema 缓存',
        status: modelHealth?.schemaCacheStatus || 'UNKNOWN',
      },
      { key: 'dictionary', label: '业务词典', status: modelHealth?.dictionaryStatus || 'UNKNOWN' },
      {
        key: 'embedding',
        label: '向量索引',
        status: modelHealth?.embeddingStatus || 'UNKNOWN',
      },
    ];
    return (
      <section className={styles.diagnosticSection} aria-labelledby="model-health-title">
        <div className={styles.diagnosticSectionHeader}>
          <div>
            <div id="model-health-title" className={styles.diagnosticSectionTitle}>
              模型健康状态
            </div>
            <div className={styles.diagnosticSectionSubtitle}>保存后的缓存与知识索引状态</div>
          </div>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={healthLoading}
            disabled={healthLoading}
            onClick={handleRevalidate}
            title="重新校验模型 SQL"
            aria-label="重新校验模型 SQL 并刷新健康状态"
          >
            重新校验
          </Button>
        </div>
        <div className={styles.healthGrid}>
          {healthItems.map((item) => {
            const presentation = getHealthStatusPresentation(item.status);
            return (
              <div key={item.key} className={styles.healthItem}>
                <div className={styles.healthItemLabel}>{item.label}</div>
                <Space size={6}>
                  {renderStatusIcon(presentation.tone)}
                  <span className={styles.healthItemStatus}>{presentation.label}</span>
                </Space>
              </div>
            );
          })}
        </div>
        {modelHealth?.lastErrorCode ? (
          <div className={styles.healthNotice}>
            最近问答失败：{modelHealth.lastErrorCode}
            {modelHealth.lastTraceId ? ` · traceId=${modelHealth.lastTraceId}` : ''}
          </div>
        ) : null}
        {modelHealth?.dictionaryStatus === 'FAILED' || modelHealth?.embeddingStatus === 'FAILED' ? (
          <div className={styles.healthNotice}>模型已保存，但知识索引刷新失败。</div>
        ) : null}
      </section>
    );
  };

  /** 将校验与健康信息收敛到单一可折叠侧栏，避免多个 Alert 挤压编辑器。 */
  const renderDiagnosticPanel = () => {
    const blockingCount = countBlockingChecks(validationResult);
    const shouldRenderPanel = Boolean(dataSourceItem?.id || validationResult || validationLoading);
    if (!shouldRenderPanel) return null;
    if (!diagnosticPanelVisible) {
      return (
        <Tooltip title={blockingCount ? `${blockingCount} 项阻断，点击查看` : '展开校验与健康'}>
          <Button
            className={`${styles.diagnosticPanelToggle} ${
              blockingCount ? styles.diagnosticPanelToggleBlocking : ''
            }`}
            icon={<DoubleLeftOutlined />}
            onClick={() => setDiagnosticPanelVisible(true)}
            title="展开校验与健康"
            aria-label={
              blockingCount ? `展开校验与健康，当前有 ${blockingCount} 项阻断` : '展开校验与健康'
            }
          />
        </Tooltip>
      );
    }
    return (
      <aside className={styles.diagnosticPanel} aria-label="模型 SQL 校验与健康状态">
        <div className={styles.diagnosticPanelHeader}>
          <Space size={8}>
            <SafetyCertificateOutlined className={styles.diagnosticPanelIcon} />
            <span className={styles.diagnosticPanelTitle}>校验与健康</span>
            {blockingCount > 0 ? <Tag color="error">{blockingCount} 项阻断</Tag> : null}
          </Space>
          <Tooltip title="收起侧栏，扩大 SQL 编辑区">
            <Button
              type="text"
              size="small"
              icon={<DoubleRightOutlined />}
              onClick={() => setDiagnosticPanelVisible(false)}
              title="收起校验与健康侧栏"
              aria-label="收起校验与健康侧栏"
            />
          </Tooltip>
        </div>
        <div className={styles.diagnosticPanelBody} aria-live="polite">
          {renderValidation()}
          {renderModelHealth()}
        </div>
      </aside>
    );
  };

  // 更新任务结果列表的高度，使其撑满容器
  useEffect(() => {
    if (isSqlResFullScreen) {
      updateFullScreenResScroll();
    } else {
      updateNormalResScroll();
    }
  }, [resultTable, isSqlResFullScreen]);

  useEffect(() => {
    const windowHeight = window.innerHeight;
    let size: ScreenSize = 'small';
    if (windowHeight > 1100) {
      size = 'large';
    } else if (windowHeight > 850) {
      size = 'middle';
    }
    setScreenSize(size);
  }, []);

  return (
    <>
      <div className={styles.sqlOprBar}>
        <div className={styles.sqlOprBarLeftBox}>
          <Tooltip title="数据类型">
            <Dropdown
              menu={{
                items: dataBaseItems,
                onClick: (e) => {
                  const value = e.key;
                  const target: any = dataBaseItems.filter((item: any) => {
                    return item.key === Number(value);
                  })[0];
                  if (target) {
                    setCurrentDatabaseItem(target);
                  }
                },
              }}
              placement="bottom"
            >
              <Button className={styles.databaseSelector}>
                <Space>
                  <CloudServerOutlined className={styles.sqlOprIcon} style={{ marginRight: 0 }} />
                  <span>{currentDatabaseItem?.label}</span>
                </Space>
              </Button>
            </Dropdown>
          </Tooltip>
          <Tooltip title="全屏">
            <Button
              type="text"
              className={styles.sqlIconButton}
              icon={<FullscreenOutlined />}
              onClick={handleFullScreenSqlIde}
              title="全屏编辑 SQL"
              aria-label="全屏编辑 SQL"
            />
          </Tooltip>
          <Tooltip title="格式化SQL语句">
            <Button
              type="text"
              className={styles.sqlIconButton}
              icon={<EditOutlined />}
              onClick={formatSQL}
              title="格式化 SQL 语句"
              aria-label="格式化 SQL 语句"
            />
          </Tooltip>
          <Tooltip title="动态变量">
            <Button
              type="text"
              className={styles.sqlIconButton}
              icon={<ApiOutlined />}
              onClick={handleVariable}
              title={variableCollapsed ? '展开动态变量' : '收起动态变量'}
              aria-label={variableCollapsed ? '展开动态变量' : '收起动态变量'}
            />
          </Tooltip>
          <Tooltip title="改变主题">
            <Button
              type="text"
              className={styles.sqlIconButton}
              icon={<SwapOutlined />}
              onClick={handleThemeChange}
              title="切换 SQL 编辑器主题"
              aria-label="切换 SQL 编辑器主题"
            />
          </Tooltip>
          <Tooltip title="执行脚本">
            <Button
              type="primary"
              icon={
                isPartial ? '' : isSqlExcLocked ? <PlayCircleOutlined /> : <PlayCircleTwoTone />
              }
              size={'small'}
              className={
                isSqlExcLocked ? `${styles.disableIcon} ${styles.sqlOprBtn}` : styles.sqlOprBtn
              }
              onClick={excuteScript}
              loading={validationLoading}
              disabled={validationLoading || isSqlExcLocked}
              title={isPartial ? '运行选中的 SQL' : '运行 SQL 并执行双重校验'}
              aria-label={isPartial ? '运行选中的 SQL' : '运行 SQL 并执行双重校验'}
            >
              {isPartial ? '部分运行' : '运行'}
            </Button>
          </Tooltip>
        </div>
      </div>
      <CompatibleSplitPane
        split="horizontal"
        onChange={(size: number) => {
          setEditorSize(size);
        }}
      >
        <Pane initialSize={'500px'}>
          <div className={styles.sqlMain}>
            <div className={styles.sqlEditorWrapper}>
              <SqlEditor
                value={sql}
                isFullScreen={isSqlIdeFullScreen}
                triggerBackToNormal={handleNormalScreenSqlIde}
                // theme="monokai"
                isRightTheme={isRight}
                sizeChanged={editorSize}
                editorConfig={{
                  onLoad: (editor) => {
                    sqlEditorRef.current = editor;
                  },
                }}
                onSqlChange={onSqlChange}
                onSelect={onSelect}
              />
            </div>
            {renderDiagnosticPanel()}
            <div className={variableCollapsed ? styles.hideSqlParams : styles.sqlParams}>
              <SqlParams
                value={sqlParams}
                onChange={(params) => {
                  setSqlParams(params);
                }}
              />
            </div>
          </div>
        </Pane>
        <div className={`${styles.sqlBottmWrap} ${screenSize}`}>
          <div className={styles.sqlResultWrap}>
            <div className={styles.sqlToolBar}>
              <Tooltip
                title={
                  !runState
                    ? '请先运行 SQL'
                    : validationResult?.overallStatus !== 'PASSED'
                    ? '请先处理阻断项并通过双重校验'
                    : ''
                }
              >
                <span>
                  <Button
                    className={styles.sqlToolBtn}
                    type="primary"
                    onClick={() => {
                      onSubmitSuccess?.({
                        columns: scriptColumns,
                        databaseId: currentDatabaseItem?.key || 0,
                        sql,
                        sqlParams,
                      });
                    }}
                    loading={validationLoading}
                    disabled={!runState || validationResult?.overallStatus !== 'PASSED'}
                  >
                    完成
                  </Button>
                </span>
              </Tooltip>
              <Button
                className={styles.sqlToolBtn}
                type="primary"
                onClick={handleFullScreenSqlResult}
                disabled={!runState}
              >
                全屏查看
              </Button>
            </div>
            <div
              className={styles.sqlResultPane}
              ref={resultInnerWrap as React.MutableRefObject<HTMLDivElement | null>}
            >
              <FullScreen
                isFullScreen={isSqlResFullScreen}
                top={`${DEFAULT_FULLSCREEN_TOP}px`}
                triggerBackToNormal={handleNormalScreenSqlResult}
              >
                {renderResult()}
              </FullScreen>
            </div>
          </div>
        </div>
      </CompatibleSplitPane>
    </>
  );
};

export default SqlDetail;
