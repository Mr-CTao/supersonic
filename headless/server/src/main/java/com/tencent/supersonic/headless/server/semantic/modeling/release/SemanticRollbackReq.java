package com.tencent.supersonic.headless.server.semantic.modeling.release;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 新增语义对象回滚请求。
 *
 * <p>
 * 职责说明：要求管理员明确记录回滚原因。第一版只触发本发布步骤中已登记对象的逆序删除， 不接受对象 ID 或任意 SQL 等客户端控制参数。
 * </p>
 */
@Data
public class SemanticRollbackReq {

    @NotBlank(message = "回滚原因不能为空")
    @Size(max = 1000, message = "回滚原因不能超过 1000 个字符")
    private String reason;
}
