package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Provider Adapter 的统一非流式请求对象。
 *
 * <p>
 * 职责说明：描述一次完整模型调用所需的模型、消息、输出格式、JSON Schema、超时和 DeepSeek 思考模式开关。调用示例：
 * {@code adapter.chat(LlmChatRequest.builder().modelName("deepseek-v4-pro").messages(messages).build())}。
 * 并发说明：该对象在一次请求内构造并传递，不作为共享状态复用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatRequest {

    /** 供应商类型，例如 DEEPSEEK。 */
    private String providerType;

    /** 普通 Chat Completion base URL。DeepSeek 普通对话默认是 https://api.deepseek.com。 */
    private String baseUrl;

    /** Beta base URL。DeepSeek 对话前缀续写、FIM 和 strict tool calling 需要 https://api.deepseek.com/beta。 */
    private String betaBaseUrl;

    /** 已解密 API Key；禁止写日志或返回给前端。 */
    private String apiKey;

    /** 模型名称，例如 deepseek-v4-pro。 */
    private String modelName;

    /** Gateway 已拼接好的完整多轮 messages。 */
    private List<LlmChatMessage> messages;

    /** text 或 json。 */
    private String responseFormat;

    /** 可选 JSON Schema；阶段 1 校验 schema 本身可解析，并校验模型输出为合法 JSON。 */
    private JsonNode jsonSchema;

    /** 采样温度；DeepSeek thinking=true 时官方说明不生效，Adapter 会主动省略。 */
    private Double temperature;

    /** 最大输出 token。 */
    private Integer maxTokens;

    /** 单次 HTTP 超时毫秒。 */
    private Long timeoutMs;

    /** 是否启用 DeepSeek 思考模式。 */
    private Boolean thinkingEnabled;

    /** DeepSeek reasoning_effort，支持 high/max。 */
    private String reasoningEffort;

    /** 是否要求 tool calling。阶段 1 不做工具编排，只做能力门禁。 */
    private Boolean requireToolCalling;

    /** 是否请求流式；阶段 1 只支持 false。 */
    private Boolean stream;
}
