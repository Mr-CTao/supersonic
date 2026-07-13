package com.tencent.supersonic.headless.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模草稿验证报告持久化对象。
 *
 * <p>
 * 职责说明：映射 {@code s2_semantic_validation_report}，将阶段 4 验证结果绑定到不可变草稿版本。 各检查结果以受控 JSON 保存，供应用层反序列化为安全
 * DTO；本对象不执行审批、正式语义资产写入、发布或回滚。并发说明：运行中的报告使用 {@code active_marker = 1}，完成或失败后置空，数据库唯一键
 * {@code (draft_id, active_marker)} 保证同一草稿最多一个验证任务处于运行态。
 * </p>
 */
@Data
@TableName("s2_semantic_validation_report")
public class SemanticModelingValidationReportDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long draftId;

    private Long draftVersionId;

    private Integer draftVersionNo;

    private String status;

    /** 触发验证时规范化后的选项 JSON，禁止直接拼接进 SQL。 */
    private String validationOptions;

    /** 固定十项必需检查结果 JSON；提交审批时必须重新反序列化并 fail-closed 校验。 */
    private String requiredCheckResults;

    /** 本版本计划新增的模型、维度、指标和术语摘要 JSON。 */
    private String plannedObjects;

    private String fieldExistenceResult;

    private String conflictResult;

    private String sensitiveFieldResult;

    private String sampleQuestionResults;

    private String sqlSafetyResult;

    private String performanceRiskResult;

    private String uncertaintyResult;

    /** 阻塞提交待审批的问题 JSON；不得包含样例原值或 Provider 原文。 */
    private String blockingItems;

    /** 非阻塞警告 JSON；不得包含样例原值或 Provider 原文。 */
    private String warningItems;

    private Integer blockingCount;

    private Integer warningCount;

    /** RUNNING 时固定为 1，终态必须置为 null 以释放单草稿运行锁。 */
    private Integer activeMarker;

    /** 仅保存稳定且脱敏的系统错误码，不保存内部异常信息。 */
    private String systemErrorCode;

    private String createdBy;

    private Date createdAt;

    private Date finishedAt;
}
