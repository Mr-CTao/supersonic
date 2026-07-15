/**
 * 语义资产路由候选折叠区。
 *
 * 职责：默认展开主候选，按需展示少量备选和安全技术证据；只显示业务名称、覆盖描述与粒度差异，不展示资产 ID 或原始评分。
 */
import { Card, Collapse, List, Space, Tag, Typography } from 'antd';
import React from 'react';
import type {
  SemanticAssetRouteCandidate,
  SemanticAssetRouteDetail,
} from '@/services/semanticAssetRouting';

const { Text } = Typography;
const MAX_ALTERNATIVE_CANDIDATES = 3;

type Props = { route: SemanticAssetRouteDetail };

/** 渲染单个候选的业务可读摘要。 */
function renderCandidate(candidate: SemanticAssetRouteCandidate) {
  const missing =
    candidate.missingCapabilities?.map((item) => (typeof item === 'string' ? item : item.name)) ??
    [];
  const grain = Array.isArray(candidate.grain) ? candidate.grain.join('、') : candidate.grain;
  return (
    <Card key={candidate.candidateHandle} size="small">
      <Space orientation="vertical" size={8} style={{ width: '100%' }}>
        <Text strong>{candidate.bizName || candidate.name}</Text>
        {candidate.description ? <Text type="secondary">{candidate.description}</Text> : null}
        <Text>覆盖情况：{candidate.coverageDescription || '服务端已完成覆盖度分析'}</Text>
        <Text>业务粒度：{grain || '未明确'}</Text>
        <Space size={[4, 4]} wrap>
          {missing.length ? (
            missing.map((item) => <Tag key={item}>缺少：{item}</Tag>)
          ) : (
            <Tag color="success">完整覆盖</Tag>
          )}
        </Space>
      </Space>
    </Card>
  );
}

/** 将安全技术证据转换为文本，不回退展示原始 JSON。 */
function renderEvidence(route: SemanticAssetRouteDetail) {
  const evidence = route.technicalEvidence ?? [];
  if (!evidence.length) return <Text type="secondary">暂无更多可展示依据</Text>;
  const stableEvidence = Array.from(
    new Map(
      evidence.map((item) => {
        const text =
          typeof item === 'string'
            ? item
            : [item.label, item.summary, item.source].filter(Boolean).join('：');
        const key = typeof item === 'string' ? item : item.key || text;
        return [key, { key, text }] as const;
      }),
    ).values(),
  );
  return (
    <List
      dataSource={stableEvidence}
      renderItem={(item) => (
        <List.Item key={item.key}>{item.text || '已通过服务端规则校验'}</List.Item>
      )}
    />
  );
}

/**
 * 渲染候选与技术依据折叠区。
 *
 * @param props 当前成功路由详情。
 * @returns 主候选、有限备选和技术证据折叠面板。
 * @throws 不抛出异常。
 */
const AssetRouteCandidateCollapse: React.FC<Props> = ({ route }) => {
  const alternatives = (route.alternativeCandidates ?? []).slice(0, MAX_ALTERNATIVE_CANDIDATES);
  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      {route.primaryCandidate ? (
        <Collapse
          defaultActiveKey={['primary']}
          items={[
            { key: 'primary', label: '主候选', children: renderCandidate(route.primaryCandidate) },
          ]}
        />
      ) : null}
      {alternatives.length ? (
        <Collapse
          items={[
            {
              key: 'alternatives',
              label: `查看其他候选（${alternatives.length}）`,
              children: (
                <Space orientation="vertical" size={8} style={{ width: '100%' }}>
                  {alternatives.map(renderCandidate)}
                  {(route.alternativeCandidates?.length ?? 0) > alternatives.length ? (
                    <Text type="secondary">已过滤其余低相关候选，避免信息过载。</Text>
                  ) : null}
                </Space>
              ),
            },
          ]}
        />
      ) : null}
      <Collapse
        items={[{ key: 'evidence', label: '查看分析依据', children: renderEvidence(route) }]}
      />
    </Space>
  );
};

export default AssetRouteCandidateCollapse;
