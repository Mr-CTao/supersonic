/**
 * 语义资产路由推荐摘要卡。
 *
 * 职责：优先展示服务端推荐动作、目标资产、业务影响和可解释理由，不暴露正式资产 ID、原始分数或 Prompt。
 */
import { Card, Descriptions, Space, Tag, Typography } from 'antd';
import React from 'react';
import type { SemanticAssetRouteDetail } from '@/services/semanticAssetRouting';
import { formatRouteDecisionSource, ROUTE_ACTION_TEXT } from './routingUtils';

const { Paragraph, Text } = Typography;

const RESULT_OPERATION_TEXT: Record<string, string> = {
  ORDER_ASC: '升序排序',
  ORDER_DESC: '降序排序',
  TOP_N: '前 N 条',
  PAGINATION: '分页展示',
};

type Props = { route: SemanticAssetRouteDetail };

/** 渲染一组面向业务的能力标签。 */
function renderCapabilityTags(values: string[] | undefined, emptyText: string) {
  if (!values?.length) return <Text type="secondary">{emptyText}</Text>;
  return (
    <Space size={[4, 4]} wrap>
      {values.map((value) => (
        <Tag key={value}>{value}</Tag>
      ))}
    </Space>
  );
}

/**
 * 渲染推荐处理方式卡片。
 *
 * @param props 服务端成功路由详情。
 * @returns 结论优先、技术细节收敛的推荐摘要。
 * @throws 不抛出异常。
 */
const AssetRouteRecommendationCard: React.FC<Props> = ({ route }) => {
  const action = route.recommendedAction;
  const targetName = route.primaryCandidate?.bizName || route.primaryCandidate?.name;
  const missingNames = route.missingCapabilities?.map((item) => item.name) ?? [];
  const operations = route.resultOperations?.map((item) => RESULT_OPERATION_TEXT[item] || item);
  return (
    <Card
      aria-label="系统推荐处理方式"
      title={
        <Space wrap>
          <Text strong>系统推荐</Text>
          {action ? (
            <Tag color="blue">{route.recommendedActionLabel || ROUTE_ACTION_TEXT[action]}</Tag>
          ) : null}
          <Tag>{formatRouteDecisionSource(route.decisionSource)}</Tag>
        </Space>
      }
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        {targetName ? (
          <Text>
            目标资产：<Text strong>{targetName}</Text>
          </Text>
        ) : null}
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="已覆盖">
            {renderCapabilityTags(route.coveredCapabilities, '暂无明确覆盖能力')}
          </Descriptions.Item>
          <Descriptions.Item label="待补充">
            {renderCapabilityTags(missingNames, '无需补充语义能力')}
          </Descriptions.Item>
          <Descriptions.Item label="查询层处理">
            {renderCapabilityTags(operations, '无额外查询层操作')}
          </Descriptions.Item>
        </Descriptions>
        <div>
          <Text strong>为什么这样推荐？</Text>
          <Paragraph style={{ marginBottom: 0, marginTop: 4 }}>
            {route.explanation || '服务端已根据现有资产覆盖、粒度、权限和风险完成裁决。'}
          </Paragraph>
        </div>
      </Space>
    </Card>
  );
};

export default AssetRouteRecommendationCard;
