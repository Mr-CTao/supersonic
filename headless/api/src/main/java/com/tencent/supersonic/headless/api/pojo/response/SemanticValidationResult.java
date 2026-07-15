package com.tencent.supersonic.headless.api.pojo.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义模型双重校验汇总。
 *
 * <p>
 * 职责说明：汇总数据源执行与 SuperSonic 编译结果，并携带内容摘要以防旧校验结果被复用。 每次请求独立创建，列表在响应返回后不再修改。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticValidationResult {
    private SemanticValidationStatus overallStatus;
    @Builder.Default
    private List<SemanticValidationCheck> checks = new ArrayList<>();
    private String contentDigest;
    private String traceId;
}
