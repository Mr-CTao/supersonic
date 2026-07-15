package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.llm.LlmConstants;
import com.tencent.supersonic.common.llm.LlmConversationCreateReq;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmConversationResp;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import com.tencent.supersonic.common.pojo.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义资产路由 Gateway Provider 契约测试。
 *
 * <p>
 * 职责：验证独立会话使用请求模型和当前用户、固定 JSON Schema、Prompt 不含正式资产 ID， 以及 Provider 错误携带会话 ID 进入
 * fail-closed。Gateway 全部使用 Mockito，不发起真实网络调用。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SemanticAssetRoutingGatewayAdviceProviderTest {

    @Mock
    private LlmConversationGatewayService gatewayService;

    private SemanticAssetRoutingGatewayAdviceProvider provider;
    private User user;

    /** 初始化无共享状态的 Provider 和测试用户。 */
    @BeforeEach
    void setUp() {
        provider =
                new SemanticAssetRoutingGatewayAdviceProvider(gatewayService, new ObjectMapper());
        user = User.get(7L, "route-admin");
    }

    /** 首次建议必须创建独立安全会话并发送固定 Schema 请求。 */
    @Test
    void shouldCreateRestrictedConversationWithFixedSchema() {
        when(gatewayService.createConversation(any(), same(user)))
                .thenReturn(LlmConversationResp.builder().conversationId(88L).build());
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(88L), any()))
                .thenReturn(successResponse(validAdvice()));

        SemanticAssetRoutingProviderResult result = provider.advise(advisorRequest(),
                new SemanticAssetRoutingAdvisorContext(9L, 2, 17, user));

        assertEquals(88L, result.conversationId());
        assertEquals(validAdvice(), result.output());
        ArgumentCaptor<LlmConversationCreateReq> conversationCaptor =
                ArgumentCaptor.forClass(LlmConversationCreateReq.class);
        verify(gatewayService).createConversation(conversationCaptor.capture(), same(user));
        assertEquals(17, conversationCaptor.getValue().getChatModelId());
        assertEquals("SEMANTIC_MODELING", conversationCaptor.getValue().getConversationType());
        assertEquals("semantic-asset-route:9", conversationCaptor.getValue().getBusinessId());

        ArgumentCaptor<LlmMessageCreateReq> messageCaptor =
                ArgumentCaptor.forClass(LlmMessageCreateReq.class);
        verify(gatewayService).appendMessageAndChatWithoutTransaction(eq(88L),
                messageCaptor.capture());
        LlmMessageCreateReq message = messageCaptor.getValue();
        assertEquals(LlmConstants.FORMAT_JSON, message.getResponseFormat());
        assertNotNull(message.getJsonSchema());
        assertFalse(message.getJsonSchema().path("additionalProperties").asBoolean(true));
        assertTrue(
                message.getJsonSchema().path("required").toString().contains("recommendedAction"));
        assertTrue(message.getContent().contains("candidate_1"));
        assertFalse(message.getContent().contains("assetId"));
        assertFalse(message.getContent().contains("101"));
        assertFalse(Boolean.TRUE.equals(message.getPersistUserContent()));
    }

    /** Provider 业务失败必须保留会话 ID，且不能伪装成无建议继续决策。 */
    @Test
    void shouldFailClosedAndKeepConversationIdForProviderError() {
        when(gatewayService.createConversation(any(), same(user)))
                .thenReturn(LlmConversationResp.builder().conversationId(88L).build());
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(88L), any()))
                .thenReturn(LlmMessageCreateResp.builder().status(LlmConstants.INVOCATION_FAILED)
                        .errorCode(LlmConstants.ERROR_RATE_LIMITED)
                        .errorMessage("provider raw message must not escape").build());

        SemanticAssetRoutingAdvisorException exception = assertThrows(
                SemanticAssetRoutingAdvisorException.class, () -> provider.advise(advisorRequest(),
                        new SemanticAssetRoutingAdvisorContext(9L, 2, 17, user)));

        assertEquals("ROUTING_ADVISOR_PROVIDER_FAILED", exception.getErrorCode());
        assertEquals(88L, exception.getLlmConversationId());
        assertFalse(exception.getReason().contains("provider raw"));
    }

    /** 修复必须复用同一会话，且不得把首次原始输出写入持久化的修复消息。 */
    @Test
    void shouldRepairInSameConversationWithoutRepeatingRawOutput() {
        when(gatewayService.appendMessageAndChatWithoutTransaction(eq(88L), any()))
                .thenReturn(successResponse(validAdvice()));

        SemanticAssetRoutingProviderResult result = provider.repair("raw-secret-output",
                advisorRequest(), new SemanticAssetRoutingAdvisorContext(9L, 2, 17, user), 88L);

        assertEquals(88L, result.conversationId());
        ArgumentCaptor<LlmMessageCreateReq> messageCaptor =
                ArgumentCaptor.forClass(LlmMessageCreateReq.class);
        verify(gatewayService).appendMessageAndChatWithoutTransaction(eq(88L),
                messageCaptor.capture());
        assertFalse(messageCaptor.getValue().getContent().contains("raw-secret-output"));
        assertTrue(messageCaptor.getValue().getContent().contains("candidate_1"));
        assertFalse(Boolean.TRUE.equals(messageCaptor.getValue().getPersistUserContent()));
        assertNotNull(messageCaptor.getValue().getJsonSchema());
        verify(gatewayService, never()).createConversation(any(), any());
    }

    /** 构造不含正式资产 ID 的 Provider 请求。 */
    private SemanticAssetRoutingAdvisorRequest advisorRequest() {
        return SemanticAssetRoutingAdvisorRequest.builder().businessGoal("库存呆滞分析")
                .requestedCapabilities(List.of("呆滞时长")).resultOperations(List.of("TOP_N"))
                .candidates(List.of(SemanticAssetRoutingAdvisorRequest.AdvisorCandidate.builder()
                        .candidateHandle("candidate_1").name("库存汇总").grain(List.of("物料"))
                        .capabilities(List.of("库存数量")).build()))
                .build();
    }

    /** 构造 Gateway 成功响应；语义建模原文只通过内部字段返回。 */
    private LlmMessageCreateResp successResponse(String output) {
        return LlmMessageCreateResp.builder().status(LlmConstants.INVOCATION_SUCCESS)
                .internalAssistantContent(output).build();
    }

    /** 返回最小合法路由建议。 */
    private String validAdvice() {
        return """
                {"recommendedAction":"EXTEND_EXISTING","candidateHandle":"candidate_1",
                 "coveredCapabilities":["库存数量"],"missingCapabilities":[],
                 "businessQuestions":[],"explanation":"仅缺少派生能力"}
                """.trim();
    }
}
