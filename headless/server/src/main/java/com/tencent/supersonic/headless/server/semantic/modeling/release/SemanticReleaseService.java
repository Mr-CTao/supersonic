package com.tencent.supersonic.headless.server.semantic.modeling.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseStepDO;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapService;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftConstants;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftException;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DimensionDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.ReleaseClaim;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.RollbackClaim;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.StepClaim;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.StepDescriptor;
import com.tencent.supersonic.headless.server.task.DictionaryReloadTask;
import com.tencent.supersonic.headless.server.task.MetaEmbeddingTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 阶段 5 审批发布、知识刷新与 AI 新增对象回滚编排服务。
 *
 * <p>
 * 职责说明：执行审批门禁，按模型、维度、指标、术语顺序调用现有语义管理服务，分别刷新 dict/embedding，关联缺口并按依赖逆序回滚。每个外部调用前后都通过
 * {@link SemanticReleaseStore} 写入独立步骤。并发说明：跨实例互斥由数据库发布/步骤行锁保证；本服务不使用 JVM 锁，也不把 长耗时 API 包在数据库事务中。
 * </p>
 */
@Service
@Slf4j
public class SemanticReleaseService {

    private static final Pattern SENSITIVE_TEXT =
            Pattern.compile("(?i)(api[-_ ]?key|token|password|authorization)\\s*[:=]\\s*"
                    + "(?:bearer\\s+)?[^,;\\s]+");

    private final SemanticReleaseStore store;
    private final FormalSemanticAssetPublisher publisher;
    private final DictionaryReloadTask dictionaryReloadTask;
    private final MetaEmbeddingTask metaEmbeddingTask;
    private final SemanticGapService semanticGapService;
    private final ObjectMapper objectMapper;

    /**
     * 创建阶段 5 发布编排服务。
     *
     * @param store 发布状态与步骤仓库。
     * @param publisher 正式语义管理 API 适配器。
     * @param dictionaryReloadTask 字典刷新任务。
     * @param metaEmbeddingTask embedding 刷新任务。
     * @param semanticGapService 语义缺口状态服务。
     * @param objectMapper JSON 解析器。
     */
    public SemanticReleaseService(SemanticReleaseStore store,
            FormalSemanticAssetPublisher publisher, DictionaryReloadTask dictionaryReloadTask,
            MetaEmbeddingTask metaEmbeddingTask, SemanticGapService semanticGapService,
            ObjectMapper objectMapper) {
        this.store = store;
        this.publisher = publisher;
        this.dictionaryReloadTask = dictionaryReloadTask;
        this.metaEmbeddingTask = metaEmbeddingTask;
        this.semanticGapService = semanticGapService;
        this.objectMapper = objectMapper;
    }

    /**
     * 审批通过待审批草稿。
     *
     * @param draftId 草稿 ID。
     * @param request 可选审批备注。
     * @param user 当前用户，必须为系统管理员。
     * @return true 表示审批完成。
     * @throws ModelingDraftException 权限、状态或验证报告门禁不满足。
     */
    public boolean approve(Long draftId, SemanticApprovalDecisionReq request, User user) {
        requireSystemAdmin(user);
        store.approve(draftId, request == null ? null : request.getReason(), user);
        return true;
    }

    /**
     * 拒绝待审批草稿。
     *
     * @param draftId 草稿 ID。
     * @param request 必填拒绝原因。
     * @param user 当前用户，必须为系统管理员。
     * @return true 表示拒绝完成。
     * @throws ModelingDraftException 权限、输入或状态门禁不满足。
     */
    public boolean reject(Long draftId, SemanticApprovalDecisionReq request, User user) {
        requireSystemAdmin(user);
        store.reject(draftId, request == null ? null : request.getReason(), user);
        return true;
    }

