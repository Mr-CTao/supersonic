package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一类验证检查的汇总结果。
 *
 * <p>
 * 职责说明：为字段存在性、命名冲突、敏感字段、SQL 安全、性能风险和不确定项等检查提供一致的统计结构。详细问题由报告的阻塞项和警告项承载。 本 DTO
 * 不触发查询或状态变更，因此无需额外并发保护。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelingValidationCheckResult {

    /** 检查类别。 */
    private String category;

    /** 检查状态，只允许 PASSED、WARNING、FAILED 或 NOT_RUN。 */
    private String status;

    /** 面向管理员的脱敏汇总。 */
    private String summary;

    /** 实际检查对象总数。 */
    private Integer checkedCount;

    /** 通过检查的对象数。 */
    private Integer passedCount;

    /** 未通过检查的对象数。 */
    private Integer failedCount;

    /** 检查方式，例如 METADATA、SEMANTIC_API、AST 或 STATIC。 */
    private String mode;
}
