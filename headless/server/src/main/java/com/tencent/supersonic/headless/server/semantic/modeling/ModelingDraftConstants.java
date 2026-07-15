package com.tencent.supersonic.headless.server.semantic.modeling;

import java.util.Set;

/**
 * AI 语义建模草稿模块常量。
 *
 * <p>
 * 职责说明：集中定义阶段 3 草稿、阶段 4 校准验证门禁和阶段 5 治理交接使用的状态、来源、允许的指标聚合和错误码，
 * 避免业务逻辑散落魔法字符串。本类只包含不可变常量，不持有共享可变状态，因此无需额外并发保护。
 * </p>
 */
public final class ModelingDraftConstants {

    public static final String SOURCE_SEMANTIC_GAP = "SEMANTIC_GAP";
    public static final String SOURCE_DATA_SOURCE = "DATA_SOURCE";

    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_GENERATION_FAILED = "GENERATION_FAILED";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_RELEASING = "RELEASING";
    public static final String STATUS_RELEASE_FAILED = "RELEASE_FAILED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    public static final String ATTEMPT_TRIGGER_INITIAL = "INITIAL";
    public static final String ATTEMPT_TRIGGER_MANUAL_REGENERATION = "MANUAL_REGENERATION";
    public static final String ATTEMPT_STATUS_QUEUED = "QUEUED";
    public static final String ATTEMPT_STATUS_GENERATING = "GENERATING";
    public static final String ATTEMPT_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String ATTEMPT_STATUS_FAILED = "FAILED";

    public static final String FAILURE_STAGE_QUEUE = "QUEUE";
    public static final String FAILURE_STAGE_CONTEXT = "CONTEXT";
    public static final String FAILURE_STAGE_GENERATE = "GENERATE";
    public static final String FAILURE_STAGE_VALIDATE = "VALIDATE";
    public static final String FAILURE_STAGE_REPAIR = "REPAIR";
    public static final String FAILURE_STAGE_TIMEOUT = "TIMEOUT";

    public static final String VERSION_AI_GENERATED = "AI_GENERATED";
    public static final String VERSION_AI_REVISED = "AI_REVISED";
    public static final String VERSION_RESTORED = "RESTORED";
    public static final String VERSION_MANUAL_SAVE = "MANUAL_SAVE";

    public static final String VALIDATION_RUNNING = "RUNNING";
    public static final String VALIDATION_PASSED = "PASSED";
    public static final String VALIDATION_WARNING = "WARNING";
    public static final String VALIDATION_FAILED = "FAILED";
    public static final String VALIDATION_NOT_RUN = "NOT_RUN";
    public static final String VALIDATION_SYSTEM_FAILED = "SYSTEM_FAILED";

    public static final String FINDING_BLOCKING = "BLOCKING";
    public static final String FINDING_WARNING = "WARNING";
    public static final String FINDING_INFO = "INFO";

    public static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    public static final String ERROR_ACCESS_DENIED = "ACCESS_DENIED";
    public static final String ERROR_NOT_FOUND = "DRAFT_NOT_FOUND";
    public static final String ERROR_CONFLICT = "LOCK_VERSION_CONFLICT";
    public static final String ERROR_REGENERATION_NOT_ALLOWED = "REGENERATION_NOT_ALLOWED";
    public static final String ERROR_REGENERATION_LIMIT = "REGENERATION_LIMIT_REACHED";
    public static final String ERROR_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
    public static final String ERROR_ACTIVE_DRAFT_CONFLICT = "ACTIVE_DRAFT_CONFLICT";
    public static final String ERROR_QUEUE_REJECTED = "GENERATION_QUEUE_REJECTED";
    public static final String ERROR_CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE";
    public static final String ERROR_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID";
    public static final String ERROR_PROVIDER = "MODEL_PROVIDER_FAILED";
    public static final String ERROR_GENERATION_TIMEOUT = "GENERATION_TIMEOUT";
    public static final String ERROR_VALIDATION_RUNNING = "VALIDATION_ALREADY_RUNNING";
    public static final String ERROR_VALIDATION_STALE_RECOVERED = "VALIDATION_RECOVERED_AS_STALE";
    public static final String ERROR_VALIDATION_FAILED = "VALIDATION_GATE_FAILED";
    public static final String ERROR_SUBMISSION_CONFLICT = "SUBMISSION_CONFLICT";
    public static final String ERROR_REVISION_FAILED = "AI_REVISION_FAILED";
    public static final String ERROR_REVISION_RUNNING = "REVISION_RUNNING";
    public static final String ERROR_REVISION_ATTEMPT_TERMINAL = "REVISION_ATTEMPT_TERMINAL";
    public static final String ERROR_REVISION_LEASE_EXPIRED = "REVISION_LEASE_EXPIRED";
    public static final String ERROR_REVISION_BASE_VERSION_CHANGED =
            "REVISION_BASE_VERSION_CHANGED";
    public static final String ERROR_SENSITIVE_INSTRUCTION = "SENSITIVE_REVISION_INSTRUCTION";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    public static final String SCHEMA_VERSION = "1.0";
    public static final String SCHEMA_VERSION_ROUTED = "2.0";
    public static final String ACTION_CREATE_NEW = "CREATE_NEW";
    public static final String ACTION_EXTEND_EXISTING = "EXTEND_EXISTING";
    public static final String CONVERSATION_TYPE = "SEMANTIC_MODELING";

    /** 结构预检一次最多返回的问题数，避免错误响应和修复 Prompt 无界增长。 */
    public static final int MAX_VALIDATION_ISSUES = 50;

    /** 人工重新生成的产品级硬上限；配置项只能收紧，不能绕过该边界。 */
    public static final int MAX_MANUAL_REGENERATIONS = 3;

    public static final Set<String> ALLOWED_AGGREGATIONS =
            Set.of("SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MAX", "MIN");
    public static final Set<String> ALLOWED_FILTER_OPERATORS = Set.of("EQ", "NE", "GT", "GTE", "LT",
            "LTE", "IN", "NOT_IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL");

    private ModelingDraftConstants() {}
}
