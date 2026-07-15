package com.tencent.supersonic.headless.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨模块结构化语义诊断载荷。
 *
 * <p>
 * 职责说明：只携带经过脱敏且长度受控的诊断元数据，不包含完整 SQL、连接参数或 Java 堆栈。 DTO 按单次请求创建并随响应只读传播，不在多个线程间修改，因此无需锁保护。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticDiagnostic {
    private SemanticDiagnosticCode code;
    private SemanticDiagnosticStage stage;
    private SemanticDiagnosticSeverity severity;
    private Long modelId;
    private String modelName;
    private Long dataSetId;
    private String engineType;
    private Integer line;
    private Integer column;
    private String token;
    private String userMessage;
    private String developerMessage;
    private String suggestion;
    private String traceId;
}
