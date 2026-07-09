package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 追加消息并调用模型的响应。
 *
 * <p>
 * 职责说明：返回用户消息 ID、assistant 消息 ID、正文、解析后的 JSON、token usage、状态和错误。并发说明：响应 DTO 不含共享状态。
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

    private String providerRequestId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;
}
