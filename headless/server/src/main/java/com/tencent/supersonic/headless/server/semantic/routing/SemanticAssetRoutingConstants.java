package com.tencent.supersonic.headless.server.semantic.routing;

/**
 * 语义资产路由配置基线常量。
 *
 * <p>
 * 职责：集中保存第一版可解释评分、数量、租约和文本边界，避免算法散落魔法数字。阈值后续可迁移到 配置中心；常量均不可变，线程安全。
 * </p>
 */
public final class SemanticAssetRoutingConstants {
    public static final int MAX_CANDIDATES = 8;
    /** 进入维度、指标和模型详情批量查询前的候选池硬上限。 */
    public static final int MAX_CANDIDATE_RECALL_POOL = 64;
    public static final int MAX_JSON_CHARACTERS = 64_000;
    public static final int MAX_ADVISOR_OUTPUT_CHARACTERS = 32_000;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    public static final int CLOSE_CANDIDATE_SCORE_GAP = 10;
    public static final int MAX_EXTENSION_MISSING_CAPABILITIES = 3;
    public static final int MIN_EXTENSION_SCORE = 60;
    public static final int ANALYSIS_LEASE_SECONDS = 120;
    public static final int ROUTE_EXPIRATION_HOURS = 24;
    public static final int ADVISOR_MAX_OUTPUT_TOKENS = 4_096;
    public static final long ADVISOR_TIMEOUT_MILLIS = 60_000L;
    public static final double ADVISOR_TEMPERATURE = 0.1D;
    public static final String ASSET_TYPE_MODEL = "MODEL";
    public static final String SOURCE_SEMANTIC_GAP = "SEMANTIC_GAP";
    public static final String SOURCE_DATA_SOURCE = "DATA_SOURCE";

    private SemanticAssetRoutingConstants() {}
}
