package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.llm.LlmConstants;
import com.tencent.supersonic.common.llm.LlmConversationCreateReq;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmConversationResp;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.GenerationContext;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.PreflightSnapshot;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftValidator.ValidatedDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 语义建模草稿异步 Worker 单元测试。
 *
 * <p>
 * 职责说明：只使用 Mockito 隔离 Gateway、上下文、校验器和短事务 Store，覆盖首次生成成功、同会话 修复成功、修复后仍失败以及 Provider
 * 超时/错误。测试同时约束会话类型、业务 ID、固定 JSON Schema 和持久化分支，防止阶段 3 意外依赖正式语义写服务或事件发布器。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ModelingDraftGenerationWorkerTest {

    private static final Long DRAFT_ID = 42L;
    private static final Long CONVERSATION_ID = 501L;
    private static final Integer ATTEMPT_NO = 1;
    private static final String FIRST_OUTPUT = "{\"attempt\":\"first\"}";
    private static final String REPAIRED_OUTPUT = "{\"attempt\":\"repair\"}";
    private static final String NORMALIZED_OUTPUT = "{\"schemaVersion\":\"1.0\"}";

    @Mock
    private ModelingDraftStore store;

    @Mock
    private ModelingDraftContextBuilder contextBuilder;

    @Mock
    private ModelingDraftValidator validator;

    @Mock
    private LlmConversationGatewayService gatewayService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SemanticModelingProperties properties = new SemanticModelingProperties();
    private final User user = User.getDefaultUser();

    private ModelingDraftGenerationWorker worker;
    private PreflightSnapshot snapshot;
    private GenerationContext generationContext;
    private JsonNode jsonSchema;

    /** 初始化可信快照和固定 Schema；每个用例可只覆盖自己关心的外部响应。 */
    @BeforeEach
    void setUp() throws Exception {
        ModelingDraftGenerateReq request = new ModelingDraftGenerateReq();
        request.setSourceType(ModelingDraftConstants.SOURCE_DATA_SOURCE);
        request.setBusinessGoal("分析订单金额趋势");
        request.setDataSourceId(7L);
        request.setSelectedTables(List.of("orders"));
        request.setChatModelId(9);
        request.setIncludeSampleData(false);

        jsonSchema = objectMapper.readTree("""
                {"type":"object","properties":{"schemaVersion":{"type":"string"}}}
                """);
        snapshot = new PreflightSnapshot(request, Map.of(), Set.of(), Map.of(), Map.of());
        generationContext = new GenerationContext("system prompt", "trusted user prompt",
                "output contract", jsonSchema, Map.of(), Set.of());
        properties.setRepairAttempts(1);
        properties.setGenerationTimeoutSeconds(30);
        worker = new ModelingDraftGenerationWorker(store, contextBuilder, validator, gatewayService,
                properties, objectMapper);

    }

    /** 合法首轮 JSON 应直接生成版本 1，不进入修复和失败分支。 */
    @Test
    void shouldCompleteDraftWhenFirstOutputIsValid() {
        stubGenerationPreamble();
        LlmMessageCreateResp response = jsonResponse(FIRST_OUTPUT);
        ValidatedDraft validated = validatedDraft(NORMALIZED_OUTPUT);
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                any(LlmMessageCreateReq.class))).thenReturn(response);
        when(validator.validateAndNormalize(FIRST_OUTPUT, Map.of(), Set.of()))
                .thenReturn(validated);
        when(store.completeGeneration(DRAFT_ID, ATTEMPT_NO, NORMALIZED_OUTPUT, FIRST_OUTPUT, null,
                CONVERSATION_ID, null, null, user)).thenReturn(true);

        worker.generate(DRAFT_ID, ATTEMPT_NO, snapshot, user);

        assertConversationContract();
        ArgumentCaptor<LlmMessageCreateReq> messageCaptor =
                ArgumentCaptor.forClass(LlmMessageCreateReq.class);
        verify(gatewayService).appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                messageCaptor.capture());
        assertJsonMessageContract(messageCaptor.getValue(), "trusted user prompt", "generate");
        verify(store).updateConversationId(DRAFT_ID, ATTEMPT_NO, CONVERSATION_ID);
        verify(store).completeGeneration(DRAFT_ID, ATTEMPT_NO, NORMALIZED_OUTPUT, FIRST_OUTPUT,
                null, CONVERSATION_ID, null, null, user);
        verify(store, never()).failGeneration(anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /** 首轮校验失败时必须复用原会话修复一次，并分别保存首轮与修复原文。 */
    @Test
    void shouldRepairOnceInSameConversationWhenFirstValidationFails() {
        stubGenerationPreamble();
        ModelingDraftException firstFailure = validationFailure("UNKNOWN_FIELD");
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                any(LlmMessageCreateReq.class))).thenReturn(rawJsonFailure(FIRST_OUTPUT),
                        assistantResponse(REPAIRED_OUTPUT));
        when(validator.validateAndNormalize(FIRST_OUTPUT, Map.of(), Set.of()))
                .thenThrow(firstFailure);
        when(validator.validateAndNormalize(REPAIRED_OUTPUT, Map.of(), Set.of()))
                .thenReturn(validatedDraft(NORMALIZED_OUTPUT));
        when(store.completeGeneration(DRAFT_ID, ATTEMPT_NO, NORMALIZED_OUTPUT, FIRST_OUTPUT,
                REPAIRED_OUTPUT, CONVERSATION_ID, null, null, user)).thenReturn(true);

        worker.generate(DRAFT_ID, ATTEMPT_NO, snapshot, user);

        assertConversationContract();
        ArgumentCaptor<LlmMessageCreateReq> messageCaptor =
                ArgumentCaptor.forClass(LlmMessageCreateReq.class);
        verify(gatewayService, times(2)).appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                messageCaptor.capture());
        List<LlmMessageCreateReq> messages = messageCaptor.getAllValues();
        assertJsonMessageContract(messages.get(0), "trusted user prompt", "generate");
        assertJsonMessageContract(messages.get(1), "UNKNOWN_FIELD", "repair");
        assertTrue(messages.get(1).getTimeoutMs() <= messages.get(0).getTimeoutMs(),
                "修复轮必须复用首轮剩余的 attempt 总超时预算");
        assertFalse(messages.get(1).getContent().contains(FIRST_OUTPUT),
                "修复提示只允许携带脱敏校验问题，不应重复模型原文");
        verify(store).completeGeneration(DRAFT_ID, ATTEMPT_NO, NORMALIZED_OUTPUT, FIRST_OUTPUT,
                REPAIRED_OUTPUT, CONVERSATION_ID, null, null, user);
        verify(store, never()).failGeneration(anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    /** 修复输出仍不合法时应保留两轮原文并落失败状态，不得创建草稿版本。 */
    @Test
    void shouldFailDraftWhenRepairOutputRemainsInvalid() {
        stubGenerationPreamble();
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                any(LlmMessageCreateReq.class))).thenReturn(assistantResponse(FIRST_OUTPUT),
                        assistantResponse(REPAIRED_OUTPUT));
        when(validator.validateAndNormalize(FIRST_OUTPUT, Map.of(), Set.of()))
                .thenThrow(validationFailure("UNKNOWN_TABLE"));
        when(validator.validateAndNormalize(REPAIRED_OUTPUT, Map.of(), Set.of()))
                .thenThrow(validationFailure("UNKNOWN_FIELD"));

        worker.generate(DRAFT_ID, ATTEMPT_NO, snapshot, user);

        verify(gatewayService, times(2)).appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                any(LlmMessageCreateReq.class));
        verify(store).failGeneration(DRAFT_ID, ATTEMPT_NO,
                ModelingDraftConstants.ERROR_OUTPUT_INVALID, "模型输出修复后仍未通过结构化草稿校验", FIRST_OUTPUT,
                REPAIRED_OUTPUT, ModelingDraftConstants.FAILURE_STAGE_REPAIR,
                validationFailure("UNKNOWN_FIELD").getIssues(), null, null, user.getName());
        verify(store, never()).completeGeneration(anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    /** Gateway 明确返回超时时应映射为稳定超时码，且不进入输出校验。 */
    @Test
    void shouldFailDraftWithTimeoutCodeWhenProviderTimesOut() {
        stubGenerationPreamble();
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                any(LlmMessageCreateReq.class)))
                        .thenReturn(errorResponse(LlmConstants.ERROR_TIMEOUT));

        worker.generate(DRAFT_ID, ATTEMPT_NO, snapshot, user);

        verify(store).failGeneration(DRAFT_ID, ATTEMPT_NO,
                ModelingDraftConstants.ERROR_GENERATION_TIMEOUT, "LLM Provider 调用失败，请稍后重试", null,
                null, ModelingDraftConstants.FAILURE_STAGE_GENERATE, List.of(), null, null,
                user.getName());
        verifyNoInteractions(validator);
        verify(store, never()).completeGeneration(anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    /** 非超时 Provider 错误应统一映射为脱敏错误码，避免向页面暴露供应商细节。 */
    @Test
    void shouldFailDraftWithProviderCodeWhenProviderReturnsError() {
        stubGenerationPreamble();
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(CONVERSATION_ID),
                any(LlmMessageCreateReq.class)))
                        .thenReturn(errorResponse(LlmConstants.ERROR_PROVIDER_UNAVAILABLE));

        worker.generate(DRAFT_ID, ATTEMPT_NO, snapshot, user);

        verify(store).failGeneration(DRAFT_ID, ATTEMPT_NO, ModelingDraftConstants.ERROR_PROVIDER,
                "LLM Provider 调用失败，请稍后重试", null, null,
                ModelingDraftConstants.FAILURE_STAGE_GENERATE, List.of(), null, null,
                user.getName());
        verifyNoInteractions(validator);
        verify(store, never()).completeGeneration(anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    /** 上下文超限是可操作的业务失败，必须保留明确错误码而非误报 Provider。 */
    @Test
    void shouldPreserveContextTooLargeFailureFromContextBuilder() {
        when(store.claimGeneration(DRAFT_ID, ATTEMPT_NO, user.getName())).thenReturn(true);
        when(contextBuilder.build(snapshot, user))
                .thenThrow(new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, "建模上下文超过安全上限，请减少选表"));

        worker.generate(DRAFT_ID, ATTEMPT_NO, snapshot, user);

        verify(store).failGeneration(DRAFT_ID, ATTEMPT_NO,
                ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, "建模上下文超过安全上限，请减少选表", null, null,
                ModelingDraftConstants.FAILURE_STAGE_CONTEXT, List.of(), null, null,
                user.getName());
        verifyNoInteractions(gatewayService, validator);
        verify(store, never()).completeGeneration(anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    /** Worker 的依赖图必须与正式资产写链路、Spring 事件发布完全隔离。 */
    @Test
    void shouldNotDependOnFormalSemanticWriteServicesOrEventPublisher() {
        List<String> forbiddenTypeNames =
                List.of("com.tencent.supersonic.headless.server.service.ModelService",
                        "com.tencent.supersonic.headless.server.service.DimensionService",
                        "com.tencent.supersonic.headless.server.service.MetricService",
                        "com.tencent.supersonic.headless.server.service.TermService");
        List<Class<?>> fieldTypes =
                Arrays.stream(ModelingDraftGenerationWorker.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getType).toList();

        assertFalse(fieldTypes.stream().map(Class::getName).anyMatch(forbiddenTypeNames::contains),
                "Worker 不得注入正式语义资产写服务");
        assertFalse(fieldTypes.stream().anyMatch(ApplicationEventPublisher.class::isAssignableFrom),
                "Worker 不得发布正式语义事件");
    }

    /** 为实际执行 Worker 的用例准备条件认领、可信上下文和 Gateway 会话。 */
    private void stubGenerationPreamble() {
        when(store.claimGeneration(DRAFT_ID, ATTEMPT_NO, user.getName())).thenReturn(true);
        when(contextBuilder.build(snapshot, user)).thenReturn(generationContext);
        when(gatewayService.createConversation(any(LlmConversationCreateReq.class), eq(user)))
                .thenReturn(conversation(CONVERSATION_ID));
    }

    /** 验证会话固定使用 SEMANTIC_MODELING 类型并把草稿 ID 作为业务 ID。 */
    private void assertConversationContract() {
        ArgumentCaptor<LlmConversationCreateReq> captor =
                ArgumentCaptor.forClass(LlmConversationCreateReq.class);
        verify(gatewayService).createConversation(captor.capture(), eq(user));
        LlmConversationCreateReq request = captor.getValue();
        assertEquals(ModelingDraftConstants.CONVERSATION_TYPE, request.getConversationType());
        assertEquals(String.valueOf(DRAFT_ID), request.getBusinessId());
        assertEquals(snapshot.request().getChatModelId(), request.getChatModelId());
        assertEquals(generationContext.systemPrompt(), request.getSystemPrompt());
    }

    /** 验证生成/修复消息都携带同一固定 JSON Schema 与阶段幂等键。 */
    private void assertJsonMessageContract(LlmMessageCreateReq request,
            String expectedContentFragment, String expectedStage) {
        assertEquals(LlmConstants.FORMAT_JSON, request.getResponseFormat());
        assertSame(jsonSchema, request.getJsonSchema());
        assertTrue(request.getContent().contains(expectedContentFragment));
        assertEquals("semantic-modeling-" + DRAFT_ID + "-" + ATTEMPT_NO + "-" + expectedStage,
                request.getIdempotencyKey());
        assertTrue(request.getTimeoutMs() > 0L && request.getTimeoutMs() <= 30_000L);
        assertEquals(Boolean.FALSE, request.getStream());
        assertNotNull(request.getMaxTokens());
    }

    /** 构造 Gateway 会话响应。 */
    private LlmConversationResp conversation(Long id) {
        return LlmConversationResp.builder().conversationId(id).status("ACTIVE").build();
    }

    /** 构造已被 Gateway 解析的 JSON 成功响应。 */
    private LlmMessageCreateResp jsonResponse(String json) {
        try {
            return LlmMessageCreateResp.builder().parsedJson(objectMapper.readTree(json))
                    .status("SUCCESS").build();
        } catch (Exception exception) {
            throw new IllegalArgumentException("测试 JSON 非法", exception);
        }
    }

    /** 构造仅包含 assistant 原文的成功响应。 */
    private LlmMessageCreateResp assistantResponse(String content) {
        return LlmMessageCreateResp.builder().assistantContent(content).status("SUCCESS").build();
    }

    /** 构造 Gateway JSON 解析失败但仍保留原始正文的响应。 */
    private LlmMessageCreateResp rawJsonFailure(String content) {
        return LlmMessageCreateResp.builder().internalAssistantContent(content)
                .errorCode(LlmConstants.ERROR_JSON_PARSE_FAILED).status("FAILED").build();
    }

    /** 构造 Provider 错误响应；错误消息不会参与草稿持久化断言。 */
    private LlmMessageCreateResp errorResponse(String errorCode) {
        return LlmMessageCreateResp.builder().errorCode(errorCode)
                .errorMessage("provider internal detail").status("FAILED").build();
    }

    /** 构造已规范化的校验结果。 */
    private ValidatedDraft validatedDraft(String json) {
        return new ValidatedDraft(new ModelingDraftPayload(), json);
    }

    /** 构造带稳定路径和错误码的 422 校验异常。 */
    private ModelingDraftException validationFailure(String code) {
        return new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                ModelingDraftConstants.ERROR_OUTPUT_INVALID, "结构化草稿校验失败",
                List.of(new ModelingValidationIssue("$.models[0]", code, "可信字段边界错误")));
    }
}
