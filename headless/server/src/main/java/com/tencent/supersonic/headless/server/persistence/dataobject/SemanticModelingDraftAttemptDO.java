package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模草稿单次生成尝试记录。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_modeling_draft_attempt}，为首次生成和每次人工重新生成保存
 * 不可覆盖的模型参数、执行状态和后端诊断证据。原始输出、修复输出和校验问题只供服务端排障， 管理端 DTO 必须显式选择安全字段。并发说明：业务层以
 * {@code (draft_id, attempt_no)} 唯一约束 和主表 {@code current_attempt_no} 条件更新共同隔离新旧 Worker。
 * </p>
 */
@Data
@TableName("s2_semantic_modeling_draft_attempt")
public class SemanticModelingDraftAttemptDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long draftId;

    private Integer attemptNo;

    private String triggerType;

    private String status;

    private Integer chatModelId;

    private Boolean includeSample;

    private String idempotencyKey;

    /** 请求指纹用于识别相同幂等键携带不同参数的冲突请求。 */
    private String requestFingerprint;

    private Long llmConversationId;

    private String generateRequestId;

    private String repairRequestId;

    /** 仅服务端诊断使用，禁止通过 REST DTO 返回。 */
    private String rawOutput;

    /** 仅服务端诊断使用，禁止通过 REST DTO 返回。 */
    private String repairedOutput;

    private String failureStage;

    /** 脱敏后的结构化校验问题 JSON；响应时仍需反序列化为安全 DTO。 */
    private String validationIssues;

    private String errorCode;

    private String errorMessage;

    private Date startedAt;

    private Date finishedAt;

    private String createdBy;

    private Date createdAt;

    private String updatedBy;

    private Date updatedAt;
}
