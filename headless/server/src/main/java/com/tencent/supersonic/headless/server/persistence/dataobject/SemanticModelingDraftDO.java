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
 * 职责说明：映射 {@code s2_semantic_modeling_draft}，只保存阶段 3 的隔离草稿、生成状态和 LLM
 * 审计信息。该对象不会映射或写入正式模型、维度、指标、术语表。并发说明：业务层通过 {@code lock_version} 条件更新实现乐观锁，通过生成开始时间的条件更新实现 Worker
 * 认领。
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

    private String createdBy;

    private Date createdAt;

    private String updatedBy;

    private Date updatedAt;
}