    /**
     * 发布审批通过的 AI 新增语义对象。
     *
     * <p>
     * 调用示例：{@code service.release(draftId, idempotencyKey, user)}。对象创建失败会保留已成功 步骤和正式 ID，返回 FAILED
     * 发布详情；重放同一草稿不会重复执行成功步骤。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param idempotencyKey 请求幂等键。
     * @param user 系统管理员。
     * @return 发布详情，包含对象与两个知识刷新结果。
     * @throws ModelingDraftException 权限、审批、验证或并发门禁不满足。
     */
    public SemanticReleaseResp release(Long draftId, String idempotencyKey, User user) {
        requireSystemAdmin(user);
        ReleaseClaim claim = store.beginRelease(draftId, idempotencyKey, user);
        if (!claim.execute()) {
            return store.getRelease(claim.release().getId());
        }
        try {
            ModelingDraftPayload payload = readPayload(claim.draft().getDraftJson());
            publishObjects(claim.release(), claim.draft(), payload, user);
            boolean dictSucceeded = refreshDict(claim.release().getId(), "RELOAD_DICT");
            boolean embeddingSucceeded =
                    refreshEmbedding(claim.release().getId(), "RELOAD_EMBEDDING");
            boolean succeeded = dictSucceeded && embeddingSucceeded;
            if (succeeded) {
                succeeded = updateGapReleased(claim.release().getId(), claim.draft(), user);
            }
            store.finishRelease(claim.release().getId(), succeeded,
                    succeeded ? null : "语义对象已创建，但知识刷新或缺口状态更新失败", user);
        } catch (ModelingDraftException exception) {
            if (SemanticReleaseConstants.ERROR_STEP_RUNNING.equals(exception.getErrorCode())) {
                throw exception;
            }
            store.finishRelease(claim.release().getId(), false, safeError(exception), user);
        } catch (Exception exception) {
            log.error("semantic release failed: releaseId={}, type={}", claim.release().getId(),
                    exception.getClass().getName(), sanitizedForLog(exception));
            store.finishRelease(claim.release().getId(), false, safeError(exception), user);
        }
        return store.getRelease(claim.release().getId());
    }

    /**
     * 单独重试发布或回滚中的 dict/embedding 刷新失败步骤。
     *
     * @param releaseId 发布 ID。
     * @param user 系统管理员。
     * @return 重试后的发布详情。
     * @throws ModelingDraftException 对象创建/删除尚未完整或状态不允许刷新重试。
     */
    public SemanticReleaseResp retryKnowledge(Long releaseId, User user) {
        requireSystemAdmin(user);
        SemanticReleaseDO release = store.requireRelease(releaseId);
        SemanticModelingDraftDO draft = store.requireDraft(release.getDraftId());
        if (SemanticReleaseConstants.RELEASE_FAILED.equals(release.getReleaseStatus())) {
            ModelingDraftPayload payload = readPayload(draft.getDraftJson());
            if (store.successfulObjectSteps(releaseId).size() != plannedObjectCount(payload)) {
                throw stateConflict("语义对象尚未全部创建，不能只重试知识刷新");
            }
            ReleaseClaim claim =
                    store.beginRelease(draft.getId(), release.getIdempotencyKey(), user);
            if (!claim.execute()) {
                return store.getRelease(releaseId);
            }
            boolean succeeded = refreshDict(releaseId, "RELOAD_DICT")
                    & refreshEmbedding(releaseId, "RELOAD_EMBEDDING");
            succeeded = succeeded && updateGapReleased(releaseId, draft, user);
            store.finishRelease(releaseId, succeeded, succeeded ? null : "知识刷新重试仍有失败步骤", user);
            return store.getRelease(releaseId);
        }
        if (SemanticReleaseConstants.RELEASE_ROLLBACK_FAILED.equals(release.getReleaseStatus())) {
            int objectCount = store.successfulObjectSteps(releaseId).size();
            if (store.successfulRollbackStepCount(releaseId) != objectCount) {
                throw stateConflict("AI 新增对象尚未全部回滚，不能只重试知识刷新");
            }
            RollbackClaim claim = store.beginRollback(releaseId, release.getRollbackReason(), user);
            if (!claim.execute()) {
                return store.getRelease(releaseId);
            }
            boolean succeeded = refreshDict(releaseId, "ROLLBACK_RELOAD_DICT")
                    & refreshEmbedding(releaseId, "ROLLBACK_RELOAD_EMBEDDING");
            succeeded = succeeded && updateGapReopened(releaseId, draft, user);
            store.finishRollback(releaseId, succeeded, succeeded ? null : "回滚后的知识刷新重试仍失败", user);
            return store.getRelease(releaseId);
        }
        if (Set.of(SemanticReleaseConstants.RELEASE_SUCCEEDED,
                SemanticReleaseConstants.RELEASE_ROLLED_BACK)
                .contains(release.getReleaseStatus())) {
            return store.getRelease(releaseId);
        }
        throw stateConflict("当前发布状态不允许知识刷新重试");
    }

