package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 草稿提交待审批响应。
 *
 * <p>
 * 职责说明：确认阶段 4 门禁已把指定版本和验证报告绑定到待审批状态。响应不表示审批通过、正式发布或知识索引刷新完成。 并发说明：幂等重放返回同一提交快照，不应再次递增草稿锁版本。
 * </p>
 */
@Data
@Builder
public class ModelingDraftSubmissionResp {

    /** 已提交的逻辑草稿 ID。 */
    private Long draftId;

    /** 提交后的草稿状态，当前固定为 PENDING_APPROVAL。 */
    private String status;

    /** 已提交的不可变草稿版本号。 */
    private Integer versionNo;

    /** 绑定的验证报告 ID。 */
    private Long validationReportId;

    /** 提交时间。 */
    private Date submittedAt;

    /** 提交用户。 */
    private String submittedBy;

    /** 是否由相同提交幂等键重放既有结果。 */
    private Boolean idempotentReplay;
}
