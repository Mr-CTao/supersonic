package com.tencent.supersonic.headless.api.pojo.response;

/**
 * 语义诊断严重级别。
 *
 * <p>
 * 职责说明：统一表达阻塞、警告和提示语义，供保存门禁与前端展示共同使用。 枚举无共享可变状态。
 * </p>
 */
public enum SemanticDiagnosticSeverity {
    BLOCKING, WARNING, INFO
}
