/**
 * 阶段 5 待审批详情抽屉。
 *
 * 职责：加载只读草稿和提交时绑定的验证报告，展示审批依据；不提供编辑、验证或正式发布写入。
 * 卸载或切换记录时使用取消标记忽略过期请求结果，避免旧响应覆盖新抽屉。
 */
import { Alert, Descriptions, Drawer, Empty, Space, Spin, Tag, Typography } from 'antd';
import React, { useEffect, useState } from 'react';
import {
  getModelingDraftDetail,
  getModelingValidationReport,
  type ModelingDraftItem,
  type ModelingValidationReport,
} from '@/services/semanticModelingDraft';
import type { SemanticApprovalItem } from '@/services/semanticRelease';
import { getRequestErrorText, unwrapResponseData } from '../../ModelingDrafts/utils';

type ApprovalDetailDrawerProps = {
  approval?: SemanticApprovalItem;
  open: boolean;
  onClose: () => void;
};

/** 将可能为 JSON 字符串的结构安全格式化为管理端只读文本。 */
function pretty(value: unknown): string {
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value ?? [], null, 2);
}

/**
 * 展示待审批草稿和验证报告。
 *
 * @param props 当前审批项、开关和关闭回调。
 * @returns 只读审批详情抽屉。
 * @throws 不向上抛出异常；请求错误显示在抽屉内。
 */
const ApprovalDetailDrawer: React.FC<ApprovalDetailDrawerProps> = ({ approval, open, onClose }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [draft, setDraft] = useState<ModelingDraftItem>();
  const [report, setReport] = useState<ModelingValidationReport>();

  useEffect(() => {
    let cancelled = false;
    if (!open || !approval) return () => undefined;
    setLoading(true);
    setError(undefined);
    Promise.all([
      getModelingDraftDetail(approval.draftId),
      approval.validationReportId
        ? getModelingValidationReport(approval.validationReportId)
        : Promise.resolve(undefined),
    ])
      .then(([draftResponse, reportResponse]) => {
        if (cancelled) return;
        setDraft(unwrapResponseData<ModelingDraftItem>(draftResponse));
        setReport(
          reportResponse ? unwrapResponseData<ModelingValidationReport>(reportResponse) : undefined,
        );
      })
      .catch((requestError) => {
        if (!cancelled) setError(getRequestErrorText(requestError));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [approval, open]);

  return (
    <Drawer title="审批依据" width={760} open={open} onClose={onClose} destroyOnClose>
      <Spin spinning={loading}>
        {error ? <Alert type="error" showIcon message={error} /> : null}
        {!loading && !draft && !error ? <Empty description="暂无审批详情" /> : null}
        {draft ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="草稿">{draft.title || `#${draft.id}`}</Descriptions.Item>
              <Descriptions.Item label="版本">v{draft.currentVersionNo}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color="processing">{approval?.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="验证">
                <Tag color={report?.status === 'PASSED' ? 'success' : 'warning'}>
                  {report?.status || approval?.validationStatus || '-'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="业务目标" span={2}>
                {draft.businessGoal || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="提交人">{approval?.submittedBy || '-'}</Descriptions.Item>
              <Descriptions.Item label="提交时间">{approval?.submittedAt || '-'}</Descriptions.Item>
            </Descriptions>
            <div>
              <Typography.Title level={5}>计划新增对象</Typography.Title>
              <Typography.Text type="secondary">
                共 {approval?.plannedObjectCount ?? 0} 个，仅支持 AI 新增对象。
              </Typography.Text>
              <pre style={{ maxHeight: 220, overflow: 'auto', whiteSpace: 'pre-wrap' }}>
                {pretty(report?.plannedObjects)}
              </pre>
            </div>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="阻塞项">{report?.blockingCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="警告项">{report?.warningCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="提交资格" span={2}>
                {report?.canSubmit === false
                  ? report.submissionBlockReason || '不可提交'
                  : '已通过门禁'}
              </Descriptions.Item>
            </Descriptions>
          </Space>
        ) : null}
      </Spin>
    </Drawer>
  );
};

export default ApprovalDetailDrawer;
