package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 追加消息并调用模型的响应。
 *
 * <p>
 * 职责说明：返回用户消息 ID、assistant 消息 ID、正文、解析后的 JSON、Gateway/Provider 请求 ID、token usage、状态和错误。
 * JSON 解析失败的模型原文仅通过 {@link #internalAssistantContent} 在服务端编排链路中传递，并被 Jackson 排除，避免 REST
 * 序列化外泄。并发说明：响应 DTO 不含共享状态。
 * </p>
 */
@Data
@Builder
public class LlmMessageCreateResp {

    private Long messageId;

    private Long assistantMessageId;

    private String assistantContent;

    private JsonNode parsedJson;

    private String reasoningContent;

    private String toolCalls;

    private String status;

    private String errorCode;

    private String errorMessage;

    /** Gateway 调用日志使用的请求 ID；Provider 未返回 ID 时由 Gateway 生成。 */
    private String requestId;

    private String providerRequestId;

    /**
     * JSON 解析失败时供后台校验、修复和审计存储使用的模型原文。
     *
     * <p>
     * 该字段禁止通过 REST 返回；调用方不得写入业务日志或面向用户的错误信息。
     * </p>
     */
    @JsonIgnore
    private String internalAssistantContent;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;
}
