package com.tencent.supersonic.headless.server.semantic.routing;

/**
 * 可插拔语义路由建议 Provider。
 *
 * <p>
 * 职责：接收已脱敏且有界的候选上下文并返回固定 JSON 契约原文。实现必须通过现有受控 LLM Gateway， 禁止直接执行 SQL
 * 或写正式资产。接口无共享状态要求，具体实现须自行保证线程安全。
 * </p>
 */
@FunctionalInterface
public interface SemanticAssetRoutingAdviceProvider {

    /**
     * 获取一次结构化建议。
     *
     * @param request 已脱敏请求。
     * @param context 路由、模型和当前用户上下文；不得写入 Prompt。
     * @return Provider JSON 原文和独立会话 ID。
     * @throws SemanticAssetRoutingAdvisorException Provider 调用失败时抛出，路由服务按 fail-closed 处理。
     */
    SemanticAssetRoutingProviderResult advise(SemanticAssetRoutingAdvisorRequest request,
            SemanticAssetRoutingAdvisorContext context);

    /**
     * 对首次非法结构最多执行一次修复。
     *
     * @param invalidOutput 首次非法输出；实现不得记录该值。
     * @param request 原脱敏请求。
     * @param context 路由调用上下文。
     * @param conversationId 首次调用创建的会话 ID。
     * @return 修复后的固定 JSON 和同一会话 ID；默认不支持修复。
     */
    default SemanticAssetRoutingProviderResult repair(String invalidOutput,
            SemanticAssetRoutingAdvisorRequest request, SemanticAssetRoutingAdvisorContext context,
            Long conversationId) {
        return new SemanticAssetRoutingProviderResult(null, conversationId);
    }
}
