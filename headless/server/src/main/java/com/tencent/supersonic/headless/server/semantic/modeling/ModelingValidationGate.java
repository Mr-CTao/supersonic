package com.tencent.supersonic.headless.server.semantic.modeling;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 阶段 4 必需检查集合与 fail-closed 状态策略。
 *
 * <p>
 * 职责说明：集中定义发布审批前必须完成的十项检查，并将缺失、重复、未运行、空状态或未知状态 转换为脱敏阻塞项。策略只处理请求内不可变 DTO，不访问数据库、不执行 SQL，也不持有共享可变状态，
 * 因此无需额外并发保护。
 * </p>
 */
public final class ModelingValidationGate {

    public static final String CHECK_JSON_SCHEMA = "JSON_SCHEMA";
    public static final String CHECK_TABLE_FIELD_EXISTENCE = "TABLE_FIELD_EXISTENCE";
    public static final String CHECK_METRIC_EXPRESSION_FIELD = "METRIC_EXPRESSION_FIELD";
    public static final String CHECK_SENSITIVE_FIELD = "SENSITIVE_FIELD";
    public static final String CHECK_NAME_CONFLICT = "NAME_CONFLICT";
    public static final String CHECK_RETRIEVAL_POLLUTION = "RETRIEVAL_POLLUTION";
    public static final String CHECK_SAMPLE_QUESTION = "SAMPLE_QUESTION";
    public static final String CHECK_SEMANTIC_SQL_GENERATION = "SEMANTIC_SQL_GENERATION";
    public static final String CHECK_SQL_READ_ONLY = "SQL_READ_ONLY";
    public static final String CHECK_PERFORMANCE_RISK = "PERFORMANCE_RISK";

    private static final List<String> REQUIRED_CHECKS = List.of(CHECK_JSON_SCHEMA,
            CHECK_TABLE_FIELD_EXISTENCE, CHECK_METRIC_EXPRESSION_FIELD, CHECK_SENSITIVE_FIELD,
            CHECK_NAME_CONFLICT, CHECK_RETRIEVAL_POLLUTION, CHECK_SAMPLE_QUESTION,
            CHECK_SEMANTIC_SQL_GENERATION, CHECK_SQL_READ_ONLY, CHECK_PERFORMANCE_RISK);
    private static final Set<String> COMPLETED_STATUSES = Set.of(
            ModelingDraftConstants.VALIDATION_PASSED, ModelingDraftConstants.VALIDATION_WARNING,
            ModelingDraftConstants.VALIDATION_FAILED);

    private ModelingValidationGate() {}

    /**
     * 返回阶段 4 固定必需检查 ID，顺序同时作为前端报告展示顺序。
     *
     * @return 不可变的十项检查 ID。
     */
    public static List<String> requiredCheckIds() {
        return REQUIRED_CHECKS;
    }

    /**
     * 评估必需检查完整性，并补齐 fail-closed 阻塞项。
     *
     * <p>
     * 调用示例：{@code ModelingValidationGate.evaluate(results, blockers, warnings)}。检查结果中的 category
     * 只作为稳定 ID，不拼接草稿值；生成的 finding 因此不会泄露样例、SQL 条件值或凭据。
     * </p>
     *
     * @param requiredResults 本次验证生成的必需检查结果。
     * @param blockingItems 业务检查已生成的阻塞项。
     * @param warningItems 业务检查已生成的警告项。
     * @return 最终状态和补齐后的不可变阻塞/警告集合。
     */
    public static GateEvaluation evaluate(List<ModelingValidationCheckResult> requiredResults,
            List<ModelingValidationFinding> blockingItems,
            List<ModelingValidationFinding> warningItems) {
        List<ModelingValidationFinding> blockers = new ArrayList<>(safe(blockingItems));
        List<ModelingValidationFinding> warnings = new ArrayList<>(safe(warningItems));
        Map<String, ModelingValidationCheckResult> indexed = index(requiredResults, blockers);
        boolean hasWarningStatus = false;

        for (String checkId : REQUIRED_CHECKS) {
            ModelingValidationCheckResult result = indexed.get(checkId);
            if (result == null) {
                blockers.add(gateFinding(checkId, "REQUIRED_CHECK_MISSING", "必需检查结果缺失，验证拒绝放行"));
                continue;
            }
            String status = normalizeStatus(result.getStatus());
            if (ModelingDraftConstants.VALIDATION_WARNING.equals(status)) {
                hasWarningStatus = true;
            } else if (ModelingDraftConstants.VALIDATION_FAILED.equals(status)) {
                addIfAbsent(blockers,
                        gateFinding(checkId, "REQUIRED_CHECK_FAILED", "必需检查未通过，验证拒绝放行"));
            } else if (!COMPLETED_STATUSES.contains(status)) {
                String code = "NOT_RUN".equals(status) ? "REQUIRED_CHECK_NOT_RUN"
                        : "REQUIRED_CHECK_STATUS_INVALID";
                addIfAbsent(blockers, gateFinding(checkId, code, "必需检查未完整执行或状态无效，验证拒绝放行"));
            }
        }

        String status = !blockers.isEmpty() ? ModelingDraftConstants.VALIDATION_FAILED
                : hasWarningStatus || !warnings.isEmpty()
                        ? ModelingDraftConstants.VALIDATION_WARNING
                        : ModelingDraftConstants.VALIDATION_PASSED;
        return new GateEvaluation(status, List.copyOf(blockers), List.copyOf(warnings));
    }

