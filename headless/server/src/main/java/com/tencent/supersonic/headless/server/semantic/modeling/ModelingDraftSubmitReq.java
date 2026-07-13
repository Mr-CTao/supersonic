package com.tencent.supersonic.headless.server.semantic.modeling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 验证通过后提交草稿待审批的请求。
 *
 * <p>
 * 职责说明：显式绑定准备提交的草稿版本和验证报告。该请求只触发阶段 4 门禁后的待审批状态，不代表批准、发布或写入正式语义资产。 并发说明：业务服务必须以当前版本和 DRAFT
 * 状态做数据库条件更新，并结合请求头幂等键防止重复提交。
 * </p>
 */
@Data
public class ModelingDraftSubmitReq {

    /** 准备提交且已被报告验证的草稿版本号。 */
    @NotNull
    @Min(1)
    private Integer versionNo;

    /** 必须属于同一草稿和版本的终态验证报告 ID。 */
    @NotNull
    @Min(1)
    private Long validationReportId;
}
