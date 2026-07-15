package com.tencent.supersonic.headless.server.semantic.routing;

import com.tencent.supersonic.common.pojo.User;

/**
 * 单次语义资产路由 LLM 调用上下文。
 *
 * <p>职责：仅向 Gateway 适配器传递路由 ID、分析版本、请求指定模型和当前用户；这些字段不会被
 * 序列化进 LLM Prompt。record 不含共享可变状态，可安全跨分析线程传递。</p>
 *
 * @param routeId 路由分析 ID。
 * @param analysisVersion 分析版本，用于生成稳定且版本隔离的 Gateway 幂等键。
 * @param chatModelId 请求选择的聊天模型配置 ID。
 * @param user 当前认证用户，用于 Gateway 模型可见性和会话归属校验。
 */
public record SemanticAssetRoutingAdvisorContext(Long routeId, Integer analysisVersion,
        Integer chatModelId, User user) {
}
