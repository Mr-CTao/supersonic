package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * AI 语义建模草稿验证报告响应。
 *
 * <p>
 * 职责说明：返回与不可变草稿版本绑定的验证汇总、样例问法结果、阻塞项、警告项及提交待审批资格。响应刻意排除内部异常、样例原值和 Provider 原文。
 * 并发说明：{@code canSubmit} 只是报告生成时的快照；提交接口仍必须原子校验草稿当前版本和状态。
 * </p>
 */
@Data
@Builder
public class SemanticValidationReportResp {

    /** 验证报告 ID。 */
    private Long id;

    /** 逻辑草稿 ID。 */
    private Long draftId;

    /** 被验证的不可变版本记录 ID。 */
    private Long draftVersionId;

    /** 被验证的草稿版本号。 */
    private Integer draftVersionNo;

    /** 报告状态，例如 RUNNING、PASSED、WARNING 或 FAILED。 */
    private String status;

    /** 本版本计划新增的语义对象。 */
    private List<ModelingPlannedObject> plannedObjects;

    /** 阶段 4 固定十项必需检查，缺失、NOT_RUN 或未知状态均阻塞提交。 */
    private List<ModelingValidationCheckResult> requiredCheckResults;

    /** 表和字段存在性检查汇总。 */
    private ModelingValidationCheckResult fieldExistenceResult;

    /** 模型、维度、指标、术语名称冲突检查汇总。 */
    private ModelingValidationCheckResult conflictResult;

    /** 敏感字段声明完整性检查汇总。 */
    private ModelingValidationCheckResult sensitiveFieldResult;

    /** 样例问法在未发布草稿上的 DRAFT_SEMANTIC_PIPELINE 隔离语义解析结果。 */
    private List<ModelingSampleQuestionResult> sampleQuestionResults;

    /** 生成 SQL 的单语句只读检查汇总。 */
    private ModelingValidationCheckResult sqlSafetyResult;

    /** 大查询和缺少限制等性能风险汇总。 */
    private ModelingValidationCheckResult performanceRiskResult;

    /** 草稿未处理不确定项检查汇总。 */
    private ModelingValidationCheckResult uncertaintyResult;

    /** 必须修复后才能提交待审批的问题。 */
    private List<ModelingValidationFinding> blockingItems;

    /** 允许管理员知情确认但不直接阻断的问题。 */
    private List<ModelingValidationFinding> warningItems;

    /** 阻塞项数量。 */
    private Integer blockingCount;

    /** 警告项数量。 */
    private Integer warningCount;

    /** 该报告快照是否满足提交待审批条件。 */
    private Boolean canSubmit;

    /** 不可提交时的脱敏原因；可以提交时为空。 */
    private String submissionBlockReason;

    /** 触发验证的用户。 */
    private String createdBy;

    /** 验证报告创建时间。 */
    private Date createdAt;

    /** 验证完成或失败时间；运行中为空。 */
    private Date finishedAt;
}
