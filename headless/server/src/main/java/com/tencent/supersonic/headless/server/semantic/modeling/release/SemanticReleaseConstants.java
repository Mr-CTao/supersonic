package com.tencent.supersonic.headless.server.semantic.modeling.release;

import java.util.Set;

/**
 * 阶段 5 审批、发布、知识刷新与回滚常量。
 *
 * <p>
 * 职责说明：集中维护治理状态、步骤类型和错误码，避免状态机散落魔法字符串。该类只包含 不可变常量，不持有共享状态，因此不需要额外并发保护。
 * </p>
 */
public final class SemanticReleaseConstants {

    public static final String DRAFT_APPROVED = "APPROVED";
    public static final String DRAFT_REJECTED = "REJECTED";
    public static final String DRAFT_RELEASING = "RELEASING";
    public static final String DRAFT_RELEASE_FAILED = "RELEASE_FAILED";
    public static final String DRAFT_RELEASED = "RELEASED";
    public static final String DRAFT_ROLLED_BACK = "ROLLED_BACK";

    public static final String RELEASE_IN_PROGRESS = "IN_PROGRESS";
    public static final String RELEASE_SUCCEEDED = "SUCCEEDED";
    public static final String RELEASE_FAILED = "FAILED";
    public static final String RELEASE_ROLLBACK_IN_PROGRESS = "ROLLBACK_IN_PROGRESS";
    public static final String RELEASE_ROLLED_BACK = "ROLLED_BACK";
    public static final String RELEASE_ROLLBACK_FAILED = "ROLLBACK_FAILED";

    public static final String STEP_IN_PROGRESS = "IN_PROGRESS";
    public static final String STEP_SUCCEEDED = "SUCCEEDED";
    public static final String STEP_FAILED = "FAILED";
    public static final String STEP_SKIPPED = "SKIPPED";

    public static final String REFRESH_PENDING = "PENDING";
    public static final String REFRESH_SUCCEEDED = "SUCCEEDED";
    public static final String REFRESH_FAILED = "FAILED";

    public static final String TYPE_MODEL = "MODEL";
    public static final String TYPE_DIMENSION = "DIMENSION";
    public static final String TYPE_METRIC = "METRIC";
    public static final String TYPE_TERM = "TERM";
    public static final String TYPE_GAP = "GAP";
    public static final String TYPE_KNOWLEDGE = "KNOWLEDGE";

    public static final String STEP_CREATE_MODEL = "CREATE_MODEL";
    public static final String STEP_CREATE_DIMENSION = "CREATE_DIMENSION";
    public static final String STEP_CREATE_METRIC = "CREATE_METRIC";
    public static final String STEP_CREATE_TERM = "CREATE_TERM";
    public static final String STEP_RELOAD_DICT = "RELOAD_DICT";
    public static final String STEP_RELOAD_EMBEDDING = "RELOAD_EMBEDDING";
    public static final String STEP_UPDATE_GAP = "UPDATE_GAP";
    public static final String STEP_ROLLBACK_TERM = "ROLLBACK_TERM";
    public static final String STEP_ROLLBACK_METRIC = "ROLLBACK_METRIC";
    public static final String STEP_ROLLBACK_DIMENSION = "ROLLBACK_DIMENSION";
    public static final String STEP_ROLLBACK_MODEL = "ROLLBACK_MODEL";

    public static final String ERROR_ACCESS_DENIED = "RELEASE_ACCESS_DENIED";
    public static final String ERROR_INVALID_STATE = "RELEASE_INVALID_STATE";
    public static final String ERROR_NOT_FOUND = "RELEASE_NOT_FOUND";
    public static final String ERROR_STEP_RUNNING = "RELEASE_STEP_RUNNING";
    public static final String ERROR_OBJECT_CONFLICT = "RELEASE_OBJECT_CONFLICT";
    public static final String ERROR_INTERNAL = "RELEASE_INTERNAL_ERROR";

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;
    public static final int ERROR_MESSAGE_MAX_LENGTH = 1000;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** 允许管理员通过发布入口恢复的状态；成功发布后只能走回滚。 */
    public static final Set<String> RELEASABLE_DRAFT_STATUSES =
            Set.of(DRAFT_APPROVED, DRAFT_RELEASE_FAILED, DRAFT_RELEASING);

    private SemanticReleaseConstants() {}
}
