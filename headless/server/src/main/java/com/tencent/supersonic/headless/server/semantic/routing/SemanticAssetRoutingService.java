package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticAssetRoutingDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 语义资产路由应用服务。
 *
 * <p>
 * 职责：编排创建、异步分析、查询、澄清重分析和最终确认。所有 Provider 调用发生在 Store 短事务 之外；幂等、租约和确认依赖数据库原子更新，不使用 JVM 全局锁。日志只记录路由
 * ID、动作、数量和 结果，不记录业务目标、Prompt、SQL 或敏感值。
 * </p>
 */
@Service
@Slf4j
public class SemanticAssetRoutingService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> ANSWER_MAP = new TypeReference<>() {};
    private static final TypeReference<List<SemanticAssetCandidate>> CANDIDATE_LIST =
            new TypeReference<>() {};
    private static final TypeReference<List<SemanticAssetCapabilityGap>> GAP_LIST =
            new TypeReference<>() {};
    private static final TypeReference<List<SemanticAssetBusinessQuestion>> QUESTION_LIST =
            new TypeReference<>() {};

    private final SemanticAssetRoutingStore store;
    private final SemanticAssetCandidateRetriever candidateRetriever;
    private final SemanticAssetCoverageAnalyzer coverageAnalyzer;
    private final SemanticAssetRoutingAdvisor advisor;
    private final SemanticAssetRoutingPolicy policy;
    private final SemanticAssetRoutingPermissionService permissionService;
    private final SemanticAssetReuseValidationService reuseValidationService;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;

    /**
     * 创建路由应用服务。
     *
     * @param store 路由短事务存储服务。
     * @param candidateRetriever 候选召回器。
     * @param coverageAnalyzer 覆盖分析器。
     * @param advisor 可选 LLM Advisor。
     * @param policy 服务端策略引擎。
     * @param permissionService 权限服务。
     * @param reuseValidationService 已有资产复用校验与知识刷新服务。
     * @param executor 复用的语义建模有界执行器。
     * @param objectMapper JSON 映射器。
     */
    public SemanticAssetRoutingService(SemanticAssetRoutingStore store,
            SemanticAssetCandidateRetriever candidateRetriever,
            SemanticAssetCoverageAnalyzer coverageAnalyzer, SemanticAssetRoutingAdvisor advisor,
            SemanticAssetRoutingPolicy policy,
            SemanticAssetRoutingPermissionService permissionService,
            SemanticAssetReuseValidationService reuseValidationService,
            @Qualifier("semanticModelingExecutor") ThreadPoolTaskExecutor executor,
            ObjectMapper objectMapper) {
        this.store = store;
        this.candidateRetriever = candidateRetriever;
        this.coverageAnalyzer = coverageAnalyzer;
        this.advisor = advisor;
        this.policy = policy;
        this.permissionService = permissionService;
        this.reuseValidationService = reuseValidationService;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建异步路由分析。
     *
     * <p>
     * 调用示例：{@code create(req, idempotencyKey, user)}。相同键和指纹重放同一路由，不重复调用 Provider；相同键不同指纹返回 409。
     * </p>
     *
     * @param request 分析请求。
     * @param idempotencyKey 必填幂等键。
     * @param user 当前用户。
     * @return PENDING/ANALYZING 或幂等重放路由详情。
     */
    public SemanticAssetRouteResp create(SemanticAssetRouteAnalyzeReq request,
            String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        // 在排序 Map 和计算指纹前拒绝空 key、深层对象及超大 JSON，避免异常或无界序列化。
        SemanticAssetBusinessAnswerValidator.validatePayload(request.getBusinessAnswers(),
                objectMapper);
        normalizeRequest(request);
        permissionService.requireAnalysisPermission(request, user);
        String fingerprint = fingerprint(createFingerprintPayload(request));
        SemanticAssetRoutingStore.CreateResult result =
                store.createPending(request, idempotencyKey, fingerprint, user);
        if (!result.replay()) {
            scheduleAnalysis(result.route().getId(), user);
        }
        return toResponse(result.route(), user, result.replay());
    }

    /**
     * 查询路由详情。
     *
     * @param id 路由 ID。
     * @param user 当前用户。
     * @return 当前持久化状态和安全摘要。
     */
    public SemanticAssetRouteResp get(Long id, User user) {
        SemanticAssetRoutingDO route = store.findById(id);
        permissionService.requireRouteReadable(route, user);
        if (SemanticAssetRouteStatus.ANALYZING.name().equals(route.getStatus())) {
            Date databaseNow = store.currentDatabaseTime();
            if (route.getLeaseExpiresAt() == null
                    || route.getLeaseExpiresAt().before(databaseNow)) {
                // 轮询发现进程崩溃遗留租约时触发安全回收；数据库 CAS 保证多客户端只会有一个新 Worker。
                scheduleAnalysis(route.getId(), user);
            }
        }
        return toResponse(route, user, false);
    }

    /**
     * 读取并重新校验一个可由草稿消费的确认路由。
     *
     * <p>
     * 调用示例：{@code requireConsumableRoute(routeId, user)}。该方法只建立请求内快照，不占用
     * 路由；真正的一对一消费由草稿插入事务通过条件更新完成。这样慢速元数据/权限检查不会持有数据库 事务，同时并发创建仍由数据库决定唯一胜者。
     * </p>
     *
     * @param id 路由分析 ID。
     * @param user 当前草稿创建用户。
     * @return 已重新校验权限、目标版本和过期时间的确认快照。
     * @throws SemanticAssetRoutingException 路由未确认、已过期、已消费、动作不可建稿或权限/版本变化时抛出。
     */
    public ConfirmedSemanticAssetRoute requireConsumableRoute(Long id, User user) {
        return requireDraftableRoute(id, null, user);
    }

    /**
     * 重新校验一个已经绑定到指定草稿的确认路由。
     *
     * <p>调用示例：{@code requireBoundRoute(routeId, draftId, user)}。人工保存、重新生成、
     * AI 修订和提交前都应调用此方法，以便在正式资产版本或权限变化后 fail-closed，而不是继续使用
     * 创建时的旧快照。仅允许路由当前消费方重放，不能被其他草稿借用。</p>
     *
     * @param id 路由分析 ID。
     * @param draftId 已绑定的草稿 ID。
     * @param user 当前操作用户。
     * @return 已重新校验权限、目标版本和过期时间的确认快照。
     * @throws SemanticAssetRoutingException 路由绑定、权限、版本、状态或有效期发生变化时抛出。
     */
    public ConfirmedSemanticAssetRoute requireBoundRoute(Long id, Long draftId, User user) {
        if (draftId == null) {
            throw new SemanticAssetRoutingException(HttpStatus.BAD_REQUEST,
                    "DRAFT_ID_REQUIRED", "校验已消费路由时必须提供草稿 ID");
        }
        return requireDraftableRoute(id, draftId, user);
    }

    /**
     * 统一校验可建稿路由；expectedDraftId 为空表示首次消费，否则只允许既有消费方重放。
     */
    private ConfirmedSemanticAssetRoute requireDraftableRoute(Long id, Long expectedDraftId,
            User user) {
        SemanticAssetRoutingDO route = store.findById(id);
        permissionService.requireRouteReadable(route, user);
        Date databaseNow = store.currentDatabaseTime();
        if (!SemanticAssetRouteStatus.SUCCEEDED.name().equals(route.getStatus())
                || route.getConfirmedAction() == null) {
            throw conflict("ROUTE_NOT_CONFIRMED", "路由尚未确认，不能生成草稿");
        }
        if (route.getExpiresAt() != null && !route.getExpiresAt().after(databaseNow)) {
            throw conflict("ROUTE_EXPIRED", "路由快照已过期，请重新分析");
        }
        if (expectedDraftId == null && route.getConsumedByDraftId() != null) {
            throw conflict("ROUTE_ALREADY_CONSUMED", "该路由已被其他草稿消费");
        }
        if (expectedDraftId != null
                && !Objects.equals(expectedDraftId, route.getConsumedByDraftId())) {
            throw conflict("ROUTE_DRAFT_BINDING_CHANGED", "路由与当前草稿的绑定关系已变化");
        }

        SemanticAssetRouteAction action = parseAction(route.getConfirmedAction());
        List<SemanticAssetCandidate> candidates = readCandidates(route.getCandidateSnapshot());
        SemanticAssetCandidate target = candidates.stream()
                .filter(candidate -> Objects.equals(candidate.getAssetId(),
                        route.getConfirmedCandidateId()))
                .filter(candidate -> Objects.equals(candidate.getAssetVersion(),
                        route.getConfirmedCandidateVersion()))
                .findFirst().orElse(null);
        if (action == SemanticAssetRouteAction.EXTEND_EXISTING) {
            if (target == null) {
                throw corruptSnapshot();
            }
            permissionService.requireCurrentCandidateVersion(target, user);
        } else if (action == SemanticAssetRouteAction.CREATE_NEW) {
            if (!permissionService.canCreateAsset(route, user)) {
                throw new SemanticAssetRoutingException(HttpStatus.FORBIDDEN,
                        "CREATE_PERMISSION_DENIED", "无权在当前主题域和数据源新建语义资产");
            }
        } else {
            throw conflict("ROUTE_ACTION_NOT_DRAFTABLE", "复用或待确认路由不能创建建模草稿");
        }

        SemanticAssetCoverageResult coverage = readJson(route.getRuleEvidence(),
                SemanticAssetCoverageResult.class, SemanticAssetCoverageResult.builder().build());
        Map<String, Object> promptContext =
                buildConfirmedPromptContext(route, action, target, coverage);
        return ConfirmedSemanticAssetRoute.builder().routeAnalysisId(route.getId())
                .sourceType(route.getSourceType()).sourceId(route.getSourceId())
                .businessGoal(route.getBusinessGoal()).domainId(route.getDomainId())
                .dataSourceId(route.getDataSourceId()).catalogName(route.getCatalogName())
                .databaseName(route.getDatabaseName())
                .selectedTables(readJson(route.getSelectedTables(), STRING_LIST, List.of()))
                .chatModelId(route.getChatModelId()).includeSampleData(route.getIncludeSample())
                .action(action).targetAssetType(target == null ? null : target.getAssetType())
                .targetAssetId(target == null ? null : target.getAssetId())
                .targetAssetVersion(target == null ? null : target.getAssetVersion())
                .promptContext(promptContext).build();
    }

    /** 构造不含正式资产 ID、分数、SQL 和样例值的草稿 Prompt 路由摘要。 */
    private Map<String, Object> buildConfirmedPromptContext(SemanticAssetRoutingDO route,
            SemanticAssetRouteAction action, SemanticAssetCandidate target,
            SemanticAssetCoverageResult coverage) {
        Map<String, Object> routeSummary = new LinkedHashMap<>();
        routeSummary.put("routeAnalysisId", route.getId());
        routeSummary.put("decisionSource", route.getDecisionSource());
        routeSummary.put("explanation", explanation(route, coverage, action));
        routeSummary.put("coveredCapabilities",
                readJson(route.getCoveredCapabilities(), STRING_LIST, List.of()));
        routeSummary.put("missingCapabilities",
                readJson(route.getMissingCapabilities(), GAP_LIST, List.of()).stream()
                        .map(SemanticAssetCapabilityGap::getName).filter(StringUtils::isNotBlank)
                        .distinct().toList());
        routeSummary.put("queryOperations",
                readJson(route.getResultOperations(), STRING_LIST, List.of()));
        routeSummary.put("businessAnswers",
                readJson(route.getBusinessAnswers(), ANSWER_MAP, Map.of()));

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schemaVersion", "2.0");
        context.put("action", action.name());
        context.put("routeSummary", routeSummary);
        if (target != null) {
            String baseTable = safe(target.getBaseTables()).stream().findFirst().orElse(null);
            if (StringUtils.isBlank(baseTable)) {
                throw corruptSnapshot();
            }
            Map<String, Object> targetAsset = new LinkedHashMap<>();
            targetAsset.put("candidateHandle", target.getCandidateHandle());
            targetAsset.put("assetType", target.getAssetType());
            targetAsset.put("name", target.getName());
            targetAsset.put("baseVersion", target.getAssetVersion());
            targetAsset.put("baseTable", baseTable);
            context.put("targetAsset", targetAsset);
        }
        return context;
    }

    /**
     * 确认、覆盖或提交业务答案重新分析。
     *
     * <p>
     * 调用示例：{@code confirm(id, req, key, user)}。NEEDS_CLARIFICATION 在必答问题完整后进入下一
     * 分析版本；其他动作使用乐观锁保存目标版本和确认审计。
     * </p>
     *
     * @param id 路由 ID。
     * @param request 确认请求。
     * @param idempotencyKey 必填确认幂等键。
     * @param user 当前用户。
     * @return 确认或待重新分析的路由详情。
     */
    public SemanticAssetRouteResp confirm(Long id, SemanticAssetRouteConfirmReq request,
            String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        // 确认指纹同样包含答案，必须先执行统一结构和 JSON 总长度门禁。
        SemanticAssetBusinessAnswerValidator.validatePayload(request.getBusinessAnswers(),
                objectMapper);
        SemanticAssetRoutingDO route = store.findById(id);
        permissionService.requireRouteReadable(route, user);
        String fingerprint = fingerprint(confirmFingerprintPayload(id, request));
        if (StringUtils.equals(route.getConfirmationIdempotencyKey(), idempotencyKey)) {
            if (!Objects.equals(route.getConfirmationRequestFingerprint(), fingerprint)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key 已用于不同路由确认请求");
            }
            retryConfirmedReuseRefresh(route, user);
            return toResponse(route, user, true);
        }
        if (route.getConfirmedAction() != null) {
            throw conflict("ROUTE_ALREADY_CONFIRMED", "该路由已确认，不能再次覆盖");
        }
        requireSucceededVersion(route, request.getAnalysisVersion());
        List<SemanticAssetCandidate> candidates = readCandidates(route.getCandidateSnapshot());
        List<SemanticAssetBusinessQuestion> questions =
                readJson(route.getBusinessQuestions(), QUESTION_LIST, List.of());
        SemanticAssetBusinessAnswerValidator.validate(questions, request.getBusinessAnswers());

        SemanticAssetRouteAction recommended = parseAction(route.getRecommendedAction());
        if (recommended == SemanticAssetRouteAction.NEEDS_CLARIFICATION) {
            if (request.getAction() != SemanticAssetRouteAction.NEEDS_CLARIFICATION) {
                throw new SemanticAssetRoutingException(HttpStatus.CONFLICT, "REANALYSIS_REQUIRED",
                        "业务口径变化后必须先重新分析，不能直接覆盖动作");
            }
            String reanalysisFingerprint = fingerprint(Map.of(
                    "previousRequestFingerprint", route.getRequestFingerprint(),
                    "analysisVersion", request.getAnalysisVersion() + 1,
                    "businessAnswers", request.getBusinessAnswers()));
            SemanticAssetRoutingDO pending =
                    store.prepareReanalysis(route, request, idempotencyKey, fingerprint,
                            reanalysisFingerprint, user);
            scheduleAnalysis(pending.getId(), user);
            return toResponse(pending, user, false);
        }

        SemanticAssetCandidate candidate = resolveConfirmationCandidate(route, request, candidates);
        boolean override =
                request.getAction() != recommended || !sameRecommendedCandidate(route, candidate);
        if (override && StringUtils.isBlank(request.getOverrideReason())) {
            throw new SemanticAssetRoutingException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "OVERRIDE_REASON_REQUIRED", "更改推荐动作或候选时必须填写覆盖原因");
        }
        validateConfirmationPermission(route, request.getAction(), candidate, user);
        SemanticAssetRoutingStore.ConfirmationResult confirmation =
                store.confirm(route, request, candidate, idempotencyKey, fingerprint, user);
        if (request.getAction() == SemanticAssetRouteAction.REUSE_EXISTING) {
            // CAS 胜者和相同请求的幂等重放都采用“至少一次”刷新：若首次事件发布在确认落库后失败，
            // 客户端可用同一幂等键恢复。DataEvent 的缓存失效/健康状态更新本质幂等；不同指纹或
            // 版本冲突仍会在 store.confirm 中被拒绝，因而不能借重放触发刷新。
            reuseValidationService.refreshConfirmed(route.getId(), candidate, user);
        }
        log.info("semantic asset route confirmed: routeId={}, action={}, result=SUCCESS",
                route.getId(), request.getAction());
        return toResponse(confirmation.route(), user, confirmation.replay());
    }

    /**
     * 为已落库的同指纹 REUSE 重放再次触发幂等刷新。
     *
     * <p>该分支只会在确认幂等键和请求指纹都完全相同时进入；候选仍从持久化白名单快照按
     * 已确认 ID 与版本解析，并由刷新服务重新校验当前权限/版本。这样既能恢复首次发布失败，
     * 又不会让不同请求借重放探测或刷新其他资产。</p>
     */
    private void retryConfirmedReuseRefresh(SemanticAssetRoutingDO route, User user) {
        if (!SemanticAssetRouteAction.REUSE_EXISTING.name().equals(route.getConfirmedAction())) {
            return;
        }
        SemanticAssetCandidate confirmed = readCandidates(route.getCandidateSnapshot()).stream()
                .filter(candidate -> Objects.equals(candidate.getAssetId(),
                        route.getConfirmedCandidateId()))
                .filter(candidate -> Objects.equals(candidate.getAssetVersion(),
                        route.getConfirmedCandidateVersion()))
                .findFirst().orElseThrow(this::corruptSnapshot);
        reuseValidationService.refreshConfirmed(route.getId(), confirmed, user);
    }

    /** 在线程池中提交路由分析；队列满时写入安全失败态。 */
    private void scheduleAnalysis(Long routeId, User user) {
        try {
            executor.execute(() -> analyze(routeId, user));
        } catch (TaskRejectedException exception) {
            store.claimAnalysis(routeId, user.getName())
                    .ifPresent(route -> store.failAnalysis(route, "ROUTING_QUEUE_REJECTED",
                            "路由分析队列已满，请稍后重试", user.getName()));
        }
    }

    /** 在事务外召回候选、调用 Advisor 并由策略裁决。 */
    private void analyze(Long routeId, User user) {
        Optional<SemanticAssetRoutingDO> claimed = store.claimAnalysis(routeId, user.getName());
        if (claimed.isEmpty()) {
            return;
        }
        SemanticAssetRoutingDO route = claimed.orElseThrow();
        long startedAt = System.currentTimeMillis();
        try {
            SemanticAssetRouteAnalyzeReq request = toAnalyzeRequest(route);
            // 每次重分析重新校验来源和数据源 ACL，权限变化时 fail-closed。
            permissionService.requireAnalysisPermission(request, user);
            List<SemanticAssetCandidate> candidates = candidateRetriever.retrieve(request, user);
            SemanticAssetCoverageResult coverage =
                    coverageAnalyzer.analyze(request.getBusinessGoal(), request.getSelectedTables(),
                            request.getBusinessAnswers(), candidates);
            // 强规则已经能完成复用、新建或待澄清裁决时不调用慢速 LLM；其余语义比较必须
            // fail-closed，不能把 Provider 不可用伪装成无建议后继续创建资产。
            SemanticAssetRoutingAdvisorResult advisorResult =
                    advisor.requiresSemanticComparison(coverage)
                            ? advisor.advise(route.getId(), route.getAnalysisVersion(),
                                    request.getChatModelId(), user, request.getBusinessGoal(),
                                    candidates, coverage)
                            : SemanticAssetRoutingAdvisorResult.ruleOnly();
            Optional<SemanticAssetRoutingAdvice> advice = advisorResult.advice();
            SemanticAssetRoutingDecision decision = policy.decide(coverage, advice);
            store.completeAnalysis(route, candidates, coverage, advice, decision,
                    advisorResult.llmConversationId(), user.getName());
            log.info(
                    "semantic asset route analyzed: routeId={}, action={}, candidates={}, "
                            + "source={}, elapsedMs={}, result=SUCCESS",
                    routeId, decision.getAction(), candidates.size(), decision.getDecisionSource(),
                    System.currentTimeMillis() - startedAt);
        } catch (SemanticAssetRoutingAdvisorException exception) {
            store.failAnalysis(route, exception.getErrorCode(),
                    StringUtils.defaultIfBlank(exception.getReason(), "AI 语义比较失败"),
                    exception.getLlmConversationId(), user.getName());
            log.warn("semantic asset route advisor failed: routeId={}, code={}, result=FAILED",
                    routeId, exception.getErrorCode());
        } catch (SemanticAssetRoutingException exception) {
            store.failAnalysis(route, exception.getErrorCode(),
                    StringUtils.defaultIfBlank(exception.getReason(), "路由分析失败"), user.getName());
            log.warn("semantic asset route failed: routeId={}, code={}, result=FAILED", routeId,
                    exception.getErrorCode());
        } catch (RuntimeException exception) {
            store.failAnalysis(route, "ROUTING_ANALYSIS_FAILED", "路由分析失败，请重试", user.getName());
            log.error("semantic asset route failed: routeId={}, type={}, result=FAILED", routeId,
                    exception.getClass().getName(), exception);
        }
    }

    /** 校验路由已成功且客户端分析版本仍为最新。 */
    private void requireSucceededVersion(SemanticAssetRoutingDO route, Integer analysisVersion) {
        if (!SemanticAssetRouteStatus.SUCCEEDED.name().equals(route.getStatus())) {
            throw conflict("ROUTE_NOT_CONFIRMABLE", "路由尚未成功完成或已过期");
        }
        if (route.getExpiresAt() != null
                && !route.getExpiresAt().after(store.currentDatabaseTime())) {
            throw conflict("ROUTE_EXPIRED", "路由快照已过期，请重新分析");
        }
        if (!Objects.equals(route.getAnalysisVersion(), analysisVersion)) {
            throw conflict("ROUTE_ANALYSIS_VERSION_CHANGED", "分析版本已变化，请刷新后确认");
        }
    }

    /** 只从持久化白名单候选解析确认 handle。 */
    private SemanticAssetCandidate resolveConfirmationCandidate(SemanticAssetRoutingDO route,
            SemanticAssetRouteConfirmReq request, List<SemanticAssetCandidate> candidates) {
        if (request.getAction() == SemanticAssetRouteAction.CREATE_NEW
                || request.getAction() == SemanticAssetRouteAction.NEEDS_CLARIFICATION) {
            if (StringUtils.isNotBlank(request.getCandidateHandle())) {
                throw new SemanticAssetRoutingException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_CANDIDATE_HANDLE", "当前动作不能指定候选资产");
            }
            return null;
        }
        String handle = request.getCandidateHandle();
        if (StringUtils.isBlank(handle)) {
            handle = candidates.stream()
                    .filter(item -> Objects.equals(item.getAssetId(),
                            route.getRecommendedCandidateId()))
                    .map(SemanticAssetCandidate::getCandidateHandle).findFirst().orElse(null);
        }
        String expectedHandle = handle;
        return candidates.stream()
                .filter(item -> Objects.equals(item.getCandidateHandle(), expectedHandle))
                .findFirst().orElseThrow(
                        () -> new SemanticAssetRoutingException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_CANDIDATE_HANDLE", "候选资产已失效，请重新分析"));
    }

    /** 判断确认候选是否仍为持久化推荐目标。 */
    private boolean sameRecommendedCandidate(SemanticAssetRoutingDO route,
            SemanticAssetCandidate candidate) {
        return candidate == null ? route.getRecommendedCandidateId() == null
                : Objects.equals(route.getRecommendedCandidateId(), candidate.getAssetId())
                        && Objects.equals(route.getRecommendedCandidateVersion(),
                                candidate.getAssetVersion());
    }

    /** 根据动作重新校验管理权限和目标版本。 */
    private void validateConfirmationPermission(SemanticAssetRoutingDO route,
            SemanticAssetRouteAction action, SemanticAssetCandidate candidate, User user) {
        switch (action) {
            case CREATE_NEW -> {
                if (!permissionService.canCreateAsset(route, user)) {
                    throw new SemanticAssetRoutingException(HttpStatus.FORBIDDEN,
                            "CREATE_PERMISSION_DENIED", "无权在当前范围新建语义资产");
                }
            }
            case EXTEND_EXISTING -> permissionService.requireCurrentCandidateVersion(candidate,
                    user);
            case REUSE_EXISTING -> reuseValidationService.validateCandidate(candidate, user);
            case NEEDS_CLARIFICATION -> throw new SemanticAssetRoutingException(HttpStatus.CONFLICT,
                    "REANALYSIS_REQUIRED", "该动作必须通过重新分析处理");
        }
    }

    /** 将持久化快照还原为重新分析请求。 */
    private SemanticAssetRouteAnalyzeReq toAnalyzeRequest(SemanticAssetRoutingDO route) {
        SemanticAssetRouteAnalyzeReq request = new SemanticAssetRouteAnalyzeReq();
        request.setSourceType(route.getSourceType());
        request.setSourceId(route.getSourceId());
        request.setBusinessGoal(route.getBusinessGoal());
        request.setDomainId(route.getDomainId());
        request.setDataSourceId(route.getDataSourceId());
        request.setCatalogName(route.getCatalogName());
        request.setDatabaseName(route.getDatabaseName());
        request.setSelectedTables(readJson(route.getSelectedTables(), STRING_LIST, List.of()));
        request.setChatModelId(route.getChatModelId());
        request.setIncludeSampleData(route.getIncludeSample());
        request.setBusinessAnswers(readJson(route.getBusinessAnswers(), ANSWER_MAP, Map.of()));
        return request;
    }

    /** 把持久化记录映射为不泄露正式 ID 和分数的 API 响应。 */
    private SemanticAssetRouteResp toResponse(SemanticAssetRoutingDO route, User user,
            boolean replay) {
        List<SemanticAssetCandidate> candidates = readCandidates(route.getCandidateSnapshot());
        // 每次轮询都复核当前 ACL 与候选版本，权限撤销后不返回持久化名称、数量或能力摘要。
        permissionService.requireCandidateSnapshotReadable(candidates, user);
        SemanticAssetCoverageResult coverage = readJson(route.getRuleEvidence(),
                SemanticAssetCoverageResult.class, SemanticAssetCoverageResult.builder().build());
        SemanticAssetCandidate primary = candidates.stream().filter(
                item -> Objects.equals(item.getAssetId(), route.getRecommendedCandidateId()))
                .findFirst().orElse(null);
        SemanticAssetCandidate confirmed = candidates.stream()
                .filter(item -> Objects.equals(item.getAssetId(), route.getConfirmedCandidateId()))
                .findFirst().orElse(null);
        List<SemanticAssetRouteAction> allowed = allowedActions(route, candidates, user);
        boolean canConfirm = route.getConfirmedAction() == null
                && SemanticAssetRouteStatus.SUCCEEDED.name().equals(route.getStatus())
                && !allowed.isEmpty();
        List<SemanticAssetRouteResp.CandidateSummary> alternatives = candidates.stream()
                .filter(item -> primary == null
                        || !Objects.equals(primary.getCandidateHandle(), item.getCandidateHandle()))
                .limit(3).map(item -> toCandidateSummary(item, coverage)).toList();
        SemanticAssetRouteAction recommended = parseAction(route.getRecommendedAction());
        return SemanticAssetRouteResp.builder().id(route.getId())
                .status(parseStatus(route.getStatus())).recommendedAction(recommended)
                .recommendedActionLabel(recommended == null ? null : recommended.getLabel())
                .explanation(explanation(route, coverage, recommended))
                .decisionSource(parseDecisionSource(route.getDecisionSource()))
                .primaryCandidate(primary == null ? null : toCandidateSummary(primary, coverage))
                .alternativeCandidates(alternatives)
                .coveredCapabilities(
                        readJson(route.getCoveredCapabilities(), STRING_LIST, List.of()))
                .missingCapabilities(readJson(route.getMissingCapabilities(), GAP_LIST, List.of()))
                .resultOperations(readJson(route.getResultOperations(), STRING_LIST, List.of()))
                .businessQuestions(readJson(route.getBusinessQuestions(), QUESTION_LIST, List.of()))
                .canConfirm(canConfirm).confirmDisabledReason(confirmDisabledReason(route, allowed))
                .allowedActions(allowed).technicalEvidence(primaryEvidence(coverage))
                .analysisVersion(route.getAnalysisVersion()).lockVersion(route.getLockVersion())
                .confirmedAction(parseAction(route.getConfirmedAction()))
                .confirmedCandidateHandle(confirmed == null ? null : confirmed.getCandidateHandle())
                .businessAnswers(readJson(route.getBusinessAnswers(), ANSWER_MAP, Map.of()))
                .overrideReason(route.getOverrideReason()).confirmedBy(route.getConfirmedBy())
                .confirmedAt(route.getConfirmedAt()).failureCode(route.getFailureCode())
                .failureMessage(route.getFailureMessage()).expiresAt(route.getExpiresAt())
                .idempotentReplay(replay).build();
    }

    /** 计算服务端允许动作集合，前端不得自行推断。 */
    private List<SemanticAssetRouteAction> allowedActions(SemanticAssetRoutingDO route,
            List<SemanticAssetCandidate> candidates, User user) {
        if (!SemanticAssetRouteStatus.SUCCEEDED.name().equals(route.getStatus())
                || route.getConfirmedAction() != null) {
            return List.of();
        }
        List<SemanticAssetBusinessQuestion> questions =
                readJson(route.getBusinessQuestions(), QUESTION_LIST, List.of());
        if (questions.stream().anyMatch(SemanticAssetBusinessQuestion::isRequired)) {
            return List.of(SemanticAssetRouteAction.NEEDS_CLARIFICATION);
        }
        Set<SemanticAssetRouteAction> actions = new LinkedHashSet<>();
        if (!candidates.isEmpty()) {
            actions.add(SemanticAssetRouteAction.REUSE_EXISTING);
        }
        if (candidates.stream().anyMatch(SemanticAssetCandidate::isManageable)) {
            actions.add(SemanticAssetRouteAction.EXTEND_EXISTING);
        }
        if (permissionService.canCreateAsset(route, user)) {
            actions.add(SemanticAssetRouteAction.CREATE_NEW);
        }
        SemanticAssetRouteAction recommended = parseAction(route.getRecommendedAction());
        if (recommended != null && actions.contains(recommended)) {
            List<SemanticAssetRouteAction> ordered = new ArrayList<>();
            ordered.add(recommended);
            actions.stream().filter(item -> item != recommended).forEach(ordered::add);
            return List.copyOf(ordered);
        }
        return List.copyOf(actions);
    }

    /** 将内部候选与覆盖结果映射为安全摘要。 */
    private SemanticAssetRouteResp.CandidateSummary toCandidateSummary(
            SemanticAssetCandidate candidate, SemanticAssetCoverageResult coverage) {
        SemanticAssetCoverageResult.CandidateCoverage item =
                safe(coverage.getCandidateCoverages()).stream()
                        .filter(candidateCoverage -> candidateCoverage.getCandidate() != null
                                && Objects.equals(candidate.getCandidateHandle(),
                                        candidateCoverage.getCandidate().getCandidateHandle()))
                        .findFirst().orElse(null);
        List<String> covered = item == null ? List.of() : safe(item.getCoveredCapabilities());
        List<String> missing = item == null ? List.of() : safe(item.getMissingCapabilities());
        String description = item != null && item.isCompleteCoverage() ? "完整覆盖"
                : "已覆盖 " + covered.size() + " 项，待补充 " + missing.size() + " 项";
        return SemanticAssetRouteResp.CandidateSummary.builder()
                .candidateHandle(candidate.getCandidateHandle()).assetType(candidate.getAssetType())
                .name(candidate.getName()).bizName(candidate.getBizName())
                .description(candidate.getDescription()).grain(safe(candidate.getGrain()))
                .coveredCapabilities(covered).missingCapabilities(missing)
                .coverageDescription(description).manageable(candidate.isManageable())
                .evidenceSources(safe(candidate.getEvidenceSources())).build();
    }

    /** 返回主候选安全技术证据。 */
    private List<String> primaryEvidence(SemanticAssetCoverageResult coverage) {
        return coverage == null || coverage.primaryCandidate() == null ? List.of()
                : safe(coverage.primaryCandidate().getTechnicalEvidence());
    }

    /** 生成简洁推荐说明；完整裁决解释已持久化在 ruleEvidence 时从动作映射。 */
    private String explanation(SemanticAssetRoutingDO route, SemanticAssetCoverageResult coverage,
            SemanticAssetRouteAction action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case REUSE_EXISTING -> "现有资产已覆盖所需业务能力，可直接复用并重新验证";
            case EXTEND_EXISTING -> "现有资产覆盖主体和粒度，仅需补充少量增量能力";
            case CREATE_NEW -> "当前授权范围没有合适候选，且业务边界已明确";
            case NEEDS_CLARIFICATION -> "仍有业务口径或候选歧义，需要回答问题后重新分析";
        };
    }

    /** 生成确认按钮的服务端禁用原因。 */
    private String confirmDisabledReason(SemanticAssetRoutingDO route,
            List<SemanticAssetRouteAction> allowed) {
        if (route.getConfirmedAction() != null) {
            return "该路由已确认";
        }
        if (!SemanticAssetRouteStatus.SUCCEEDED.name().equals(route.getStatus())) {
            return "路由分析尚未完成或已过期";
        }
        return allowed.isEmpty() ? "当前用户没有可执行的确认动作" : null;
    }

    /** 规范化创建请求并固定指纹输入顺序。 */
    private void normalizeRequest(SemanticAssetRouteAnalyzeReq request) {
        request.setSourceType(StringUtils.upperCase(StringUtils.trim(request.getSourceType())));
        request.setBusinessGoal(StringUtils.trim(request.getBusinessGoal()));
        request.setCatalogName(StringUtils.trimToNull(request.getCatalogName()));
        request.setDatabaseName(StringUtils.trimToNull(request.getDatabaseName()));
        request.setSelectedTables(safe(request.getSelectedTables()).stream().map(String::trim)
                .filter(StringUtils::isNotBlank).distinct().sorted().toList());
        request.setBusinessAnswers(
                new TreeMap<>(Objects.requireNonNullElse(request.getBusinessAnswers(), Map.of())));
    }

    /** 构造创建请求指纹负载。 */
    private Object createFingerprintPayload(SemanticAssetRouteAnalyzeReq request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceType", request.getSourceType());
        payload.put("sourceId", request.getSourceId());
        payload.put("businessGoal", request.getBusinessGoal());
        payload.put("domainId", request.getDomainId());
        payload.put("dataSourceId", request.getDataSourceId());
        payload.put("catalogName", request.getCatalogName());
        payload.put("databaseName", request.getDatabaseName());
        payload.put("selectedTables", request.getSelectedTables());
        payload.put("chatModelId", request.getChatModelId());
        payload.put("includeSampleData", request.getIncludeSampleData());
        payload.put("businessAnswers", request.getBusinessAnswers());
        return payload;
    }

    /** 构造确认请求指纹负载。 */
    private Object confirmFingerprintPayload(Long id, SemanticAssetRouteConfirmReq request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeId", id);
        payload.put("analysisVersion", request.getAnalysisVersion());
        payload.put("action", request.getAction());
        payload.put("candidateHandle", StringUtils.trimToNull(request.getCandidateHandle()));
        payload.put("businessAnswers",
                new TreeMap<>(Objects.requireNonNullElse(request.getBusinessAnswers(), Map.of())));
        payload.put("overrideReason", StringUtils.trimToNull(request.getOverrideReason()));
        return payload;
    }

    /** 计算稳定 SHA-256 请求指纹。 */
    private String fingerprint(Object payload) {
        try {
            byte[] canonical =
                    objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new SemanticAssetRoutingException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ROUTING_FINGERPRINT_FAILED", "无法计算路由请求指纹");
        }
    }

    /** 校验幂等键长度。 */
    private void validateIdempotencyKey(String key) {
        if (StringUtils.isBlank(key)
                || key.length() > SemanticAssetRoutingConstants.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new SemanticAssetRoutingException(HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key 不能为空且长度不能超过 128");
        }
    }

    /** 读取候选快照。 */
    private List<SemanticAssetCandidate> readCandidates(String json) {
        return readJson(json, CANDIDATE_LIST, List.of());
    }

    /** 读取指定类型 JSON；空值返回默认值，损坏快照 fail-closed。 */
    private <T> T readJson(String json, TypeReference<T> type, T defaultValue) {
        if (StringUtils.isBlank(json)) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw corruptSnapshot();
        }
    }

    /** 读取普通 Class 类型 JSON。 */
    private <T> T readJson(String json, Class<T> type, T defaultValue) {
        if (StringUtils.isBlank(json)) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw corruptSnapshot();
        }
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 解析可空动作枚举。 */
    private SemanticAssetRouteAction parseAction(String value) {
        return StringUtils.isBlank(value) ? null : SemanticAssetRouteAction.valueOf(value);
    }

    /** 解析状态枚举。 */
    private SemanticAssetRouteStatus parseStatus(String value) {
        return SemanticAssetRouteStatus.valueOf(value);
    }

    /** 解析可空决策来源。 */
    private SemanticAssetDecisionSource parseDecisionSource(String value) {
        return StringUtils.isBlank(value) ? null : SemanticAssetDecisionSource.valueOf(value);
    }

    /** 创建损坏持久化快照错误。 */
    private SemanticAssetRoutingException corruptSnapshot() {
        return new SemanticAssetRoutingException(HttpStatus.CONFLICT, "ROUTE_SNAPSHOT_INVALID",
                "路由分析快照已损坏，请重新分析");
    }

    /** 创建 409 冲突。 */
    private SemanticAssetRoutingException conflict(String code, String message) {
        return new SemanticAssetRoutingException(HttpStatus.CONFLICT, code, message);
    }
}
