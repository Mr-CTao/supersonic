package com.tencent.supersonic.headless.server.semantic.gap;

/**
 * 语义缺口处理状态枚举。
 *
 * <p>职责说明：表达缺口从待分析、草稿、确认、发布、忽略到重新打开的状态流转。阶段 2 只实现待分析、忽略和重新打开，
 * 其余状态为后续 AI 草稿和发布阶段预留，避免后续迁移状态值。并发说明：枚举不可变，无线程安全风险。</p>
 */
public enum SemanticGapStatus {

    /** 新采集或重新归并后的缺口，等待管理员分析。 */
    PENDING_ANALYSIS,

    /** 后续阶段预留：AI 草稿生成中。 */
    DRAFTING,

    /** 后续阶段预留：等待管理员确认语义草稿。 */
    WAITING_CONFIRMATION,

    /** 后续阶段预留：缺口对应语义资产已发布。 */
    RELEASED,

    /** 管理员确认暂不处理。 */
    IGNORED,

    /** 被忽略后重新打开，重新进入治理视野。 */
    REOPENED
}
