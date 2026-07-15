package com.tencent.supersonic.headless.api.pojo.response;

/**
 * 语义问答稳定错误码。
 *
 * <p>
 * 职责说明：区分语义资产缺失、模型编译、翻译、执行和刷新失败，避免上层把所有故障 都折叠成 NO_SELECTED_PARSE。枚举不可变且线程安全。
 * </p>
 */
public enum SemanticDiagnosticCode {
    SCHEMA_ITEM_NOT_FOUND,
    MODEL_NOT_MATCHED,
    AMBIGUOUS_MODEL_MATCH,
    LLM_OUTPUT_INVALID,
    NO_SELECTED_PARSE,
    MODEL_SQL_PARSE_FAILED,
    MODEL_SQL_VALIDATION_FAILED,
    METRIC_EXPRESSION_INVALID,
    SEMANTIC_TRANSLATION_FAILED,
    SOURCE_SQL_EXECUTION_FAILED,
    CACHE_REFRESH_FAILED,
    KNOWLEDGE_REFRESH_FAILED,
    UNKNOWN_SEMANTIC_FAILURE
}
