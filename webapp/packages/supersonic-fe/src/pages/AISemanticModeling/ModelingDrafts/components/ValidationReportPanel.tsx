/**
 * 阶段 4 草稿验证报告与提交审批门禁面板。
 *
 * 职责：触发当前不可变版本验证、展示阻塞项/警告项与隔离语义验证结果，并提供“带入 AI
 * 修订”和受门禁约束的提交审批入口。面板不执行 SQL，也不实现审批处理或正式发布。
 *
 * 并发说明：所有异步状态由父工作台控制；验证、刷新和提交按钮分别使用 loading/disabled
 * 锁，服务端仍需对同版本验证和提交执行互斥、幂等与版本复核。
 */
import {
  CheckCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  InputNumber,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import React from 'react';
import type {
  ModelingDraftStatus,
  ModelingValidationReport,
  ModelingValidationStatus,
} from '@/services/semanticModelingDraft';
import type { ModelingSubmitGateState } from '../utils';
import { normalizeModelingValidationItems } from '../utils';
import ValidationCheckSections from './ValidationCheckSections';
import ValidationReportFindings from './ValidationReportFindings';
import styles from '../style.less';

const { Paragraph, Text } = Typography;
const STATUS_TEXT: Record<ModelingValidationStatus, string> = {
  PASSED: '通过',
  WARNING: '有警告',
  FAILED: '失败',
  NOT_RUN: '未运行',
  RUNNING: '验证中',
  SYSTEM_FAILED: '系统异常',
};

const STATUS_COLOR: Record<ModelingValidationStatus, string> = {
  PASSED: 'success',
  WARNING: 'warning',
  FAILED: 'error',
  NOT_RUN: 'error',
  RUNNING: 'processing',
  SYSTEM_FAILED: 'error',
};

type Props = {
  currentVersionNo?: number;
  draftStatus: ModelingDraftStatus;
  gate: ModelingSubmitGateState;
  loading: boolean;
  report?: ModelingValidationReport;
  sqlPreviewLimit: number;
  submitting: boolean;
  validating: boolean;
  validationDisabledReason?: string;
  onRefresh: () => void;
  onRepairBlockingItems: () => void;
  onRunValidation: () => void;
  onSqlPreviewLimitChange: (value: number) => void;
  onSubmitApproval: () => void;
};

/** 格式化验证审计时间，解析失败时保留后端值。 */
function formatDateTime(value?: string): string {
  if (!value) return '-';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

/**
 * 渲染当前版本验证报告与提交入口。
 *
 * @param props 当前版本、报告、门禁、异步状态及操作回调。
 * @returns 验证操作区、报告详情和阻塞修复入口。
 * @throws 不抛出异常；非标准报告结构按有界 JSON 文本只读降级。
 */
const ValidationReportPanel: React.FC<Props> = ({
  currentVersionNo,
  draftStatus,
  gate,
  loading,
  report,
  sqlPreviewLimit,
  submitting,
  validating,
  validationDisabledReason,
  onRefresh,
  onRepairBlockingItems,
  onRunValidation,
  onSqlPreviewLimitChange,
  onSubmitApproval,
}) => {
  const blockingItems = normalizeModelingValidationItems(report?.blockingItems);
  const warningItems = normalizeModelingValidationItems(report?.warningItems);
  const validationDisabled = Boolean(validationDisabledReason) || draftStatus !== 'DRAFT';
  const reportIsCurrent = Number(report?.draftVersionNo) === Number(currentVersionNo);
  const reportedBlockingCount = Math.max(blockingItems.length, Number(report?.blockingCount || 0));

  return (
    <Space className={styles.stage4Panel} orientation="vertical" size={16}>
      <Card size="small" title={`当前草稿版本 ${currentVersionNo || '-'}`}>
        <Space align="center" wrap>
          <Text>隔离语义翻译 SQL 行数上限</Text>
          <InputNumber
            aria-label="隔离语义翻译 SQL 行数上限"
            disabled={validating || draftStatus !== 'DRAFT'}
            max={100}
            min={1}
            precision={0}
            value={sqlPreviewLimit}
            onChange={(value) => onSqlPreviewLimitChange(Number(value || 20))}
          />
          <Tooltip
            title={
              validationDisabled
                ? validationDisabledReason || '当前草稿状态不可验证'
                : '验证当前不可变版本'
            }
          >
            <span className={styles.tooltipButtonWrapper}>
              <Button
                disabled={validationDisabled}
                icon={<SafetyCertificateOutlined />}
                loading={validating}
                type="primary"
                onClick={onRunValidation}
              >
                执行验证
              </Button>
            </span>
          </Tooltip>
          <Tooltip title="刷新当前版本验证报告">
            <Button
              aria-label="刷新验证报告"
              icon={<ReloadOutlined />}
              loading={loading}
              title="刷新验证报告"
              onClick={onRefresh}
            />
          </Tooltip>
          <Tooltip title={gate.reason}>
            <span className={styles.tooltipButtonWrapper}>
              <Button
                disabled={!gate.allowed}
                icon={<SendOutlined />}
                loading={submitting}
                onClick={onSubmitApproval}
              >
                提交审批
              </Button>
            </span>
          </Tooltip>
        </Space>
      </Card>

      {draftStatus === 'PENDING_APPROVAL' ? (
        <Alert
          showIcon
          type="success"
          icon={<CheckCircleOutlined />}
          title="当前版本已提交审批"
          description="本阶段只记录待审批状态，不在此页面执行审批、正式发布或回滚。"
        />
      ) : null}
      {validationDisabledReason ? (
        <Alert showIcon type="warning" title={validationDisabledReason} />
      ) : null}

      {report ? (
        <Card
          loading={loading}
          size="small"
          title={
            <Space wrap>
              <Text strong>验证报告 #{report.id}</Text>
              <Tag color={STATUS_COLOR[report.status] || 'default'}>
                {STATUS_TEXT[report.status] || report.status}
              </Tag>
              <Tag color={reportIsCurrent ? 'blue' : 'default'}>版本 {report.draftVersionNo}</Tag>
            </Space>
          }
        >
          {!reportIsCurrent ? (
            <Alert showIcon type="error" title="这是旧版本验证报告，不能用于当前版本提交审批" />
          ) : null}
          {report.status === 'RUNNING' ? (
            <Alert
              className={styles.stage4InlineAlert}
              showIcon
              type="info"
              title="验证正在运行，页面会自动刷新报告"
            />
          ) : null}
          <Descriptions bordered column={3} size="small">
            <Descriptions.Item label="报告版本">{report.draftVersionNo}</Descriptions.Item>
            <Descriptions.Item label="触发人">{report.createdBy || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {formatDateTime(report.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="完成时间" span={3}>
              {formatDateTime(report.finishedAt)}
            </Descriptions.Item>
          </Descriptions>

          <ValidationReportFindings
            blockingItems={blockingItems}
            report={report}
            reportedBlockingCount={reportedBlockingCount}
            warningItems={warningItems}
            onRepairBlockingItems={onRepairBlockingItems}
          />
          <ValidationCheckSections report={report} />
          <Paragraph className={styles.stage4GateHint} type="secondary">
            提交门禁：{gate.reason}
            。页面禁用只用于减少误操作，服务端仍会原子复核版本、报告和阻塞项。
          </Paragraph>
        </Card>
      ) : (
        <Card loading={loading} size="small">
          <Empty description={loading ? '正在读取验证报告' : '当前版本尚无验证报告'} />
        </Card>
      )}
    </Space>
  );
};

export default ValidationReportPanel;
