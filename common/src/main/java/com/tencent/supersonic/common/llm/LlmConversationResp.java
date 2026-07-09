package com.tencent.supersonic.common.llm;

import com.tencent.supersonic.common.persistence.dataobject.LlmMessageDO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM 会话响应。
 *
 * <p>
 * 职责说明：返回会话 ID、现有模型配置 ID、模型名、状态和消息列表，供最小调试接口查看 Gateway 拼接后的本地上下文。 并发说明：响应 DTO 只在请求线程内组装。
 * </p>
 */
@Data
@Builder
public class LlmConversationResp {

    private Long conversationId;

    private Integer chatModelId;

    private Integer providerId;

    private String providerType;

    private String modelName;

    private String status;

    private List<LlmMessageDO> messages;
}
