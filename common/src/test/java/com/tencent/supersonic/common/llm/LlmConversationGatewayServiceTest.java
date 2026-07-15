package com.tencent.supersonic.common.llm;

import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.persistence.dataobject.LlmConversationDO;
import com.tencent.supersonic.common.persistence.dataobject.LlmInvocationLogDO;
import com.tencent.supersonic.common.persistence.dataobject.LlmMessageDO;
import com.tencent.supersonic.common.persistence.dataobject.LlmModelCapabilityDO;
import com.tencent.supersonic.common.persistence.mapper.LlmConversationMapper;
import com.tencent.supersonic.common.persistence.mapper.LlmInvocationLogMapper;
import com.tencent.supersonic.common.persistence.mapper.LlmMessageMapper;
import com.tencent.supersonic.common.persistence.mapper.LlmModelCapabilityMapper;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.ChatModelService;
import com.tencent.supersonic.common.util.JsonUtil;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM Conversation Gateway 失败上下文与并发控制验证用例。
 *
 * <p>
 * 职责说明：验证 JSON 解析失败时会在服务端保留无效 assistant 原文、公开响应完成脱敏、Adapter JSON
 * 协议门禁生效，同时保证其他 Provider 错误不产生伪造的 assistant 消息；并发用例验证同一会话串行、固定锁容量以及异常后的可靠解锁。
 * 测试使用独立 mock 和局部线程池，不共享数据库或静态可变状态。
 * </p>
 */
class LlmConversationGatewayServiceTest {

    private static final Long CONVERSATION_ID = 42L;
    private static final Integer CHAT_MODEL_ID = 7;

    private ChatModelService chatModelService;
    private LlmProviderAdapterRegistry adapterRegistry;
    private LlmModelCapabilityMapper capabilityMapper;
    private LlmConversationMapper conversationMapper;
    private LlmMessageMapper messageMapper;
    private LlmInvocationLogMapper invocationLogMapper;
    private LlmProviderAdapter adapter;
    private LlmConversationGatewayService gatewayService;

    @BeforeEach
    void setUp() {
        chatModelService = mock(ChatModelService.class);
        adapterRegistry = mock(LlmProviderAdapterRegistry.class);
        capabilityMapper = mock(LlmModelCapabilityMapper.class);
        conversationMapper = mock(LlmConversationMapper.class);
        messageMapper = mock(LlmMessageMapper.class);
        invocationLogMapper = mock(LlmInvocationLogMapper.class);
        adapter = mock(LlmProviderAdapter.class);
        gatewayService = new LlmConversationGatewayService(chatModelService, adapterRegistry,
                capabilityMapper, conversationMapper, messageMapper, invocationLogMapper);

        LlmConversationDO conversation = new LlmConversationDO();
        conversation.setId(CONVERSATION_ID);
        conversation.setChatModelId(CHAT_MODEL_ID);
        conversation.setProviderType(LlmConstants.PROVIDER_DEEPSEEK);
        conversation.setModelName("deepseek-chat");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);

        ChatModelConfig config = ChatModelConfig.builder().provider(LlmConstants.PROVIDER_DEEPSEEK)
                .baseUrl(DeepSeekProviderAdapter.DEFAULT_BASE_URL).apiKey("test-key")
                .modelName("deepseek-chat").build();
        ChatModel chatModel = new ChatModel();
        chatModel.setId(CHAT_MODEL_ID);
        chatModel.setConfig(config);
        when(chatModelService.getChatModel(CHAT_MODEL_ID)).thenReturn(chatModel);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(adapterRegistry.getAdapter(anyString(), anyString(), anyString())).thenReturn(adapter);
        when(adapter.jsonOutputMode()).thenReturn(LlmJsonOutputMode.JSON_OBJECT);

