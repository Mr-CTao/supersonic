/**
 * 建模草稿路由感知不确定项编辑器。
 *
 * 职责：按服务端严重级别、分类和业务对象分组展示，默认展开阻断项；保留原始业务 key 和处理建议编辑能力。
 *
 * 并发说明：组件无异步请求或共享缓存；所有变更通过不可变数组回传，保存互斥由工作台负责。
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Empty,
  Form,
  Input,
  Row,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import React, { useMemo } from 'react';
import type { DraftUncertainty } from '@/services/semanticModelingDraft';
import { createDraftObjectKey, removeArrayItem, replaceArrayItem } from './editorUtils';
import {
  groupUncertainties,
  normalizeUncertaintySeverity,
  UNCERTAINTY_SEVERITY_ORDER,
  type NormalizedUncertaintySeverity,
  type UncertaintyEntry,
} from './uncertaintyUtils';
import { useStableEditorRowKeys } from './useStableEditorRowKeys';

const { Text } = Typography;

type Props = {
  value?: DraftUncertainty[];
  onChange: (value: DraftUncertainty[]) => void;
};

/** 创建一条必须补充原因和处理建议的新不确定项。 */
function createUncertainty(): DraftUncertainty {
  return {
    key: createDraftObjectKey('uncertainty'),
    modelKey: '',
    objectKey: '',
    field: '',
    category: 'BUSINESS_DEFINITION',
    severity: 'WARNING',
    reason: '',
    suggestion: '',
  };
}

/** 返回与门禁一致的中文严重级文案。 */
function severityLabel(severity: NormalizedUncertaintySeverity) {
  if (severity === 'BLOCKING') return '阻断项';
  if (severity === 'WARNING') return '警告';
  return '提示';
}

/** 说明当前分组为何出现以及建议的解决语义。 */
function resolutionHint(entry: UncertaintyEntry) {
  if (entry.item.category === 'BUSINESS_DEFINITION') {
    return '该问题源自业务口径未确认；请优先回到路由分析的“业务口径”重新确认，不要通过删除条目规避。';
  }
  return '请根据原因和处理建议修改增量对象；只有服务端重新验证通过后门禁才会放行。';
}

/**
 * 渲染按严重级和业务对象分组的不确定项列表。
 *
 * @param props 当前不确定项数组和变更回调。
 * @returns 默认展开阻断项的可访问折叠编辑器。
 * @throws 不抛出异常。
 */
