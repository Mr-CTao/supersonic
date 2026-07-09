package com.tencent.supersonic.common.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * LLM 调用日志响应。
 *
 * <p>
 * 职责说明：为前端调用日志列表和详情提供脱敏后的调用元数据、请求摘要、响应摘要和错误信息。安全说明：不包含 API Key、Token 或完整请求体。 并发说明：响应 DTO 不含共享状态。
 * </p>
 */
@Data
@Builder
public class LlmInvocationLogResp {

    private Long id;

    private Long conversationId;

    private String conversationType;

    private Integer chatModelId;

    private String providerType;

    private String modelName;

    private String requestId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long latencyMs;

    private String status;

    private String errorCode;

    private String errorMessage;

    private String requestSummary;

    private String rawResponseRef;

    private Boolean hasReasoningContent;

    private Boolean hasToolCalls;

    private Date createdAt;
}