    /**
     * 逆序删除发布步骤登记的 AI 新增对象，并刷新知识索引。
     *
     * @param releaseId 发布 ID。
     * @param request 必填回滚原因。
     * @param user 系统管理员。
     * @return 回滚后的发布详情。
     * @throws ModelingDraftException 权限、输入或发布状态不允许回滚。
     */
    public SemanticReleaseResp rollback(Long releaseId, SemanticRollbackReq request, User user) {
        requireSystemAdmin(user);
        RollbackClaim claim =
                store.beginRollback(releaseId, request == null ? null : request.getReason(), user);
        if (!claim.execute()) {
            return store.getRelease(releaseId);
        }

        boolean objectsSucceeded = true;
        List<SemanticReleaseStepDO> objects =
                new ArrayList<>(store.successfulObjectSteps(releaseId));
        objects.sort(Comparator.comparingInt(this::rollbackOrder));
        for (SemanticReleaseStepDO object : objects) {
            objectsSucceeded &= rollbackObject(releaseId, object, user);
        }
        // 即使部分对象删除失败，也刷新一次当前真实状态，避免已删除对象继续命中。
        boolean dictSucceeded = refreshDict(releaseId, "ROLLBACK_RELOAD_DICT");
        boolean embeddingSucceeded = refreshEmbedding(releaseId, "ROLLBACK_RELOAD_EMBEDDING");
        boolean succeeded = objectsSucceeded && dictSucceeded && embeddingSucceeded;
        SemanticModelingDraftDO draft = store.requireDraft(claim.release().getDraftId());
        succeeded = succeeded && updateGapReopened(releaseId, draft, user);
        store.finishRollback(releaseId, succeeded, succeeded ? null : "部分对象回滚或知识刷新失败", user);
        return store.getRelease(releaseId);
    }

    /**
     * 查询待审批列表。
     *
     * @param request 分页筛选。
     * @param user 当前用户，必须为系统管理员。
     * @return 待审批与审批决定分页。
     */
    public PageInfo<SemanticApprovalResp> queryApprovals(SemanticReleaseQueryReq request,
            User user) {
        requireSystemAdmin(user);
        return store.queryApprovals(request);
    }

    /**
     * 查询发布审计列表。
     *
     * @param request 分页筛选。
     * @param user 当前用户，必须为系统管理员。
     * @return 发布记录分页。
     */
    public PageInfo<SemanticReleaseResp> queryReleases(SemanticReleaseQueryReq request, User user) {
        requireSystemAdmin(user);
        return store.queryReleases(request);
    }

    /**
     * 查询包含步骤的发布详情。
     *
     * @param releaseId 发布 ID。
     * @param user 当前用户，必须为系统管理员。
     * @return 发布详情。
     */
    public SemanticReleaseResp getRelease(Long releaseId, User user) {
        requireSystemAdmin(user);
        return store.getRelease(releaseId);
    }