const UncertaintyListEditor: React.FC<Props> = ({ value = [], onChange }) => {
  const fallbackRowKeys = useStableEditorRowKeys(value.length, 'uncertainty-row');
  const grouped = useMemo(() => groupUncertainties(value), [value]);

  /** 按原数组位置更新指定不确定项。 */
  const update = (index: number, patch: Partial<DraftUncertainty>) => {
    onChange(replaceArrayItem(value, index, { ...value[index], ...patch }));
  };

  const collapseItems = UNCERTAINTY_SEVERITY_ORDER.filter((severity) => grouped.has(severity)).map(
    (severity) => {
      const categoryGroups = grouped.get(severity) ?? new Map<string, UncertaintyEntry[]>();
      const count = Array.from(categoryGroups.values()).reduce(
        (total, entries) => total + entries.length,
        0,
      );
      return {
        key: severity,
        label: (
          <Space>
            <Text strong>{severityLabel(severity)}</Text>
            <Tag color={severity === 'BLOCKING' ? 'red' : severity === 'WARNING' ? 'orange' : 'blue'}>
              {count}
            </Tag>
          </Space>
        ),
        children: (
          <Space orientation="vertical" size={12} style={{ width: '100%' }}>
            {Array.from(categoryGroups.entries()).map(([groupKey, entries]) => {
              const [category, target] = groupKey.split('::');
              return (
                <Card key={groupKey} size="small" title={`${category} · ${target}`}>
                  <Alert
                    showIcon
                    type={severity === 'BLOCKING' ? 'error' : severity === 'WARNING' ? 'warning' : 'info'}
                    title="为什么出现 / 如何处理"
                    description={resolutionHint(entries[0])}
                    style={{ marginBottom: 12 }}
                  />
                  <Space orientation="vertical" size={12} style={{ width: '100%' }}>
                    {entries.map(({ item, originalIndex }) => (
                      <Card
                        key={item.key || fallbackRowKeys[originalIndex]}
                        size="small"
                        title={item.objectKey || item.field || item.key || '待定位问题'}
                        extra={
                          <Tooltip title="删除仅用于人工录入错误，不会自动解决服务端门禁">
                            <Button
                              aria-label={`删除不确定项 ${item.key || target}`}
                              danger
                              icon={<DeleteOutlined />}
                              size="small"
                              title="删除不确定项"
                              onClick={() => onChange(removeArrayItem(value, originalIndex))}
                            />
                          </Tooltip>
                        }
                      >
                        <Form layout="vertical">
                          <Row gutter={12}>
                            <Col xs={24} md={8}>
                              <Form.Item label="稳定业务 Key" required>
                                <Input
                                  value={item.key}
                                  onChange={(event) =>
                                    update(originalIndex, { key: event.target.value })
                                  }
                                />
                              </Form.Item>
                            </Col>
                            <Col xs={24} md={8}>
                              <Form.Item label="模型 / 对象引用">
                                <Input
                                  value={item.objectKey || item.modelKey}
                                  onChange={(event) =>
                                    update(originalIndex, { objectKey: event.target.value })
                                  }
                                />
                              </Form.Item>
                            </Col>
                            <Col xs={24} md={8}>
                              <Form.Item label="字段">
                                <Input
                                  value={item.field}
                                  onChange={(event) =>
                                    update(originalIndex, { field: event.target.value })
                                  }
                                />
                              </Form.Item>
                            </Col>
                            <Col xs={24} md={12}>
                              <Form.Item label="分类" required>
                                <Input
                                  value={item.category}
                                  onChange={(event) =>
                                    update(originalIndex, { category: event.target.value })
                                  }
                                />
                              </Form.Item>
                            </Col>
                            <Col xs={24} md={12}>
                              <Form.Item label="严重级" required>
                                <Select
                                  options={UNCERTAINTY_SEVERITY_ORDER.map((option) => ({
                                    label: severityLabel(option),
                                    value: option,
                                  }))}
                                  value={normalizeUncertaintySeverity(item.severity)}
                                  onChange={(nextSeverity) =>
                                    update(originalIndex, { severity: nextSeverity })
                                  }
                                />
                              </Form.Item>
                            </Col>
                          </Row>
                          <Form.Item label="待确认原因" required>
                            <Input.TextArea
                              autoSize={{ minRows: 2, maxRows: 5 }}
                              value={item.reason}
                              onChange={(event) =>
                                update(originalIndex, { reason: event.target.value })
                              }
                            />
                          </Form.Item>
                          <Form.Item label="处理建议">
                            <Input.TextArea
                              autoSize={{ minRows: 2, maxRows: 5 }}
                              value={item.suggestion}
                              onChange={(event) =>
                                update(originalIndex, { suggestion: event.target.value })
                              }
                            />
                          </Form.Item>
                        </Form>
                      </Card>
                    ))}
                  </Space>
                </Card>
              );
            })}
          </Space>
        ),
      };
    },
  );

  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      {value.length ? (
        <Collapse
          aria-label="按严重级分组的不确定项"
          defaultActiveKey={grouped.has('BLOCKING') ? ['BLOCKING'] : []}
          items={collapseItems}
        />
      ) : (
        <Empty description="暂无不确定项" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      )}
      <Button
        icon={<PlusOutlined />}
        type="dashed"
        onClick={() => onChange([...value, createUncertainty()])}
      >
        新增不确定项
      </Button>
    </Space>
  );
};

export default UncertaintyListEditor;
