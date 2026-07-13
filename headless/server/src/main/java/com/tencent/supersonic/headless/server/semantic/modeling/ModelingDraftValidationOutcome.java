package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 阶段 4 验证引擎的内存结果。
 *
 * <p>
 * 职责说明：在验证引擎与持久化服务之间传递结构化检查结果，不包含草稿原始模型输出、样例行或数据源 凭据。对象在单次请求内创建后只读使用，不共享可变状态。
 * </p>
 */
@Getter
@Builder
public class ModelingDraftValidationOutcome {

    private final List<ModelingPlannedObject> plannedObjects;
    private final List<ModelingValidationCheckResult> requiredCheckResults;
    private final ModelingValidationCheckResult fieldExistenceResult;
    private final ModelingValidationCheckResult conflictResult;
    private final ModelingValidationCheckResult sensitiveFieldResult;
    private final List<ModelingSampleQuestionResult> sampleQuestionResults;
    private final ModelingValidationCheckResult sqlSafetyResult;
    private final ModelingValidationCheckResult performanceRiskResult;
    private final ModelingValidationCheckResult uncertaintyResult;
    private final List<ModelingValidationFinding> blockingItems;
    private final List<ModelingValidationFinding> warningItems;

    /**
     * 根据固定必需检查、阻塞项和警告项计算报告最终状态。
     *
     * @return {@code FAILED}、{@code WARNING} 或 {@code PASSED}。
     */
    public String resolveStatus() {
        return gateEvaluation().status();
    }

    /**
     * 返回包含必需检查兜底项的最终阻塞集合。
     *
     * @return 不包含草稿原值的不可变阻塞项。
     */
    public List<ModelingValidationFinding> effectiveBlockingItems() {
        return gateEvaluation().blockingItems();
    }

    /**
     * 返回最终警告集合。
     *
     * @return 不可变警告项。
     */
    public List<ModelingValidationFinding> effectiveWarningItems() {
        return gateEvaluation().warningItems();
    }

    /** 使用统一策略计算门禁，避免状态和持久化 finding 发生偏差。 */
    private ModelingValidationGate.GateEvaluation gateEvaluation() {
        return ModelingValidationGate.evaluate(requiredCheckResults, blockingItems, warningItems);
    }
}
