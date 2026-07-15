/**
 * AI 语义建模草稿两步创建抽屉。
 *
 * 职责：第一步确认分析范围并发起资产路由，第二步展示服务端推荐、业务问题和候选依据；确认后仅对新建/增强动作消费路由快照创建草稿。
 *
 * 并发说明：分析、确认、重分析和建稿共享 useAssetRoutingWorkflow 的同步互斥令牌；关闭、切 gap 和范围变化会废弃迟到响应。
 */
import { Alert, Button, Drawer, Form, message, Space, Spin, Steps } from 'antd';
import React, { useState } from 'react';
import type { CreateSemanticAssetRouteReq } from '@/services/semanticAssetRouting';
import { createModelingDraft, createModelingDraftFromGap } from '@/services/semanticModelingDraft';
import type { CreateModelingDraftReq } from '@/services/semanticModelingDraft';
import { extractDraftId, getRequestErrorText, unwrapResponseData } from '../utils';
import AssetRouteBusinessQuestionsForm from './create/AssetRouteBusinessQuestionsForm';
import AssetRouteCandidateCollapse from './create/AssetRouteCandidateCollapse';
import AssetRouteOverrideModal from './create/AssetRouteOverrideModal';
import AssetRouteRecommendationCard from './create/AssetRouteRecommendationCard';
import AssetRouteScopeStep from './create/AssetRouteScopeStep';
import type { CreateDraftFormValues } from './create/creationTypes';
import type { AssetRoutingDecision } from './create/routingTypes';
import { buildAssetRouteRequest, ROUTE_PRIMARY_BUTTON_TEXT } from './create/routingUtils';
import { useAssetRoutingWorkflow } from './create/useAssetRoutingWorkflow';
import { useDraftCreationOptions } from './create/useDraftCreationOptions';

type Props = {
  open: boolean;
  initialGapId?: number;
  onClose: () => void;
  onCreated: (draftId?: number) => void;
};

/** 将编辑中的表单转换成部分路由范围，用于即时失效旧指纹。 */
function buildPartialRouteRequest(
  initialGapId: number | undefined,
  values: Partial<CreateDraftFormValues>,
): Partial<CreateSemanticAssetRouteReq> {
  return {
    sourceType: initialGapId ? 'SEMANTIC_GAP' : 'DATA_SOURCE',
    sourceId: initialGapId,
    businessGoal: values.businessGoal,
    domainId: values.domainId,
    dataSourceId: values.dataSourceId,
    catalogName: values.catalogName,
    databaseName: values.databaseName,
    selectedTables: values.selectedTables,
    chatModelId: values.chatModelId,
    includeSampleData: values.includeSampleData,
  };
}

/**
 * 草稿创建抽屉组件。
 *
 * @param props 开关状态、可选缺口 ID 和创建完成回调。
 * @returns 资产路由分析与确认两步 Drawer。
 * @throws 不向上抛出异常；表单与请求错误在组件内展示并保留输入。
 */
