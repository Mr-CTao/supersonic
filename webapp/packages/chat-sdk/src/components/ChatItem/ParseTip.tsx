/**
 * Chat BI 解析状态与结构化诊断卡。
 *
 * 职责：普通用户显示安全可执行消息；开发者按权限展开阶段、位置、token、建议和 traceId。
 * 安全说明：所有文本经 React 转义，复制内容长度受限，不使用 HTML 注入。
 */
import React, { ReactNode } from 'react';
import {
  ChatContextType,
  DateInfoType,
  EntityInfoType,
  FilterItemType,
  SemanticDiagnosticType,
} from '../../common/type';
import { Button, Collapse, DatePicker, Row, Col, Space, Tag, message } from 'antd';
import { CheckCircleFilled, CloseCircleFilled, ReloadOutlined } from '@ant-design/icons';
import Loading from './Loading';
import FilterItem from './FilterItem';
import MarkDown from '../ChatMsg/MarkDown';
import classNames from 'classnames';
import { isMobile } from '../../utils/utils';
import dayjs, { Dayjs } from 'dayjs';
import quarterOfYear from 'dayjs/plugin/quarterOfYear';
import { prefixCls, getTipNode } from './ParseTipUtils';
import { diagnosticCopyText, diagnosticTitle } from './SemanticDiagnosticUtils';

import 'dayjs/locale/zh-cn';

dayjs.extend(quarterOfYear);
dayjs.locale('zh-cn');

const { RangePicker } = DatePicker;

type Props = {
  parseLoading: boolean;
  parseInfoOptions: ChatContextType[];
  parseTip: string;
  currentParseInfo?: ChatContextType;
  agentId?: number;
  dimensionFilters: FilterItemType[];
  dateInfo: DateInfoType;
  entityInfo: EntityInfoType;
  integrateSystem?: string;
  parseTimeCost?: number;
  isDeveloper?: boolean;
  isSimpleMode?: boolean;
  diagnostic?: SemanticDiagnosticType;
  onSelectParseInfo: (parseInfo: ChatContextType) => void;
  onSwitchEntity: (entityId: string) => void;
  onFiltersChange: (filters: FilterItemType[]) => void;
  onDateInfoChange: (dateRange: any) => void;
  onRefresh: () => void;
  handlePresetClick: any;
};

type RangeValue = [Dayjs, Dayjs];
type RangeKeys = '近7日' | '近14日' | '近30日' | '本周' | '本月' | '上月' | '本季度' | '本年';

