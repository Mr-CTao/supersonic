package com.tencent.supersonic.headless.server.semantic.modeling;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.llm.LlmConstants;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingRevisionAttemptDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingValidationReportDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingValidationReportMapper;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.ValidationContext;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftRevisionStore.ClaimDisposition;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftRevisionStore.ClaimResult;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftRevisionStore.CompletionDisposition;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftRevisionStore.CompletionResult;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStage4Store.RestoreResult;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStage4Store.SubmissionResult;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftValidator.ValidatedDraft;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/**
 * 阶段 4 多轮校准、版本差异、验证报告和提交审批门禁应用服务。
 *
 * <p>
 * 职责说明：在阶段 3 不可变草稿版本之上追加同会话 AI 修订，按需计算版本差异，生成绑定明确版本的 验证报告，并在数据库行锁内把通过门禁的草稿迁移到
 * {@code PENDING_APPROVAL}。本服务不创建或修改 正式模型、维度、指标和术语，不调用知识刷新，也不实现批准、发布或回滚。
 * </p>
 *
 * <p>
 * 并发说明：64 段 {@link ReentrantLock} 只串行化单 JVM 内的短时认领，避免同草稿同时争抢数据库；锁在 Provider 调用前释放，
 * 防止哈希到同一段的无关草稿被长时间阻塞。跨实例互斥由持久化修订 attempt 租约、幂等唯一键、草稿行锁和活动验证唯一键保证。 外部 LLM 调用绝不位于 JVM 锁或数据库事务中。
 * </p>
 */
@Service
@Slf4j
public class ModelingDraftStage4Service {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final int REVISION_LOCK_STRIPES = 64;
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ModelingPlannedObject>> PLANNED_OBJECTS_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<ModelingSampleQuestionResult>> SAMPLE_RESULTS_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<ModelingValidationFinding>> FINDINGS_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<ModelingValidationCheckResult>> CHECK_RESULTS_TYPE =
            new TypeReference<>() {};

