package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import com.tencent.supersonic.common.pojo.User;
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
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftValidator.ValidatedDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 应用服务 AI 修订单元测试。
 *
 * <p>
 * 职责说明：验证同一 Gateway 会话修订会重新执行草稿校验、生成确定性版本差异并交给短事务存储；同时 验证过期基线会在调用 LLM 前被拒绝。测试不调用真实 Provider 或数据库。
 * </p>
 */
class ModelingDraftStage4ServiceTest {

    private ModelingDraftStage4PermissionService permissionService;
    private ModelingDraftStage4Store store;
    private ModelingDraftRevisionStore revisionStore;
    private SemanticModelingDraftVersionMapper versionMapper;
    private ModelingDraftContextBuilder contextBuilder;
    private ModelingDraftValidator validator;
    private LlmConversationGatewayService gatewayService;
    private SemanticModelingValidationReportMapper reportMapper;
    private ModelingDraftStage4Service service;
    private ObjectMapper objectMapper;
    private User user;

    /** 初始化应用服务和隔离 mock。 */
    @BeforeEach
    void setUp() {
        permissionService = mock(ModelingDraftStage4PermissionService.class);
        store = mock(ModelingDraftStage4Store.class);
        revisionStore = mock(ModelingDraftRevisionStore.class);
        versionMapper = mock(SemanticModelingDraftVersionMapper.class);
        reportMapper = mock(SemanticModelingValidationReportMapper.class);
        contextBuilder = mock(ModelingDraftContextBuilder.class);
        validator = mock(ModelingDraftValidator.class);
        ModelingDraftValidationEngine validationEngine = mock(ModelingDraftValidationEngine.class);
        gatewayService = mock(LlmConversationGatewayService.class);
        objectMapper = new ObjectMapper();
        SemanticModelingProperties properties = new SemanticModelingProperties();
        SemanticModelingSensitivityClassifier classifier =
                new SemanticModelingSensitivityClassifier();
        service = new ModelingDraftStage4Service(permissionService, store, revisionStore,
                versionMapper, reportMapper, contextBuilder, validator, validationEngine,
                new ModelingDraftDiffService(objectMapper, classifier), classifier, gatewayService,
                properties, objectMapper);
        user = User.getDefaultUser();
    }

