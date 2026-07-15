package com.tencent.supersonic.headless.server.semantic.modeling;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftAttemptDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftAttemptMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.PreflightSnapshot;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.ValidationContext;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStore.CreateResult;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStore.RegenerationResult;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftValidator.ValidatedDraft;
import com.tencent.supersonic.headless.server.semantic.routing.ConfirmedSemanticAssetRoute;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetCandidate;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRouteAction;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRoutingException;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRoutingPermissionService;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRoutingService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * AI 语义建模草稿应用服务。
 *
 * <p>
 * 职责说明：编排创建预检、GENERATING 短事务、事务提交后入队、分页权限过滤、详情、人工保存和版本
 * 只读查询。该服务从不调用正式模型/维度/指标/术语写接口，也不发布事件或触发知识重载。并发说明：创建 使用幂等唯一键，Gap 使用行锁，保存使用
 * lockVersion，生成使用有界线程池和数据库条件认领。
 * </p>
 */
@Service
public class ModelingDraftService {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ModelingValidationIssue>> VALIDATION_ISSUE_LIST_TYPE =
            new TypeReference<>() {};

    private final ModelingDraftContextBuilder contextBuilder;
    private final ModelingDraftStore store;
    private final ModelingDraftGenerationWorker worker;
    private final SemanticModelingDraftMapper draftMapper;
    private final SemanticModelingDraftAttemptMapper attemptMapper;
    private final SemanticModelingDraftVersionMapper versionMapper;
    private final com.tencent.supersonic.headless.server.service.DatabaseService databaseService;
    private final ModelingDraftStage4PermissionService writePermissionService;
    private final ModelingDraftValidator validator;
    private final ModelingDraftRouteGuard routeGuard;
    private final SemanticAssetRoutingService routingService;
    private final SemanticAssetRoutingPermissionService routingPermissionService;
    private final ModelService modelService;
    private final SemanticModelingProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;

    /**
     * 创建草稿应用服务。
     *
     * @param contextBuilder 安全上下文构建器。
     * @param store 短事务存储服务。
     * @param worker 异步生成 Worker。
     * @param draftMapper 草稿 Mapper。
     * @param attemptMapper 生成尝试 Mapper。
     * @param versionMapper 版本 Mapper。
     * @param databaseService 数据源 ACL 服务。
     * @param writePermissionService 草稿统一写权限服务。
     * @param validator 草稿校验器。
     * @param routeGuard 已确认路由的人工保存不可漂移校验器。
     * @param routingService 已确认路由读取与权限/版本复核服务。
     * @param routingPermissionService 路由目标模型的当前 VIEWER 权限与版本复核服务。
     * @param modelService 模型 ACL 批量查询服务，用于在数据库分页前过滤不可见增强草稿。
     * @param properties 阶段 3 配置。
     * @param executor 专用有界执行器。
     * @param objectMapper JSON 映射器。
     */
    public ModelingDraftService(ModelingDraftContextBuilder contextBuilder,
            ModelingDraftStore store, ModelingDraftGenerationWorker worker,
            SemanticModelingDraftMapper draftMapper,
            SemanticModelingDraftAttemptMapper attemptMapper,
            SemanticModelingDraftVersionMapper versionMapper,
            com.tencent.supersonic.headless.server.service.DatabaseService databaseService,
            ModelingDraftStage4PermissionService writePermissionService,
            ModelingDraftValidator validator, ModelingDraftRouteGuard routeGuard,
            SemanticAssetRoutingService routingService,
            SemanticAssetRoutingPermissionService routingPermissionService,
            ModelService modelService,
            SemanticModelingProperties properties,
            @Qualifier("semanticModelingExecutor") ThreadPoolTaskExecutor executor,
            ObjectMapper objectMapper) {
        this.contextBuilder = contextBuilder;
        this.store = store;
        this.worker = worker;
        this.draftMapper = draftMapper;
        this.attemptMapper = attemptMapper;
        this.versionMapper = versionMapper;
        this.databaseService = databaseService;
        this.writePermissionService = writePermissionService;
        this.validator = validator;
        this.routeGuard = routeGuard;
        this.routingService = routingService;
        this.routingPermissionService = routingPermissionService;
        this.modelService = modelService;
        this.properties = properties;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建异步语义建模草稿。
     *
     * <p>
     * 调用示例：{@code create(req, idempotencyKey, user)}。方法先完成 ACL 与元数据预检，再用短事务只 保存
     * GENERATING；事务返回后才向有界执行器提交。幂等重放直接返回原 ID，不重复调用 LLM。
     * </p>
     *
     * @param request 创建请求。
     * @param idempotencyKey 请求头幂等键。
     * @param user 当前用户。
     * @return 新建或重放的草稿摘要。
     */
    public ModelingDraftResp create(ModelingDraftGenerateReq request, String idempotencyKey,
            User user) {
        validateIdempotencyKey(idempotencyKey);
        if (request.getRouteAnalysisId() == null) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST,
                    "创建草稿前必须先确认语义资产路由");
        }
        SemanticModelingDraftDO existing =
                store.findByIdempotencyKey(user.getName(), idempotencyKey);
        if (existing != null) {
            return replayExistingCreation(request, existing, user);
        }
        ConfirmedSemanticAssetRoute route =
                routingService.requireConsumableRoute(request.getRouteAnalysisId(), user);
        applyConfirmedRoute(request, route, false);
        PreflightSnapshot snapshot = contextBuilder.preflight(request, user);
        String requestFingerprint = fingerprint(createFingerprintPayload(snapshot.request()));
        // 元数据预检可能访问远端数据源；入库前再次校验权限、目标版本与未消费状态，缩短 TOCTOU 窗口。
        routingService.requireConsumableRoute(request.getRouteAnalysisId(), user);
        CreateResult result;
        try {
            result = store.createGenerating(snapshot.request(), idempotencyKey, requestFingerprint,
                    user);
        } catch (DuplicateKeyException exception) {
            // 不同实例可能同时通过前置查询；数据库唯一键决定胜者，败者在新事务中读取既有记录。
            SemanticModelingDraftDO replay =
                    store.findByIdempotencyKey(user.getName(), idempotencyKey);
            if (replay == null) {
                throw exception;
            }
            result = new CreateResult(replay, true);
        }
        if (result.replay()) {
            // 数据库唯一键或 Gap 行锁可能让本实例成为并发败者。返回前必须同时复核：路由仍绑定
            // 该草稿、目标权限/版本未漂移、attempt 1 指纹一致，以及既有草稿数据源仍可访问。
            if (!Objects.equals(result.draft().getRouteAnalysisId(), request.getRouteAnalysisId())) {
                throw new ModelingDraftException(HttpStatus.CONFLICT,
                        ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key 已用于不同路由快照的草稿请求");
            }
            routingService.requireBoundRoute(request.getRouteAnalysisId(),
                    result.draft().getId(), user);
            store.validateCreationReplay(result.draft().getId(), requestFingerprint,
                    result.draft());
            verifyDatabaseAccess(result.draft(), user);
        }
        if (!result.replay()) {
            try {
                Long draftId = result.draft().getId();
                executor.execute(() -> worker.generate(draftId, 1, snapshot, user));
            } catch (TaskRejectedException exception) {
                store.failGeneration(result.draft().getId(), 1,
                        ModelingDraftConstants.ERROR_QUEUE_REJECTED, "草稿生成队列已满，请稍后重新生成", null, null,
                        ModelingDraftConstants.FAILURE_STAGE_QUEUE, List.of(), null, null,
                        user.getName());
                result = new CreateResult(draftMapper.selectById(result.draft().getId()), false);
            }
        }
        return toResponse(result.draft(), result.replay());
    }

