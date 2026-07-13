/**
 * AI 语义建模草稿工作台纯视图。
 *
 * 职责：组合 Drawer 工具栏、阶段 3 编辑区、阶段 4 修订/验证/diff 面板及历史弹层。所有请求、
 * latest-wins、幂等键和 loading 状态由控制器/Hook 提供，本组件不直接调用 service。
 */
import {
  HistoryOutlined,
  ProfileOutlined,
  RedoOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { Button, Drawer, Empty, Space, Spin, Tooltip } from 'antd';
import React from 'react';
import type {
  AiReviseModelingDraftResp,
  ModelingDraftItem,
  ModelingValidationReport,
  SemanticModelingDraftJson,
} from '@/services/semanticModelingDraft';
import type { ModelingSubmitGateState } from '../utils';
import AiRevisionPanel from './AiRevisionPanel';
import DraftWorkbenchContent from './DraftWorkbenchContent';
import GenerationAttemptHistoryDrawer from './GenerationAttemptHistoryDrawer';
import RegenerateDraftModal from './RegenerateDraftModal';
import ValidationReportPanel from './ValidationReportPanel';
import VersionDiffPanel from './VersionDiffPanel';
import VersionHistoryDrawer from './VersionHistoryDrawer';
import type { DraftMutationCoordinator } from './useDraftMutationCoordinator';
import styles from '../style.less';

type Props = {
  activeDetail?: ModelingDraftItem;
  activeTab: string;
  attemptOpen: boolean;
  currentVersionNo?: number;
  dirty: boolean;
  draft?: SemanticModelingDraftJson;
  draftId?: number;
  editable: boolean;
  jsonError?: string;
  jsonText: string;
  loadParseError: string;
  loading: boolean;
  mutationCoordinator: DraftMutationCoordinator;
  open: boolean;
  regenerateOpen: boolean;
  regenerating: boolean;
  regenerationAvailability: { allowed: boolean; reason?: string };
  revisionDisabledReason?: string;
  revisionInstruction: string;
  revisionResult?: AiReviseModelingDraftResp;
  revising: boolean;
  saving: boolean;
  sqlPreviewLimit: number;
  stage4Busy: boolean;
  submitGate: ModelingSubmitGateState;
  submitting: boolean;
  validating: boolean;
  validationDisabledReason?: string;
  validationLoading: boolean;
  validationReport?: ModelingValidationReport;
  validationRunning: boolean;
  versionOpen: boolean;
  versionRefreshToken: number;
  writeBlockedReason?: string;
  onActiveTabChange: (tab: string) => void;
  onAttemptOpenChange: (open: boolean) => void;
  onClose: () => void;
  onDraftChange: (draft: SemanticModelingDraftJson) => void;
  onJsonTextChange: (value: string) => void;
  onRefresh: () => void;
  onRegenerateOpenChange: (open: boolean) => void;
  onRegenerateSubmitted: (detail: ModelingDraftItem) => void;
  onRegeneratingChange: (submitting: boolean) => void;
  onRepairBlockingItems: () => void;
  onRevisionInstructionChange: (value: string) => void;
  onRunValidation: () => void;
  onSave: () => void;
  onSqlPreviewLimitChange: (value: number) => void;
  onSubmitApproval: () => void;
  onSubmitRevision: () => void;
  onValidationRefresh: () => void;
  onVersionOpenChange: (open: boolean) => void;
  onVersionRestored: () => Promise<void> | void;
};

/**
 * 渲染工作台纯视图。
 *
 * @param props 控制器提供的只读状态和受锁操作回调。
 * @returns 工作台 Drawer 与辅助弹层。
 * @throws 不抛出异常，也不直接访问网络。
 */
const DraftWorkbenchDrawerView: React.FC<Props> = (props) => {
  const {
    activeDetail,
    activeTab,
    currentVersionNo,
    regenerationAvailability,
    revisionResult,
    submitGate,
    validationReport,
  } = props;
  return (
    <>
      <Drawer
        destroyOnHidden
        maskClosable={
          !props.dirty && !props.saving && !props.revising && !props.validating && !props.submitting
        }
        open={props.open}
        placement="right"
        title={`建模草稿工作台${props.draftId ? ` · #${props.draftId}` : ''}`}
        size="100vw"
        extra={
          <Space className={styles.nowrapActions}>
            <Button
              aria-label="刷新草稿"
              disabled={
                props.dirty ||
                props.saving ||
                props.revising ||
                props.validating ||
                props.submitting
              }
              icon={<ReloadOutlined />}
              loading={props.loading}
              title="刷新草稿"
              onClick={props.onRefresh}
            />
            <Button
              aria-label="查看版本"
              icon={<HistoryOutlined />}
              title="查看版本"
              onClick={() => props.onVersionOpenChange(true)}
            />
            <Button
              aria-label="查看生成尝试"
              icon={<ProfileOutlined />}
              title="查看生成尝试"
              onClick={() => props.onAttemptOpenChange(true)}
            />
            {activeDetail?.status === 'GENERATION_FAILED' ? (
              <Tooltip title={regenerationAvailability.reason}>
                <span className={styles.tooltipButtonWrapper}>
                  <Button
                    aria-label="重新生成草稿"
                    disabled={!regenerationAvailability.allowed}
                    icon={<RedoOutlined />}
                    loading={props.regenerating}
                    title="重新生成草稿"
                    onClick={() => props.onRegenerateOpenChange(true)}
                  />
                </span>
              </Tooltip>
            ) : null}
            {props.editable ? (
              <Button
                aria-label="保存草稿"
                disabled={
                  !props.dirty || Boolean(props.jsonError) || props.stage4Busy || props.loading
                }
                icon={<SaveOutlined />}
                loading={props.saving}
                title="保存草稿"
                type="primary"
                onClick={props.onSave}
              >
                保存
              </Button>
            ) : null}
          </Space>
        }
        onClose={props.onClose}
      >
        <Spin spinning={props.loading && !activeDetail}>
          {activeDetail ? (
            <DraftWorkbenchContent
              activeTab={activeTab}
              aiRevisionContent={
                <AiRevisionPanel
                  baseVersionNo={currentVersionNo}
                  disabledReason={props.revisionDisabledReason}
                  instruction={props.revisionInstruction}
                  loading={props.revising}
                  result={revisionResult}
                  onInstructionChange={props.onRevisionInstructionChange}
                  onSubmit={props.onSubmitRevision}
                />
              }
              detail={activeDetail}
              dirty={props.dirty}
              draft={props.draft}
              jsonError={props.jsonError}
              jsonText={props.jsonText}
              loadParseError={props.loadParseError}
              writeBlockedReason={props.writeBlockedReason}
              regenerationAllowed={regenerationAvailability.allowed}
              regenerationReason={regenerationAvailability.reason ?? ''}
              regenerating={props.regenerating}
              validationContent={
                <ValidationReportPanel
                  currentVersionNo={currentVersionNo}
                  draftStatus={activeDetail.status}
                  gate={submitGate}
                  loading={props.validationLoading}
                  report={validationReport}
                  sqlPreviewLimit={props.sqlPreviewLimit}
                  submitting={props.submitting}
                  validating={props.validating || props.validationRunning}
                  validationDisabledReason={props.validationDisabledReason}
                  onRefresh={props.onValidationRefresh}
                  onRepairBlockingItems={props.onRepairBlockingItems}
                  onRunValidation={props.onRunValidation}
                  onSqlPreviewLimitChange={props.onSqlPreviewLimitChange}
                  onSubmitApproval={props.onSubmitApproval}
                />
              }
              versionDiffContent={
                <VersionDiffPanel
                  active={activeTab === 'version-diff'}
                  currentVersionNo={currentVersionNo}
                  draftId={activeDetail.id}
                  lockVersion={activeDetail.lockVersion}
                  canRestore={
                    activeDetail.status === 'DRAFT' &&
                    props.editable &&
                    !props.dirty &&
                    !props.stage4Busy
                  }
                  mutationCoordinator={props.mutationCoordinator}
                  onVersionRestored={props.onVersionRestored}
                  recentRevision={revisionResult}
                  refreshToken={props.versionRefreshToken}
                />
              }
              onActiveTabChange={props.onActiveTabChange}
              onDraftChange={props.onDraftChange}
              onJsonTextChange={props.onJsonTextChange}
              onRegenerate={() => props.onRegenerateOpenChange(true)}
            />
          ) : (
            <Empty description={props.loading ? '正在加载草稿' : '未找到草稿详情'} />
          )}
        </Spin>
      </Drawer>
      <VersionHistoryDrawer
        draftId={props.draftId}
        open={props.versionOpen}
        onClose={() => props.onVersionOpenChange(false)}
      />
      <GenerationAttemptHistoryDrawer
        draftId={props.draftId}
        open={props.attemptOpen}
        onClose={() => props.onAttemptOpenChange(false)}
      />
      <RegenerateDraftModal
        draft={activeDetail}
        open={props.regenerateOpen}
        onClose={() => props.onRegenerateOpenChange(false)}
        onConflict={props.onRefresh}
        onSubmitted={props.onRegenerateSubmitted}
        onSubmittingChange={props.onRegeneratingChange}
      />
    </>
  );
};

export default DraftWorkbenchDrawerView;
