package com.tencent.supersonic.headless.server.semantic.gap;

/**
 * 语义缺口失败类型枚举。
 *
 * <p>
 * 职责说明：统一描述 Chat BI 问答链路中可沉淀为语义治理待办的失败或负反馈信号，避免 Controller、Service 和前端使用散落的魔法字符串。
 * 并发说明：枚举本身不可变且无共享可写状态，不需要额外并发保护。
 * </p>
 */
public enum SemanticGapFailureType {

    /** 没有选出可执行语义解析，典型错误为 parser error,no selectedParses。 */
    NO_SELECTED_PARSE,

    /** 单个 parser 在解析循环中抛出异常。 */
    PARSER_EXCEPTION,

    /** 用户或诊断结果表明命中了错误模型。 */
    WRONG_MODEL_MATCHED,

    /** 解析候选存在但置信度过低。 */
    LOW_CONFIDENCE,

    /** SQL 或语义查询执行阶段抛出异常。 */
    SQL_EXECUTION_ERROR,

    /** 结果为空且用户反馈实际应有数据。 */
    EMPTY_RESULT_SUSPECTED,

    /** 用户对问答结果点击不满意或填写负反馈。 */
    USER_NEGATIVE_FEEDBACK,

    /** 问答链路回退到 LLM 直接生成 SQL。 */
    FALLBACK_TO_LLM_SQL,

    /** 业务口径无法由系统确定，需要业务人员确认。 */
    BUSINESS_DEFINITION_UNCERTAIN,

    /** 确认缺少模型、指标、维度或关系等语义资产。 */
    SEMANTIC_ASSET_MISSING,

    /** 模型 SQL、表达式、Calcite 翻译或执行等确定性技术校验失败。 */
    TECHNICAL_VALIDATION_FAILED,

    /** 暂时无法归类的失败信号。 */
    UNKNOWN
}