    private final ModelingDraftStage4PermissionService permissionService;
    private final ModelingDraftStage4Store store;
    private final ModelingDraftRevisionStore revisionStore;
    private final SemanticModelingDraftVersionMapper versionMapper;
    private final SemanticModelingValidationReportMapper reportMapper;
    private final ModelingDraftContextBuilder contextBuilder;
    private final ModelingDraftValidator validator;
    private final ModelingDraftValidationEngine validationEngine;
    private final ModelingDraftDiffService diffService;
    private final SemanticModelingSensitivityClassifier sensitivityClassifier;
    private final LlmConversationGatewayService gatewayService;
    private final SemanticModelingProperties properties;
    private final ObjectMapper objectMapper;
    private final ReentrantLock[] revisionLocks = IntStream.range(0, REVISION_LOCK_STRIPES)
            .mapToObj(index -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    /**
     * 创建阶段 4 应用服务。
     *
     * @param permissionService 阶段 4 管理权限服务。
     * @param store 短事务存储服务。
     * @param revisionStore 跨实例 AI 修订认领、租约和幂等重放存储服务。
     * @param versionMapper 草稿版本 Mapper。
     * @param reportMapper 验证报告 Mapper。
     * @param contextBuilder 真实字段和授权名称上下文构建器。
     * @param validator 阶段 3 Schema/字段校验器。
     * @param validationEngine 阶段 4 确定性验证引擎。
     * @param diffService 版本差异服务。
     * @param sensitivityClassifier 管理员指令和报告文本脱敏器。
     * @param gatewayService 阶段 1 LLM 会话网关。
     * @param properties 建模安全边界配置。
     * @param objectMapper JSON 映射器。
     */
    public ModelingDraftStage4Service(ModelingDraftStage4PermissionService permissionService,
            ModelingDraftStage4Store store, ModelingDraftRevisionStore revisionStore,
            SemanticModelingDraftVersionMapper versionMapper,
            SemanticModelingValidationReportMapper reportMapper,
            ModelingDraftContextBuilder contextBuilder, ModelingDraftValidator validator,
            ModelingDraftValidationEngine validationEngine, ModelingDraftDiffService diffService,
            SemanticModelingSensitivityClassifier sensitivityClassifier,
            LlmConversationGatewayService gatewayService, SemanticModelingProperties properties,
            ObjectMapper objectMapper) {
        this.permissionService = permissionService;
        this.store = store;
        this.revisionStore = revisionStore;
        this.versionMapper = versionMapper;
        this.reportMapper = reportMapper;
        this.contextBuilder = contextBuilder;
        this.validator = validator;
        this.validationEngine = validationEngine;
        this.diffService = diffService;
        this.sensitivityClassifier = sensitivityClassifier;
        this.gatewayService = gatewayService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 在既有建模会话中追加管理员指令并保存一个 AI 修订版本。
     *
     * <p>
     * 调用示例：{@code service.aiRevise(12L, request, idempotencyKey, user)}。保存前会重新读取真实
     * 字段和授权名称，并对模型返回的完整 JSON 重新执行阶段 3 校验；任何并发基线变化都会在短事务中 被拒绝。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param request 修订指令和基线版本。
     * @param idempotencyKey 请求幂等键。
     * @param user 当前用户。
     * @return 新版本、完整草稿和确定性差异。
     */
    public ModelingDraftAiReviseResp aiRevise(Long draftId, ModelingDraftAiReviseReq request,
            String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        if (request == null
                || sensitivityClassifier.containsSensitiveValue(request.getInstruction())) {
            // 指令中的秘密没有安全恢复路径，不能用固定掩码继续生成语义不完整的修订。
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_SENSITIVE_INSTRUCTION,
                    "修订指令不能包含手机号、邮箱、Token 等敏感值");
        }
        ReentrantLock lock = revisionLock(draftId);
        SemanticModelingDraftDO draft;
        String fingerprint;
        ClaimResult claim;
        // JVM 分段锁只保护短时认领；持久化租约已建立后必须在调用慢速 Provider 前释放。
        lock.lock();
        try {
            draft = permissionService.requireManageable(draftId, user);
            fingerprint = revisionFingerprint(draftId, request);
            claim = revisionStore.claim(draftId, request.getBaseVersionNo(), idempotencyKey,
                    fingerprint, user);
        } finally {
            lock.unlock();
        }
        if (!claim.shouldInvokeProvider()) {
            // SUCCEEDED 重放必须在 claim 事务结束后重新读取当前草稿；认领前快照可能已被并发保存推进。
            SemanticModelingDraftDO responseDraft =
                    claim.disposition() == ClaimDisposition.REPLAY_SUCCEEDED
                            ? permissionService.requireManageable(draftId, user)
                            : draft;
            return handleClaimWithoutProvider(responseDraft, claim, fingerprint);
        }
        return executeClaimedRevision(draft, request, idempotencyKey, claim.attempt(), user);
    }

    /**
     * 计算两个不可变草稿版本之间的结构化差异。
     *
     * @param draftId 草稿 ID。
     * @param fromVersionNo 基线版本。
     * @param toVersionNo 目标版本。
     * @param user 当前用户。
     * @return 路径级差异；不会修改任何版本。
     */
    public ModelingDraftDiffResp diff(Long draftId, Integer fromVersionNo, Integer toVersionNo,
            User user) {
        // 版本快照与详情使用相同基础读取 ACL；该纯读取接口不应把 viewer 误导为管理操作。
        permissionService.requireReadable(draftId, user);
        if (fromVersionNo == null || toVersionNo == null || fromVersionNo < 1 || toVersionNo < 1
                || Objects.equals(fromVersionNo, toVersionNo)) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "版本差异参数不合法");
        }
        SemanticModelingDraftVersionDO from = requireVersion(draftId, fromVersionNo);
        SemanticModelingDraftVersionDO to = requireVersion(draftId, toVersionNo);
        return diffService.compare(draftId, fromVersionNo, toVersionNo, from.getDraftJson(),
                to.getDraftJson());
    }

    /**
     * 把指定历史快照追加为新的当前草稿版本。
     *
     * <p>
     * 调用示例：{@code service.restoreVersion(12L, 2, request, key, user)}。本操作不修改历史版本或正式语义对象。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param targetVersionNo 目标历史版本号。
     * @param request 客户端确认的当前版本和锁版本。
     * @param idempotencyKey 请求幂等键。
     * @param user 当前管理员。
     * @return 新版本、锁版本和恢复后的完整草稿。
     */
    public ModelingDraftRestoreResp restoreVersion(Long draftId, Integer targetVersionNo,
            ModelingDraftRestoreReq request, String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        if (request == null || targetVersionNo == null || targetVersionNo <= 0) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "恢复版本参数不完整");
        }
        permissionService.requireManageable(draftId, user);
        RestoreResult result = store.restoreVersion(draftId, targetVersionNo,
                request.getCurrentVersionNo(), request.getLockVersion(), idempotencyKey,
                restoreFingerprint(draftId, targetVersionNo, request), user);
        return ModelingDraftRestoreResp.builder().draftId(draftId).targetVersionNo(targetVersionNo)
                .baseVersionNo(request.getCurrentVersionNo())
                .newVersionNo(result.version().getVersionNo()).lockVersion(result.lockVersion())
                .currentDraft(readTree(result.version().getDraftJson()))
                .idempotentReplay(result.idempotentReplay()).build();
    }

    /**
     * 对指定当前版本执行验证并持久化报告。
     *
     * <p>
     * 验证报告先以 RUNNING 状态提交短事务，再执行元数据读取和静态检查，最终用条件更新结束；即使检查 抛出系统异常，也会生成不包含内部异常详情的 SYSTEM_FAILED
     * 报告并释放活动标记。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param request 版本和安全验证选项。
     * @param user 当前用户。
     * @return 已完成的版本绑定验证报告。
     */
    public SemanticValidationReportResp validate(Long draftId, ModelingDraftValidationReq request,
            User user) {
        SemanticModelingDraftDO draft = permissionService.requireManageable(draftId, user);
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(draft.getStatus())
                || !Objects.equals(draft.getCurrentVersionNo(), request.getVersionNo())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "只能验证当前 DRAFT 版本");
        }
        SemanticModelingDraftVersionDO version = requireVersion(draftId, request.getVersionNo());
        ModelingValidationOptions options =
                request.getValidationOptions() == null ? new ModelingValidationOptions()
                        : request.getValidationOptions();
        if (options.getSqlPreviewLimit() == null) {
            options.setSqlPreviewLimit(20);
        }
        SemanticModelingValidationReportDO running =
                store.startValidation(draftId, version, writeJson(options), user);

        ModelingDraftValidationOutcome outcome;
        String forcedStatus = null;
        String systemErrorCode = null;
        try {
            ValidationContext context = contextBuilder.reloadValidationContext(
                    draft.getDataSourceId(), draft.getCatalogName(), draft.getDatabaseName(),
                    readSelectedTables(draft.getSelectedTables()), draft.getDomainId(), user);
            ValidatedDraft validated = validator.validateAndNormalize(version.getDraftJson(),
                    context.columnsByTable(), context.existingNames());
            outcome = validationEngine.validate(validated.payload(), context.columnsByTable(),
                    draft.getDataSourceId(), user, options.getSqlPreviewLimit());
        } catch (ModelingDraftException exception) {
            outcome = failureOutcome(exception.getIssues(), exception.getMessage());
            forcedStatus = ModelingDraftConstants.VALIDATION_FAILED;
            systemErrorCode = exception.getErrorCode();
        } catch (Exception exception) {
            RuntimeException sanitized = new RuntimeException(
                    "Unexpected validation exception type: " + exception.getClass().getName());
            sanitized.setStackTrace(exception.getStackTrace());
            log.error("semantic modeling validation failed: draftId={}, versionNo={}, reportId={}",
                    draftId, request.getVersionNo(), running.getId(), sanitized);
            outcome = failureOutcome(List.of(), "验证服务暂时不可用，请稍后重新验证");
            forcedStatus = ModelingDraftConstants.VALIDATION_SYSTEM_FAILED;
            systemErrorCode = ModelingDraftConstants.ERROR_INTERNAL;
        }
        SemanticModelingValidationReportDO completed =
                completeReport(running, outcome, forcedStatus, systemErrorCode);
        SemanticModelingDraftDO refreshed = permissionService.requireManageable(draftId, user);
        SemanticModelingValidationReportDO latest =
                store.findLatestReport(draftId, refreshed.getCurrentVersionNo());
        return toReportResponse(completed, refreshed, latest,
                revisionStore.hasActiveRevision(draftId));
    }

    /**
     * 分页查询管理员级草稿验证报告。
     *
     * @param draftId 草稿 ID。
     * @param page 页码。
     * @param pageSize 页大小。
     * @param user 当前用户。
     * @return 报告倒序分页。
     * @throws ModelingDraftException 草稿不存在或当前用户不具备管理权限。
     */
    public PageInfo<SemanticValidationReportResp> queryReports(Long draftId, int page, int pageSize,
            User user) {
        // 报告包含检查证据、SQL 摘要和阻塞原因，不能按普通草稿只读 ACL 向 viewer/public 开放。
        SemanticModelingDraftDO draft = permissionService.requireManageable(draftId, user);
        store.recoverStaleValidation(draftId);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        PageInfo<SemanticModelingValidationReportDO> reports =
                PageHelper.startPage(safePage, safePageSize)
                        .doSelectPageInfo(() -> reportMapper.selectList(
                                new LambdaQueryWrapper<SemanticModelingValidationReportDO>()
                                        .eq(SemanticModelingValidationReportDO::getDraftId, draftId)
                                        .orderByDesc(SemanticModelingValidationReportDO::getId)));
        SemanticModelingValidationReportDO latest =
                store.findLatestReport(draftId, draft.getCurrentVersionNo());
        // 每页只读取一次租约快照，避免按报告条目产生 N+1 查询。
        boolean activeRevision = revisionStore.hasActiveRevision(draftId);
        List<SemanticValidationReportResp> responses = reports.getList().stream()
                .map(report -> toReportResponse(report, draft, latest, activeRevision)).toList();
        return mapPage(reports, responses);
    }

    /**
     * 查询单个验证报告，并复核关联草稿管理权限。
     *
     * @param reportId 报告 ID。
     * @param user 当前用户。
     * @return 验证报告详情。
     * @throws ModelingDraftException 报告不存在或当前用户无权访问；两种情况统一返回 404。
     */
    public SemanticValidationReportResp getReport(Long reportId, User user) {
        SemanticModelingValidationReportDO report = reportMapper.selectById(reportId);
        SemanticModelingDraftDO draft = requireReportManageable(report, user);
        if (store.recoverStaleValidation(draft.getId())) {
            // 原报告可能正是被恢复的活动报告，必须重读后再响应，不能把旧 RUNNING 快照返回前端。
            report = reportMapper.selectById(reportId);
        }
        SemanticModelingValidationReportDO latest =
                store.findLatestReport(draft.getId(), draft.getCurrentVersionNo());
        return toReportResponse(report, draft, latest,
                revisionStore.hasActiveRevision(draft.getId()));
    }

    /**
     * 校验报告关联草稿的管理权限，并把“不存在”和“无权访问”统一为相同 404。
     *
     * <p>
     * 报告详情 URL 只有 reportId，没有可信 draftId；若对存在报告返回 403、对不存在报告返回 404，攻击者可枚举 ID。 因此这里只保留统一业务错误，不回显草稿
     * ID、数据源或授权对象。
     * </p>
     */
    private SemanticModelingDraftDO requireReportManageable(
            SemanticModelingValidationReportDO report, User user) {
        if (report == null) {
            throw reportUnavailable();
        }
        try {
            return permissionService.requireManageable(report.getDraftId(), user);
        } catch (ModelingDraftException exception) {
            if (HttpStatus.NOT_FOUND.equals(exception.getStatus())
                    || HttpStatus.FORBIDDEN.equals(exception.getStatus())) {
                throw reportUnavailable();
            }
            throw exception;
        }
    }

    /** 创建统一的报告不可用响应，避免泄露 ID 是否存在。 */
    private ModelingDraftException reportUnavailable() {
        return new ModelingDraftException(HttpStatus.NOT_FOUND,
                ModelingDraftConstants.ERROR_NOT_FOUND, "验证报告不存在或不可访问");
    }

    /**
     * 使用当前版本最新验证报告提交待审批状态。
     *
     * <p>
     * 本方法只完成阶段 4 门禁和 {@code PENDING_APPROVAL} 状态迁移，不批准、不发布、不写正式语义资产。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param request 当前版本和报告 ID。
     * @param idempotencyKey 提交幂等键。
     * @param user 当前用户。
     * @return 提交摘要。
     * @throws ModelingDraftException 幂等键、版本、报告或管理权限不满足提交门禁。
     */
    public ModelingDraftSubmissionResp submit(Long draftId, ModelingDraftSubmitReq request,
            String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        permissionService.requireManageable(draftId, user);
        SubmissionResult result = store.submit(draftId, request.getVersionNo(),
                request.getValidationReportId(), idempotencyKey, user);
        SemanticModelingDraftDO submitted = result.draft();
        return ModelingDraftSubmissionResp.builder().draftId(submitted.getId())
                .status(submitted.getStatus()).versionNo(submitted.getCurrentVersionNo())
                .validationReportId(submitted.getSubmittedValidationReportId())
                .submittedAt(submitted.getSubmittedAt()).submittedBy(submitted.getSubmittedBy())
                .idempotentReplay(result.replay()).build();
    }

    /** 把非 CLAIMED 认领结果映射为安全重放响应或稳定的 409 错误码。 */
    private ModelingDraftAiReviseResp handleClaimWithoutProvider(SemanticModelingDraftDO draft,
            ClaimResult claim, String fingerprint) {
        ClaimDisposition disposition = claim.disposition();
        return switch (disposition) {
            case REPLAY_SUCCEEDED -> replayRevision(draft,
                    requireRevisionResult(draft.getId(), claim.attempt()), fingerprint);
            case SAME_KEY_RUNNING, OTHER_ACTIVE_REVISION -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_RUNNING, "该草稿已有 AI 修订正在运行，请等待完成后重试");
            case SAME_KEY_TERMINAL -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_ATTEMPT_TERMINAL,
                    "该修订请求已进入失败终态，请使用新的 Idempotency-Key 重试");
            case SAME_KEY_EXPIRED -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_LEASE_EXPIRED,
                    "该修订请求租约已过期，请使用新的 Idempotency-Key 重试");
            case IDEMPOTENCY_CONFLICT -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT, "Idempotency-Key 已用于不同修订请求");
            case DRAFT_VERSION_CONFLICT -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_BASE_VERSION_CHANGED,
                    "草稿版本已变化，请重新加载后再修订");
            case CLAIMED -> throw new IllegalStateException(
                    "CLAIMED revision must execute the Provider path");
        };
    }

    /** 在持久化租约认领成功后执行事务外上下文读取、Provider 调用和草稿校验。 */
    private ModelingDraftAiReviseResp executeClaimedRevision(SemanticModelingDraftDO draft,
            ModelingDraftAiReviseReq request, String idempotencyKey,
            SemanticModelingRevisionAttemptDO attempt, User user) {
        if (attempt == null || attempt.getId() == null) {
            throw new IllegalStateException("Claimed revision attempt must have a persisted id");
        }
        try {
            assertRevisionPreconditions(draft, request);
            List<String> selectedTables = readSelectedTables(draft.getSelectedTables());
            ValidationContext context = contextBuilder.reloadValidationContext(
                    draft.getDataSourceId(), draft.getCatalogName(), draft.getDatabaseName(),
                    selectedTables, draft.getDomainId(), user);
            gatewayService.assertConversationAccess(draft.getLlmConversationId(), user);
            ValidatedDraft validated = requestRevision(draft, request, idempotencyKey, context);
            ModelingDraftDiffResp difference =
                    diffService.compare(draft.getId(), request.getBaseVersionNo(),
                            request.getBaseVersionNo() + 1, draft.getDraftJson(), validated.json());
            CompletionResult completion = revisionStore.completeSuccess(attempt.getId(),
                    validated.json(), difference.getSummary(), draft.getLlmConversationId(), user);
            return completeRevisionResponse(completion);
        } catch (RuntimeException exception) {
            // Provider、结构校验和最终保存任一失败都必须释放活动租约；终态 attempt 会幂等忽略该调用。
            completeRevisionFailureSafely(draft.getId(), attempt.getId(), exception, user);
            throw exception;
        }
    }

    /** 把完成事务结果映射为新版本或幂等重放响应。 */
    private ModelingDraftAiReviseResp completeRevisionResponse(CompletionResult completion) {
        CompletionDisposition disposition = completion.disposition();
        return switch (disposition) {
            case SUCCEEDED -> buildRevisionResponse(completion.draft(), completion.version(),
                    false);
            case REPLAY_SUCCEEDED -> buildRevisionResponse(completion.draft(), completion.version(),
                    true);
            case LEASE_EXPIRED -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_LEASE_EXPIRED,
                    "AI 修订执行超出租约时间，结果未保存，请使用新的 Idempotency-Key 重试");
            case DRAFT_VERSION_CONFLICT -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_BASE_VERSION_CHANGED,
                    "草稿版本已变化，AI 修订结果未保存");
            case TERMINAL_REJECTED -> throw revisionConflict(
                    ModelingDraftConstants.ERROR_REVISION_ATTEMPT_TERMINAL, "AI 修订尝试已进入终态，结果未保存");
        };
    }

    /** 尽力结束失败 attempt，且不让清理异常覆盖原始业务异常。 */
    private void completeRevisionFailureSafely(Long draftId, Long attemptId,
            RuntimeException original, User user) {
        String errorCode = original instanceof ModelingDraftException modelingException
                ? modelingException.getErrorCode()
                : ModelingDraftConstants.ERROR_REVISION_FAILED;
        try {
            revisionStore.completeFailure(attemptId, errorCode, user);
        } catch (RuntimeException cleanupException) {
            RuntimeException sanitized = new RuntimeException(
                    "Revision cleanup exception type: " + cleanupException.getClass().getName());
            sanitized.setStackTrace(cleanupException.getStackTrace());
            log.error("semantic modeling revision cleanup failed: draftId={}, attemptId={}",
                    draftId, attemptId, sanitized);
        }
    }

    /** 查询成功 attempt 绑定的不可变版本，并拒绝损坏或跨草稿关联。 */
    private SemanticModelingDraftVersionDO requireRevisionResult(Long draftId,
            SemanticModelingRevisionAttemptDO attempt) {
        SemanticModelingDraftVersionDO version =
                attempt == null || attempt.getResultVersionId() == null ? null
                        : versionMapper.selectById(attempt.getResultVersionId());
        if (version == null || !Objects.equals(version.getDraftId(), draftId)
                || !Objects.equals(version.getVersionNo(), attempt.getResultVersionNo())) {
            throw revisionConflict(ModelingDraftConstants.ERROR_REVISION_ATTEMPT_TERMINAL,
                    "AI 修订成功记录缺少对应版本，请联系管理员检查数据一致性");
        }
        return version;
    }

    /** 构造带稳定错误码的修订冲突。 */
    private ModelingDraftException revisionConflict(String errorCode, String message) {
        return new ModelingDraftException(HttpStatus.CONFLICT, errorCode, message);
    }

    /** 调用 Gateway，并在一次失败后用固定安全提示修复结构化输出。 */
    private ValidatedDraft requestRevision(SemanticModelingDraftDO draft,
            ModelingDraftAiReviseReq request, String idempotencyKey, ValidationContext context) {
        JsonNode protectedDraftSource = readTree(draft.getDraftJson());
        SemanticModelingProtectedDraftContext protectedContext =
                new SemanticModelingProtectedDraftContext(sensitivityClassifier);
        JsonNode protectedDraft = protectedContext.protect(protectedDraftSource);
        String prompt = buildRevisionPrompt(request, protectedDraft);
        LlmMessageCreateResp response;
        try {
            response = gatewayService.appendMessageAndChatWithoutTransaction(
                    draft.getLlmConversationId(), createRevisionMessage(prompt,
                            "semantic-revise-" + draft.getId() + "-" + idempotencyKey));
            assertProviderAvailable(response);
        } catch (ModelingDraftException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelingDraftException(HttpStatus.BAD_GATEWAY,
                    ModelingDraftConstants.ERROR_REVISION_FAILED, "AI 修订服务调用失败，请稍后重试");
        }

        try {
            return validateProtectedCandidate(response, protectedContext, context.columnsByTable(),
                    context.existingNames());
        } catch (ModelingDraftException firstValidationError) {
            String repairPrompt =
                    buildRevisionRepairPrompt(request, firstValidationError, protectedDraft);
            LlmMessageCreateResp repair;
            try {
                repair = gatewayService.appendMessageAndChatWithoutTransaction(
                        draft.getLlmConversationId(), createRevisionMessage(repairPrompt,
                                "semantic-revise-repair-" + draft.getId() + "-" + idempotencyKey));
                assertProviderAvailable(repair);
            } catch (ModelingDraftException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new ModelingDraftException(HttpStatus.BAD_GATEWAY,
                        ModelingDraftConstants.ERROR_REVISION_FAILED, "AI 修订输出修复失败，请稍后重试");
            }
            try {
                return validateProtectedCandidate(repair, protectedContext,
                        context.columnsByTable(), context.existingNames());
            } catch (ModelingDraftException repairValidationError) {
                throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_OUTPUT_INVALID, "AI 修订结果未通过草稿安全校验",
                        repairValidationError.getIssues());
            }
        }
    }

    /** 模型输出必须先恢复受保护字段，再进入 schema、业务校验、diff 和持久化链路。 */
    private ValidatedDraft validateProtectedCandidate(LlmMessageCreateResp response,
            SemanticModelingProtectedDraftContext protectedContext,
            Map<String, List<DBColumn>> columnsByTable, Set<String> existingNames) {
        JsonNode candidate = readTree(extractCandidate(response));
        JsonNode restored = protectedContext.restore(candidate);
        return validator.validateAndNormalize(writeJson(restored), columnsByTable, existingNames);
    }

    /** 构造完整草稿修订 Prompt，明确禁止修改无关对象或返回局部补丁。 */
    private String buildRevisionPrompt(ModelingDraftAiReviseReq request, JsonNode protectedDraft) {
        try {
            Map<String, Object> prompt =
                    Map.of("baseVersionNo", request.getBaseVersionNo(), "administratorInstruction",
                            request.getInstruction(), "currentDraft", protectedDraft);
            String content = "请修订下面的当前语义草稿。currentDraft 是唯一已提交且权威的基线状态；"
                    + "忽略本会话历史中任何未提交、失败或超时的修订消息。只修改管理员指令涉及的对象，保留其他对象、key 和数组顺序；"
                    + "以 __S2_PROTECTED_ 开头的值是路径绑定保护占位符，必须在原路径原样保留且不得复制、移动、删除或修改；"
                    + "返回符合既有 JSON Schema 的完整草稿，禁止返回补丁、Markdown、任意 SQL 或正式资产 ID。\n"
                    + objectMapper.writeValueAsString(prompt);
            if (content.length() > properties.getMaxContextCharacters()) {
                throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, "当前草稿过大，无法安全执行 AI 修订");
            }
            return content;
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_OUTPUT_INVALID, "当前草稿不是合法结构化 JSON");
        }
    }

    /** 构造不包含模型原文的第二轮结构修复 Prompt。 */
    private String buildRevisionRepairPrompt(ModelingDraftAiReviseReq request,
            ModelingDraftException validationError, JsonNode protectedDraft) {
        try {
            return "上一条完整草稿未通过安全校验。下面的 protectedCurrentDraft 仍是唯一已提交基线；"
                    + "所有 __S2_PROTECTED_ 占位符必须留在原路径且只出现一次。请继续遵循原管理员指令，只输出完整 JSON。"
                    + "不要解释，不要输出 Markdown，也不要改动无关对象。protectedCurrentDraft："
                    + objectMapper.writeValueAsString(protectedDraft) + "；校验问题："
                    + objectMapper.writeValueAsString(validationError.getIssues()) + "；原指令："
                    + request.getInstruction();
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_OUTPUT_INVALID, "无法构建 AI 修订修复请求");
        }
    }

    /** 构造固定 JSON Schema 的非流式修订请求。 */
    private LlmMessageCreateReq createRevisionMessage(String content, String idempotencyKey) {
        LlmMessageCreateReq message = new LlmMessageCreateReq();
        message.setContent(content);
        message.setResponseFormat(LlmConstants.FORMAT_JSON);
        message.setJsonSchema(validator.getJsonSchema());
        message.setTemperature(0.1D);
        message.setMaxTokens(properties.getMaxOutputTokens());
        // 租约按最多两轮该超时加安全余量计算，合法 Provider 调用不会在执行中失去互斥权。
        message.setTimeoutMs(properties.resolveRevisionProviderTimeoutMillis());
        message.setStream(false);
        message.setRequireToolCalling(false);
        message.setIdempotencyKey(idempotencyKey);
        return message;
    }

    /** 将 Provider 失败与可修复 JSON 解析失败区分。 */
    private void assertProviderAvailable(LlmMessageCreateResp response) {
        if (response == null) {
            throw new ModelingDraftException(HttpStatus.BAD_GATEWAY,
                    ModelingDraftConstants.ERROR_REVISION_FAILED, "AI 修订服务未返回响应");
        }
        if (StringUtils.isBlank(response.getErrorCode())
                || LlmConstants.ERROR_JSON_PARSE_FAILED.equals(response.getErrorCode())) {
            return;
        }
        String code = LlmConstants.ERROR_TIMEOUT.equals(response.getErrorCode())
                ? ModelingDraftConstants.ERROR_GENERATION_TIMEOUT
                : ModelingDraftConstants.ERROR_REVISION_FAILED;
        throw new ModelingDraftException(HttpStatus.BAD_GATEWAY, code, "AI 修订服务调用失败，请稍后重试");
    }

    /** 优先读取 Gateway 解析 JSON，解析失败时使用仅服务端可见正文。 */
    private String extractCandidate(LlmMessageCreateResp response) {
        if (response.getParsedJson() != null) {
            return response.getParsedJson().toString();
        }
        return StringUtils.defaultIfBlank(response.getInternalAssistantContent(),
                response.getAssistantContent());
    }

    /** 校验 AI 修订的状态、会话和基线版本。 */
    private void assertRevisionPreconditions(SemanticModelingDraftDO draft,
            ModelingDraftAiReviseReq request) {
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(draft.getStatus())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "只有 DRAFT 状态可以执行 AI 修订");
        }
        if (!Objects.equals(draft.getCurrentVersionNo(), request.getBaseVersionNo())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "草稿版本已变化，请重新加载后再修订");
        }
        if (draft.getLlmConversationId() == null) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_REVISION_FAILED, "草稿没有可继续的 AI 建模会话");
        }
    }

    /** 构造同幂等键重放响应；草稿后续已更新时拒绝回放过期快照。 */
    private ModelingDraftAiReviseResp replayRevision(SemanticModelingDraftDO draft,
            SemanticModelingDraftVersionDO version, String fingerprint) {
        if (!Objects.equals(version.getRequestFingerprint(), fingerprint)) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT, "Idempotency-Key 已用于不同修订请求");
        }
        if (!Objects.equals(draft.getCurrentVersionNo(), version.getVersionNo())) {
            throw revisionConflict(ModelingDraftConstants.ERROR_REVISION_BASE_VERSION_CHANGED,
                    "该修订请求已处理，但草稿已有更新，请重新加载");
        }
        return buildRevisionResponse(draft, version, true);
    }

    /** 根据已保存的不可变版本重建安全响应，避免重放 Provider 输出。 */
    private ModelingDraftAiReviseResp buildRevisionResponse(SemanticModelingDraftDO draft,
            SemanticModelingDraftVersionDO version, boolean replay) {
        if (draft == null || version == null || version.getVersionNo() == null
                || version.getVersionNo() < 2
                || !Objects.equals(draft.getId(), version.getDraftId())) {
            throw revisionConflict(ModelingDraftConstants.ERROR_REVISION_ATTEMPT_TERMINAL,
                    "AI 修订结果版本不完整，请联系管理员检查数据一致性");
        }
        SemanticModelingDraftVersionDO base =
                requireVersion(draft.getId(), version.getVersionNo() - 1);
        ModelingDraftDiffResp difference = diffService.compare(draft.getId(), base.getVersionNo(),
                version.getVersionNo(), base.getDraftJson(), version.getDraftJson());
        ModelingDraftPayload payload = readPayload(version.getDraftJson());
        return ModelingDraftAiReviseResp.builder().draftId(draft.getId())
                .baseVersionNo(base.getVersionNo()).newVersionNo(version.getVersionNo())
                .lockVersion(draft.getLockVersion()).draftJson(readTree(version.getDraftJson()))
                .changeSummary(difference.getSummary()).changes(difference.getItems())
                .uncertaintyItems(payload.getUncertainties()).idempotentReplay(replay).build();
    }

    /** 完成并序列化一份验证报告。 */
    private SemanticModelingValidationReportDO completeReport(
            SemanticModelingValidationReportDO running, ModelingDraftValidationOutcome outcome,
            String forcedStatus, String systemErrorCode) {
        List<ModelingValidationFinding> blockingItems = outcome.effectiveBlockingItems();
        List<ModelingValidationFinding> warningItems = outcome.effectiveWarningItems();
        SemanticModelingValidationReportDO completed = new SemanticModelingValidationReportDO();
        completed.setId(running.getId());
        completed.setStatus(StringUtils.defaultIfBlank(forcedStatus, outcome.resolveStatus()));
        completed.setRequiredCheckResults(writeJson(outcome.getRequiredCheckResults()));
        completed.setPlannedObjects(writeJson(outcome.getPlannedObjects()));
        completed.setFieldExistenceResult(writeJson(outcome.getFieldExistenceResult()));
        completed.setConflictResult(writeJson(outcome.getConflictResult()));
        completed.setSensitiveFieldResult(writeJson(outcome.getSensitiveFieldResult()));
        completed.setSampleQuestionResults(writeJson(outcome.getSampleQuestionResults()));
        completed.setSqlSafetyResult(writeJson(outcome.getSqlSafetyResult()));
        completed.setPerformanceRiskResult(writeJson(outcome.getPerformanceRiskResult()));
        completed.setUncertaintyResult(writeJson(outcome.getUncertaintyResult()));
        completed.setBlockingItems(writeJson(blockingItems));
        completed.setWarningItems(writeJson(warningItems));
        completed.setBlockingCount(blockingItems.size());
        completed.setWarningCount(warningItems.size());
        completed.setSystemErrorCode(systemErrorCode);
        completed.setFinishedAt(new Date());
        return store.completeValidation(completed);
    }

    /** 把阶段 3 校验失败或系统失败转换为可展示的阻塞报告。 */
    private ModelingDraftValidationOutcome failureOutcome(List<ModelingValidationIssue> issues,
            String fallbackMessage) {
        List<ModelingValidationFinding> findings = new ArrayList<>();
        for (ModelingValidationIssue issue : issues == null ? List.<ModelingValidationIssue>of()
                : issues) {
            String category = issue.getCode() != null
                    && (issue.getCode().contains("FIELD") || issue.getCode().contains("TABLE"))
                            ? "FIELD_EXISTENCE"
                            : "SCHEMA";
            findings.add(ModelingValidationFinding.builder().category(category)
                    .code(StringUtils.defaultIfBlank(issue.getCode(), "VALIDATION_FAILED"))
                    .severity(ModelingDraftConstants.FINDING_BLOCKING).path(issue.getPath())
                    .message(StringUtils.defaultIfBlank(issue.getMessage(), "草稿未通过安全校验")).build());
        }
        if (findings.isEmpty()) {
            findings.add(
                    ModelingValidationFinding.builder().category("SYSTEM").code("VALIDATION_FAILED")
                            .severity(ModelingDraftConstants.FINDING_BLOCKING).path("$")
                            .message(StringUtils.defaultIfBlank(fallbackMessage, "验证未通过")).build());
        }
        ModelingValidationCheckResult field = check("FIELD_EXISTENCE", "FAILED", "草稿结构或真实字段校验未通过",
                findings.size(), 0, findings.size(), "SERVER_METADATA");
        ModelingValidationCheckResult notRun =
                check("NOT_RUN", "NOT_RUN", "基础校验失败，后续检查未运行", 0, 0, 0, "NOT_RUN");
        List<ModelingValidationCheckResult> requiredChecks = new ArrayList<>();
        for (String checkId : ModelingValidationGate.requiredCheckIds()) {
            boolean schemaCheck = ModelingValidationGate.CHECK_JSON_SCHEMA.equals(checkId)
                    || ModelingValidationGate.CHECK_TABLE_FIELD_EXISTENCE.equals(checkId)
                    || ModelingValidationGate.CHECK_METRIC_EXPRESSION_FIELD.equals(checkId);
            requiredChecks.add(check(checkId,
                    schemaCheck ? ModelingDraftConstants.VALIDATION_FAILED
                            : ModelingDraftConstants.VALIDATION_NOT_RUN,
                    schemaCheck ? "草稿基础结构或字段检查未通过" : "前置检查失败，本项未运行",
                    schemaCheck ? findings.size() : 0, 0, schemaCheck ? findings.size() : 0,
                    schemaCheck ? "SERVER_METADATA" : "NOT_RUN"));
        }
        return ModelingDraftValidationOutcome.builder().plannedObjects(List.of())
                .requiredCheckResults(List.copyOf(requiredChecks)).fieldExistenceResult(field)
                .conflictResult(notRun).sensitiveFieldResult(notRun)
                .sampleQuestionResults(List.of()).sqlSafetyResult(notRun)
                .performanceRiskResult(notRun).uncertaintyResult(notRun)
                .blockingItems(List.copyOf(findings)).warningItems(List.of()).build();
    }

    /** 构造检查摘要。 */
    private ModelingValidationCheckResult check(String category, String status, String summary,
            int checked, int passed, int failed, String mode) {
        return ModelingValidationCheckResult.builder().category(category).status(status)
                .summary(summary).checkedCount(checked).passedCount(passed).failedCount(failed)
                .mode(mode).build();
    }

    /** 把持久化 JSON 安全转换为管理端报告 DTO，并复用提交门禁的状态和最新报告语义。 */
    private SemanticValidationReportResp toReportResponse(SemanticModelingValidationReportDO report,
            SemanticModelingDraftDO draft, SemanticModelingValidationReportDO latest,
            boolean activeRevision) {
        List<ModelingValidationCheckResult> requiredChecks =
                readJsonList(report.getRequiredCheckResults(), CHECK_RESULTS_TYPE);
        boolean draftOpen = ModelingDraftConstants.STATUS_DRAFT.equals(draft.getStatus());
        boolean current = Objects.equals(report.getDraftVersionNo(), draft.getCurrentVersionNo());
        boolean latestReport = latest != null && Objects.equals(report.getId(), latest.getId());
        boolean passed = ModelingDraftConstants.VALIDATION_PASSED.equals(report.getStatus())
                || ModelingDraftConstants.VALIDATION_WARNING.equals(report.getStatus());
        boolean checksComplete = ModelingValidationGate.isCompleteForSubmission(requiredChecks);
        boolean canSubmit = draftOpen && current && latestReport && !activeRevision && passed
                && report.getFinishedAt() != null
                && Objects.requireNonNullElse(report.getBlockingCount(), 0) == 0 && checksComplete;
        String blockReason = canSubmit ? null
                : !draftOpen ? "草稿当前状态不能提交审批"
                        : !current ? "报告不属于草稿当前版本"
                                : !latestReport ? "该报告不是当前版本最新验证报告"
                                        : activeRevision ? "AI 正在修订该草稿，暂不能提交审批"
                                                : !passed || report.getFinishedAt() == null
                                                        ? "验证报告尚未通过"
                                                        : Objects.requireNonNullElse(
                                                                report.getBlockingCount(), 0) > 0
                                                                        ? "验证报告仍包含阻塞项"
                                                                        : "验证报告必需检查不完整";
        return SemanticValidationReportResp.builder().id(report.getId())
                .draftId(report.getDraftId()).draftVersionId(report.getDraftVersionId())
                .draftVersionNo(report.getDraftVersionNo()).status(report.getStatus())
                .plannedObjects(readJsonList(report.getPlannedObjects(), PLANNED_OBJECTS_TYPE))
                .requiredCheckResults(requiredChecks)
                .fieldExistenceResult(readCheck(report.getFieldExistenceResult()))
                .conflictResult(readCheck(report.getConflictResult()))
                .sensitiveFieldResult(readCheck(report.getSensitiveFieldResult()))
                .sampleQuestionResults(
                        readJsonList(report.getSampleQuestionResults(), SAMPLE_RESULTS_TYPE))
                .sqlSafetyResult(readCheck(report.getSqlSafetyResult()))
                .performanceRiskResult(readCheck(report.getPerformanceRiskResult()))
                .uncertaintyResult(readCheck(report.getUncertaintyResult()))
                .blockingItems(readJsonList(report.getBlockingItems(), FINDINGS_TYPE))
                .warningItems(readJsonList(report.getWarningItems(), FINDINGS_TYPE))
                .blockingCount(Objects.requireNonNullElse(report.getBlockingCount(), 0))
                .warningCount(Objects.requireNonNullElse(report.getWarningCount(), 0))
                .canSubmit(canSubmit).submissionBlockReason(blockReason)
                .createdBy(report.getCreatedBy()).createdAt(report.getCreatedAt())
                .finishedAt(report.getFinishedAt()).build();
    }

    /** 查询不可变版本。 */
    private SemanticModelingDraftVersionDO requireVersion(Long draftId, Integer versionNo) {
        SemanticModelingDraftVersionDO version =
                versionMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftVersionDO>()
                        .eq(SemanticModelingDraftVersionDO::getDraftId, draftId)
                        .eq(SemanticModelingDraftVersionDO::getVersionNo, versionNo));
        if (version == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "草稿版本不存在");
        }
        return version;
    }

    /** 校验幂等键长度和安全字符集。 */
    private void validateIdempotencyKey(String key) {
        if (StringUtils.isBlank(key) || key.length() > IDEMPOTENCY_KEY_MAX_LENGTH
                || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST,
                    "Idempotency-Key 必填，且只能包含字母、数字、点、横线、下划线或冒号");
        }
    }

    /** 计算不包含指令明文的幂等请求指纹。 */
    private String revisionFingerprint(Long draftId, ModelingDraftAiReviseReq request) {
        String canonical = draftId + "\n" + request.getBaseVersionNo() + "\n"
                + StringUtils.trim(request.getInstruction());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 恢复指纹绑定草稿、目标版本和两个客户端基线，防止同键错误重放到不同请求。 */
    private String restoreFingerprint(Long draftId, Integer targetVersionNo,
            ModelingDraftRestoreReq request) {
        String canonical = "RESTORE\n" + draftId + "\n" + targetVersionNo + "\n"
                + request.getCurrentVersionNo() + "\n" + request.getLockVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 选择固定分段锁，避免草稿 ID 数量导致锁对象无界增长。 */
    private ReentrantLock revisionLock(Long draftId) {
        int index = Math.floorMod(Objects.hashCode(draftId), revisionLocks.length);
        return revisionLocks[index];
    }

    /** 对修复 Prompt 中的指令做长度保护；指令本身不写日志和报告。 */
    private String sensitivitySafeInstruction(String instruction) {
        return StringUtils.abbreviate(sensitivityClassifier.sanitizeText(instruction), 2000);
    }

    /** 对进入 LLM 的当前草稿文本值递归脱敏，不修改数据库中的不可变版本快照。 */
    private JsonNode sanitizeDraftForPrompt(JsonNode source) {
        JsonNode copy = source.deepCopy();
        sanitizeNode(copy);
        return copy;
    }

    /** 递归替换对象和数组中的敏感文本节点。 */
    private void sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode child = objectNode.get(fieldName);
                if (child != null && child.isTextual()) {
                    objectNode.put(fieldName,
                            sensitivityClassifier.sanitizeText(child.textValue()));
                } else if (child != null) {
                    sanitizeNode(child);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int index = 0; index < arrayNode.size(); index++) {
                JsonNode child = arrayNode.get(index);
                if (child.isTextual()) {
                    arrayNode.set(index, objectMapper.getNodeFactory()
                            .textNode(sensitivityClassifier.sanitizeText(child.textValue())));
                } else {
                    sanitizeNode(child);
                }
            }
        }
    }

    /** 读取选表 JSON；数据库异常数据安全退化为空列表。 */
    private List<String> readSelectedTables(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /** 读取草稿 JSON 树。 */
    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_OUTPUT_INVALID, "草稿 JSON 无法读取");
        }
    }

    /** 读取类型化草稿。 */
    private ModelingDraftPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, ModelingDraftPayload.class);
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_OUTPUT_INVALID, "草稿 JSON 无法读取");
        }
    }

    /** 序列化报告片段；不会把 Provider 原文或样例行传入此方法。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ModelingDraftConstants.ERROR_INTERNAL, "验证报告无法序列化");
        }
    }

    /** 读取检查摘要，异常历史数据安全退化为 null。 */
    private ModelingValidationCheckResult readCheck(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ModelingValidationCheckResult.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 读取报告列表片段，异常历史数据安全退化为空列表。 */
    private <T> List<T> readJsonList(String json, TypeReference<List<T>> type) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /** 复制分页元信息并替换响应列表。 */
    private <S, T> PageInfo<T> mapPage(PageInfo<S> source, List<T> list) {
        PageInfo<T> target = new PageInfo<>();
        target.setPageNum(source.getPageNum());
        target.setPageSize(source.getPageSize());
        target.setSize(source.getSize());
        target.setStartRow(source.getStartRow());
        target.setEndRow(source.getEndRow());
        target.setTotal(source.getTotal());
        target.setPages(source.getPages());
        target.setPrePage(source.getPrePage());
        target.setNextPage(source.getNextPage());
        target.setIsFirstPage(source.isIsFirstPage());
        target.setIsLastPage(source.isIsLastPage());
        target.setHasPreviousPage(source.isHasPreviousPage());
        target.setHasNextPage(source.isHasNextPage());
        target.setNavigatePages(source.getNavigatePages());
        target.setNavigatepageNums(source.getNavigatepageNums());
        target.setNavigateFirstPage(source.getNavigateFirstPage());
        target.setNavigateLastPage(source.getNavigateLastPage());
        target.setList(list);
        return target;
    }
}
