/**
 * 下钻维度选择模块。
 *
 * 职责：
 * - 展示推荐下钻维度；
 * - 当推荐维度过多时通过下拉菜单展示额外维度；
 * - 为动态列表提供稳定 key，并使用 antd v5 的 Dropdown menu API。
 */
import classNames from 'classnames';
import { CLS_PREFIX } from '../../common/constants';
import { DrillDownDimensionType } from '../../common/type';
import { Dropdown } from 'antd';
import { DownOutlined } from '@ant-design/icons';

type Props = {
  drillDownDimension?: DrillDownDimensionType;
  dimensions: DrillDownDimensionType[];
  isSecondDrillDown?: boolean;
  onSelectDimension: (dimension?: DrillDownDimensionType) => void;
  onCancelDrillDown: () => void;
};

const DEFAULT_DIMENSION_COUNT = 5;

const DimensionSection: React.FC<Props> = ({
  drillDownDimension,
  dimensions,
  isSecondDrillDown,
  onSelectDimension,
  onCancelDrillDown,
}) => {
  const prefixCls = `${CLS_PREFIX}-drill-down-dimensions`;

  const defaultDimensions = dimensions.slice(0, DEFAULT_DIMENSION_COUNT);
  const extraDimensions = dimensions.slice(DEFAULT_DIMENSION_COUNT);

  if (defaultDimensions.length === 0) {
    return null;
  }

  return (
    <div className={`${prefixCls}-section`}>
      <div className={`${prefixCls}-title`}>{isSecondDrillDown ? '二级' : '推荐'}下钻维度：</div>
      <div className={`${prefixCls}-content`}>
        {defaultDimensions.map((dimension, index) => {
          const itemNameClass = classNames(`${prefixCls}-content-item-name`, {
            [`${prefixCls}-content-item-active`]: drillDownDimension?.id === dimension.id,
          });
          return (
            <div key={dimension.id}>
              <span
                className={itemNameClass}
                onClick={() => {
                  onSelectDimension(
                    drillDownDimension?.id === dimension.id ? undefined : dimension
                  );
                }}
              >
                {dimension.name}
              </span>
              {index !== defaultDimensions.length - 1 && <span>、</span>}
            </div>
          );
        })}
        {dimensions.length > DEFAULT_DIMENSION_COUNT && (
          <div>
            <span>、</span>
            <Dropdown
              menu={{
                items: extraDimensions.map(dimension => {
                    const itemNameClass = classNames({
                      [`${prefixCls}-menu-item-active`]: drillDownDimension?.id === dimension.id,
                    });
                    return {
                      key: String(dimension.id),
                      label: <span className={itemNameClass}>{dimension.name}</span>,
                    };
                  }),
                onClick: ({ key }) => {
                  const selectedDimension = extraDimensions.find(dimension => String(dimension.id) === key);
                  if (selectedDimension) {
                    onSelectDimension(selectedDimension);
                  }
                },
              }}
            >
              <span>
                <span className={`${prefixCls}-content-item-name`}>更多</span>
                <DownOutlined className={`${prefixCls}-down-arrow`} />
              </span>
            </Dropdown>
          </div>
        )}
        {drillDownDimension && (
          <div className={`${prefixCls}-cancel-drill-down`} onClick={onCancelDrillDown}>
            取消{isSecondDrillDown ? '二级' : ''}下钻
          </div>
        )}
      </div>
    </div>
  );
};

export default DimensionSection;
