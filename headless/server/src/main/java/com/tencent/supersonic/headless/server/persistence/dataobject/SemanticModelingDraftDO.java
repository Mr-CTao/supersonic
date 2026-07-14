package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模草稿主记录。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_modeling_draft}，保存阶段 3 的隔离草稿、生成状态和 LLM 审计信息，并保存阶段 4
 * 通过验证门禁后提交待审批的最小交接信息，并承载阶段 5 审批与发布状态。该对象不会直接映射或写入正式模型、维度、指标、术语表。并发说明：业务层通过
 * {@code lock_version}、当前版本号和状态条件更新实现乐观锁，通过生成开始时间的条件更新实现 Worker 认领。
 * </p>
 */
@Data
@TableName("s2_semantic_modeling_draft")
public class SemanticModelingDraftDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sourceType;

    private Long sourceId;

    private String title;

    private String businessGoal;

    private Long domainId;

    private Long dataSourceId;

    private String catalogName;

    private String databaseName;

    /** 服务器序列化的 JSON 字符串数组，禁止直接拼接进 SQL。 */
    private String selectedTables;

    private Integer chatModelId;

    private Long llmConversationId;

    private Boolean includeSample;

    private String idempotencyKey;

    private String status;

    private Integer currentVersionNo;

    /**
     * 当前生成尝试序号。Worker 的全部状态更新都会同时校验该值，避免超时的旧 Worker 覆盖后续人工重新生成的结果。
     */
    private Integer currentAttemptNo;

    private Integer lockVersion;

    private Date generationStartedAt;

    private Date generationFinishedAt;

    private String draftJson;

    /** 仅后端故障诊断使用，任何管理端响应都不得返回该字段。 */
    private String rawOutput;

    /** 仅后端故障诊断使用，任何管理端响应都不得返回该字段。 */
    private String repairedOutput;

    private String errorCode;

    private String errorMessage;

    /** 阶段 4 提交待审批时绑定的验证报告；正式审批和发布由后续阶段实现。 */
    private Long submittedValidationReportId;

    /** 提交待审批请求的幂等键，禁止用于绕过最新草稿版本和验证报告校验。 */
    private String submissionIdempotencyKey;

    private String submittedBy;

    private Date submittedAt;

    /** 阶段 5 审批人；只有系统管理员审批通过后才允许进入发布编排。 */
    private String approvedBy;

    private Date approvedAt;

    /** 审批通过备注或拒绝原因；返回管理端前仍按普通业务文本处理。 */
    private String approvalReason;

    private String rejectedBy;

    private Date rejectedAt;

    private String createdBy;

    private Date createdAt;

    private String updatedBy;

    private Date updatedAt;
}
