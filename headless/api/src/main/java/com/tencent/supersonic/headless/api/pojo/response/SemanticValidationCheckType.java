package com.tencent.supersonic.headless.api.pojo.response;

/**
 * 语义模型校验项类型。
 *
 * <p>
 * 职责说明：定义 SQL 编辑页和发布门禁共享的检查维度；枚举无共享状态。
 * </p>
 */
public enum SemanticValidationCheckType {
    SOURCE_DATABASE, SEMANTIC_COMPILER, FIELD_EXPRESSION, SEMANTIC_QUERY_SMOKE
}