    /** 按依赖顺序发布模型、维度、指标和术语，并维护草稿 key 到正式 ID 的映射。 */
    private void publishObjects(SemanticReleaseDO release, SemanticModelingDraftDO draft,
            ModelingDraftPayload payload, User user) throws Exception {
        Map<String, Long> objectIds = new LinkedHashMap<>();
        for (ModelDraft model : safe(payload.getModels())) {
            Long modelId = executeObjectStep(release.getId(),
                    descriptor(SemanticReleaseConstants.STEP_CREATE_MODEL,
                            SemanticReleaseConstants.TYPE_MODEL, model.getKey(), model.getName()),
                    () -> {
                        ModelResp owned = publisher.findOwnedModel(release.getId(), draft, model);
                        return owned != null ? owned.getId()
                                : publisher.createModel(release.getId(), draft, model, user)
                                        .getId();
                    });
            objectIds.put(normalize(model.getKey()), modelId);

            for (DimensionDraft dimension : safe(model.getDimensions())) {
                Long dimensionId = executeObjectStep(release.getId(),
                        descriptor(SemanticReleaseConstants.STEP_CREATE_DIMENSION,
                                SemanticReleaseConstants.TYPE_DIMENSION, dimension.getKey(),
                                dimension.getName()),
                        () -> {
                            DimensionResp owned = publisher.findOwnedDimension(release.getId(),
                                    draft.getId(), dimension, modelId);
                            return owned != null ? owned.getId()
                                    : publisher.createDimension(release.getId(), draft, model,
                                            dimension, modelId, user).getId();
                        });
                objectIds.put(normalize(dimension.getKey()), dimensionId);
            }
            for (MetricDraft metric : safe(model.getMetrics())) {
                Long metricId = executeObjectStep(release.getId(),
                        descriptor(SemanticReleaseConstants.STEP_CREATE_METRIC,
                                SemanticReleaseConstants.TYPE_METRIC, metric.getKey(),
                                metric.getName()),
                        () -> {
                            MetricResp owned = publisher.findOwnedMetric(release.getId(),
                                    draft.getId(), metric, modelId);
                            return owned != null ? owned.getId()
                                    : publisher.createMetric(release.getId(), draft, model, metric,
                                            modelId, user).getId();
                        });
                objectIds.put(normalize(metric.getKey()), metricId);
            }
        }
        for (TermDraft term : safe(payload.getTerms())) {
            Long termId = executeObjectStep(release.getId(),
                    descriptor(SemanticReleaseConstants.STEP_CREATE_TERM,
                            SemanticReleaseConstants.TYPE_TERM, term.getKey(), term.getName()),
                    () -> {
                        TermResp owned =
                                publisher.findOwnedTerm(draft, term, user, release.getCreatedAt());
                        return owned != null ? owned.getId()
                                : publisher.createTerm(draft, term, objectIds, user).getId();
                    });
            objectIds.put(normalize(term.getKey()), termId);
        }
    }

    /** 执行单个对象步骤；失败时先持久化步骤结果再向上抛出。 */
    private Long executeObjectStep(Long releaseId, StepDescriptor descriptor,
            CheckedSupplier<Long> supplier) throws Exception {
        StepClaim claim = store.claimStep(releaseId, descriptor);
        if (!claim.execute()) {
            return claim.step().getTargetId();
        }
        try {
            Long targetId = Objects.requireNonNull(supplier.get(), "正式对象 ID 不能为空");
            store.completeStep(claim.step().getId(), targetId);
            return targetId;
        } catch (Exception exception) {
            store.failStep(claim.step().getId(), safeError(exception));
            throw exception;
        }
    }

    /** 执行严格 dict 刷新，失败写入独立步骤但不阻止 embedding 继续尝试。 */
    private boolean refreshDict(Long releaseId, String stepKey) {
        return executeRefreshStep(releaseId,
                descriptor(stepKey, SemanticReleaseConstants.STEP_RELOAD_DICT,
                        SemanticReleaseConstants.TYPE_KNOWLEDGE, "dict", "语义字典"),
                dictionaryReloadTask::reloadKnowledgeOrThrow);
    }

    /** 执行严格 embedding 刷新，失败写入独立步骤。 */
    private boolean refreshEmbedding(Long releaseId, String stepKey) {
        return executeRefreshStep(releaseId,
                descriptor(stepKey, SemanticReleaseConstants.STEP_RELOAD_EMBEDDING,
                        SemanticReleaseConstants.TYPE_KNOWLEDGE, "embedding", "语义向量"),
                metaEmbeddingTask::reloadMetaEmbeddingOrThrow);
    }