    /**
     * 安全重放既有创建结果，并重新执行参数、ACL 和目标版本校验。
     *
     * <p>快速重放不能仅比较路由 ID：相同键可能携带不同 LLM、样例开关或范围参数，且增强目标
     * 可能在首次请求后被撤权或升级。因此先按既有草稿绑定读取确认路由，再用冻结路由重建预检
     * 快照并核对 attempt 1 指纹；慢速预检后再次检查绑定，避免 TOCTOU。</p>
     *
     * @param request 本次创建请求，路由范围会由确认快照覆盖。
     * @param existing 幂等键命中的既有草稿。
     * @param user 当前用户。
     * @return 原草稿的幂等重放响应。
     * @throws ModelingDraftException 路由、权限、版本或请求指纹不一致时抛出冲突。
     */
    private ModelingDraftResp replayExistingCreation(ModelingDraftGenerateReq request,
            SemanticModelingDraftDO existing, User user) {
        if (!Objects.equals(existing.getRouteAnalysisId(), request.getRouteAnalysisId())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key 已用于不同路由快照的草稿请求");
        }
        ConfirmedSemanticAssetRoute route = routingService.requireBoundRoute(
                existing.getRouteAnalysisId(), existing.getId(), user);
        applyConfirmedRoute(request, route, false);
        PreflightSnapshot snapshot = contextBuilder.preflight(request, user);
        String requestFingerprint = fingerprint(createFingerprintPayload(snapshot.request()));
        routingService.requireBoundRoute(existing.getRouteAnalysisId(), existing.getId(), user);
        store.validateCreationReplay(existing.getId(), requestFingerprint, existing);
        verifyDatabaseAccess(existing, user);
        return toResponse(existing, true);
    }

