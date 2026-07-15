package com.tencent.supersonic.headless.server.semantic.routing;

import java.util.Optional;

/**
 * 经过安全校验的可选 LLM 建议结果。
 *
 * <p>
 * 职责：把合法建议和会话审计引用一起交给策略及 Store；纯规则路径通过 {@link #ruleOnly()} 明确表达未调用 LLM，避免把 Provider 不可用误判为规则决策。
 * </p>
 *
 * @param advice 已通过 Schema、禁止字段和 handle 白名单校验的建议。
 * @param llmConversationId 实际调用 LLM 时创建的独立会话 ID。
 */
public record SemanticAssetRoutingAdvisorResult(Optional<SemanticAssetRoutingAdvice>advice,Long llmConversationId){

/**
 * 创建未调用 LLM 的纯规则结果。
 *
 * @return 无建议、无会话 ID 的不可变结果。
 */
public static SemanticAssetRoutingAdvisorResult ruleOnly(){return new SemanticAssetRoutingAdvisorResult(Optional.empty(),null);}}
