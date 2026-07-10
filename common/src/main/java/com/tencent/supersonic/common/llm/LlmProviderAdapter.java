package com.tencent.supersonic.common.llm;

/**
 * 大模型供应商适配器 SPI。
 *
 * <p>
 * 职责说明：定义统一的非流式 chat 调用、流式预留能力、错误归一化和 token 估算入口，让业务层不直接依赖某个厂商 SDK 或 HTTP 协议。实现示例：DeepSeek Adapter
 * 将 {@link LlmChatRequest} 转换为官方 `/chat/completions` 请求。
 * </p>
 *
 * <p>
 * 并发说明：Adapter 由 Spring 单例管理。实现类不得在字段中保存某次请求的可变 messages、API Key 或响应内容；需要缓存的内容必须使用线程安全结构。
 * </p>
 */
public interface LlmProviderAdapter {

    /**
     * 返回当前 Adapter 实际支持的 JSON 输出协议。
     *
     * <p>
     * 默认值为 {@link LlmJsonOutputMode#NONE}，避免新增 Provider 在未完成协议适配时仅凭模型能力配置误入 JSON
     * 调用链路。实现类必须按真实请求协议显式覆写。
     * </p>
     *
     * @return Adapter 的 JSON 输出模式。
     */
    default LlmJsonOutputMode jsonOutputMode() {
        return LlmJsonOutputMode.NONE;
    }

    /**
     * 判断当前 Adapter 是否支持指定供应商配置。
     *
     * @param providerType 供应商类型，例如 DEEPSEEK。
     * @param baseUrl 当前模型配置的 base URL。
     * @param modelName 当前模型名。
     * @return 支持返回 true，否则返回 false。
     */
    boolean supports(String providerType, String baseUrl, String modelName);

    /**
     * 发起一次非流式对话补全。
     *
     * @param request Gateway 已完成上下文拼接和能力门禁后的统一请求。
     * @return 统一响应，包含正文、reasoning_content、usage 和错误信息。
     */
    LlmChatResponse chat(LlmChatRequest request);

    /**
     * 流式对话预留入口。
     *
     * @param request Gateway 统一请求。
     * @return 当前阶段固定抛出不支持异常。
     * @throws UnsupportedOperationException 阶段 1 不实现流式响应。
     */
    default LlmChatResponse streamChat(LlmChatRequest request) {
        throw new UnsupportedOperationException("LLM streaming is reserved for later phases.");
    }

    /**
     * 根据 HTTP 状态码和厂商错误体归一化错误码。
     *
     * @param httpStatus HTTP 状态码。
     * @param responseBody 厂商返回体。
     * @return 统一错误码。
     */
    String normalizeError(int httpStatus, String responseBody);

    /**
     * 估算消息 token 数。
     *
     * @param messages 完整消息列表。
     * @return 粗略 token 数；没有 tokenizer 时允许返回字符级近似值。
     */
    default int estimateTokens(java.util.List<LlmChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        return messages.stream().map(LlmChatMessage::getContent).filter(java.util.Objects::nonNull)
                .mapToInt(content -> Math.max(1, content.length() / 4)).sum();
    }
}
