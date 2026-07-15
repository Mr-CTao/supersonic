package com.tencent.supersonic.headless.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单项语义模型校验结果。
 *
 * <p>
 * 职责说明：把每项校验的独立状态与可选诊断组合起来，避免数据源网络失败吞掉 Calcite 结果。 对象仅在单次请求内构造，不共享可变状态。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticValidationCheck {
    private SemanticValidationCheckType type;
    private SemanticValidationStatus status;
    private String message;
    private SemanticDiagnostic diagnostic;
}
