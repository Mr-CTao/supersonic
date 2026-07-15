package com.tencent.supersonic.headless.api.pojo.response;

/**
 * 语义模型校验状态。
 *
 * <p>
 * 职责说明：让彼此独立的数据源执行、语义编译和表达式检查可以分别报告结果。 枚举不可变且线程安全。
 * </p>
 */
public enum SemanticValidationStatus {
    PASSED, BLOCKING, WARNING, SKIPPED
}