    /** 执行知识刷新步骤并将异常转换为 false，供调用方组合独立结果。 */
    private boolean executeRefreshStep(Long releaseId, StepDescriptor descriptor,
            CheckedRunnable runnable) {
        StepClaim claim = store.claimStep(releaseId, descriptor);
        if (!claim.execute()) {
            return true;
        }
        try {
            runnable.run();
            store.completeStep(claim.step().getId(), null);
            return true;
        } catch (Exception exception) {
            log.error("semantic knowledge refresh failed: releaseId={}, step={}, type={}",
                    releaseId, descriptor.stepType(), exception.getClass().getName(),
                    sanitizedForLog(exception));
            store.failStep(claim.step().getId(), safeError(exception));
            return false;
        }
    }

    /** 回滚单个对象并继续处理后续对象，局部失败通过返回值汇总。 */
    private boolean rollbackObject(Long releaseId, SemanticReleaseStepDO object, User user) {
        String stepType = rollbackStepType(object.getTargetType());
        StepDescriptor descriptor = descriptor(stepType + ":" + object.getTargetId(), stepType,
                object.getTargetType(), object.getTargetKey(), object.getTargetName());
        StepClaim claim = store.claimStep(releaseId, descriptor);
        if (!claim.execute()) {
            return true;
        }
        try {
            publisher.delete(object.getTargetType(), object.getTargetId(), user);
            store.completeStep(claim.step().getId(), object.getTargetId());
            return true;
        } catch (Exception exception) {
            log.error("semantic object rollback failed: releaseId={}, targetType={}, type={}",
                    releaseId, object.getTargetType(), exception.getClass().getName(),
                    sanitizedForLog(exception));
            store.failStep(claim.step().getId(), safeError(exception));
            return false;
        }
    }

