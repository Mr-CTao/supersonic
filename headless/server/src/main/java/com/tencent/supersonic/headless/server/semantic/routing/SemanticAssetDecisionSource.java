package com.tencent.supersonic.headless.server.semantic.routing;

/**
 * 语义资产路由决策来源。
 *
 * <p>
 * 职责：区分纯确定性规则和规则结合 LLM 建议，便于审计与前端解释；LLM 不能单独成为决策来源。 枚举不可变且线程安全。
 * </p>
 */
public enum SemanticAssetDecisionSource {
    RULE_ONLY, RULE_AND_LLM
}