    /** 合法当前版本应在同一会话生成新版本和可展示差异。 */
    @Test
    void shouldReviseCurrentVersionAndReturnDiff() throws Exception {
        String before = draftJson("旧业务目标");
        String after = draftJson("新业务目标");
        SemanticModelingDraftDO draft = draft(2, before);
        SemanticModelingDraftDO saved = draft(3, after);
        saved.setLockVersion(4);
        SemanticModelingDraftVersionDO baseVersion = version(20L, 2, before);
        SemanticModelingDraftVersionDO savedVersion = version(21L, 3, after);
        SemanticModelingRevisionAttemptDO attempt = attempt(30L, 2, "revision-key");
        ModelingDraftPayload payload = payload("新业务目标");
        ModelingDraftAiReviseReq request = new ModelingDraftAiReviseReq();
        request.setInstruction("将业务目标改为新业务目标");
        request.setBaseVersionNo(2);
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(2), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.CLAIMED, attempt));
        when(contextBuilder.reloadValidationContext(eq(3L), eq(null), eq("demo"), any(), eq(null),
                eq(user))).thenReturn(new ValidationContext(Map.of(), Set.of()));
        when(validator.getJsonSchema()).thenReturn(objectMapper.createObjectNode());
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(9L), any())).thenReturn(
                LlmMessageCreateResp.builder().parsedJson(objectMapper.readTree(after)).build());
        when(validator.validateAndNormalize(eq(after), any(), any()))
                .thenReturn(new ValidatedDraft(payload, after));
        when(revisionStore.completeSuccess(eq(30L), eq(after), anyString(), eq(9L), eq(user)))
                .thenReturn(new CompletionResult(CompletionDisposition.SUCCEEDED, attempt, saved,
                        savedVersion));
        when(versionMapper.selectOne(any())).thenReturn(baseVersion);

        ModelingDraftAiReviseResp response = service.aiRevise(1L, request, "revision-key", user);

        assertThat(response.getNewVersionNo()).isEqualTo(3);
        assertThat(response.getLockVersion()).isEqualTo(4);
        assertThat(response.getChangeSummary()).contains("修改 1 项");
        assertThat(response.getChanges()).extracting(ModelingDraftDiffItem::getPath)
                .contains("$.businessGoal");
        assertThat(response.getIdempotentReplay()).isFalse();
        verify(gatewayService).assertConversationAccess(9L, user);
        ArgumentCaptor<LlmMessageCreateReq> messageCaptor =
                ArgumentCaptor.forClass(LlmMessageCreateReq.class);
        verify(gatewayService).appendMessageAndChatWithoutTransaction(eq(9L),
                messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).contains("currentDraft 是唯一已提交且权威的基线状态")
                .contains("忽略本会话历史中任何未提交、失败或超时的修订消息");
        InOrder executionOrder = inOrder(revisionStore, gatewayService);
        executionOrder.verify(revisionStore).claim(eq(1L), eq(2), eq("revision-key"), anyString(),
                eq(user));
        executionOrder.verify(gatewayService).appendMessageAndChatWithoutTransaction(eq(9L), any());
        executionOrder.verify(revisionStore).completeSuccess(eq(30L), eq(after), anyString(),
                eq(9L), eq(user));
    }

    /** 指令包含敏感值时必须在权限、认领和 Provider 调用前稳定拒绝。 */
    @Test
    void shouldRejectSensitiveRevisionInstructionBeforeProviderCall() {
        ModelingDraftAiReviseReq request = new ModelingDraftAiReviseReq();
        request.setBaseVersionNo(2);
        request.setInstruction("请联系 owner@example.test 后修改目标");

        assertThatThrownBy(() -> service.aiRevise(1L, request, "revision-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ModelingDraftConstants.ERROR_SENSITIVE_INSTRUCTION);
                    assertThat(exception.getMessage()).doesNotContain("owner@example.test");
                });

        verify(permissionService, never()).requireManageable(anyLong(), any());
        verify(revisionStore, never()).claim(anyLong(), anyInt(), anyString(), anyString(), any());
        verify(gatewayService, never()).appendMessageAndChatWithoutTransaction(anyLong(), any());
    }

    /** 首轮结构校验失败进入 repair 时必须复用同一占位符上下文并恢复原敏感值。 */
    @Test
    void shouldReuseProtectedContextDuringRepair() throws Exception {
        ObjectNode beforeNode =
                (ObjectNode) objectMapper.readTree(draftJson("联系人 owner@example.test"));
        String before = objectMapper.writeValueAsString(beforeNode);
        ObjectNode afterNode = beforeNode.deepCopy();
        afterNode.put("schemaVersion", "1.1");
        String after = objectMapper.writeValueAsString(afterNode);
        SemanticModelingDraftDO draft = draft(2, before);
        SemanticModelingDraftDO saved = draft(3, after);
        saved.setLockVersion(4);
        SemanticModelingDraftVersionDO baseVersion = version(20L, 2, before);
        SemanticModelingDraftVersionDO savedVersion = version(21L, 3, after);
        SemanticModelingRevisionAttemptDO attempt = attempt(30L, 2, "revision-key");
        ModelingDraftAiReviseReq request = new ModelingDraftAiReviseReq();
        request.setInstruction("只调整非敏感的 schema 版本字段");
        request.setBaseVersionNo(2);
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(2), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.CLAIMED, attempt));
        when(contextBuilder.reloadValidationContext(eq(3L), eq(null), eq("demo"), any(), eq(null),
                eq(user))).thenReturn(new ValidationContext(Map.of(), Set.of()));
        when(validator.getJsonSchema()).thenReturn(objectMapper.createObjectNode());
        Pattern tokenPattern = Pattern.compile("__S2_PROTECTED_[A-Za-z0-9_]+__");
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(9L), any()))
                .thenAnswer(invocation -> {
                    LlmMessageCreateReq message = invocation.getArgument(1);
                    Matcher matcher = tokenPattern.matcher(message.getContent());
                    assertThat(matcher.find()).isTrue();
                    ObjectNode candidate =
                            (ObjectNode) objectMapper.readTree(draftJson(matcher.group()));
                    candidate.put("schemaVersion", "1.1");
                    return LlmMessageCreateResp.builder().parsedJson(candidate).build();
                });
        when(validator.validateAndNormalize(anyString(), any(), any()))
                .thenThrow(new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_OUTPUT_INVALID, "合成结构错误"))
                .thenReturn(new ValidatedDraft(payload("联系人 owner@example.test"), after));
        when(revisionStore.completeSuccess(eq(30L), eq(after), anyString(), eq(9L), eq(user)))
                .thenReturn(new CompletionResult(CompletionDisposition.SUCCEEDED, attempt, saved,
                        savedVersion));
        when(versionMapper.selectOne(any())).thenReturn(baseVersion);

        service.aiRevise(1L, request, "revision-key", user);

        ArgumentCaptor<LlmMessageCreateReq> messageCaptor =
                ArgumentCaptor.forClass(LlmMessageCreateReq.class);
        verify(gatewayService, times(2)).appendMessageAndChatWithoutTransaction(eq(9L),
                messageCaptor.capture());
        List<LlmMessageCreateReq> messages = messageCaptor.getAllValues();
        String firstToken = extractProtectedToken(messages.get(0).getContent(), tokenPattern);
        String repairToken = extractProtectedToken(messages.get(1).getContent(), tokenPattern);
        assertThat(repairToken).isEqualTo(firstToken);
        assertThat(messages).allSatisfy(
                message -> assertThat(message.getContent()).doesNotContain("owner@example.test"));
        verify(revisionStore).completeSuccess(eq(30L), eq(after), anyString(), eq(9L), eq(user));
    }

    /** 管理员恢复结果应保持目标、基线、新版本和锁版本绑定。 */
    @Test
    void shouldRestoreHistoricalVersionThroughManagedService() {
        ModelingDraftRestoreReq request = new ModelingDraftRestoreReq();
        request.setCurrentVersionNo(3);
        request.setLockVersion(8);
        SemanticModelingDraftVersionDO restored = version(40L, 4, draftJson("历史目标"));
        when(permissionService.requireManageable(1L, user)).thenReturn(draft(3, draftJson("当前目标")));
        when(store.restoreVersion(eq(1L), eq(1), eq(3), eq(8), eq("restore-key"), anyString(),
                eq(user)))
                        .thenReturn(new ModelingDraftStage4Store.RestoreResult(restored, 9, false));

        ModelingDraftRestoreResp response =
                service.restoreVersion(1L, 1, request, "restore-key", user);

        assertThat(response.getTargetVersionNo()).isEqualTo(1);
        assertThat(response.getBaseVersionNo()).isEqualTo(3);
        assertThat(response.getNewVersionNo()).isEqualTo(4);
        assertThat(response.getLockVersion()).isEqualTo(9);
        assertThat(response.getCurrentDraft().path("businessGoal").asText()).isEqualTo("历史目标");
    }

    /** viewer/public 被权限服务拒绝时，恢复入口不得触达写事务。 */
    @Test
    void shouldRejectRestoreWithoutManagePermission() {
        ModelingDraftRestoreReq request = new ModelingDraftRestoreReq();
        request.setCurrentVersionNo(3);
        request.setLockVersion(8);
        when(permissionService.requireManageable(1L, user)).thenThrow(new ModelingDraftException(
                HttpStatus.FORBIDDEN, ModelingDraftConstants.ERROR_ACCESS_DENIED, "无管理权限"));

        assertThatThrownBy(() -> service.restoreVersion(1L, 1, request, "restore-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_ACCESS_DENIED));

        verify(store, never()).restoreVersion(anyLong(), anyInt(), anyInt(), anyInt(), anyString(),
                anyString(), any());
    }

    /** 过期基线必须在任何 Provider 调用前返回 409。 */
    @Test
    void shouldRejectStaleBaseVersionBeforeCallingGateway() {
        SemanticModelingDraftDO draft = draft(2, draftJson("当前目标"));
        ModelingDraftAiReviseReq request = new ModelingDraftAiReviseReq();
        request.setInstruction("修改目标");
        request.setBaseVersionNo(1);
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(1), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.DRAFT_VERSION_CONFLICT, null));

        assertThatThrownBy(() -> service.aiRevise(1L, request, "revision-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class, exception -> {
                    assertThat(exception.getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ModelingDraftConstants.ERROR_REVISION_BASE_VERSION_CHANGED);
                });
        verify(gatewayService, never()).appendMessageAndChatWithoutTransaction(anyLong(), any());
        verify(contextBuilder, never()).reloadValidationContext(anyLong(), any(), any(), any(),
                any(), any());
    }

    /** 版本差异是纯读取能力，应复用基础 ACL 而不是要求阶段 4 管理权限。 */
    @Test
    void shouldAllowReadableUserToCompareImmutableVersions() {
        String before = draftJson("旧目标");
        String after = draftJson("新目标");
        when(permissionService.requireReadable(1L, user)).thenReturn(draft(2, after));
        when(versionMapper.selectOne(any())).thenReturn(version(20L, 1, before),
                version(21L, 2, after));

        ModelingDraftDiffResp response = service.diff(1L, 1, 2, user);

        assertThat(response.getItems()).extracting(ModelingDraftDiffItem::getPath)
                .contains("$.businessGoal");
        verify(permissionService).requireReadable(1L, user);
        verify(permissionService, never()).requireManageable(1L, user);
    }

    /** 相同键仍在运行时必须返回稳定冲突码，且绝不能再次调用 Provider。 */
    @Test
    void shouldNotCallProviderWhenSameKeyIsRunning() {
        SemanticModelingDraftDO draft = draft(2, draftJson("当前目标"));
        ModelingDraftAiReviseReq request = revisionRequest(2);
        SemanticModelingRevisionAttemptDO attempt = attempt(30L, 2, "revision-key");
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(2), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.SAME_KEY_RUNNING, attempt));

        assertThatThrownBy(() -> service.aiRevise(1L, request, "revision-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_REVISION_RUNNING));

        verify(gatewayService, never()).appendMessageAndChatWithoutTransaction(anyLong(), any());
        verify(contextBuilder, never()).reloadValidationContext(anyLong(), any(), any(), any(),
                any(), any());
    }

    /** 版本表中的共享键冲突必须在 Provider 和上下文加载前返回稳定幂等错误。 */
    @Test
    void shouldNotCallProviderWhenVersionOperationOwnsIdempotencyKey() {
        SemanticModelingDraftDO draft = draft(2, draftJson("当前目标"));
        ModelingDraftAiReviseReq request = revisionRequest(2);
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(2), eq("shared-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.IDEMPOTENCY_CONFLICT, null));

        assertThatThrownBy(() -> service.aiRevise(1L, request, "shared-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT));

        verify(gatewayService, never()).appendMessageAndChatWithoutTransaction(anyLong(), any());
        verify(contextBuilder, never()).reloadValidationContext(anyLong(), any(), any(), any(),
                any(), any());
    }

    /** 成功 attempt 的相同键重试必须直接重建既有版本响应，不能再次调用 Provider。 */
    @Test
    void shouldReplaySucceededRevisionWithoutProviderCall() {
        String before = draftJson("旧目标");
        String after = draftJson("新目标");
        SemanticModelingDraftDO draft = draft(3, after);
        draft.setLockVersion(4);
        ModelingDraftAiReviseReq request = revisionRequest(2);
        SemanticModelingRevisionAttemptDO attempt = attempt(30L, 2, "revision-key");
        attempt.setStatus(ModelingDraftRevisionStore.STATUS_SUCCEEDED);
        attempt.setActiveMarker(null);
        attempt.setResultVersionId(21L);
        attempt.setResultVersionNo(3);
        SemanticModelingDraftVersionDO baseVersion = version(20L, 2, before);
        SemanticModelingDraftVersionDO savedVersion = version(21L, 3, after);
        savedVersion.setRequestFingerprint(revisionFingerprintForTest(1L, request));
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(2), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.REPLAY_SUCCEEDED, attempt));
        when(versionMapper.selectById(21L)).thenReturn(savedVersion);
        when(versionMapper.selectOne(any())).thenReturn(baseVersion);

        ModelingDraftAiReviseResp response = service.aiRevise(1L, request, "revision-key", user);

        assertThat(response.getNewVersionNo()).isEqualTo(3);
        assertThat(response.getIdempotentReplay()).isTrue();
        verify(gatewayService, never()).appendMessageAndChatWithoutTransaction(anyLong(), any());
        verify(revisionStore, never()).completeSuccess(anyLong(), any(), any(), any(), any());
    }

    /** 成功重放必须复读当前草稿，不能把认领前快照误判为仍是最新版本。 */
    @Test
    void shouldRejectSucceededReplayWhenDraftAdvancedDuringClaim() {
        String after = draftJson("修订目标");
        SemanticModelingDraftDO beforeClaim = draft(3, after);
        SemanticModelingDraftDO afterConcurrentSave = draft(4, draftJson("人工保存后的目标"));
        ModelingDraftAiReviseReq request = revisionRequest(2);
        SemanticModelingRevisionAttemptDO attempt = attempt(30L, 2, "revision-key");
        attempt.setStatus(ModelingDraftRevisionStore.STATUS_SUCCEEDED);
        attempt.setActiveMarker(null);
        attempt.setResultVersionId(21L);
        attempt.setResultVersionNo(3);
        SemanticModelingDraftVersionDO savedVersion = version(21L, 3, after);
        savedVersion.setRequestFingerprint(revisionFingerprintForTest(1L, request));
        when(permissionService.requireManageable(1L, user)).thenReturn(beforeClaim,
                afterConcurrentSave);
        when(revisionStore.claim(eq(1L), eq(2), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.REPLAY_SUCCEEDED, attempt));
        when(versionMapper.selectById(21L)).thenReturn(savedVersion);

        assertThatThrownBy(() -> service.aiRevise(1L, request, "revision-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(
                                ModelingDraftConstants.ERROR_REVISION_BASE_VERSION_CHANGED));

        verify(permissionService, times(2)).requireManageable(1L, user);
        verify(gatewayService, never()).appendMessageAndChatWithoutTransaction(anyLong(), any());
        verify(versionMapper, never()).selectOne(any());
    }

    /** Provider 失败必须结束 attempt 并释放活动租约，后续请求才能使用新键重试。 */
    @Test
    void shouldReleaseRevisionLeaseWhenProviderFails() {
        SemanticModelingDraftDO draft = draft(2, draftJson("当前目标"));
        ModelingDraftAiReviseReq request = revisionRequest(2);
        SemanticModelingRevisionAttemptDO attempt = attempt(30L, 2, "revision-key");
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(revisionStore.claim(eq(1L), eq(2), eq("revision-key"), anyString(), eq(user)))
                .thenReturn(new ClaimResult(ClaimDisposition.CLAIMED, attempt));
        when(contextBuilder.reloadValidationContext(eq(3L), eq(null), eq("demo"), any(), eq(null),
                eq(user))).thenReturn(new ValidationContext(Map.of(), Set.of()));
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(9L), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> service.aiRevise(1L, request, "revision-key", user))
                .isInstanceOf(ModelingDraftException.class);

        verify(revisionStore).completeFailure(30L, ModelingDraftConstants.ERROR_REVISION_FAILED,
                user);
        verify(revisionStore, never()).completeSuccess(anyLong(), any(), any(), any(), any());
    }

    /** 报告响应只有在草稿仍可提交且报告为当前版本最新终态时才允许展示提交入口。 */
    @Test
    void shouldAlignCanSubmitWithLatestReportAndDraftState() {
        SemanticModelingDraftDO draft = draft(2, draftJson("当前目标"));
        SemanticModelingValidationReportDO passed =
                report(8L, ModelingDraftConstants.VALIDATION_PASSED, new Date());
        SemanticModelingValidationReportDO running =
                report(9L, ModelingDraftConstants.VALIDATION_RUNNING, null);
        when(reportMapper.selectById(8L)).thenReturn(passed);
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(store.findLatestReport(1L, 2)).thenReturn(running, passed);

        SemanticValidationReportResp staleResponse = service.getReport(8L, user);

        assertThat(staleResponse.getCanSubmit()).isFalse();
        assertThat(staleResponse.getSubmissionBlockReason()).contains("不是当前版本最新");

        draft.setStatus(ModelingDraftConstants.STATUS_PENDING_APPROVAL);
        SemanticValidationReportResp closedResponse = service.getReport(8L, user);

        assertThat(closedResponse.getCanSubmit()).isFalse();
        assertThat(closedResponse.getSubmissionBlockReason()).contains("当前状态不能提交");
    }

    /** 跨客户端存在活动修订时，旧通过报告也不能向前端暴露可提交状态。 */
    @Test
    void shouldHideSubmitCapabilityWhileAnotherClientIsRevising() {
        SemanticModelingDraftDO draft = draft(2, draftJson("当前目标"));
        SemanticModelingValidationReportDO passed =
                report(8L, ModelingDraftConstants.VALIDATION_PASSED, new Date());
        when(reportMapper.selectById(8L)).thenReturn(passed);
        when(permissionService.requireManageable(1L, user)).thenReturn(draft);
        when(store.findLatestReport(1L, 2)).thenReturn(passed);
        when(revisionStore.hasActiveRevision(1L)).thenReturn(true);

        SemanticValidationReportResp response = service.getReport(8L, user);

        assertThat(response.getCanSubmit()).isFalse();
        assertThat(response.getSubmissionBlockReason()).contains("AI 正在修订");
    }

    /** 报告列表属于管理员级证据，viewer 即使可读草稿也不能查询。 */
    @Test
    void shouldRequireManagePermissionForReportList() {
        ModelingDraftException denied = new ModelingDraftException(HttpStatus.FORBIDDEN,
                ModelingDraftConstants.ERROR_ACCESS_DENIED, "无权访问");
        when(permissionService.requireManageable(1L, user)).thenThrow(denied);

        assertThatThrownBy(() -> service.queryReports(1L, 1, 20, user)).isSameAs(denied);

        verify(reportMapper, never()).selectList(any());
        verify(store, never()).findLatestReport(anyLong(), anyInt());
    }

    /** 已存在但无管理权限的 reportId 必须与不存在 ID 返回完全相同的 404 语义。 */
    @Test
    void shouldMaskUnauthorizedReportAsNotFound() {
        SemanticModelingValidationReportDO report =
                report(8L, ModelingDraftConstants.VALIDATION_PASSED, new Date());
        when(reportMapper.selectById(8L)).thenReturn(report);
        when(permissionService.requireManageable(1L, user)).thenThrow(new ModelingDraftException(
                HttpStatus.FORBIDDEN, ModelingDraftConstants.ERROR_ACCESS_DENIED, "无权访问"));

        assertThatThrownBy(() -> service.getReport(8L, user))
                .isInstanceOfSatisfying(ModelingDraftException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ModelingDraftConstants.ERROR_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("验证报告不存在或不可访问");
                });

        verify(store, never()).findLatestReport(anyLong(), anyInt());
    }

    /** 未知 reportId 与无权访问现有报告使用同一响应，避免存在性侧信道。 */
    @Test
    void shouldReturnSameNotFoundForUnknownReport() {
        when(reportMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getReport(404L, user))
                .isInstanceOfSatisfying(ModelingDraftException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ModelingDraftConstants.ERROR_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("验证报告不存在或不可访问");
                });

        verify(permissionService, never()).requireManageable(anyLong(), any());
    }

    /** 构造草稿主记录。 */
    private SemanticModelingDraftDO draft(int versionNo, String json) {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(1L);
        draft.setStatus(ModelingDraftConstants.STATUS_DRAFT);
        draft.setCurrentVersionNo(versionNo);
        draft.setLockVersion(versionNo);
        draft.setDraftJson(json);
        draft.setDataSourceId(3L);
        draft.setDatabaseName("demo");
        draft.setSelectedTables("[]");
        draft.setLlmConversationId(9L);
        return draft;
    }

    /** 构造版本 2 的最小验证报告。 */
    private SemanticModelingValidationReportDO report(Long id, String status, Date finishedAt) {
        SemanticModelingValidationReportDO report = new SemanticModelingValidationReportDO();
        report.setId(id);
        report.setDraftId(1L);
        report.setDraftVersionId(20L);
        report.setDraftVersionNo(2);
        report.setStatus(status);
        report.setBlockingCount(0);
        report.setWarningCount(0);
        report.setFinishedAt(finishedAt);
        return report;
    }

    /** 构造最小 AI 修订请求。 */
    private ModelingDraftAiReviseReq revisionRequest(int baseVersionNo) {
        ModelingDraftAiReviseReq request = new ModelingDraftAiReviseReq();
        request.setInstruction("修改目标");
        request.setBaseVersionNo(baseVersionNo);
        return request;
    }

    /** 构造持久化修订 attempt。 */
    private SemanticModelingRevisionAttemptDO attempt(Long id, int baseVersionNo, String key) {
        SemanticModelingRevisionAttemptDO attempt = new SemanticModelingRevisionAttemptDO();
        attempt.setId(id);
        attempt.setDraftId(1L);
        attempt.setBaseVersionNo(baseVersionNo);
        attempt.setIdempotencyKey(key);
        attempt.setStatus(ModelingDraftRevisionStore.STATUS_RUNNING);
        attempt.setActiveMarker(1);
        return attempt;
    }

    /** 构造不可变草稿版本。 */
    private SemanticModelingDraftVersionDO version(Long id, int versionNo, String json) {
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(id);
        version.setDraftId(1L);
        version.setVersionNo(versionNo);
        version.setDraftJson(json);
        return version;
    }

    /** 与生产服务一致地计算已知测试修订指纹，供成功重放数据构造使用。 */
    private String revisionFingerprintForTest(Long draftId, ModelingDraftAiReviseReq request) {
        String canonical = draftId + "\n" + request.getBaseVersionNo() + "\n"
                + request.getInstruction().trim();
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 构造满足差异服务的最小完整 JSON。 */
    private String draftJson(String businessGoal) {
        return """
                {"schemaVersion":"1.0","businessGoal":"%s","models":[],"terms":[],"uncertainties":[]}
                """
                .formatted(businessGoal).trim();
    }

    /** 构造类型化模型响应。 */
    private ModelingDraftPayload payload(String businessGoal) {
        ModelingDraftPayload payload = new ModelingDraftPayload();
        payload.setSchemaVersion(ModelingDraftConstants.SCHEMA_VERSION);
        payload.setBusinessGoal(businessGoal);
        payload.setModels(new ArrayList<>());
        payload.setTerms(new ArrayList<>());
        payload.setUncertainties(new ArrayList<>());
        return payload;
    }

    /** 从合成 Prompt 中提取占位符，仅用于比较首轮和 repair 是否复用同一请求上下文。 */
    private String extractProtectedToken(String prompt, Pattern tokenPattern) {
        Matcher matcher = tokenPattern.matcher(prompt);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }
}
