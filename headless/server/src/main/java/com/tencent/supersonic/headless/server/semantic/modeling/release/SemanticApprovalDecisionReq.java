package com.tencent.supersonic.headless.server.semantic.modeling.release;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 阶段 5 审批决定请求。
 *
 * <p>
 * 职责说明：承载通过备注或拒绝原因；控制器完成 Bean Validation 后交给状态机处理。实例仅在 单次请求中使用，不存在共享可变状态。
 * </p>
 */
@Data
public class SemanticApprovalDecisionReq {

    /** 审批备注；拒绝时服务层要求非空。 */
    @Size(max = 1000, message = "审批说明不能超过 1000 个字符")
    private String reason;
}
