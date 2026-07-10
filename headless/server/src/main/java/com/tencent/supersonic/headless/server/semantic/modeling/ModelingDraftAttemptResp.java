package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 草稿生成尝试的安全只读响应。
 *
 * <p>
 * 职责说明：向管理端提供状态、模型快照、请求关联 ID 和脱敏校验问题；刻意排除 Prompt、样例行、 rawOutput 和 repairedOutput。并发说明：DTO
 * 在单次读取中创建，不共享可变状态。
 * </p>
 */
@Data
@Builder
public class ModelingDraftAttemptResp {
    private Long id;
    private Long draftId;
    private Integer attemptNo;
    private String triggerType;
    private String status;
    private Integer chatModelId;
    private Boolean includeSampleData;
    private Long llmConversationId;
    private String generateRequestId;
    private String repairRequestId;
    private String failureStage;
    private List<ModelingValidationIssue> validationIssues;
    private String errorCode;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
    private String createdBy;
    private Date createdAt;
    private String updatedBy;
    private Date updatedAt;
}