    /** 发布成功后用独立步骤关联语义缺口状态。 */
    private boolean updateGapReleased(Long releaseId, SemanticModelingDraftDO draft, User user) {
        if (!ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draft.getSourceType())
                || draft.getSourceId() == null) {
            return true;
        }
        try {
            executeObjectStep(releaseId,
                    descriptor(SemanticReleaseConstants.STEP_UPDATE_GAP,
                            SemanticReleaseConstants.TYPE_GAP, String.valueOf(draft.getSourceId()),
                            "关联语义缺口"),
                    () -> {
                        semanticGapService.markReleased(draft.getSourceId(), user.getName());
                        return draft.getSourceId();
                    });
            return true;
        } catch (Exception exception) {
            log.error("semantic gap release update failed: releaseId={}, type={}", releaseId,
                    exception.getClass().getName(), sanitizedForLog(exception));
            return false;
        }
    }

    /** 回滚成功后用独立步骤把关联缺口重新放回治理队列。 */
    private boolean updateGapReopened(Long releaseId, SemanticModelingDraftDO draft, User user) {
        if (!ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(draft.getSourceType())
                || draft.getSourceId() == null) {
            return true;
        }
        StepDescriptor descriptor = descriptor("ROLLBACK_UPDATE_GAP",
                SemanticReleaseConstants.STEP_UPDATE_GAP, SemanticReleaseConstants.TYPE_GAP,
                String.valueOf(draft.getSourceId()), "回滚后重新打开语义缺口");
        StepClaim claim = store.claimStep(releaseId, descriptor);
        if (!claim.execute()) {
            return true;
        }
        try {
            semanticGapService.markReopenedAfterRollback(draft.getSourceId(), user.getName());
            store.completeStep(claim.step().getId(), draft.getSourceId());
            return true;
        } catch (Exception exception) {
            store.failStep(claim.step().getId(), safeError(exception));
            log.error("semantic gap rollback update failed: releaseId={}, type={}", releaseId,
                    exception.getClass().getName(), sanitizedForLog(exception));
            return false;
        }
    }

    /** 计算草稿计划发布的模型、维度、指标和术语总数。 */
    private int plannedObjectCount(ModelingDraftPayload payload) {
        int count = safe(payload.getTerms()).size();
        for (ModelDraft model : safe(payload.getModels())) {
            count += 1 + safe(model.getDimensions()).size() + safe(model.getMetrics()).size();
        }
        return count;
    }

    /** 按术语、指标、维度、模型顺序排序回滚对象。 */
    private int rollbackOrder(SemanticReleaseStepDO step) {
        return switch (step.getTargetType()) {
            case SemanticReleaseConstants.TYPE_TERM -> 0;
            case SemanticReleaseConstants.TYPE_METRIC -> 1;
            case SemanticReleaseConstants.TYPE_DIMENSION -> 2;
            case SemanticReleaseConstants.TYPE_MODEL -> 3;
            default -> 4;
        };
    }

    /** 映射对象类型到回滚步骤类型。 */
    private String rollbackStepType(String type) {
        return switch (type) {
            case SemanticReleaseConstants.TYPE_TERM -> SemanticReleaseConstants.STEP_ROLLBACK_TERM;
            case SemanticReleaseConstants.TYPE_METRIC -> SemanticReleaseConstants.STEP_ROLLBACK_METRIC;
            case SemanticReleaseConstants.TYPE_DIMENSION -> SemanticReleaseConstants.STEP_ROLLBACK_DIMENSION;
            case SemanticReleaseConstants.TYPE_MODEL -> SemanticReleaseConstants.STEP_ROLLBACK_MODEL;
            default -> throw new IllegalArgumentException("不支持的回滚对象类型");
        };
    }

    /** 构造对象步骤描述，步骤键默认由步骤类型和草稿 key 组成。 */
    private StepDescriptor descriptor(String stepType, String targetType, String targetKey,
            String targetName) {
        return descriptor(stepType + ":" + normalize(targetKey), stepType, targetType, targetKey,
                targetName);
    }

    /** 构造带显式步骤键的步骤描述。 */
    private StepDescriptor descriptor(String stepKey, String stepType, String targetType,
            String targetKey, String targetName) {
        return new StepDescriptor(stepKey, stepType, targetType, targetKey, targetName);
    }

    /** 解析已验证草稿；解析失败按发布状态错误处理。 */
    private ModelingDraftPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, ModelingDraftPayload.class);
        } catch (Exception exception) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    SemanticReleaseConstants.ERROR_INVALID_STATE, "审批版本草稿 JSON 无法读取");
        }
    }

    /** 强制阶段 5 写操作仅系统管理员可用。 */
    private void requireSystemAdmin(User user) {
        if (user == null || !user.isSuperAdmin() || StringUtils.isBlank(user.getName())) {
            throw new ModelingDraftException(HttpStatus.FORBIDDEN,
                    SemanticReleaseConstants.ERROR_ACCESS_DENIED, "只有系统管理员可以审批发布或回滚");
        }
    }

    /** 将异常压缩为管理员可见且不包含常见凭证的摘要。 */
    private String safeError(Throwable throwable) {
        String message = StringUtils.defaultIfBlank(throwable.getMessage(), "操作失败");
        return StringUtils.abbreviate(SENSITIVE_TEXT.matcher(message).replaceAll("$1=***"),
                SemanticReleaseConstants.ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * 构造脱敏日志异常并保留原始调用栈。
     *
     * <p>
     * 不能直接把外部语义 API 的异常对象交给日志框架，因为异常 message 可能携带 token； 这里保留定位所需的 stack trace，并由结构化 type
     * 字段记录原异常类型。
     * </p>
     */
    private RuntimeException sanitizedForLog(Throwable throwable) {
        RuntimeException sanitized = new RuntimeException(safeError(throwable));
        sanitized.setStackTrace(throwable.getStackTrace());
        return sanitized;
    }

    /** 构造阶段 5 状态冲突。 */
    private ModelingDraftException stateConflict(String message) {
        return new ModelingDraftException(HttpStatus.CONFLICT,
                SemanticReleaseConstants.ERROR_INVALID_STATE, message);
    }

    /** 规范草稿对象 key。 */
    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 对可空列表安全退化为空列表。 */
    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 可抛异常且返回值的步骤函数。 */
    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    /** 可抛异常的无返回步骤函数。 */
    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
