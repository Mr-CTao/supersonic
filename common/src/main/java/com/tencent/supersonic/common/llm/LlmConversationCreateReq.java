package com.tencent.supersonic.common.llm;

import lombok.Data;

/**
 * 创建 LLM 会话请求。
 *
 * <p>
 * 职责说明：承载调试接口创建本地会话所需的会话类型、模型配置 ID、业务对象和可选 system prompt。调用示例：
 * {@code POST /api/llm/conversations}。并发说明：请求 DTO 不含共享状态。
 * </p>
 */
@Data
public class LlmConversationCreateReq {

    /** 会话类型，例如 SEMANTIC_MODELING_DEBUG。 */
    private String conversationType;

    /** 复用现有 `s2_chat_model.id` 作为 Provider 配置来源。 */
    private Integer chatModelId;

    /** 兼容阶段文档中的 providerId 命名，语义等同于 chatModelId。 */
    private Integer providerId;

    /** 可选模型名；为空时使用 `s2_chat_model.config.modelName`。 */
    private String modelName;

    /** 关联业务对象 ID；阶段 1 只保存，不触发业务编排。 */
    private String businessId;

    /** 可选 system prompt，创建会话时保存为第一条消息。 */
    private String systemPrompt;
}
