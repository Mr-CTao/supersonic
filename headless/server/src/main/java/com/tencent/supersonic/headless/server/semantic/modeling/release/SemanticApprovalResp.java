package com.tencent.supersonic.headless.server.semantic.modeling.release;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 待审批草稿管理端摘要。
 *
 * <p>
 * 职责说明：只返回审批所需的草稿、验证和计划对象计数，不返回 LLM 原文、样例数据或 SQL。 响应在单次请求内创建，不共享可变状态。
 * </p>
 */
@Data
@Builder
public class SemanticApprovalResp {
    private Long draftId;
    private String title;
    private String businessGoal;
    private Long sourceGapId;
    private Long domainId;
    private Long dataSourceId;
    private String status;
    private Integer draftVersionNo;
    private Long validationReportId;
    private String validationStatus;
    private Integer plannedObjectCount;
    private String submittedBy;
    private Date submittedAt;
    private String approvedBy;
    private Date approvedAt;
    private String approvalReason;
}