const ParseTip: React.FC<Props> = ({
  isSimpleMode = false,
  parseLoading,
  parseInfoOptions,
  parseTip,
  currentParseInfo,
  agentId,
  dimensionFilters,
  dateInfo,
  entityInfo,
  integrateSystem,
  parseTimeCost,
  isDeveloper,
  diagnostic,
  onSelectParseInfo,
  onSwitchEntity,
  onFiltersChange,
  onDateInfoChange,
  onRefresh,
  handlePresetClick,
}) => {
  const ranges: Record<RangeKeys, RangeValue> = {
    近7日: [dayjs().subtract(7, 'day'), dayjs()],
    近14日: [dayjs().subtract(14, 'day'), dayjs()],
    近30日: [dayjs().subtract(30, 'day'), dayjs()],
    本周: [dayjs().startOf('week'), dayjs().endOf('week')],
    本月: [dayjs().startOf('month'), dayjs().endOf('month')],
    上月: [
      dayjs().subtract(1, 'month').startOf('month'),
      dayjs().subtract(1, 'month').endOf('month'),
    ],
    本季度: [dayjs().startOf('quarter'), dayjs().endOf('quarter')], // 使用 quarterOfYear 插件
    本年: [dayjs().startOf('year'), dayjs().endOf('year')],
  };

  const getNode = (tipTitle: ReactNode, tipNode?: ReactNode, failed?: boolean) => {
    return (
      <div className={`${prefixCls}-parse-tip`}>
        <div className={`${prefixCls}-title-bar`}>
          {!failed ? (
            <CheckCircleFilled className={`${prefixCls}-step-icon`} />
          ) : (
            <CloseCircleFilled className={`${prefixCls}-step-error-icon`} />
          )}
          <div className={`${prefixCls}-step-title`}>
            {tipTitle}
            {tipNode === undefined && <Loading />}
          </div>
        </div>
        {(tipNode || tipNode === null) && (
          <div
            className={classNames(
              `${prefixCls}-content-container`,
              tipNode === null && `${prefixCls}-empty-content-container`,
              failed && `${prefixCls}-content-container-failed`
            )}
          >
            {tipNode}
          </div>
        )}
      </div>
    );
  };

  if (parseLoading) {
    return getNode('意图解析中');
  }

  if (diagnostic) {
    const safeCopyText = diagnosticCopyText(diagnostic);
    const developerDetails = isDeveloper ? (
      <Collapse
        size="small"
        items={[
          {
            key: 'diagnostic',
            label: '查看诊断详情',
            children: (
              <Space direction="vertical" size={4}>
                <div>
                  查询理解 <Tag color="success">成功</Tag>
                </div>
                <div>
                  语义查询生成 <Tag color="success">成功</Tag>
                </div>
                <div>
                  模型 SQL 编译 <Tag color="error">失败</Tag>
                </div>
                <div>
                  物理 SQL 执行 <Tag>未执行</Tag>
                </div>
                <div>
                  模型：{diagnostic.modelName || '-'} / {diagnostic.modelId || '-'}
                </div>
                <div>数据集：{diagnostic.dataSetId || '-'}</div>
                <div>
                  阶段 / 错误码：{diagnostic.stage || '-'} / {diagnostic.code || '-'}
                </div>
                <div>
                  位置：{diagnostic.line || '-'}:{diagnostic.column || '-'} /{' '}
                  {diagnostic.token || '-'}
                </div>
                <div>{diagnostic.developerMessage}</div>
                <div>{diagnostic.suggestion}</div>
                <div>traceId：{diagnostic.traceId || '-'}</div>
                <Button
                  aria-label="复制诊断信息"
                  size="small"
                  title="复制诊断信息"
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(safeCopyText);
                      message.success('诊断信息已复制');
                    } catch {
                      message.error('复制失败，请检查浏览器剪贴板权限');
                    }
                  }}
                >
                  复制诊断信息
                </Button>
              </Space>
            ),
          },
        ]}
      />
    ) : null;
    return getNode(
      diagnosticTitle(diagnostic.stage),
      <Space direction="vertical" size={8}>
        <div>{diagnostic.userMessage || '语义模型当前不可用，请联系模型管理员重新校验模型。'}</div>
        <div>
          请联系模型管理员重新校验模型
          {diagnostic.traceId ? `（traceId: ${diagnostic.traceId}）` : ''}
        </div>
        {developerDetails}
      </Space>,
      true
    );
  }

  if (parseTip) {
    return getNode(
      <>
        意图解析失败
        {!!parseTimeCost && isDeveloper && (
          <span className={`${prefixCls}-title-tip`}>(耗时: {parseTimeCost}ms)</span>
        )}
      </>,
      parseTip,
      true
    );
  }

  if (isSimpleMode || parseInfoOptions.length === 0) {
    return null;
  }

  const {
    modelId,
    queryMode,
    properties,
    entity,
    nativeQuery,
    textInfo = '',
  } = currentParseInfo || {};

  const entityAlias = entity?.alias?.[0]?.split('.')?.[0];

  const getFilterContent = (filters: any) => {
    const itemValueClass = `${prefixCls}-tip-item-value`;
    const { startDate, endDate } = dateInfo || {};
    const tipItemOptionClass = classNames(`${prefixCls}-tip-item-option`, {
      [`${prefixCls}-mobile-tip-item-option`]: isMobile,
    });
    return (
      <div className={`${prefixCls}-tip-item-filter-content`}>
        {!!dateInfo && (
          <div className={tipItemOptionClass}>
            <span className={`${prefixCls}-tip-item-filter-name`}>数据时间：</span>
            {nativeQuery ? (
              <span className={itemValueClass}>
                {startDate === endDate ? startDate : `${startDate} ~ ${endDate}`}
              </span>
            ) : (
              <RangePicker
                value={[dayjs(startDate), dayjs(endDate)]}
                onChange={onDateInfoChange}
                format="YYYY-MM-DD"
                renderExtraFooter={() => (
                  <Row gutter={[28, 28]}>
                    {Object.keys(ranges).map(key => (
                      <Col key={key}>
                        <Button
                          size="small"
                          onClick={() => handlePresetClick(ranges[key as RangeKeys])}
                        >
                          {key}
                        </Button>
                      </Col>
                    ))}
                  </Row>
                )}
              />
            )}
          </div>
        )}
        {filters?.map((filter: any, index: number) => (
          <FilterItem
            modelId={modelId!}
            filters={filters}
            filter={filter}
            index={index}
            chatContext={currentParseInfo!}
            entityAlias={entityAlias}
            agentId={agentId}
            integrateSystem={integrateSystem}
            onFiltersChange={onFiltersChange}
            onSwitchEntity={onSwitchEntity}
            key={`${filter.name}_${index}`}
          />
        ))}
      </div>
    );
  };

  const getFiltersNode = () => {
    return (
      <>
        {(!!dateInfo || !!dimensionFilters?.length) && (
          <div className={`${prefixCls}-tip-item`}>
            <div className={`${prefixCls}-tip-item-name`}>筛选条件：</div>
            <div className={`${prefixCls}-tip-item-content`}>
              {getFilterContent(dimensionFilters)}
            </div>
          </div>
        )}
        <Button className={`${prefixCls}-reload`} size="small" onClick={onRefresh}>
          <ReloadOutlined />
          重新查询
        </Button>
      </>
    );
  };

  const { type: agentType } = properties || {};

  const tipNode = (
    <div className={`${prefixCls}-tip`}>
      {getTipNode({ parseInfo: currentParseInfo, dimensionFilters, entityInfo })}
      {!(!!agentType && queryMode !== 'LLM_S2SQL') && getFiltersNode()}
    </div>
  );

  return getNode(
    <div className={`${prefixCls}-title-bar`}>
      <div>
        意图解析
        {!!parseTimeCost && isDeveloper && (
          <span className={`${prefixCls}-title-tip`}>(耗时: {parseTimeCost}ms)</span>
        )}
      </div>
    </div>,
    // isSimpleMode ? <MarkDown markdown={textInfo} /> : queryMode === 'PLAIN_TEXT' ? null : tipNode
    queryMode === 'PLAIN_TEXT' ? null : tipNode
  );
};

export default ParseTip;
