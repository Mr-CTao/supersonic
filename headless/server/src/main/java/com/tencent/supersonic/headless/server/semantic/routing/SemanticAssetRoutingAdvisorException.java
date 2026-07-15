package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 路由 Advisor 安全失败异常。
 *
 * <p>
 * 职责：在不携带 Prompt、Provider 原文或凭据的前提下，把已经创建的 LLM 会话 ID 传给失败 持久化路径。异常只在单次异步分析线程中使用，不共享。
 * </p>
 */
@Getter
public class SemanticAssetRoutingAdvisorException extends SemanticAssetRoutingException {

    private final Long llmConversationId;

    /**
     * 创建可安全持久化的 Advisor 异常。
     *
     * @param status HTTP 状态。
     * @param errorCode 稳定错误码。
     * @param message 面向用户的脱敏消息。
     * @param llmConversationId 已创建会话 ID；会话创建前失败时为空。
     */
    public SemanticAssetRoutingAdvisorException(HttpStatus status, String errorCode, String message,
            Long llmConversationId) {
        super(status, errorCode, message);
        this.llmConversationId = llmConversationId;
    }
}
