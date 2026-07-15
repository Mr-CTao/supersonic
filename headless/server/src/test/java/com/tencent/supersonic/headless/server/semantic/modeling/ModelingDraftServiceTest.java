package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftAttemptDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
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
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 语义建模草稿应用服务的并发与权限边界测试。
 *
 * <p>
 * 职责说明：使用纯 Mockito 隔离数据库、Gateway Worker 和执行器，验证幂等重放不会重复入队、 队列拒绝会安全落为失败、读取时会重新校验数据源
 * ACL、人工保存不会吞掉乐观锁冲突，以及超时恢复 使用配置化截止时间。测试不启动 Spring 容器，也不会连接或修改正式语义资产表。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ModelingDraftServiceTest {

    private static final Long DRAFT_ID = 101L;
    private static final Long DATA_SOURCE_ID = 7L;
    private static final Long ROUTE_ANALYSIS_ID = 17L;
    private static final String IDEMPOTENCY_KEY = "draft-create-101";
    private static final long GENERATION_TIMEOUT_SECONDS = 180L;

    @Mock
    private ModelingDraftContextBuilder contextBuilder;

    @Mock
    private ModelingDraftStore store;

    @Mock
    private ModelingDraftGenerationWorker worker;

    @Mock
    private SemanticModelingDraftMapper draftMapper;

    @Mock
    private SemanticModelingDraftAttemptMapper attemptMapper;

    @Mock
    private SemanticModelingDraftVersionMapper versionMapper;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private ModelingDraftStage4PermissionService writePermissionService;

    @Mock
    private ModelingDraftValidator validator;

    @Mock
    private ModelingDraftRouteGuard routeGuard;

    @Mock
    private SemanticAssetRoutingService routingService;

    @Mock
    private SemanticAssetRoutingPermissionService routingPermissionService;

    @Mock
    private ModelService modelService;

    @Mock
    private ThreadPoolTaskExecutor executor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final User user = User.getDefaultUser();
    private final SemanticModelingProperties properties = new SemanticModelingProperties();

    private ModelingDraftService service;

    /** 创建只含 Mockito 依赖的应用服务，确保每个测试独立。 */
    @BeforeEach
    void setUp() {
        properties.setGenerationTimeoutSeconds(GENERATION_TIMEOUT_SECONDS);
        lenient().when(writePermissionService.requireManageable(anyLong(), any(User.class)))
                .thenAnswer(invocation -> draftMapper.selectById(invocation.getArgument(0)));
        lenient().when(routingService.requireConsumableRoute(eq(ROUTE_ANALYSIS_ID), eq(user)))
                .thenReturn(confirmedRoute(ModelingDraftConstants.SOURCE_DATA_SOURCE, null,
                        DATA_SOURCE_ID));
        lenient().when(routingService.requireBoundRoute(eq(ROUTE_ANALYSIS_ID), anyLong(), eq(user)))
                .thenReturn(confirmedRoute(ModelingDraftConstants.SOURCE_DATA_SOURCE, null,
                        DATA_SOURCE_ID));
        service = new ModelingDraftService(contextBuilder, store, worker, draftMapper,
                attemptMapper, versionMapper, databaseService, writePermissionService, validator,
                routeGuard, routingService, routingPermissionService, modelService, properties,
                executor, objectMapper);
    }

    /** 幂等重放必须复用既有 ID，并禁止再次向生成执行器提交任务。 */
    @Test
    void shouldNotEnqueueGenerationForIdempotentReplay() {
        ModelingDraftGenerateReq request = newGenerateRequest();
        PreflightSnapshot snapshot = newSnapshot(request);
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_GENERATING);
        when(contextBuilder.preflight(request, user)).thenReturn(snapshot);
        when(store.createGenerating(eq(request), eq(IDEMPOTENCY_KEY), anyString(), eq(user)))
                .thenReturn(new CreateResult(draft, true));
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenReturn(DatabaseResp.builder().id(DATA_SOURCE_ID).build());

        ModelingDraftResp response = service.create(request, IDEMPOTENCY_KEY, user);

        assertEquals(DRAFT_ID, response.getId());
        assertTrue(response.getIdempotentReplay());
        verify(store).createGenerating(eq(request), eq(IDEMPOTENCY_KEY), anyString(), eq(user));
        verifyNoInteractions(executor, worker);
    }

    /** 快速幂等重放必须重新校验路由绑定、目标权限/版本和首次请求指纹。 */
    @Test
    void shouldRevalidateBoundRouteAndFingerprintForFastCreationReplay() {
        ModelingDraftGenerateReq request = newGenerateRequest();
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_GENERATING);
        when(store.findByIdempotencyKey(user.getName(), IDEMPOTENCY_KEY)).thenReturn(draft);
        when(contextBuilder.preflight(request, user)).thenReturn(newSnapshot(request));
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenReturn(DatabaseResp.builder().id(DATA_SOURCE_ID).build());

        ModelingDraftResp response = service.create(request, IDEMPOTENCY_KEY, user);

        assertEquals(DRAFT_ID, response.getId());
        assertTrue(response.getIdempotentReplay());
        verify(routingService, times(2)).requireBoundRoute(ROUTE_ANALYSIS_ID, DRAFT_ID, user);
        verify(store).validateCreationReplay(eq(DRAFT_ID), anyString(), eq(draft));
        verify(store, never()).createGenerating(any(), anyString(), anyString(), any());
        verifyNoInteractions(executor, worker);
    }

    /** Gap 去重复用其他草稿时必须按被复用草稿的数据源复核 ACL，禁止返回越权内容。 */
    @Test
    void shouldRejectGapReplayWhenActiveDraftDataSourceIsNotAccessible() {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setSourceType(ModelingDraftConstants.SOURCE_SEMANTIC_GAP);
        request.setSourceId(55L);
        when(routingService.requireConsumableRoute(ROUTE_ANALYSIS_ID, user))
                .thenReturn(confirmedRoute(ModelingDraftConstants.SOURCE_SEMANTIC_GAP, 55L,
                        DATA_SOURCE_ID));
        PreflightSnapshot snapshot = newSnapshot(request);
        SemanticModelingDraftDO activeDraft = newDraft(ModelingDraftConstants.STATUS_GENERATING);
        activeDraft.setSourceType(ModelingDraftConstants.SOURCE_SEMANTIC_GAP);
        activeDraft.setSourceId(55L);
        activeDraft.setDataSourceId(88L);
        when(contextBuilder.preflight(request, user)).thenReturn(snapshot);
        when(store.createGenerating(eq(request), eq(IDEMPOTENCY_KEY), anyString(), eq(user)))
                .thenReturn(new CreateResult(activeDraft, true));
        when(databaseService.getDatabase(88L, user))
                .thenThrow(new IllegalStateException("permission denied"));

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> service.create(request, IDEMPOTENCY_KEY, user));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(ModelingDraftConstants.ERROR_ACCESS_DENIED, exception.getErrorCode());
        verify(databaseService).getDatabase(88L, user);
        verifyNoInteractions(executor, worker);
    }

    /** 有界队列拒绝任务时，服务必须把 GENERATING 草稿转为可观察的失败状态。 */
    @Test
    void shouldMarkDraftFailedWhenExecutorRejectsTask() {
        ModelingDraftGenerateReq request = newGenerateRequest();
        PreflightSnapshot snapshot = newSnapshot(request);
        SemanticModelingDraftDO generating = newDraft(ModelingDraftConstants.STATUS_GENERATING);
        SemanticModelingDraftDO failed = newDraft(ModelingDraftConstants.STATUS_GENERATION_FAILED);
        failed.setErrorCode(ModelingDraftConstants.ERROR_QUEUE_REJECTED);
        failed.setErrorMessage("草稿生成队列已满，请稍后重新发起");
        when(contextBuilder.preflight(request, user)).thenReturn(snapshot);
        when(store.createGenerating(eq(request), eq(IDEMPOTENCY_KEY), anyString(), eq(user)))
                .thenReturn(new CreateResult(generating, false));
        doThrow(new TaskRejectedException("queue full")).when(executor)
                .execute(any(Runnable.class));
        when(store.failGeneration(eq(DRAFT_ID), eq(1),
                eq(ModelingDraftConstants.ERROR_QUEUE_REJECTED), anyString(), eq(null), eq(null),
                eq(ModelingDraftConstants.FAILURE_STAGE_QUEUE), eq(List.of()), eq(null), eq(null),
                eq(user.getName()))).thenReturn(true);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(failed);

        ModelingDraftResp response = service.create(request, IDEMPOTENCY_KEY, user);

        assertEquals(ModelingDraftConstants.STATUS_GENERATION_FAILED, response.getStatus());
        assertEquals(ModelingDraftConstants.ERROR_QUEUE_REJECTED, response.getErrorCode());
        verify(store).failGeneration(DRAFT_ID, 1, ModelingDraftConstants.ERROR_QUEUE_REJECTED,
                "草稿生成队列已满，请稍后重新生成", null, null, ModelingDraftConstants.FAILURE_STAGE_QUEUE,
                List.of(), null, null, user.getName());
        verify(worker, never()).generate(any(), any(), any(), any());
    }

    /** 人工重试只能覆盖模型和样例开关，并把新 attempt 序号交给 Worker。 */
    @Test
    void shouldRegenerateFailedDraftWithImmutableBusinessScope() {
        String retryKey = "draft-regenerate-101";
        SemanticModelingDraftDO failed = newDraft(ModelingDraftConstants.STATUS_GENERATION_FAILED);
        SemanticModelingDraftDO generating = newDraft(ModelingDraftConstants.STATUS_GENERATING);
        generating.setChatModelId(12);
        generating.setIncludeSample(true);
        generating.setCurrentAttemptNo(2);
        generating.setLockVersion(1);
        SemanticModelingDraftAttemptDO attempt = new SemanticModelingDraftAttemptDO();
        attempt.setDraftId(DRAFT_ID);
        attempt.setAttemptNo(2);
        ModelingDraftRegenerateReq request = new ModelingDraftRegenerateReq();
        request.setLockVersion(0);
        request.setChatModelId(12);
        request.setIncludeSampleData(true);
        PreflightSnapshot snapshot = newSnapshot(newGenerateRequest());

        when(draftMapper.selectById(DRAFT_ID)).thenReturn(failed, generating);
        when(contextBuilder.preflight(any(ModelingDraftGenerateReq.class), eq(user)))
                .thenReturn(snapshot);
        when(store.regenerate(eq(failed), eq(0), eq(12), eq(true), eq(retryKey), anyString(), eq(3),
                eq(user))).thenReturn(new RegenerationResult(generating, attempt, false));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ModelingDraftResp response = service.regenerate(DRAFT_ID, request, retryKey, user);

        assertEquals(ModelingDraftConstants.STATUS_GENERATING, response.getStatus());
        assertEquals(2, response.getCurrentAttemptNo());
        ArgumentCaptor<ModelingDraftGenerateReq> preflightCaptor =
                ArgumentCaptor.forClass(ModelingDraftGenerateReq.class);
        verify(contextBuilder).preflight(preflightCaptor.capture(), eq(user));
        ModelingDraftGenerateReq rebuilt = preflightCaptor.getValue();
        assertEquals(failed.getBusinessGoal(), rebuilt.getBusinessGoal());
        assertEquals(failed.getDataSourceId(), rebuilt.getDataSourceId());
        assertEquals(List.of("orders"), rebuilt.getSelectedTables());
        assertEquals(12, rebuilt.getChatModelId());
        assertTrue(rebuilt.getIncludeSampleData());
        verify(worker).generate(DRAFT_ID, 2, snapshot, user);
    }

    /** 相同幂等键重放必须直接返回当前草稿，不再次预检、入队或调用 Worker。 */
    @Test
    void shouldReplayRegenerationWithoutDuplicateGeneration() {
        String retryKey = "draft-regenerate-replay";
        SemanticModelingDraftDO failed = newDraft(ModelingDraftConstants.STATUS_GENERATION_FAILED);
        SemanticModelingDraftAttemptDO attempt = new SemanticModelingDraftAttemptDO();
        attempt.setDraftId(DRAFT_ID);
        attempt.setAttemptNo(2);
        attempt.setTriggerType(ModelingDraftConstants.ATTEMPT_TRIGGER_MANUAL_REGENERATION);
        ModelingDraftRegenerateReq request = new ModelingDraftRegenerateReq();
        request.setLockVersion(0);
        request.setChatModelId(12);
        request.setIncludeSampleData(false);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(failed);
        when(store.findAttemptByIdempotencyKey(user.getName(), retryKey)).thenReturn(attempt);

        ModelingDraftResp response = service.regenerate(DRAFT_ID, request, retryKey, user);

        assertTrue(response.getIdempotentReplay());
        verify(store).validateRegenerationReplay(eq(DRAFT_ID), anyString(), eq(attempt));
        verifyNoInteractions(contextBuilder, executor, worker);
    }

    /** attempt 4 已失败时代表三次人工重试耗尽，必须在读取元数据前直接返回 409。 */
    @Test
    void shouldRejectRegenerationAfterThreeManualAttempts() {
        // 配置值只能收紧，不能把产品约定的三次硬上限放大。
        properties.setMaxManualRegenerations(99);
        SemanticModelingDraftDO failed = newDraft(ModelingDraftConstants.STATUS_GENERATION_FAILED);
        failed.setCurrentAttemptNo(4);
        ModelingDraftRegenerateReq request = new ModelingDraftRegenerateReq();
        request.setLockVersion(0);
        request.setChatModelId(12);
        request.setIncludeSampleData(false);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(failed);
        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> service.regenerate(DRAFT_ID, request, "draft-regenerate-limit", user));

        assertEquals(ModelingDraftConstants.ERROR_REGENERATION_LIMIT, exception.getErrorCode());
        verifyNoInteractions(contextBuilder, executor, worker);
    }

    /** 草稿详情读取必须复核当前 ACL，而不能依赖创建时曾经拥有的权限。 */
    @Test
    void shouldRejectDetailWhenDataSourceAclCheckFails() {
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenThrow(new IllegalStateException("permission revoked"));

        ModelingDraftException exception =
                assertThrows(ModelingDraftException.class, () -> service.get(DRAFT_ID, user));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(ModelingDraftConstants.ERROR_ACCESS_DENIED, exception.getErrorCode());
        verify(databaseService).getDatabase(DATA_SOURCE_ID, user);
        verify(store).failStaleGenerations(any(Date.class));
    }

    /** 增强草稿详情必须复核目标模型 VIEWER 权限，且响应 JSON 绝不能包含正式模型 ID。 */
    @Test
    void shouldRecheckExtendTargetAndRedactFormalAssetIdFromDetail() {
        SemanticModelingDraftDO draft = extendDraft(DRAFT_ID, 501L);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenReturn(DatabaseResp.builder().id(DATA_SOURCE_ID).build());

        ModelingDraftResp response = service.get(DRAFT_ID, user);

        assertFalse(objectMapper.valueToTree(response).has("routeTargetAssetId"));
        ArgumentCaptor<SemanticAssetCandidate> targetCaptor =
                ArgumentCaptor.forClass(SemanticAssetCandidate.class);
        verify(routingPermissionService).requireReadableCandidateVersion(targetCaptor.capture(),
                eq(user));
        assertEquals(501L, targetCaptor.getValue().getAssetId());
        assertEquals(7L, targetCaptor.getValue().getAssetVersion());
    }

    /** 目标删除和撤权均应折叠为同一草稿不可访问结果，禁止透传权限服务中的差异信息。 */
    @Test
    void shouldCollapseRevokedOrDeletedExtendTargetIntoSafeUnavailable() {
        SemanticModelingDraftDO draft = extendDraft(DRAFT_ID, 501L);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenReturn(DatabaseResp.builder().id(DATA_SOURCE_ID).build());
        when(routingPermissionService.requireReadableCandidateVersion(any(), eq(user)))
                .thenThrow(new SemanticAssetRoutingException(HttpStatus.NOT_FOUND,
                        "TARGET_DELETED", "model 501 was deleted"))
                .thenThrow(new SemanticAssetRoutingException(HttpStatus.NOT_FOUND,
                        "TARGET_REVOKED", "permission denied for model 501"));

        ModelingDraftException deleted = assertThrows(ModelingDraftException.class,
                () -> service.get(DRAFT_ID, user));
        ModelingDraftException revoked = assertThrows(ModelingDraftException.class,
                () -> service.get(DRAFT_ID, user));

        assertEquals(HttpStatus.NOT_FOUND, deleted.getStatus());
        assertEquals(HttpStatus.NOT_FOUND, revoked.getStatus());
        assertEquals(deleted.getErrorCode(), revoked.getErrorCode());
        assertEquals("语义建模草稿不存在或不可访问", deleted.getMessage());
        assertEquals(deleted.getMessage(), revoked.getMessage());
        assertFalse(deleted.getMessage().contains("501"));
        assertFalse(revoked.getMessage().contains("permission"));
    }

    /**
     * 列表应一次批量获取模型 VIEWER ACL，并保留历史/新建草稿、过滤不可见增强草稿。
     */
    @Test
    void shouldBatchFilterUnreadableExtendTargetsWithoutAffectingOtherDrafts() {
        User viewer = User.getVisitUser();
        SemanticModelingDraftDO readableExtend = extendDraft(101L, 501L);
        SemanticModelingDraftDO revokedExtend = extendDraft(102L, 502L);
        SemanticModelingDraftDO createNew = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        createNew.setId(103L);
        SemanticModelingDraftDO historical = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        historical.setId(104L);
        historical.setRouteAnalysisId(null);
        historical.setRouteAction(null);
        ModelResp readableModel = new ModelResp();
        readableModel.setId(501L);
        when(databaseService.getDatabaseList(viewer))
                .thenReturn(List.of(DatabaseResp.builder().id(DATA_SOURCE_ID).build()));
        when(modelService.getModelListWithAuth(eq(viewer), isNull(), eq(AuthType.VIEWER)))
                .thenReturn(List.of(readableModel));
        when(draftMapper.selectList(any()))
                .thenReturn(List.of(readableExtend, revokedExtend, createNew, historical));

        service.query(new ModelingDraftQueryReq(), viewer);

        Set<Long> readableModelIds = Set.of(501L);
        assertTrue(service.isRouteTargetReadable(readableExtend, readableModelIds));
        assertFalse(service.isRouteTargetReadable(revokedExtend, readableModelIds));
        assertTrue(service.isRouteTargetReadable(createNew, readableModelIds));
        assertTrue(service.isRouteTargetReadable(historical, readableModelIds));
        verify(modelService, times(1)).getModelListWithAuth(eq(viewer), isNull(),
                eq(AuthType.VIEWER));
        verifyNoInteractions(routingPermissionService);
    }

    /** 可读取但不可管理的账号必须收到显式只读能力，供前端隐藏阶段 4 写入口。 */
    @Test
    void shouldExposeReadOnlyCapabilityOnDraftDetail() {
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenReturn(DatabaseResp.builder().id(DATA_SOURCE_ID).build());
        when(writePermissionService.canManage(draft, user)).thenReturn(false);

        ModelingDraftResp response = service.get(DRAFT_ID, user);

        assertFalse(response.getCanManage());
        verify(writePermissionService).canManage(draft, user);
    }

    /** 保存已经通过管理权限校验，成功响应必须继续携带 canManage=true，避免前端瞬时降为只读。 */
    @Test
    void shouldKeepManageCapabilityAfterManualSave() {
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftDO saved = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        saved.setCurrentVersionNo(2);
        saved.setLockVersion(1);
        saved.setDraftJson("{\"schemaVersion\":\"1.0\"}");
        ModelingDraftSaveReq request = new ModelingDraftSaveReq();
        request.setLockVersion(0);
        request.setCurrentDraft(objectMapper.createObjectNode().put("schemaVersion", "1.0"));
        ValidationContext validationContext = new ValidationContext(Map.of(), Set.of());
        ValidatedDraft validated = new ValidatedDraft(null, saved.getDraftJson());

        when(writePermissionService.requireManageable(DRAFT_ID, user)).thenReturn(draft);
        when(contextBuilder.reloadValidationContext(DATA_SOURCE_ID, "catalog", "warehouse",
                List.of("orders"), null, user)).thenReturn(validationContext);
        when(validator.validateAndNormalize(anyString(), eq(Map.of()), eq(Set.of())))
                .thenReturn(validated);
        when(store.saveVersion(draft, 0, validated.json(), null, user)).thenReturn(saved);

        ModelingDraftResp response = service.save(DRAFT_ID, request, user);

        assertTrue(response.getCanManage());
        assertEquals(2, response.getCurrentVersionNo());
        verify(writePermissionService).requireManageable(DRAFT_ID, user);
        verify(writePermissionService, never()).canManage(any(), any());
    }

    /** 人工保存必须原样传播存储层 409，禁止静默覆盖其他管理员的新版本。 */
    @Test
    void shouldPropagateOptimisticLockConflictFromManualSave() {
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        ModelingDraftSaveReq request = new ModelingDraftSaveReq();
        request.setLockVersion(0);
        request.setCurrentDraft(objectMapper.createObjectNode().put("schemaVersion", "1.0"));
        ValidationContext validationContext = new ValidationContext(Map.of(), Set.of());
        ValidatedDraft validated = new ValidatedDraft(null, "{\"schemaVersion\":\"1.0\"}");
        ModelingDraftException conflict = new ModelingDraftException(HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_CONFLICT, "草稿已被其他操作更新，请重新加载后再保存");

        when(draftMapper.selectById(DRAFT_ID)).thenReturn(draft);
        when(contextBuilder.reloadValidationContext(DATA_SOURCE_ID, "catalog", "warehouse",
                List.of("orders"), null, user)).thenReturn(validationContext);
        when(validator.validateAndNormalize(anyString(), eq(Map.of()), eq(Set.of())))
                .thenReturn(validated);
        when(store.saveVersion(draft, 0, validated.json(), null, user)).thenThrow(conflict);

        ModelingDraftException actual = assertThrows(ModelingDraftException.class,
                () -> service.save(DRAFT_ID, request, user));

        assertSame(conflict, actual);
        assertEquals(HttpStatus.CONFLICT, actual.getStatus());
        verify(store).saveVersion(draft, 0, validated.json(), null, user);
    }

    /** 超时恢复应把配置秒数转换为截止时间，并返回存储层实际更新数量。 */
    @Test
    void shouldRecoverGeneratingDraftsUsingConfiguredTimeout() {
        ArgumentCaptor<Date> cutoffCaptor = ArgumentCaptor.forClass(Date.class);
        when(store.failStaleGenerations(any(Date.class))).thenReturn(2);
        long before = System.currentTimeMillis();

        int recovered = service.recoverStaleGenerations();
        long after = System.currentTimeMillis();

        assertEquals(2, recovered);
        verify(store).failStaleGenerations(cutoffCaptor.capture());
        long cutoff = cutoffCaptor.getValue().getTime();
        assertTrue(cutoff >= before - GENERATION_TIMEOUT_SECONDS * 1000L);
        assertTrue(cutoff <= after - GENERATION_TIMEOUT_SECONDS * 1000L);
        verifyNoInteractions(draftMapper, databaseService, worker, executor);
    }

    /** 构造满足应用层创建路径所需的最小请求。 */
    private ModelingDraftGenerateReq newGenerateRequest() {
        ModelingDraftGenerateReq request = new ModelingDraftGenerateReq();
        request.setSourceType(ModelingDraftConstants.SOURCE_DATA_SOURCE);
        request.setRouteAnalysisId(ROUTE_ANALYSIS_ID);
        request.setBusinessGoal("分析订单销售额");
        request.setDataSourceId(DATA_SOURCE_ID);
        request.setCatalogName("catalog");
        request.setDatabaseName("warehouse");
        request.setSelectedTables(List.of("orders"));
        request.setChatModelId(3);
        request.setIncludeSampleData(false);
        return request;
    }

    /** 构造已完成 ACL 与元数据校验的可信快照。 */
    private PreflightSnapshot newSnapshot(ModelingDraftGenerateReq request) {
        return new PreflightSnapshot(request, Map.of(), Set.of(), Map.of(), Map.of());
    }

    /** 构造不会携带原始模型输出的草稿持久化对象。 */
    private SemanticModelingDraftDO newDraft(String status) {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(DRAFT_ID);
        draft.setSourceType(ModelingDraftConstants.SOURCE_DATA_SOURCE);
        draft.setTitle("订单语义草稿");
        draft.setBusinessGoal("分析订单销售额");
        draft.setDataSourceId(DATA_SOURCE_ID);
        draft.setCatalogName("catalog");
        draft.setDatabaseName("warehouse");
        draft.setSelectedTables("[\"orders\"]");
        draft.setChatModelId(3);
        draft.setIncludeSample(false);
        draft.setRouteAnalysisId(ROUTE_ANALYSIS_ID);
        draft.setRouteAction(ModelingDraftConstants.ACTION_CREATE_NEW);
        draft.setStatus(status);
        draft.setCurrentVersionNo(ModelingDraftConstants.STATUS_DRAFT.equals(status) ? 1 : 0);
        draft.setCurrentAttemptNo(1);
        draft.setLockVersion(0);
        draft.setCreatedBy(user.getName());
        draft.setUpdatedBy(user.getName());
        return draft;
    }

    /** 构造带已确认目标快照的增强草稿，正式模型 ID 只存在于服务端 DO。 */
    private SemanticModelingDraftDO extendDraft(Long draftId, Long targetModelId) {
        SemanticModelingDraftDO draft = newDraft(ModelingDraftConstants.STATUS_DRAFT);
        draft.setId(draftId);
        draft.setDomainId(5L);
        draft.setRouteAction(ModelingDraftConstants.ACTION_EXTEND_EXISTING);
        draft.setRouteTargetAssetType("MODEL");
        draft.setRouteTargetAssetId(targetModelId);
        draft.setRouteTargetAssetVersion(7L);
        return draft;
    }

    /** 构造与测试请求一致的已确认新建路由。 */
    private ConfirmedSemanticAssetRoute confirmedRoute(String sourceType, Long sourceId,
            Long dataSourceId) {
        return ConfirmedSemanticAssetRoute.builder().routeAnalysisId(ROUTE_ANALYSIS_ID)
                .sourceType(sourceType).sourceId(sourceId).businessGoal("分析订单销售额")
                .dataSourceId(dataSourceId).catalogName("catalog").databaseName("warehouse")
                .selectedTables(List.of("orders")).chatModelId(3).includeSampleData(false)
                .action(SemanticAssetRouteAction.CREATE_NEW)
                .promptContext(Map.of("schemaVersion", "2.0", "action", "CREATE_NEW",
                        "routeSummary", Map.of("routeAnalysisId", ROUTE_ANALYSIS_ID)))
                .build();
    }
}