const CreateDraftDrawer: React.FC<Props> = ({ open, initialGapId, onClose, onCreated }) => {
  const [form] = Form.useForm<CreateDraftFormValues>();
  const [messageApi, messageContextHolder] = message.useMessage();
  const [overrideOpen, setOverrideOpen] = useState(false);
  const creationOptions = useDraftCreationOptions({ form, initialGapId, open });
  const workflow = useAssetRoutingWorkflow({ initialGapId, open });
  const busy = Boolean(workflow.busyKind);
  const route = workflow.route;

  /** 校验第一步表单并发起或继续当前范围的资产路由分析。 */
  const analyze = async () => {
    try {
      const values = await form.validateFields();
      const result = await workflow.analyze(buildAssetRouteRequest(initialGapId, values));
      if (result?.status === 'SUCCEEDED') {
        messageApi.success('现有资产分析完成，请确认处理方式');
      }
    } catch (error: any) {
      if (!error?.errorFields) messageApi.error(getRequestErrorText(error));
    }
  };

  /** 使用分析时冻结的范围创建草稿，避免确认后表单漂移。 */
  const createDraftFromConfirmedRoute = async (routeAnalysisId: number, idempotencyKey: string) => {
    const scope = workflow.analyzedScope?.request;
    if (!scope) throw new Error('缺少已分析的范围快照，请重新分析');
    const payload: CreateModelingDraftReq = { ...scope, routeAnalysisId };
    return scope.sourceType === 'SEMANTIC_GAP' && scope.sourceId
      ? createModelingDraftFromGap(scope.sourceId, payload, idempotencyKey)
      : createModelingDraft(payload, idempotencyKey);
  };

  /** 确认推荐或覆盖选择，并按服务端确认动作进入重分析、复用或建稿结果。 */
  const submitDecision = async (decision: AssetRoutingDecision): Promise<boolean> => {
    try {
      const result = await workflow.confirmAndContinue({
        decision,
        createDraft: createDraftFromConfirmedRoute,
      });
      if (result.kind === 'REANALYZED') {
        messageApi.success(
          result.route.recommendedAction === 'NEEDS_CLARIFICATION'
            ? '业务答案已保存，请继续补充新的必答问题'
            : '业务口径已更新，系统已重新评估推荐',
        );
        return true;
      }
      if (result.kind === 'REUSED') {
        messageApi.success('已确认复用现有资产，未创建重复草稿');
        onCreated(undefined);
        return true;
      }
      const draftId = extractDraftId(unwrapResponseData<any>(result.draftResponse));
      messageApi.success(
        result.route.confirmedAction === 'EXTEND_EXISTING'
          ? '增量草稿生成任务已提交'
          : '新建草稿生成任务已提交',
      );
      onCreated(draftId);
      return true;
    } catch (error) {
      messageApi.error(getRequestErrorText(error));
      return false;
    }
  };

  /** 接受服务端推荐；候选只能使用本次返回的主候选句柄。 */
  const acceptRecommendation = async () => {
    if (!route?.recommendedAction) return;
    const requiresCandidate =
      route.recommendedAction === 'REUSE_EXISTING' || route.recommendedAction === 'EXTEND_EXISTING';
    await submitDecision({
      action: route.recommendedAction,
      candidateHandle: requiresCandidate ? route.primaryCandidate?.candidateHandle : undefined,
    });
  };

  /** 处理中禁止关闭，防止用户误以为服务端请求已取消。 */
  const close = () => {
    if (busy) {
      messageApi.warning('当前分析或确认尚未完成，请稍候再关闭');
      return;
    }
    setOverrideOpen(false);
    onClose();
  };

  const requiredAnswersMissing = Object.keys(workflow.answerErrors).length > 0;
  const scopeBlocked =
    busy ||
    creationOptions.initializing ||
    creationOptions.metadataLoading ||
    creationOptions.capabilities.length === 0;
  const decisionBlocked =
    busy || route?.status !== 'SUCCEEDED' || route?.canConfirm !== true || requiredAnswersMissing;
  const canOverride = Boolean(
    route?.canConfirm === true &&
      (route.allowedActions?.some((action) => action !== route.recommendedAction) ||
        (route.recommendedAction === 'EXTEND_EXISTING' &&
          route.alternativeCandidates?.some((candidate) => candidate.manageable === true))),
  );

  return (
    <Drawer
      mask={{ closable: !busy }}
      open={open}
      size="large"
      title={initialGapId ? `语义缺口 #${initialGapId} · 分析并建模` : '分析现有资产并建模'}
      footer={
        workflow.step === 'SCOPE' ? (
          <Space style={{ display: 'flex', justifyContent: 'flex-end', overflowX: 'auto' }}>
            <Button aria-label="取消资产分析" title="取消资产分析" onClick={close}>
              取消
            </Button>
            <Button
              aria-label="分析现有资产"
              disabled={scopeBlocked}
              loading={workflow.busyKind === 'ANALYZE'}
              title="分析现有资产"
              type="primary"
              onClick={() => void analyze()}
            >
              分析现有资产
            </Button>
          </Space>
        ) : (
          <Space style={{ display: 'flex', justifyContent: 'flex-end', overflowX: 'auto' }}>
            <Button
              aria-label="返回修改分析范围"
              disabled={busy}
              title="返回修改分析范围"
              onClick={() => workflow.setStep('SCOPE')}
            >
              返回修改范围
            </Button>
            {canOverride ? (
              <Button
                aria-label="更改处理方式"
                disabled={busy}
                title="更改处理方式"
                onClick={() => setOverrideOpen(true)}
              >
                更改处理方式
              </Button>
            ) : null}
            <Button
              aria-label={
                route?.recommendedAction
                  ? ROUTE_PRIMARY_BUTTON_TEXT[route.recommendedAction]
                  : '确认处理方式'
              }
              disabled={decisionBlocked}
              loading={busy}
              title={
                route?.recommendedAction
                  ? ROUTE_PRIMARY_BUTTON_TEXT[route.recommendedAction]
                  : '确认处理方式'
              }
              type="primary"
              onClick={() => void acceptRecommendation()}
            >
              {route?.recommendedAction
                ? ROUTE_PRIMARY_BUTTON_TEXT[route.recommendedAction]
                : '确认处理方式'}
            </Button>
          </Space>
        )
      }
      onClose={close}
    >
      {messageContextHolder}
      <Steps
        current={workflow.step === 'SCOPE' ? 0 : 1}
        items={[{ title: '确认分析范围' }, { title: '确认处理方式' }]}
        style={{ marginBottom: 24 }}
      />
      {workflow.errorText ? (
        <Alert showIcon type="error" title="当前操作未完成" description={workflow.errorText} />
      ) : null}
      {!workflow.errorText && workflow.progressText ? (
        <Alert
          showIcon
          type="info"
          title="资产分析仍在进行"
          description={workflow.progressText}
        />
      ) : null}
      <Spin spinning={busy || creationOptions.initializing || creationOptions.metadataLoading}>
        {workflow.step === 'SCOPE' ? (
          <AssetRouteScopeStep
            form={form}
            initialGapId={initialGapId}
            invalidationText={workflow.scopeInvalidationText}
            options={creationOptions}
            onValuesChange={(_, values) =>
              workflow.markScopeChanged(buildPartialRouteRequest(initialGapId, values))
            }
          />
        ) : route ? (
          <Space orientation="vertical" size={16} style={{ width: '100%' }}>
            <AssetRouteRecommendationCard route={route} />
            {route.canConfirm !== true && route.confirmDisabledReason ? (
              <Alert
                showIcon
                type="warning"
                title="当前账号不能确认该处理方式"
                description={route.confirmDisabledReason}
              />
            ) : null}
            <AssetRouteBusinessQuestionsForm
              answers={workflow.businessAnswers}
              disabled={busy}
              errors={workflow.answerErrors}
              questions={route.businessQuestions}
              onAnswerChange={workflow.updateBusinessAnswer}
            />
            <AssetRouteCandidateCollapse route={route} />
          </Space>
        ) : (
          <Alert showIcon type="warning" title="分析结果已失效，请返回第一步重新分析" />
        )}
      </Spin>
      {route ? (
        <AssetRouteOverrideModal
          loading={busy}
          open={overrideOpen}
          route={route}
          onCancel={() => setOverrideOpen(false)}
          onSubmit={async (decision) => {
            const completed = await submitDecision(decision);
            if (completed) setOverrideOpen(false);
          }}
        />
      ) : null}
    </Drawer>
  );
};

export default CreateDraftDrawer;
