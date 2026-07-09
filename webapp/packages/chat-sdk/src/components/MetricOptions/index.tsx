/**
 * 聊天推荐指标选项模块。
 *
 * 负责在聊天答案下方展示可切换的相关指标，并处理当前指标高亮、取消选择等交互。
 */
import { CLS_PREFIX } from '../../common/constants';
import { FieldType } from '../../common/type';
import classNames from 'classnames';
import { isMobile } from '../../utils/utils';

type Props = {
  metrics: FieldType[];
  defaultMetric?: FieldType;
  currentMetric?: FieldType;
  isMetricCard?: boolean;
  onSelectMetric: (metric?: FieldType) => void;
};

/**
 * 渲染聊天答案中的推荐指标选项。
 *
 * @param props 推荐指标列表、当前指标、默认指标和选择回调。
 * @returns 推荐指标切换区域；没有可推荐指标时返回 null。
 */
const MetricOptions: React.FC<Props> = ({
  metrics,
  defaultMetric,
  currentMetric,
  isMetricCard,
  onSelectMetric,
}) => {
  const DEFAULT_DIMENSION_COUNT = isMobile ? 2 : 5;
  const prefixCls = `${CLS_PREFIX}-metric-options`;

  const defaultMetrics = metrics
    .filter(metric => metric.id !== defaultMetric?.id)
    .slice(0, DEFAULT_DIMENSION_COUNT);

  const sectionClass = classNames(`${prefixCls}-section`, {
    [`${prefixCls}-metric-card`]: isMetricCard,
  });

  if (!defaultMetrics.length) {
    return null;
  }

  return (
    <div className={prefixCls}>
      <div className={sectionClass}>
        <div className={`${prefixCls}-title`}>推荐相关指标：</div>
        <div className={`${prefixCls}-content`}>
          {defaultMetrics.map((metric, index) => {
            const itemNameClass = classNames(`${prefixCls}-content-item-name`, {
              [`${prefixCls}-content-item-active`]: currentMetric?.id === metric.id,
            });
            return (
              <div key={metric.id ?? metric.bizName ?? metric.name ?? index}>
                <span
                  className={itemNameClass}
                  onClick={() => {
                    onSelectMetric(currentMetric?.id === metric.id ? defaultMetric : metric);
                  }}
                >
                  {metric.name}
                </span>
                {index !== defaultMetrics.length - 1 && <span>、</span>}
              </div>
            );
          })}
        </div>
        {currentMetric?.id !== defaultMetric?.id && (
          <div
            className={`${prefixCls}-cancel-select`}
            onClick={() => {
              onSelectMetric(defaultMetric);
            }}
          >
            取消
          </div>
        )}
      </div>
    </div>
  );
};

export default MetricOptions;
