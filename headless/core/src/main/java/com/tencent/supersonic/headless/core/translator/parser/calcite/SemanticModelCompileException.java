package com.tencent.supersonic.headless.core.translator.parser.calcite;

import com.tencent.supersonic.common.pojo.exception.StructuredException;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic;

/**
 * 语义模型编译领域异常。
 *
 * <p>
 * 职责说明：保留 Calcite 原始异常为 cause，同时仅向上层暴露经过脱敏的结构化诊断。 异常实例按请求创建，不包含共享可变状态。
 * </p>
 */
public class SemanticModelCompileException extends RuntimeException implements StructuredException {

    private final SemanticDiagnostic diagnostic;

    /**
     * 创建领域异常。
     *
     * @param diagnostic 安全的结构化诊断。
     * @param cause Calcite 原始异常，仅用于服务端日志堆栈。
     */
    public SemanticModelCompileException(SemanticDiagnostic diagnostic, Throwable cause) {
        super(diagnostic == null ? "语义模型编译失败" : diagnostic.getUserMessage(), cause);
        this.diagnostic = diagnostic;
    }

    /**
     * 返回安全诊断载荷。
     *
     * @return 结构化诊断。
     */
    public SemanticDiagnostic getDiagnostic() {
        return diagnostic;
    }

    /** {@inheritDoc} */
    @Override
    public int getStructuredCode() {
        return 400;
    }

    /** {@inheritDoc} */
    @Override
    public Object getStructuredData() {
        return diagnostic;
    }
}
