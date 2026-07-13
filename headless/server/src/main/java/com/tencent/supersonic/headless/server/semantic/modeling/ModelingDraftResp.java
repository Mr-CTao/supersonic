package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * AI 语义建模草稿管理端响应。
 *
 * <p>
 * 职责说明：返回可展示的元信息和当前结构化草稿；刻意不包含原始 LLM 输出、修复输出或样例行， 防止敏感内容通过管理端接口外泄。并发说明：响应在单次请求内组装，不共享可变状态。
 * </p>
 */
@Data
@Builder
public class ModelingDraftResp {
    private Long id;
    private String sourceType;
    private Long sourceId;
    private String title;
    private String businessGoal;
    private Long domainId;
    private Long dataSourceId;
    private String catalogName;
    private String databaseName;
    private List<String> selectedTables;
    private Integer chatModelId;
    private Boolean includeSampleData;
    private String status;
    private Integer currentVersionNo;
    /** 兼容管理端早期字段命名，值与 currentVersionNo 相同。 */
    private Integer currentVersion;
    private Integer lockVersion;
    private Integer currentAttemptNo;
    private Integer manualRegenerationCount;
    private Integer remainingManualRegenerations;
    private Boolean canRegenerate;
    private String regenerationBlockReason;
    /** 当前用户是否具备保存、AI 修订、验证和提交审批权限；详情接口始终返回。 */
    private Boolean canManage;
    private JsonNode currentDraft;
    /** 兼容旧前端表单；内容与 currentDraft 相同，不含原始模型正文。 */
    private String draftJson;
    private String errorCode;
    private String errorMessage;
    private String createdBy;
    private Date createdAt;
    private String updatedBy;
    private Date updatedAt;
    private Date generationStartedAt;
    private Date generationFinishedAt;
    /** 阶段 4 提交待审批时绑定的最新通过报告。 */
    private Long submittedValidationReportId;
    private String submittedBy;
    private Date submittedAt;
    private Boolean idempotentReplay;
}
