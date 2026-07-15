package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 追加用户消息并调用模型的请求。
 *
 * <p>
 * 职责说明：承载用户消息、输出格式、JSON Schema、温度、超时、thinking 和工具调用开关。调用示例： {@code POST
 * /api/llm/conversations/{conversationId}/messages}。并发说明：请求 DTO 不含共享状态。
 * </p>
 */
@Data
public class LlmMessageCreateReq {

    /** 用户消息内容。 */
    private String content;

    /**
     * 是否把用户消息原文保存到通用消息表。路由等高敏建模调用可设为 false；Gateway 仍把本次原文
     * 发送给 Provider，但数据库只保存长度和 SHA-256 审计摘要。
     */
    private Boolean persistUserContent = true;

    /** text 或 json。 */
    private String responseFormat = LlmConstants.FORMAT_TEXT;

    /** 可选 JSON Schema；阶段 1 只校验 schema 本身可解析以及模型输出是合法 JSON。 */
    private JsonNode jsonSchema;

    /** 采样温度；thinking=true 时 DeepSeek Adapter 会省略该参数。 */
    private Double temperature;

    /** 最大输出 token。 */
    private Integer maxTokens;

    /** HTTP 超时毫秒。 */
    private Long timeoutMs;

    /** 阶段 1 只支持 false；true 会返回明确错误。 */
    private Boolean stream = false;

    /** 是否启用 DeepSeek 思考模式。 */
    private Boolean thinkingEnabled;

    /** DeepSeek reasoning_effort，支持 high/max。 */
    private String reasoningEffort;

    /** 是否要求工具调用；阶段 1 只做能力门禁，不做工具编排。 */
    private Boolean requireToolCalling = false;

    /** 可选幂等键；阶段 1 保存接口形态，后续可接入请求去重表。 */
    private String idempotencyKey;
}