    /**
     * 对尚未产生版本的失败草稿发起下一次人工生成。
     *
     * <p>
     * 调用示例：{@code regenerate(id, req, idempotencyKey, user)}。方法从主表恢复不可变业务范围，
     * 仅覆盖模型和样例开关；ACL、表字段和模型能力会重新预检。短事务提交后才入队，避免 Worker 读取到未提交 attempt。
     * </p>
     *
     * @param id 草稿 ID。
     * @param request 人工重新生成参数。
     * @param idempotencyKey 请求头幂等键。
     * @param user 当前用户。
     * @return GENERATING 草稿或幂等重放结果。
     */
    public ModelingDraftResp regenerate(Long id, ModelingDraftRegenerateReq request,
            String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        SemanticModelingDraftDO draft = writePermissionService.requireManageable(id, user);
        String requestFingerprint = fingerprint(regenerationFingerprintPayload(id, request));
        SemanticModelingDraftAttemptDO existing =
                store.findAttemptByIdempotencyKey(user.getName(), idempotencyKey);
        if (existing != null) {
            store.validateRegenerationReplay(id, requestFingerprint, existing);
            return toResponse(writePermissionService.requireManageable(id, user), true);
        }
        assertRegenerationPreconditions(draft);

        // 重建创建请求以复用完全相同的 ACL、模型能力、Gap 和服务端真实表字段预检。
        ModelingDraftGenerateReq generationRequest = toRegenerationGenerateReq(draft, request);
        if (draft.getRouteAnalysisId() != null) {
            ConfirmedSemanticAssetRoute route = routingService.requireBoundRoute(
                    draft.getRouteAnalysisId(), draft.getId(), user);
            applyConfirmedRoute(generationRequest, route, true);
        }
        PreflightSnapshot snapshot = contextBuilder.preflight(generationRequest, user);
        if (draft.getRouteAnalysisId() != null) {
            // 重新生成入队前再次校验，防止慢速预检期间目标资产被更新或权限被撤销。
            routingService.requireBoundRoute(draft.getRouteAnalysisId(), draft.getId(), user);
        }
        RegenerationResult result;
        try {
            result = store.regenerate(draft, request.getLockVersion(), request.getChatModelId(),
                    Boolean.TRUE.equals(request.getIncludeSampleData()), idempotencyKey,
                    requestFingerprint, configuredManualRegenerationLimit(), user);
        } catch (DuplicateKeyException exception) {
            SemanticModelingDraftAttemptDO replay =
                    store.findAttemptByIdempotencyKey(user.getName(), idempotencyKey);
            if (replay == null) {
                throw exception;
            }
            store.validateRegenerationReplay(id, requestFingerprint, replay);
            result = new RegenerationResult(draftMapper.selectById(id), replay, true);
        }

        if (!result.replay()) {
            Integer attemptNo = result.attempt().getAttemptNo();
            try {
                executor.execute(() -> worker.generate(id, attemptNo, snapshot, user));
            } catch (TaskRejectedException exception) {
                store.failGeneration(id, attemptNo, ModelingDraftConstants.ERROR_QUEUE_REJECTED,
                        "草稿生成队列已满，请稍后重新生成", null, null, ModelingDraftConstants.FAILURE_STAGE_QUEUE,
                        List.of(), null, null, user.getName());
            }
        }
        return toResponse(draftMapper.selectById(id), result.replay());
    }

