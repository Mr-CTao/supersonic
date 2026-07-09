package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider Adapter 的统一非流式响应对象。
 *
 * <p>
 * 职责说明：归一化模型输出正文、DeepSeek reasoning_content、Tool Calls、token usage、厂商请求 ID 和错误信息。调用示例：
 * {@code LlmChatResponse response = adapter.chat(request)}。并发说明：响应对象只在请求线程内使用，无共享可变状态。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatResponse {

    /** 模型最终回答正文。 */
    private String content;

    /** JSON mode 下后端解析后的 JSON；解析失败时为空并返回 JSON_PARSE_FAILED。 */
    private JsonNode parsedJson;

    /** DeepSeek 思考模式返回内容，必须持久化，避免后续工具调用场景丢失上下文。 */
    private String reasoningContent;

    /** Tool Calls 原始 JSON；阶段 1 仅预留。 */
    private String toolCalls;

    /** stop、length、content_filter、tool_calls 等厂商 finish reason。 */
    private String finishReason;

    /** 厂商请求 ID 或 response id。 */
    private String providerRequestId;

    /** 脱敏后的原始响应摘要引用，用于排障，不保存 API Key。 */
    private String rawResponseRef;

    /** 输入 token。 */
    private Integer promptTokens;

    /** 输出 token。 */
    private Integer completionTokens;

    /** 总 token。 */
    private Integer totalTokens;

    /** 调用是否成功。 */
    private boolean success;

    /** 统一错误码。 */
    private String errorCode;

    /** 脱敏后的错误摘要。 */
    private String errorMessage;
}
