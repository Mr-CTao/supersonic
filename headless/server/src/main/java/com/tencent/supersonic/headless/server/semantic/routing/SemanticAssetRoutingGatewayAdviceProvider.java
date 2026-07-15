package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.llm.LlmConstants;
import com.tencent.supersonic.common.llm.LlmConversationCreateReq;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmConversationResp;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于现有 LLM Conversation Gateway 的语义资产路由 Provider。
 *
 * <p>
 * 职责：为每个路由分析创建独立 {@code SEMANTIC_MODELING} 会话，使用请求指定 chatModelId 和 当前用户发起固定 JSON Schema 调用；候选仅以服务端
 * handle 作为身份。Gateway 的建模隔离模式不会 保存 assistant 原文或生成日志摘要，本组件也不记录 Prompt、原始输出或 Provider 错误正文。
 * </p>
 *
 * <p>
 * 并发说明：固定 Schema 以只读 JsonNode 保存，每次请求传递深拷贝；其余状态均为方法局部变量， Spring
 * 单例可安全并发复用。修复只复用调用方显式提供的会话，不使用进程内会话缓存。
 * </p>
 */
@Component
public class SemanticAssetRoutingGatewayAdviceProvider
        implements SemanticAssetRoutingAdviceProvider {

    private static final String CONVERSATION_TYPE = "SEMANTIC_MODELING";
    private static final String BUSINESS_ID_PREFIX = "semantic-asset-route:";
    private static final String SYSTEM_PROMPT = """
            你是语义资产路由比较器。你只能比较用户消息中的授权候选，候选身份只能使用 candidateHandle。
            禁止输出正式资产 ID、模型 ID、SQL、字段值、凭据或任何可执行变更。
            只返回符合给定 JSON Schema 的单个 JSON 对象，不要输出 Markdown 或解释性前后缀。
            recommendedAction 只是语义建议，最终动作由服务端策略裁决。
            """;
    private static final String REPAIR_PROMPT = """
            上一次输出未通过固定结构或安全校验。不要引用或复述上一次输出，必须根据会话中原始的
            授权候选上下文重新生成完整 JSON。候选只能使用 candidateHandle；禁止正式资产 ID 和 SQL。
            只返回符合本次 JSON Schema 的 JSON 对象。
            """;
    static final String JSON_SCHEMA_TEXT =
            """
                    {
                      "type":"object",
                      "additionalProperties":false,
                      "required":["recommendedAction","coveredCapabilities","missingCapabilities",
                                  "businessQuestions","explanation"],
                      "properties":{
                        "recommendedAction":{"type":"string","enum":["REUSE_EXISTING",
                          "EXTEND_EXISTING","CREATE_NEW","NEEDS_CLARIFICATION"]},
                        "candidateHandle":{"type":["string","null"],"maxLength":64},
                        "intent":{
                          "type":["object","null"],"additionalProperties":false,
                          "properties":{
                            "subject":{"type":["string","null"],"maxLength":256},
                            "grain":{"type":"array","maxItems":20,"items":{"type":"string","maxLength":128}},
                            "dimensions":{"type":"array","maxItems":50,"items":{"type":"string","maxLength":128}},
                            "measures":{"type":"array","maxItems":50,"items":{"type":"string","maxLength":128}},
                            "resultOperations":{"type":"array","maxItems":20,
                              "items":{"type":"string","enum":["ORDER_ASC","ORDER_DESC","TOP_N","PAGINATION"]}}
                          }
                        },
                        "coveredCapabilities":{"type":"array","maxItems":100,
                          "items":{"type":"string","maxLength":128}},
                        "missingCapabilities":{"type":"array","maxItems":50,"items":{
                          "type":"object","additionalProperties":false,
                          "required":["type","name","reason"],
                          "properties":{
                            "type":{"type":"string","maxLength":64},
                            "name":{"type":"string","maxLength":128},
                            "reason":{"type":"string","maxLength":500}
                          }
                        }},
                        "businessQuestions":{"type":"array","maxItems":20,"items":{
                          "type":"object","additionalProperties":false,
                          "required":["key","question","required"],
                          "properties":{
                            "key":{"type":"string","pattern":"^[a-z][a-z0-9_]{0,63}$"},
                            "question":{"type":"string","maxLength":500},
                            "required":{"type":"boolean"},
                            "answerType":{"type":["string","null"],"enum":["SINGLE_SELECT","BOOLEAN","TEXT",null]},
                            "options":{"type":"array","maxItems":20,"items":{
                              "type":"object","additionalProperties":false,"required":["key","label"],
                              "properties":{"key":{"type":"string","maxLength":64},
                                "label":{"type":"string","maxLength":128}}
                            }},
                            "maxLength":{"type":["integer","null"],"minimum":1,"maximum":1000},
                            "affectsRecommendation":{"type":"boolean"}
                          }
                        }},
                        "explanation":{"type":"string","maxLength":1000}
                      }
                    }
                    """;

    private final LlmConversationGatewayService gatewayService;
    private final ObjectMapper objectMapper;
    private final JsonNode jsonSchema;

    /**
     * 创建 Gateway 路由 Provider。
     *
     * @param gatewayService 现有统一 LLM 会话网关。
     * @param objectMapper JSON 映射器。
     * @throws IllegalStateException 固定 Schema 在构建期被意外破坏时抛出。
     */
    public SemanticAssetRoutingGatewayAdviceProvider(LlmConversationGatewayService gatewayService,
            ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.objectMapper = objectMapper;
        try {
            this.jsonSchema = objectMapper.readTree(JSON_SCHEMA_TEXT);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Semantic asset routing JSON Schema is invalid",
                    exception);
        }
    }

    /**
     * 创建独立会话并获取首轮路由建议。
     *
     * @param request 已脱敏且有界的候选上下文。
     * @param context 路由、模型和当前用户上下文。
     * @return 未记录的模型正文和新会话 ID。
     * @throws SemanticAssetRoutingAdvisorException 上下文非法或 Gateway 调用失败时 fail-closed。
     */
    @Override
    public SemanticAssetRoutingProviderResult advise(SemanticAssetRoutingAdvisorRequest request,
            SemanticAssetRoutingAdvisorContext context) {
        validateContext(context);
        Long conversationId = null;
        try {
            LlmConversationResp conversation = gatewayService
                    .createConversation(createConversationRequest(context), context.user());
            conversationId = conversation == null ? null : conversation.getConversationId();
            if (conversationId == null) {
                throw providerFailure(null);
            }
            LlmMessageCreateResp response =
                    gatewayService.appendMessageAndChatWithoutTransaction(conversationId,
                            createMessageRequest(buildUserPrompt(request), context, "advise"));
            return response(response, conversationId);
        } catch (SemanticAssetRoutingAdvisorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Provider/Gateway 原始异常不进入路由日志或用户消息。
            throw providerFailure(conversationId);
        }
    }

    /**
     * 在同一会话中执行唯一一次完整结构修复。
     *
     * @param invalidOutput 首次非法输出；出于安全原因不会写入修复 Prompt。
     * @param request 原始受限请求；原消息已存在会话中，无需重复写入。
     * @param context 路由调用上下文。
     * @param conversationId 首次调用的独立会话 ID。
     * @return 修复输出和同一会话 ID。
     * @throws SemanticAssetRoutingAdvisorException 会话缺失或 Gateway 调用失败时 fail-closed。
     */
    @Override
    public SemanticAssetRoutingProviderResult repair(String invalidOutput,
            SemanticAssetRoutingAdvisorRequest request, SemanticAssetRoutingAdvisorContext context,
            Long conversationId) {
        validateContext(context);
        if (conversationId == null) {
            throw providerFailure(null);
        }
        try {
            String repairContent = REPAIR_PROMPT + "\n原始受限上下文：\n"
                    + buildUserPrompt(request);
            LlmMessageCreateResp response = gatewayService.appendMessageAndChatWithoutTransaction(
                    conversationId, createMessageRequest(repairContent, context, "repair"));
            return response(response, conversationId);
        } catch (SemanticAssetRoutingAdvisorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw providerFailure(conversationId);
        }
    }

    /** 构造不含 Prompt 外业务信息的会话创建请求。 */
    private LlmConversationCreateReq createConversationRequest(
            SemanticAssetRoutingAdvisorContext context) {
        LlmConversationCreateReq request = new LlmConversationCreateReq();
        request.setConversationType(CONVERSATION_TYPE);
        request.setChatModelId(context.chatModelId());
        request.setBusinessId(BUSINESS_ID_PREFIX + context.routeId());
        request.setSystemPrompt(SYSTEM_PROMPT);
        return request;
    }

    /** 构造固定 JSON Schema 的非流式 Gateway 请求。 */
    private LlmMessageCreateReq createMessageRequest(String content,
            SemanticAssetRoutingAdvisorContext context, String stage) {
        LlmMessageCreateReq request = new LlmMessageCreateReq();
        request.setContent(content);
        // 路由 Prompt 即使已脱敏也不写通用消息表；Gateway 只保留长度与 SHA-256 审计摘要。
        request.setPersistUserContent(false);
        request.setResponseFormat(LlmConstants.FORMAT_JSON);
        request.setJsonSchema(jsonSchema.deepCopy());
        request.setTemperature(SemanticAssetRoutingConstants.ADVISOR_TEMPERATURE);
        request.setMaxTokens(SemanticAssetRoutingConstants.ADVISOR_MAX_OUTPUT_TOKENS);
        request.setTimeoutMs(SemanticAssetRoutingConstants.ADVISOR_TIMEOUT_MILLIS);
        request.setStream(false);
        request.setRequireToolCalling(false);
        request.setIdempotencyKey("semantic-asset-route-" + context.routeId() + "-"
                + context.analysisVersion() + "-" + stage);
        return request;
    }

    /** 只序列化允许进入 Prompt 的业务摘要和候选 handle。 */
    private String buildUserPrompt(SemanticAssetRoutingAdvisorRequest request) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("businessGoal", request == null ? null : request.getBusinessGoal());
        prompt.put("requestedCapabilities",
                request == null ? List.of() : safe(request.getRequestedCapabilities()));
        prompt.put("resultOperations",
                request == null ? List.of() : safe(request.getResultOperations()));
        prompt.put("candidates", request == null ? List.of() : safe(request.getCandidates()));
        try {
            return objectMapper.writeValueAsString(prompt);
        } catch (JsonProcessingException exception) {
            throw new SemanticAssetRoutingAdvisorException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "ROUTING_ADVISOR_REQUEST_INVALID", "无法构建安全的 AI 路由请求", null);
        }
    }

    /** 将 Gateway 响应归一化；非 JSON 解析错误一律 fail-closed。 */
    private SemanticAssetRoutingProviderResult response(LlmMessageCreateResp response,
            Long conversationId) {
        if (response == null) {
            throw providerFailure(conversationId);
        }
        boolean repairableJsonFailure =
                LlmConstants.ERROR_JSON_PARSE_FAILED.equals(response.getErrorCode());
        if ((!LlmConstants.INVOCATION_SUCCESS.equals(response.getStatus())
                && !repairableJsonFailure)
                || (StringUtils.isNotBlank(response.getErrorCode()) && !repairableJsonFailure)) {
            throw providerFailure(conversationId);
        }
        String output = StringUtils.defaultIfBlank(response.getInternalAssistantContent(),
                response.getAssistantContent());
        if (StringUtils.isBlank(output) && response.getParsedJson() != null) {
            output = response.getParsedJson().toString();
        }
        return new SemanticAssetRoutingProviderResult(output, conversationId);
    }

    /** 校验 Gateway 调用所需上下文，纯规则路径不会进入这里。 */
    private void validateContext(SemanticAssetRoutingAdvisorContext context) {
        if (context == null || context.routeId() == null || context.analysisVersion() == null
                || context.chatModelId() == null || context.user() == null
                || StringUtils.isBlank(context.user().getName())) {
            throw new SemanticAssetRoutingAdvisorException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ROUTING_ADVISOR_CONTEXT_INVALID", "AI 路由分析缺少模型或用户上下文", null);
        }
    }

    /** 创建不暴露 Provider 原文的统一失败。 */
    private SemanticAssetRoutingAdvisorException providerFailure(Long conversationId) {
        return new SemanticAssetRoutingAdvisorException(HttpStatus.BAD_GATEWAY,
                "ROUTING_ADVISOR_PROVIDER_FAILED", "AI 语义比较暂时不可用，请重试分析", conversationId);
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