    /**
     * 分页查询当前用户可访问数据源下的草稿。
     *
     * @param request 筛选条件。
     * @param user 当前用户。
     * @return 权限过滤后的分页结果。
     */
    public PageInfo<ModelingDraftResp> query(ModelingDraftQueryReq request, User user) {
        recoverStaleGenerations();
        Set<Long> accessibleDatabaseIds =
                databaseService.getDatabaseList(user).stream().map(DatabaseResp::getId)
                        .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (accessibleDatabaseIds.isEmpty()) {
            return emptyPage(request.getPage(), request.getPageSize());
        }

        // 一次性加载当前账号可读取的模型 ID，并把过滤条件下推到分页 SQL。这样既不会在草稿循环中
        // 产生 N+1 ACL 查询，也不会把已撤权/删除目标对应的草稿数量暴露在分页 total 中。
        Set<Long> readableModelIds = loadReadableModelIds(user);

        LambdaQueryWrapper<SemanticModelingDraftDO> wrapper =
                new LambdaQueryWrapper<SemanticModelingDraftDO>()
                        .in(SemanticModelingDraftDO::getDataSourceId, accessibleDatabaseIds)
                        .orderByDesc(SemanticModelingDraftDO::getId);
        applyRouteTargetAclFilter(wrapper, readableModelIds);
        if (StringUtils.isNotBlank(request.getSourceType())) {
            wrapper.eq(SemanticModelingDraftDO::getSourceType,
                    request.getSourceType().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.isNotBlank(request.getStatus())) {
            wrapper.eq(SemanticModelingDraftDO::getStatus,
                    request.getStatus().trim().toUpperCase(Locale.ROOT));
        }
        if (request.getDataSourceId() != null) {
            wrapper.eq(SemanticModelingDraftDO::getDataSourceId, request.getDataSourceId());
        }
        if (StringUtils.isNotBlank(request.getKeyword())) {
            String keyword = StringUtils.abbreviate(request.getKeyword().trim(), 128);
            wrapper.and(nested -> nested.like(SemanticModelingDraftDO::getTitle, keyword).or()
                    .like(SemanticModelingDraftDO::getBusinessGoal, keyword));
        }
        PageInfo<SemanticModelingDraftDO> page =
                PageHelper.startPage(request.getPage(), request.getPageSize())
                        .doSelectPageInfo(() -> draftMapper.selectList(wrapper));
        // 列表不逐条查询主题域/数据源管理 ACL，避免 N+1；超级管理员可直接确认写权限，
        // 其他用户进入详情后由 canManage 精确判定并开放重生成等写入口。
        Boolean listManageCapability = user.isSuperAdmin() ? Boolean.TRUE : null;
        return mapPage(page, page.getList().stream()
                // Mapper 单测不会执行 Lambda 条件；这里保留同语义的纵深防御，不额外访问数据库。
                .filter(item -> isRouteTargetReadable(item, readableModelIds))
                .map(item -> toResponse(item, false, false, listManageCapability)).toList());
    }

    /**
     * 查询草稿详情并复核数据源权限。
     *
     * @param id 草稿 ID。
     * @param user 当前用户。
     * @return 当前草稿。
     */
    public ModelingDraftResp get(Long id, User user) {
        recoverStaleGenerations();
        SemanticModelingDraftDO draft = requireAccessible(id, user);
        requireRouteTargetReadable(draft, user);
        return toResponse(draft, false, true, writePermissionService.canManage(draft, user));
    }

    /**
     * 校验并保存人工修改，生成下一不可变版本。
     *
     * @param id 草稿 ID。
     * @param request 保存请求。
     * @param user 当前用户。
     * @return 保存后的草稿。
     * @throws ModelingDraftException 草稿状态、管理权限、版本或结构化内容不满足保存约束。
     */
    public ModelingDraftResp save(Long id, ModelingDraftSaveReq request, User user) {
        SemanticModelingDraftDO draft = writePermissionService.requireManageable(id, user);
        if (!ModelingDraftConstants.STATUS_DRAFT.equals(draft.getStatus())) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT, "只有生成成功的 DRAFT 状态可以保存");
        }
        if (draft.getRouteAnalysisId() != null) {
            routingService.requireBoundRoute(draft.getRouteAnalysisId(), draft.getId(), user);
        }
        String json = resolveSaveJson(request);
        List<String> selectedTables = readSelectedTables(draft.getSelectedTables());
        ValidationContext context = contextBuilder.reloadValidationContext(draft.getDataSourceId(),
                draft.getCatalogName(), draft.getDatabaseName(), selectedTables,
                draft.getDomainId(), user);
        ValidatedDraft validated = validator.validateAndNormalize(json, context.columnsByTable(),
                context.existingNames());
        routeGuard.validateMutation(draft, draft.getDraftJson(), validated.payload());
        SemanticModelingDraftDO saved = store.saveVersion(draft, request.getLockVersion(),
                validated.json(), request.getChangeSummary(), user);
        // 本请求已由 requireManageable 完成服务端授权，保存不会改变数据源/主题域绑定；响应必须保留
        // 明确的管理能力，否则前端用完整响应替换详情后会被临时降为只读。后续写请求仍会重新鉴权。
        return toResponse(saved, false, true, Boolean.TRUE);
    }

    /**
     * 分页查询草稿版本摘要，快照按需加载。
     *
     * @param draftId 草稿 ID。
     * @param page 页码。
     * @param pageSize 页大小。
     * @param user 当前用户。
     * @return 版本摘要分页。
     */
    public PageInfo<ModelingDraftVersionResp> queryVersions(Long draftId, int page, int pageSize,
            User user) {
        requireAccessible(draftId, user);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        PageInfo<SemanticModelingDraftVersionDO> result = PageHelper
                .startPage(safePage, safePageSize)
                .doSelectPageInfo(() -> versionMapper
                        .selectList(new LambdaQueryWrapper<SemanticModelingDraftVersionDO>()
                                // 历史列表只读取摘要列；完整 draft_json 仅在按版本查看或比较时单条加载，
                                // 避免 50 个大 JSON 快照同时进入 JVM 内存。
                                .select(SemanticModelingDraftVersionDO::getId,
                                        SemanticModelingDraftVersionDO::getDraftId,
                                        SemanticModelingDraftVersionDO::getVersionNo,
                                        SemanticModelingDraftVersionDO::getChangeSource,
                                        SemanticModelingDraftVersionDO::getChangeSummary,
                                        SemanticModelingDraftVersionDO::getCreatedBy,
                                        SemanticModelingDraftVersionDO::getCreatedAt)
                                .eq(SemanticModelingDraftVersionDO::getDraftId, draftId)
                                .orderByDesc(SemanticModelingDraftVersionDO::getVersionNo)));
        List<ModelingDraftVersionResp> responses = result.getList().stream()
                .map(version -> toVersionResponse(version, false)).toList();
        return mapPage(result, responses);
    }

    /**
     * 按版本号加载不可变快照。
     *
     * @param draftId 草稿 ID。
     * @param versionNo 版本号。
     * @param user 当前用户。
     * @return 版本快照。
     */
    public ModelingDraftVersionResp getVersion(Long draftId, Integer versionNo, User user) {
        requireAccessible(draftId, user);
        SemanticModelingDraftVersionDO version =
                versionMapper.selectOne(new LambdaQueryWrapper<SemanticModelingDraftVersionDO>()
                        .eq(SemanticModelingDraftVersionDO::getDraftId, draftId)
                        .eq(SemanticModelingDraftVersionDO::getVersionNo, versionNo));
        if (version == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "草稿版本不存在");
        }
        return toVersionResponse(version, true);
    }

    /**
     * 分页查询草稿生成尝试的安全摘要。
     *
     * @param draftId 草稿 ID。
     * @param page 页码。
     * @param pageSize 页大小。
     * @param user 当前用户。
     * @return attempt 倒序分页，不含模型正文和 Prompt。
     */
    public PageInfo<ModelingDraftAttemptResp> queryAttempts(Long draftId, int page, int pageSize,
            User user) {
        requireAccessible(draftId, user);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        PageInfo<SemanticModelingDraftAttemptDO> result =
                PageHelper.startPage(safePage, safePageSize)
                        .doSelectPageInfo(() -> attemptMapper.selectList(safeAttemptQuery(draftId)
                                .orderByDesc(SemanticModelingDraftAttemptDO::getAttemptNo)));
        return mapPage(result, result.getList().stream().map(this::toAttemptResponse).toList());
    }

    /**
     * 查询单个生成尝试的安全详情。
     *
     * @param draftId 草稿 ID。
     * @param attemptNo 尝试序号。
     * @param user 当前用户。
     * @return attempt 安全详情。
     */
    public ModelingDraftAttemptResp getAttempt(Long draftId, Integer attemptNo, User user) {
        requireAccessible(draftId, user);
        SemanticModelingDraftAttemptDO attempt = attemptMapper.selectOne(safeAttemptQuery(draftId)
                .eq(SemanticModelingDraftAttemptDO::getAttemptNo, attemptNo));
        if (attempt == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "草稿生成尝试不存在");
        }
        return toAttemptResponse(attempt);
    }

    /** 在列表和详情读取前懒恢复超时任务。 */
    public int recoverStaleGenerations() {
        long timeoutMillis = properties.resolveGenerationTimeoutMillis();
        return store.failStaleGenerations(new Date(System.currentTimeMillis() - timeoutMillis));
    }

    /** 在读取元数据前快速拒绝明显不允许重试的状态，事务内仍会再次复核。 */
    private void assertRegenerationPreconditions(SemanticModelingDraftDO draft) {
        if (!ModelingDraftConstants.STATUS_GENERATION_FAILED.equals(draft.getStatus())
                || Objects.requireNonNullElse(draft.getCurrentVersionNo(), 0) != 0) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_REGENERATION_NOT_ALLOWED, "只有尚未产生版本的生成失败草稿可以重新生成");
        }
        int manualCount =
                Math.max(0, Objects.requireNonNullElse(draft.getCurrentAttemptNo(), 1) - 1);
        if (manualCount >= configuredManualRegenerationLimit()) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_REGENERATION_LIMIT, "该草稿已达到人工重新生成次数上限");
        }
    }

    /** 从主表恢复不可变业务范围，只应用重试接口允许修改的两个参数。 */
    private ModelingDraftGenerateReq toRegenerationGenerateReq(SemanticModelingDraftDO draft,
            ModelingDraftRegenerateReq request) {
        ModelingDraftGenerateReq generation = new ModelingDraftGenerateReq();
        generation.setSourceType(draft.getSourceType());
        generation.setSourceId(draft.getSourceId());
        generation.setTitle(draft.getTitle());
        generation.setBusinessGoal(draft.getBusinessGoal());
        generation.setDomainId(draft.getDomainId());
        generation.setDataSourceId(draft.getDataSourceId());
        generation.setCatalogName(draft.getCatalogName());
        generation.setDatabaseName(draft.getDatabaseName());
        generation.setSelectedTables(readSelectedTables(draft.getSelectedTables()));
        generation.setChatModelId(request.getChatModelId());
        generation.setIncludeSampleData(Boolean.TRUE.equals(request.getIncludeSampleData()));
        generation.setRouteAnalysisId(draft.getRouteAnalysisId());
        return generation;
    }

    /**
     * 校验客户端范围与确认路由完全一致，并用服务端快照填充不可伪造字段。
     *
     * <p>路由是草稿创建的授权事实源。首次创建时 LLM 与样例开关也必须匹配分析快照；人工重试只
     * 允许覆盖这两个生成选项，因为它们不会改变已确认的业务范围、候选目标和口径答案。其余范围
     * 任一变化都要求重新分析，不能在草稿接口中偷偷替换。</p>
     */
    private void applyConfirmedRoute(ModelingDraftGenerateReq request,
            ConfirmedSemanticAssetRoute route, boolean allowGenerationOptionOverride) {
        List<String> requestTables = normalizeTables(request.getSelectedTables());
        List<String> routeTables = normalizeTables(route.getSelectedTables());
        boolean matches = Objects.equals(StringUtils.upperCase(request.getSourceType()),
                route.getSourceType())
                && Objects.equals(request.getSourceId(), route.getSourceId())
                && Objects.equals(StringUtils.trim(request.getBusinessGoal()),
                        route.getBusinessGoal())
                && Objects.equals(request.getDomainId(), route.getDomainId())
                && Objects.equals(request.getDataSourceId(), route.getDataSourceId())
                && Objects.equals(StringUtils.trimToNull(request.getCatalogName()),
                        StringUtils.trimToNull(route.getCatalogName()))
                && Objects.equals(StringUtils.trimToNull(request.getDatabaseName()),
                        StringUtils.trimToNull(route.getDatabaseName()))
                && Objects.equals(requestTables, routeTables)
                && (allowGenerationOptionOverride
                        || (Objects.equals(request.getChatModelId(), route.getChatModelId())
                                && Objects.equals(
                                        Boolean.TRUE.equals(request.getIncludeSampleData()),
                                        Boolean.TRUE.equals(route.getIncludeSampleData()))));
        if (!matches) {
            throw new ModelingDraftException(HttpStatus.CONFLICT,
                    ModelingDraftConstants.ERROR_CONFLICT,
                    "草稿范围与已确认路由快照不一致，请重新分析");
        }
        request.setSourceType(route.getSourceType());
        request.setSourceId(route.getSourceId());
        request.setBusinessGoal(route.getBusinessGoal());
        request.setDomainId(route.getDomainId());
        request.setDataSourceId(route.getDataSourceId());
        request.setCatalogName(route.getCatalogName());
        request.setDatabaseName(route.getDatabaseName());
        request.setSelectedTables(routeTables);
        request.setRouteAction(route.getAction().name());
        request.setRouteContext(new LinkedHashMap<>(route.getPromptContext()));
        request.setRouteTargetAssetType(route.getTargetAssetType());
        request.setRouteTargetAssetId(route.getTargetAssetId());
        request.setRouteTargetAssetVersion(route.getTargetAssetVersion());
    }

    /** 规范化选表顺序，使路由指纹和草稿范围不受 UI 选择顺序影响。 */
    private List<String> normalizeTables(List<String> tables) {
        return Objects.requireNonNullElse(tables, List.<String>of()).stream().map(StringUtils::trim)
                .filter(StringUtils::isNotBlank).distinct().sorted().toList();
    }

    /** 构造首次创建 attempt 的稳定指纹载荷。 */
    private Object createFingerprintPayload(ModelingDraftGenerateReq request) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "CREATE");
        payload.put("routeAnalysisId", request.getRouteAnalysisId());
        payload.put("routeAction", request.getRouteAction());
        payload.put("routeTargetAssetVersion", request.getRouteTargetAssetVersion());
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
        return payload;
    }

    /** 构造人工重试幂等指纹载荷，保留客户端原始 lockVersion 以支持网络重放。 */
    private Object regenerationFingerprintPayload(Long draftId,
            ModelingDraftRegenerateReq request) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "REGENERATE");
        payload.put("draftId", draftId);
        payload.put("lockVersion", request.getLockVersion());
        payload.put("chatModelId", request.getChatModelId());
        payload.put("includeSampleData", request.getIncludeSampleData());
        return payload;
    }

    /** 使用 SHA-256 生成不含业务明文的稳定请求指纹。 */
    private String fingerprint(Object payload) {
        try {
            byte[] canonical =
                    objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new ModelingDraftException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ModelingDraftConstants.ERROR_INTERNAL, "无法生成幂等请求指纹");
        }
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

    /** 查询草稿并复核数据源 ACL。 */
    private SemanticModelingDraftDO requireAccessible(Long id, User user) {
        SemanticModelingDraftDO draft = draftMapper.selectById(id);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        verifyDatabaseAccess(draft, user);
        return draft;
    }

    /** 复核草稿关联数据源权限。 */
    private void verifyDatabaseAccess(SemanticModelingDraftDO draft, User user) {
        try {
            if (databaseService.getDatabase(draft.getDataSourceId(), user) == null) {
                throw new IllegalArgumentException("Database not found");
            }
        } catch (RuntimeException exception) {
            throw new ModelingDraftException(HttpStatus.FORBIDDEN,
                    ModelingDraftConstants.ERROR_ACCESS_DENIED, "无权访问该草稿的数据源");
        }
    }

    /**
     * 一次性读取当前用户拥有 VIEWER 权限的全部模型 ID。
     *
     * <p>传入空主题域沿用现有模型权限服务的“全部可见主题域”语义。该集合只在当前请求内使用，
     * 不做单例缓存，避免撤权后继续命中陈旧权限。</p>
     */
    private Set<Long> loadReadableModelIds(User user) {
        List<ModelResp> readableModels = modelService.getModelListWithAuth(user, null,
                AuthType.VIEWER);
        if (readableModels == null || readableModels.isEmpty()) {
            return Set.of();
        }
        return readableModels.stream().filter(Objects::nonNull).map(ModelResp::getId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 将增强草稿的目标模型 ACL 下推到分页 SQL，避免 total 泄露不可见目标的草稿数量。 */
    void applyRouteTargetAclFilter(LambdaQueryWrapper<SemanticModelingDraftDO> wrapper,
            Set<Long> readableModelIds) {
        wrapper.and(nested -> {
            // 历史草稿和 CREATE_NEW 等动作不依赖正式目标模型，必须保持原有可见性。
            nested.isNull(SemanticModelingDraftDO::getRouteAction).or()
                    .ne(SemanticModelingDraftDO::getRouteAction,
                            SemanticAssetRouteAction.EXTEND_EXISTING.name());
            if (!readableModelIds.isEmpty()) {
                nested.or().in(SemanticModelingDraftDO::getRouteTargetAssetId,
                        readableModelIds);
            }
        });
    }

    /** 判断单条草稿是否仍满足与分页 SQL 相同的目标模型可见性规则。 */
    boolean isRouteTargetReadable(SemanticModelingDraftDO draft,
            Set<Long> readableModelIds) {
        if (!SemanticAssetRouteAction.EXTEND_EXISTING.name().equals(draft.getRouteAction())) {
            return true;
        }
        return draft.getRouteTargetAssetId() != null
                && readableModelIds.contains(draft.getRouteTargetAssetId());
    }

    /**
     * 详情读取时复核增强目标的当前 VIEWER 权限、存在性与基线版本。
     *
     * <p>权限服务对撤权、删除和未知目标统一返回安全不可用，服务层不会把正式模型 ID、名称或
     * ACL 判断差异写入响应。版本漂移继续沿用路由层的明确冲突语义。</p>
     */
    private void requireRouteTargetReadable(SemanticModelingDraftDO draft, User user) {
        if (!SemanticAssetRouteAction.EXTEND_EXISTING.name().equals(draft.getRouteAction())) {
            return;
        }
        if (draft.getRouteTargetAssetId() == null || draft.getRouteTargetAssetVersion() == null
                || draft.getDomainId() == null) {
            throw routeTargetUnavailable();
        }
        SemanticAssetCandidate target = SemanticAssetCandidate.builder()
                .candidateHandle("confirmed_route_target")
                .assetType(draft.getRouteTargetAssetType())
                .assetId(draft.getRouteTargetAssetId())
                .assetVersion(draft.getRouteTargetAssetVersion())
                .domainId(draft.getDomainId())
                .dataSourceId(draft.getDataSourceId())
                .build();
        try {
            routingPermissionService.requireReadableCandidateVersion(target, user);
        } catch (SemanticAssetRoutingException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                // 不透传底层关于删除或撤权的差异信息，统一表现为草稿不可访问。
                throw routeTargetUnavailable();
            }
            throw exception;
        }
    }

    /** 创建不区分目标删除、撤权或损坏快照的安全不可用异常。 */
    private ModelingDraftException routeTargetUnavailable() {
        return new ModelingDraftException(HttpStatus.NOT_FOUND,
                ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在或不可访问");
    }

    /** 解析人工保存 JSON 的两个兼容字段。 */
    private String resolveSaveJson(ModelingDraftSaveReq request) {
        if (request.getCurrentDraft() != null && !request.getCurrentDraft().isNull()) {
            if (!request.getCurrentDraft().isObject()) {
                throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                        ModelingDraftConstants.ERROR_INVALID_REQUEST, "currentDraft 必须是完整 JSON 对象");
            }
            return request.getCurrentDraft().toString();
        }
        if (StringUtils.isNotBlank(request.getDraftJson())) {
            return request.getDraftJson();
        }
        throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                ModelingDraftConstants.ERROR_INVALID_REQUEST, "必须提供 currentDraft 或 draftJson");
    }

    /** 转换主记录，刻意排除 rawOutput 和 repairedOutput。 */
    private ModelingDraftResp toResponse(SemanticModelingDraftDO draft, boolean replay) {
        return toResponse(draft, replay, true, null);
    }

    /** 转换主记录，并允许列表场景省略大 JSON 快照。 */
    private ModelingDraftResp toResponse(SemanticModelingDraftDO draft, boolean replay,
            boolean includeDraft) {
        return toResponse(draft, replay, includeDraft, null);
    }

    /** 转换主记录，并在详情场景返回当前用户的服务端写权限判定。 */
    private ModelingDraftResp toResponse(SemanticModelingDraftDO draft, boolean replay,
            boolean includeDraft, Boolean canManage) {
        JsonNode currentDraft = includeDraft ? readTree(draft.getDraftJson()) : null;
        int currentAttemptNo = Objects.requireNonNullElse(draft.getCurrentAttemptNo(), 1);
        int manualRegenerationCount = Math.max(0, currentAttemptNo - 1);
        int remaining = Math.max(0, configuredManualRegenerationLimit() - manualRegenerationCount);
        boolean failedWithoutVersion =
                ModelingDraftConstants.STATUS_GENERATION_FAILED.equals(draft.getStatus())
                        && Objects.requireNonNullElse(draft.getCurrentVersionNo(), 0) == 0;
        boolean canRegenerate = failedWithoutVersion && remaining > 0;
        String regenerationBlockReason = null;
        if (!canRegenerate) {
            regenerationBlockReason =
                    failedWithoutVersion ? "已达到人工重新生成次数上限" : "只有尚未产生版本的生成失败草稿可以重新生成";
        }
        return ModelingDraftResp.builder().id(draft.getId()).sourceType(draft.getSourceType())
                .sourceId(draft.getSourceId()).title(draft.getTitle())
                .businessGoal(draft.getBusinessGoal()).domainId(draft.getDomainId())
                .dataSourceId(draft.getDataSourceId()).catalogName(draft.getCatalogName())
                .databaseName(draft.getDatabaseName())
                .selectedTables(readSelectedTables(draft.getSelectedTables()))
                .chatModelId(draft.getChatModelId()).includeSampleData(draft.getIncludeSample())
                .routeAnalysisId(draft.getRouteAnalysisId()).routeAction(draft.getRouteAction())
                .routeTargetAssetType(draft.getRouteTargetAssetType())
                .routeTargetAssetVersion(draft.getRouteTargetAssetVersion())
                .status(draft.getStatus()).currentVersionNo(draft.getCurrentVersionNo())
                .currentVersion(draft.getCurrentVersionNo()).lockVersion(draft.getLockVersion())
                .currentAttemptNo(currentAttemptNo).manualRegenerationCount(manualRegenerationCount)
                .remainingManualRegenerations(remaining).canRegenerate(canRegenerate)
                .regenerationBlockReason(regenerationBlockReason).canManage(canManage)
                .currentDraft(currentDraft).draftJson(includeDraft ? draft.getDraftJson() : null)
                .errorCode(draft.getErrorCode()).errorMessage(draft.getErrorMessage())
                .createdBy(draft.getCreatedBy()).createdAt(draft.getCreatedAt())
                .updatedBy(draft.getUpdatedBy()).updatedAt(draft.getUpdatedAt())
                .generationStartedAt(draft.getGenerationStartedAt())
                .generationFinishedAt(draft.getGenerationFinishedAt())
                .submittedValidationReportId(draft.getSubmittedValidationReportId())
                .submittedBy(draft.getSubmittedBy()).submittedAt(draft.getSubmittedAt())
                .idempotentReplay(replay).build();
    }

    /**
     * 解析人工重试上限；部署配置可以关闭或收紧重试，但不得突破产品约定的三次硬边界。
     */
    private int configuredManualRegenerationLimit() {
        return Math.min(ModelingDraftConstants.MAX_MANUAL_REGENERATIONS,
                Math.max(0, properties.getMaxManualRegenerations()));
    }

    /** 转换 attempt 并明确排除 Prompt、样例和模型原文。 */
    private ModelingDraftAttemptResp toAttemptResponse(SemanticModelingDraftAttemptDO attempt) {
        return ModelingDraftAttemptResp.builder().id(attempt.getId()).draftId(attempt.getDraftId())
                .attemptNo(attempt.getAttemptNo()).triggerType(attempt.getTriggerType())
                .status(attempt.getStatus()).chatModelId(attempt.getChatModelId())
                .includeSampleData(attempt.getIncludeSample())
                .llmConversationId(attempt.getLlmConversationId())
                .generateRequestId(attempt.getGenerateRequestId())
                .repairRequestId(attempt.getRepairRequestId())
                .failureStage(attempt.getFailureStage())
                .validationIssues(readValidationIssues(attempt.getValidationIssues()))
                .errorCode(attempt.getErrorCode()).errorMessage(attempt.getErrorMessage())
                .startedAt(attempt.getStartedAt()).finishedAt(attempt.getFinishedAt())
                .createdBy(attempt.getCreatedBy()).createdAt(attempt.getCreatedAt())
                .updatedBy(attempt.getUpdatedBy()).updatedAt(attempt.getUpdatedAt()).build();
    }

    /** 转换版本摘要或完整快照。 */
    private ModelingDraftVersionResp toVersionResponse(SemanticModelingDraftVersionDO version,
            boolean includeSnapshot) {
        return ModelingDraftVersionResp.builder().id(version.getId()).draftId(version.getDraftId())
                .versionNo(version.getVersionNo()).changeSource(version.getChangeSource())
                .changeSummary(version.getChangeSummary()).createdBy(version.getCreatedBy())
                .createdAt(version.getCreatedAt())
                .snapshot(includeSnapshot ? readTree(version.getDraftJson()) : null)
                .draftJson(includeSnapshot ? version.getDraftJson() : null).build();
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

    /** 读取草稿 JSON；生成中或异常历史数据返回 null。 */
    private JsonNode readTree(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 解析已脱敏校验问题；异常历史数据安全退化为空列表。 */
    private List<ModelingValidationIssue> readValidationIssues(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, VALIDATION_ISSUE_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 构造 attempt 管理端安全投影；显式排除幂等指纹、Prompt 相关诊断以及两轮模型原文大字段。
     */
    private LambdaQueryWrapper<SemanticModelingDraftAttemptDO> safeAttemptQuery(Long draftId) {
        return new LambdaQueryWrapper<SemanticModelingDraftAttemptDO>()
                .select(SemanticModelingDraftAttemptDO::getId,
                        SemanticModelingDraftAttemptDO::getDraftId,
                        SemanticModelingDraftAttemptDO::getAttemptNo,
                        SemanticModelingDraftAttemptDO::getTriggerType,
                        SemanticModelingDraftAttemptDO::getStatus,
                        SemanticModelingDraftAttemptDO::getChatModelId,
                        SemanticModelingDraftAttemptDO::getIncludeSample,
                        SemanticModelingDraftAttemptDO::getLlmConversationId,
                        SemanticModelingDraftAttemptDO::getGenerateRequestId,
                        SemanticModelingDraftAttemptDO::getRepairRequestId,
                        SemanticModelingDraftAttemptDO::getFailureStage,
                        SemanticModelingDraftAttemptDO::getValidationIssues,
                        SemanticModelingDraftAttemptDO::getErrorCode,
                        SemanticModelingDraftAttemptDO::getErrorMessage,
                        SemanticModelingDraftAttemptDO::getStartedAt,
                        SemanticModelingDraftAttemptDO::getFinishedAt,
                        SemanticModelingDraftAttemptDO::getCreatedBy,
                        SemanticModelingDraftAttemptDO::getCreatedAt,
                        SemanticModelingDraftAttemptDO::getUpdatedBy,
                        SemanticModelingDraftAttemptDO::getUpdatedAt)
                .eq(SemanticModelingDraftAttemptDO::getDraftId, draftId);
    }

    /** 构造空分页。 */
    private <T> PageInfo<T> emptyPage(int page, int pageSize) {
        PageInfo<T> result = new PageInfo<>();
        result.setPageNum(page);
        result.setPageSize(pageSize);
        result.setTotal(0);
        result.setPages(0);
        result.setList(new ArrayList<>());
        return result;
    }

    /** 保留 PageHelper 总数信息并替换为安全响应 DTO。 */
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
        target.setList(list);
        return target;
    }
}
