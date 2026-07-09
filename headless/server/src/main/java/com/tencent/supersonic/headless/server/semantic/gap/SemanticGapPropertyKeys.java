package com.tencent.supersonic.headless.server.semantic.gap;

/**
 * 语义缺口采集在解析上下文中使用的扩展属性键。
 *
 * <p>职责说明：集中管理 parseInfo.properties 中供阶段 2 缺口采集识别的轻量标记，避免 Chat BI parser 与执行链路之间散落魔法字符串。
 * 并发说明：本类只包含不可变常量，没有共享可写状态，不需要额外并发保护。</p>
 */
public final class SemanticGapPropertyKeys {

    /** 标记该解析结果来自真实 fallback 流程，而不是普通 LLM_S2SQL 查询模式。 */
    public static final String FALLBACK = "semantic_gap_fallback";

    /** 记录 fallback 原因，便于缺口池详情页定位链路。 */
    public static final String FALLBACK_REASON = "semantic_gap_fallback_reason";

    /**
     * 禁止实例化常量类。
     */
    private SemanticGapPropertyKeys() {}
}