    /**
     * 判断持久化报告中的必需检查是否可用于提交审批。
     *
     * @param requiredResults 从报告 JSON 反序列化得到的检查结果。
     * @return 仅当十项检查齐全且状态均为 PASSED/WARNING 时返回 true。
     */
    public static boolean isCompleteForSubmission(
            List<ModelingValidationCheckResult> requiredResults) {
        GateEvaluation evaluation = evaluate(requiredResults, List.of(), List.of());
        return !ModelingDraftConstants.VALIDATION_FAILED.equals(evaluation.status());
    }

    /** 建立 category 索引；重复或空 category 不能覆盖既有结果。 */
    private static Map<String, ModelingValidationCheckResult> index(
            List<ModelingValidationCheckResult> results, List<ModelingValidationFinding> blockers) {
        Map<String, ModelingValidationCheckResult> indexed = new LinkedHashMap<>();
        for (ModelingValidationCheckResult result : safe(results)) {
            if (result == null || StringUtils.isBlank(result.getCategory())) {
                blockers.add(gateFinding("UNKNOWN", "REQUIRED_CHECK_CATEGORY_MISSING",
                        "必需检查类别缺失，验证拒绝放行"));
                continue;
            }
            String category = result.getCategory().trim().toUpperCase(Locale.ROOT);
            if (indexed.putIfAbsent(category, result) != null) {
                blockers.add(gateFinding(category, "REQUIRED_CHECK_DUPLICATED", "必需检查结果重复，验证拒绝放行"));
            }
        }
        return indexed;
    }

    /** 规范检查状态；空状态保留为空以触发无效状态门禁。 */
    private static String normalizeStatus(String status) {
        return StringUtils.trimToEmpty(status).toUpperCase(Locale.ROOT);
    }

    /** 创建不包含草稿原值的门禁 finding。 */
    private static ModelingValidationFinding gateFinding(String checkId, String code,
            String message) {
        return ModelingValidationFinding.builder().category(checkId).code(code)
                .severity(ModelingDraftConstants.FINDING_BLOCKING)
                .path("$.requiredCheckResults[" + checkId + "]").message(message)
                .objectType("VALIDATION_CHECK").objectKey(checkId).build();
    }

    /** 避免同一检查的业务失败与门禁兜底产生重复 code。 */
    private static void addIfAbsent(List<ModelingValidationFinding> findings,
            ModelingValidationFinding candidate) {
        boolean exists = findings.stream()
                .anyMatch(item -> item != null
                        && StringUtils.equals(item.getCategory(), candidate.getCategory())
                        && StringUtils.equals(item.getCode(), candidate.getCode()));
        if (!exists) {
            findings.add(candidate);
        }
    }

    /** 对可空列表安全退化为空列表。 */
    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 门禁最终状态及可直接持久化的脱敏 finding 集合。 */
    public record GateEvaluation(String status, List<ModelingValidationFinding> blockingItems,
            List<ModelingValidationFinding> warningItems) {}
}
