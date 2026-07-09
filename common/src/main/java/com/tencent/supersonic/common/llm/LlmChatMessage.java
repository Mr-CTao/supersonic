package com.tencent.supersonic.common.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一会话消息对象。
 *
 * <p>
 * 职责说明：承载 Gateway 内部和 Provider Adapter 之间传递的标准 message，屏蔽 DeepSeek、OpenAI-compatible 等厂商在 assistant
 * reasoning、tool call 字段上的协议差异。并发说明：该对象是请求级 DTO，不被服务单例长期共享，无需额外同步。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatMessage {

    /** 消息角色，取值见 {@link LlmConstants#ROLE_USER} 等常量。 */
    private String role;

    /** 文本或 JSON 字符串内容。 */
    private String content;

    /** DeepSeek 思考模式返回的推理内容；阶段 1 保存该字段，但默认不参与普通多轮上下文拼接。 */
    private String reasoningContent;

    /** Tool Calls 原始 JSON；阶段 1 仅预留，不做工具编排。 */
    private String toolCalls;

    /** tool 消息需要回填的 tool_call_id。 */
    private String toolCallId;

    /**
     * DeepSeek 对话前缀续写标记。
     *
     * <p>
     * 阶段 1 仅识别该 Beta 能力，不在调试接口中开放；保留字段是为了后续接入时不用改变统一消息对象。
     * </p>
     */
    private Boolean prefix;
}
