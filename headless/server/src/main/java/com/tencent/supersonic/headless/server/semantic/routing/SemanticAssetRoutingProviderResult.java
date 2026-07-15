package com.tencent.supersonic.headless.server.semantic.routing;

/**
 * 受限路由 Provider 单次调用结果。
 *
 * <p>职责：在服务内部短暂传递未记录的模型正文及其独立会话 ID。正文只进入 Advisor 结构校验，
 * 不写日志或通用路由响应；会话 ID 用于可审计持久化。</p>
 *
 * @param output Provider 返回的待校验 JSON 原文，可以为空。
 * @param conversationId 本次路由分析独立 LLM 会话 ID。
 */
public record SemanticAssetRoutingProviderResult(String output, Long conversationId) {
}