        AtomicLong nextMessageId = new AtomicLong(100L);
        doAnswer(invocation -> {
            LlmMessageDO message = invocation.getArgument(0);
            message.setId(nextMessageId.getAndIncrement());
            return 1;
        }).when(messageMapper).insert(any(LlmMessageDO.class));
    }

    @Test
    void semanticModelingJsonParseFailureShouldOnlyReturnRawContentInternally() throws Exception {
        LlmConversationDO semanticConversation = semanticModelingConversation();
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(semanticConversation);
        LlmChatResponse adapterResponse = LlmChatResponse.builder().success(false)
                .content("not-json").reasoningContent("invalid-json reasoning").toolCalls("[]")
                .providerRequestId("deepseek-request-2").promptTokens(13).completionTokens(5)
                .totalTokens(18).errorCode(LlmConstants.ERROR_JSON_PARSE_FAILED)
                .errorMessage("DeepSeek returned invalid JSON").rawResponseRef("not-json-secret")
                .build();
        when(adapter.chat(any(LlmChatRequest.class))).thenReturn(adapterResponse);

        LlmMessageCreateResp response =
                gatewayService.appendMessageAndChat(CONVERSATION_ID, jsonRequest());

        ArgumentCaptor<LlmMessageDO> messageCaptor = ArgumentCaptor.forClass(LlmMessageDO.class);
        verify(messageMapper, times(1)).insert(messageCaptor.capture());
        List<LlmMessageDO> savedMessages = messageCaptor.getAllValues();
        assertEquals(LlmConstants.ROLE_USER, savedMessages.get(0).getRole());

        assertEquals(LlmConstants.INVOCATION_FAILED, response.getStatus());
        assertEquals(LlmConstants.ERROR_JSON_PARSE_FAILED, response.getErrorCode());
        assertNull(response.getAssistantMessageId());
        assertNull(response.getAssistantContent());
        assertNull(response.getReasoningContent());
        assertEquals("not-json", response.getInternalAssistantContent());
        assertEquals("deepseek-request-2", response.getRequestId());
        assertEquals("deepseek-request-2", response.getProviderRequestId());
        assertEquals(18, response.getTotalTokens());

        String publicJson = JsonUtil.INSTANCE.getObjectMapper().writeValueAsString(response);
        assertFalse(publicJson.contains("internalAssistantContent"));
        assertFalse(publicJson.contains("not-json"));
        assertFalse(publicJson.contains("invalid-json reasoning"));

        ArgumentCaptor<LlmInvocationLogDO> logCaptor =
                ArgumentCaptor.forClass(LlmInvocationLogDO.class);
        verify(invocationLogMapper).insert(logCaptor.capture());
        assertNull(logCaptor.getValue().getRawResponseRef());
        assertFalse(logCaptor.getValue().getErrorMessage().contains("DeepSeek"));
    }

    /** 成功建模输出也只通过内部字段交给 attempt，不落入通用消息或 REST 响应。 */
    @Test
    void semanticModelingSuccessShouldNotExposeOrPersistAssistantOutput() throws Exception {
        when(conversationMapper.selectById(CONVERSATION_ID))
                .thenReturn(semanticModelingConversation());
        String modelOutput = "{\"models\":[],\"secret\":\"owner@example.com\"}";
        when(adapter.chat(any(LlmChatRequest.class))).thenReturn(LlmChatResponse.builder()
                .success(true).content(modelOutput).parsedJson(JsonUtil.readTree(modelOutput))
                .providerRequestId("deepseek-request-success")
                .rawResponseRef("provider response copied model output").build());

        LlmMessageCreateResp response =
                gatewayService.appendMessageAndChat(CONVERSATION_ID, jsonRequest());

        verify(messageMapper, times(1)).insert(any(LlmMessageDO.class));
        assertNull(response.getAssistantMessageId());
        assertNull(response.getAssistantContent());
        assertNull(response.getParsedJson());
        assertEquals(modelOutput, response.getInternalAssistantContent());
        String publicJson = JsonUtil.INSTANCE.getObjectMapper().writeValueAsString(response);
        assertFalse(publicJson.contains("owner@example.com"));
        assertFalse(publicJson.contains("models"));

        ArgumentCaptor<LlmInvocationLogDO> logCaptor =
                ArgumentCaptor.forClass(LlmInvocationLogDO.class);
        verify(invocationLogMapper).insert(logCaptor.capture());
        assertNull(logCaptor.getValue().getRawResponseRef());
    }

    /** 高敏调用可只落盘不可逆摘要，同时 Provider 仍收到当前请求原文。 */
    @Test
    void nonPersistentUserContentShouldStoreHashButSendCurrentPrompt() {
        when(conversationMapper.selectById(CONVERSATION_ID))
                .thenReturn(semanticModelingConversation());
        String prompt = "脱敏后的受限候选上下文";
        java.util.concurrent.atomic.AtomicReference<LlmMessageDO> persisted =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            LlmMessageDO message = invocation.getArgument(0);
            message.setId(101L);
            persisted.set(message);
            return 1;
        }).when(messageMapper).insert(any(LlmMessageDO.class));
        when(messageMapper.selectList(any())).thenAnswer(invocation -> persisted.get() == null
                ? List.of()
                : List.of(persisted.get()));
        when(adapter.chat(any(LlmChatRequest.class))).thenReturn(
                LlmChatResponse.builder().success(true).content("{}")
                        .parsedJson(JsonUtil.readTree("{}")).build());
        LlmMessageCreateReq request = jsonRequest();
        request.setContent(prompt);
        request.setPersistUserContent(false);

        gatewayService.appendMessageAndChat(CONVERSATION_ID, request);

        assertFalse(persisted.get().getContent().contains(prompt));
        assertTrue(persisted.get().getContent().startsWith("[prompt omitted; sha256="));
        ArgumentCaptor<LlmChatRequest> adapterRequest =
                ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(adapter).chat(adapterRequest.capture());
        assertEquals(prompt, adapterRequest.getValue().getMessages().getLast().getContent());
    }

    @Test
    void providerFailureWithoutAssistantContentShouldKeepPreviousBehavior() {
        when(adapter.chat(any(LlmChatRequest.class))).thenReturn(
                LlmChatResponse.builder().success(false).errorCode(LlmConstants.ERROR_TIMEOUT)
                        .errorMessage("Provider timeout").build());

        LlmMessageCreateResp response =
                gatewayService.appendMessageAndChat(CONVERSATION_ID, jsonRequest());

        verify(messageMapper, times(1)).insert(any(LlmMessageDO.class));
        assertEquals(LlmConstants.INVOCATION_FAILED, response.getStatus());
        assertEquals(LlmConstants.ERROR_TIMEOUT, response.getErrorCode());
        assertNull(response.getAssistantMessageId());
        assertNull(response.getAssistantContent());
        assertNotNull(response.getRequestId());

        ArgumentCaptor<LlmInvocationLogDO> logCaptor =
                ArgumentCaptor.forClass(LlmInvocationLogDO.class);
        verify(invocationLogMapper).insert(logCaptor.capture());
        assertEquals(response.getRequestId(), logCaptor.getValue().getRequestId());
    }

    /** 能力表允许 JSON 也不能绕过 Adapter 的真实协议能力。 */
    @Test
    void jsonRequestShouldRejectAdapterWithoutJsonProtocolSupport() {
        when(adapter.jsonOutputMode()).thenReturn(LlmJsonOutputMode.NONE);

        LlmMessageCreateResp response =
                gatewayService.appendMessageAndChat(CONVERSATION_ID, jsonRequest());

        assertEquals(LlmConstants.INVOCATION_FAILED, response.getStatus());
        assertEquals(LlmConstants.ERROR_JSON_OUTPUT_UNSUPPORTED, response.getErrorCode());
        verify(adapter, never()).chat(any(LlmChatRequest.class));
        verify(invocationLogMapper, never()).insert(any(LlmInvocationLogDO.class));
    }

    /** Strict Adapter 必须取得 Schema，避免构造名义严格、实际无契约的请求。 */
    @Test
    void strictJsonAdapterShouldRejectRequestWithoutSchema() {
        when(adapter.jsonOutputMode()).thenReturn(LlmJsonOutputMode.JSON_SCHEMA_STRICT);

        LlmMessageCreateResp response =
                gatewayService.appendMessageAndChat(CONVERSATION_ID, jsonRequest());

        assertEquals(LlmConstants.ERROR_BAD_REQUEST, response.getErrorCode());
        assertTrue(response.getErrorMessage().contains("jsonSchema"));
        verify(adapter, never()).chat(any(LlmChatRequest.class));
    }

    /** REST 会话读取只允许创建者或超级管理员，防止枚举 ID 读取草稿 Prompt。 */
    @Test
    void conversationReadShouldRejectNonOwnerAndAllowOwner() {
        LlmConversationDO ownedConversation = new LlmConversationDO();
        ownedConversation.setId(CONVERSATION_ID);
        ownedConversation.setCreatedBy("alice");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(ownedConversation);

        assertThrows(InvalidPermissionException.class,
                () -> gatewayService.getConversation(CONVERSATION_ID, User.get(2L, "bob")));

        LlmConversationResp response =
                gatewayService.getConversation(CONVERSATION_ID, User.get(3L, "alice"));
        assertEquals(CONVERSATION_ID, response.getConversationId());
    }

    /** 会话详情必须隐藏仅供同一会话修复使用的无效模型正文。 */
    @Test
    void semanticModelingConversationReadShouldHideAllPromptAndModelMessages() {
        LlmConversationDO ownedConversation = new LlmConversationDO();
        ownedConversation.setId(CONVERSATION_ID);
        ownedConversation.setCreatedBy("alice");
        ownedConversation.setConversationType("SEMANTIC_MODELING");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(ownedConversation);

        LlmMessageDO visibleUserMessage = new LlmMessageDO();
        visibleUserMessage.setRole(LlmConstants.ROLE_USER);
        visibleUserMessage.setContent("generate draft");
        visibleUserMessage.setContentType(LlmConstants.CONTENT_TYPE_JSON);
        LlmMessageDO internalAssistantMessage = new LlmMessageDO();
        internalAssistantMessage.setRole(LlmConstants.ROLE_ASSISTANT);
        internalAssistantMessage.setContent("not-json-secret");
        internalAssistantMessage.setContentType(LlmConstants.CONTENT_TYPE_INTERNAL_JSON_ERROR);
        when(messageMapper.selectList(any()))
                .thenReturn(List.of(visibleUserMessage, internalAssistantMessage));

        LlmConversationResp response =
                gatewayService.getConversation(CONVERSATION_ID, User.get(3L, "alice"));

        assertTrue(response.getMessages().isEmpty());
    }

    /** 普通用户不能通过猜测模型 ID 创建会话并消耗不可见 Provider 凭据。 */
    @Test
    void createConversationShouldRejectInvisibleChatModel() {
        LlmConversationCreateReq request = new LlmConversationCreateReq();
        request.setChatModelId(CHAT_MODEL_ID);
        User visitor = User.get(9L, "visitor");
        when(chatModelService.getChatModels(visitor)).thenReturn(List.of());

        assertThrows(InvalidPermissionException.class,
                () -> gatewayService.createConversation(request, visitor));

        verify(conversationMapper, never()).insert(any(LlmConversationDO.class));
    }

    /** 模型能力决定阶段 3 JSON 门禁，只允许超级管理员修改。 */
    @Test
    void saveCapabilityShouldRejectNonAdministrator() {
        LlmModelCapabilityDO capability = new LlmModelCapabilityDO();
        capability.setChatModelId(CHAT_MODEL_ID);
        capability.setModelName("deepseek-chat");

        assertThrows(InvalidPermissionException.class,
                () -> gatewayService.saveCapability(capability, User.get(9L, "visitor")));

        verify(capabilityMapper, never()).insert(any(LlmModelCapabilityDO.class));
        verify(capabilityMapper, never()).updateById(any(LlmModelCapabilityDO.class));
    }

    /** 普通用户日志权限必须由数据库 EXISTS 过滤，不能预加载其全部会话 ID。 */
    @Test
    void invocationLogListShouldDelegateOwnerFilterToDatabase() {
        LlmInvocationLogQueryReq request = new LlmInvocationLogQueryReq();
        request.setProviderType(LlmConstants.PROVIDER_DEEPSEEK);
        request.setPageSize(500);
        when(invocationLogMapper.selectAccessibleLogs(request, "alice", 200))
                .thenReturn(List.of());

        List<LlmInvocationLogResp> result =
                gatewayService.listInvocationLogs(request, User.get(3L, "alice"));

        assertTrue(result.isEmpty());
        verify(invocationLogMapper).selectAccessibleLogs(request, "alice", 200);
        verify(conversationMapper, never()).selectList(any());
    }

    /** 超级管理员不带所有者条件，但仍由 Mapper 使用统一筛选和安全 limit。 */
    @Test
    void invocationLogListShouldOmitOwnerFilterForAdministrator() {
        when(invocationLogMapper.selectAccessibleLogs(isNull(), isNull(), eq(20)))
                .thenReturn(List.of());

        List<LlmInvocationLogResp> result =
                gatewayService.listInvocationLogs(null, User.getDefaultUser());

        assertTrue(result.isEmpty());
        verify(invocationLogMapper).selectAccessibleLogs(isNull(), isNull(), eq(20));
        verify(conversationMapper, never()).selectList(any());
    }

    /** Mapper SQL 必须保持相关 EXISTS，防止后续回退为大规模 IN 参数列表。 */
    @Test
    void invocationLogMapperShouldUseExistsOwnershipFilter() throws NoSuchMethodException {
        Method method = LlmInvocationLogMapper.class.getMethod("selectAccessibleLogs",
                LlmInvocationLogQueryReq.class, String.class, int.class);
        Select select = method.getAnnotation(Select.class);
        assertNotNull(select);
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("EXISTS (SELECT 1 FROM s2_llm_conversation"));
        assertFalse(sql.contains(" conversation_id IN "));
    }

    /** 建模日志必须强制置空摘要与原响应引用，且不从数据库读取 Prompt 到 JVM。 */
    @Test
    void semanticModelingInvocationLogsShouldHideRequestAndResponseContent() {
        LlmInvocationLogDO first = invocationLog(1L, CONVERSATION_ID);
        LlmInvocationLogDO second = invocationLog(2L, CONVERSATION_ID);
        first.setRawResponseRef("legacy raw output owner@example.com");
        second.setRawResponseRef("legacy repaired output token=secret-value");
        when(invocationLogMapper.selectAccessibleLogs(isNull(), isNull(), eq(20)))
                .thenReturn(List.of(first, second));

        LlmConversationDO conversation = new LlmConversationDO();
        conversation.setId(CONVERSATION_ID);
        conversation.setConversationType("SEMANTIC_MODELING");
        when(conversationMapper.selectByIds(any())).thenReturn(List.of(conversation));
        LlmMessageDO message = new LlmMessageDO();
        message.setConversationId(CONVERSATION_ID);
        message.setMessageOrder(1);
        message.setRole(LlmConstants.ROLE_USER);
        message.setContent("build a model");
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        List<LlmInvocationLogResp> result =
                gatewayService.listInvocationLogs(null, User.getDefaultUser());

        assertEquals(2, result.size());
        assertTrue(result.stream()
                .allMatch(item -> "SEMANTIC_MODELING".equals(item.getConversationType())));
        assertTrue(result.stream().allMatch(item -> item.getRequestSummary() == null));
        assertTrue(result.stream().allMatch(item -> item.getRawResponseRef() == null));
        verify(conversationMapper, times(1)).selectByIds(any());
        verify(messageMapper, never()).selectList(any());
        verify(conversationMapper, never()).selectById(any());
    }

    /** 普通调试日志仍批量加载会话消息，安全特例不影响既有调试能力。 */
    @Test
    void debugInvocationLogListShouldBatchLoadResponseContext() {
        LlmInvocationLogDO first = invocationLog(1L, CONVERSATION_ID);
        LlmInvocationLogDO second = invocationLog(2L, CONVERSATION_ID);
        when(invocationLogMapper.selectAccessibleLogs(isNull(), isNull(), eq(20)))
                .thenReturn(List.of(first, second));

        LlmConversationDO conversation = new LlmConversationDO();
        conversation.setId(CONVERSATION_ID);
        conversation.setConversationType("LLM_DEBUG");
        when(conversationMapper.selectByIds(any())).thenReturn(List.of(conversation));
        LlmMessageDO message = new LlmMessageDO();
        message.setConversationId(CONVERSATION_ID);
        message.setMessageOrder(1);
        message.setRole(LlmConstants.ROLE_USER);
        message.setContent("debug request");
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        List<LlmInvocationLogResp> result =
                gatewayService.listInvocationLogs(null, User.getDefaultUser());

        assertEquals(2, result.size());
        assertTrue(result.stream()
                .allMatch(item -> item.getRequestSummary().contains("debug request")));
        verify(conversationMapper, times(1)).selectByIds(any());
        verify(messageMapper, times(1)).selectList(any());
    }

    @Test
    void appendMessageAndChatShouldSerializeSameConversation() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();
        AtomicInteger activeInvocations = new AtomicInteger();
        AtomicInteger maxActiveInvocations = new AtomicInteger();
        LlmConversationGatewayService lockProbeService = lockProbeService((conversationId, req) -> {
            int invocationNumber = invocationCount.incrementAndGet();
            int active = activeInvocations.incrementAndGet();
            maxActiveInvocations.accumulateAndGet(active, Math::max);
            if (invocationNumber == 1) {
                firstEntered.countDown();
            } else {
                secondEntered.countDown();
            }
            try {
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                return LlmMessageCreateResp.builder().messageId(conversationId).build();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Lock probe was interrupted", exception);
            } finally {
                activeInvocations.decrementAndGet();
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LlmMessageCreateResp> first = executor
                    .submit(() -> lockProbeService.appendMessageAndChat(CONVERSATION_ID, null));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<LlmMessageCreateResp> second = executor
                    .submit(() -> lockProbeService.appendMessageAndChat(CONVERSATION_ID, null));
            assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertNotNull(first.get(1, TimeUnit.SECONDS));
            assertNotNull(second.get(1, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, maxActiveInvocations.get());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void appendMessageAndChatShouldUseBoundedLocksAndUnlockAfterFailure() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger();
        LlmConversationGatewayService lockProbeService = lockProbeService((conversationId, req) -> {
            if (invocationCount.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated failure");
            }
            return LlmMessageCreateResp.builder().messageId(conversationId).build();
        });

        assertThrows(IllegalStateException.class,
                () -> lockProbeService.appendMessageAndChat(CONVERSATION_ID, null));
        ReentrantLock[] locks = readConversationLocks(lockProbeService);
        assertEquals(64, locks.length);
        assertTrue(List.of(locks).stream().noneMatch(ReentrantLock::isLocked));
        assertNotNull(lockProbeService.appendMessageAndChat(CONVERSATION_ID, null));
    }

    /**
     * 创建 JSON mode 请求，用于触发结构化输出路径。
     */
    private static LlmMessageCreateReq jsonRequest() {
        LlmMessageCreateReq request = new LlmMessageCreateReq();
        request.setContent("return json");
        request.setResponseFormat(LlmConstants.FORMAT_JSON);
        return request;
    }

    /** 构造最小调用日志，用于列表批量上下文测试。 */
    private static LlmInvocationLogDO invocationLog(Long id, Long conversationId) {
        LlmInvocationLogDO log = new LlmInvocationLogDO();
        log.setId(id);
        log.setConversationId(conversationId);
        log.setStatus(LlmConstants.INVOCATION_SUCCESS);
        return log;
    }

    /** 构造含建模敏感上下文隔离策略的会话。 */
    private static LlmConversationDO semanticModelingConversation() {
        LlmConversationDO conversation = new LlmConversationDO();
        conversation.setId(CONVERSATION_ID);
        conversation.setChatModelId(CHAT_MODEL_ID);
        conversation.setProviderType(LlmConstants.PROVIDER_DEEPSEEK);
        conversation.setModelName("deepseek-chat");
        conversation.setConversationType("SEMANTIC_MODELING");
        return conversation;
    }

    /**
     * 创建仅覆写锁内逻辑的探针服务，隔离数据库与 Provider 依赖。
     */
    private static LlmConversationGatewayService lockProbeService(LockInvocation invocation) {
        return new LlmConversationGatewayService(null, null, null, null, null, null) {
            @Override
            protected LlmMessageCreateResp appendMessageAndChatLocked(Long conversationId,
                    LlmMessageCreateReq req) {
                return invocation.invoke(conversationId, req);
            }
        };
    }

    /**
     * 读取条带锁仅用于断言固定容量与异常后的解锁状态。
     */
    private static ReentrantLock[] readConversationLocks(LlmConversationGatewayService service)
            throws ReflectiveOperationException {
        Field field = LlmConversationGatewayService.class.getDeclaredField("conversationLocks");
        field.setAccessible(true);
        return (ReentrantLock[]) field.get(service);
    }

    /**
     * 描述锁内调用行为，便于并发测试精确控制进入和退出时机。
     */
    @FunctionalInterface
    private interface LockInvocation {

        /**
         * 执行一次锁内调用。
         *
         * @param conversationId 会话 ID。
         * @param req 消息请求。
         * @return 探针响应。
         */
        LlmMessageCreateResp invoke(Long conversationId, LlmMessageCreateReq req);
    }
}
