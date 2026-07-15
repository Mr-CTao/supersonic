package com.tencent.supersonic.headless.api.pojo.response;

/**
 * 语义问答诊断阶段。
 *
 * <p>
 * 职责说明：为模型校验、语义解析、翻译、执行及刷新链路提供稳定的跨模块阶段标识。 枚举不可变且不持有共享状态，不需要并发保护。
 * </p>
 */
public enum SemanticDiagnosticStage {
    SCHEMA_MAPPING,
    SEMANTIC_PARSE,
    MODEL_SQL_COMPILE,
    METRIC_EXPRESSION_COMPILE,
    SEMANTIC_TRANSLATION,
    PHYSICAL_SQL_EXECUTION,
    SCHEMA_CACHE_REFRESH,
    KNOWLEDGE_REFRESH
}
