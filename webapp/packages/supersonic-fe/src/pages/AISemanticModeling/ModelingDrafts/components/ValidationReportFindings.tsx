/**
 * 阶段 4 验证报告 finding 展示区。
 *
 * 职责：只展示服务端已脱敏的 blocking/warning finding，并在逐项明细缺失时保持 fail-closed 提示。
 * 组件不发请求、不执行 SQL、不持有异步状态，因此无需额外并发控制。
 */
import { Alert, Button, List, Space, Typography } from 'antd';
import React from 'react';
import type {
  ModelingValidationItem,
  ModelingValidationReport,
} from '@/services/semanticModelingDraft';
import styles from '../style.less';

const { Text } = Typography;
const MAX_VISIBLE_VALIDATION_ITEMS = 50;

type Props = {
  blockingItems: ModelingValidationItem[];
  reportedBlockingCount: number;
  report: ModelingValidationReport;
  warningItems: ModelingValidationItem[];
  onRepairBlockingItems: () => void;
};

/**
 * 展示阻塞项与警告项。
 *
 * @param props 报告、归一化 finding、计数和带入 AI 修订回调。
 * @returns finding 告警区域。
 * @throws 不抛出异常；缺失消息使用保守占位文案。
 */
const ValidationReportFindings: React.FC<Props> = ({
  blockingItems,
  reportedBlockingCount,
  report,
  warningItems,
  onRepairBlockingItems,
}) => {
  const reportPassed = report.status === 'PASSED' || report.status === 'WARNING';
  return (
    <>
      {blockingItems.length ? (
        <Alert
          className={styles.stage4InlineAlert}
          showIcon
          type="error"
          title={`发现 ${blockingItems.length} 个阻塞项`}
          action={
            <Button danger size="small" onClick={onRepairBlockingItems}>
              带入 AI 修订
            </Button>
          }
          description={
            <>
              <List
                size="small"
                dataSource={blockingItems.slice(0, MAX_VISIBLE_VALIDATION_ITEMS)}
                renderItem={(item, index) => (
                  <List.Item key={`${item.path || '$'}-${item.code || ''}-${index}`}>
                    <Space orientation="vertical" size={0}>
                      <Text strong>
                        {[item.objectType, item.objectKey, item.path, item.code, item.category]
                          .filter(Boolean)
                          .join(' · ') || `阻塞项 ${index + 1}`}
                      </Text>
                      <Text>{item.message || item.detail || '请修复该阻塞项'}</Text>
                    </Space>
                  </List.Item>
                )}
              />
              {blockingItems.length > MAX_VISIBLE_VALIDATION_ITEMS ? (
                <Text type="secondary">
                  当前仅展示前 {MAX_VISIBLE_VALIDATION_ITEMS} 项，请通过分类结果继续复核。
                </Text>
              ) : null}
            </>
          }
        />
      ) : report.status === 'RUNNING' ? (
        <Alert
          className={styles.stage4InlineAlert}
          showIcon
          type="info"
          title="阻塞项将在验证完成后更新"
        />
      ) : !reportPassed || reportedBlockingCount > 0 ? (
        <Alert
          className={styles.stage4InlineAlert}
          showIcon
          type="error"
          title="当前报告未通过，不能提交审批"
          description={
            reportedBlockingCount > 0
              ? `报告记录了 ${reportedBlockingCount} 个阻塞项，但没有可展示的逐项明细，请重新执行验证。`
              : '验证失败且没有可展示的逐项明细，请刷新或重新执行验证。'
          }
        />
      ) : (
        <Alert
          className={styles.stage4InlineAlert}
          showIcon
          type="success"
          title="当前报告没有阻塞项"
        />
      )}

      {warningItems.length ? (
        <Alert
          className={styles.stage4InlineAlert}
          showIcon
          type="warning"
          title={`${warningItems.length} 个警告项需要管理员确认`}
          description={warningItems
            .slice(0, 20)
            .map((item) => (item.message || item.detail || item.code || '').slice(0, 300))
            .filter(Boolean)
            .join('；')
            .slice(0, 4000)}
        />
      ) : null}
    </>
  );
};

export default ValidationReportFindings;
