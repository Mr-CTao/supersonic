package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义资产路由详情响应。
 *
 * <p>
 * 职责：返回面向管理员的动作、原因、候选安全摘要、问题、权限和状态恢复字段。响应不暴露正式资产 ID、原始分数、Prompt、SQL 条件值或 Provider 原文。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetRouteResp {
    private Long id;
    private SemanticAssetRouteStatus status;
    private SemanticAssetRouteAction recommendedAction;
    private String recommendedActionLabel;
    private String explanation;
    private SemanticAssetDecisionSource decisionSource;
    private CandidateSummary primaryCandidate;
    @Builder.Default
    private List<CandidateSummary> alternativeCandidates = new ArrayList<>();
    @Builder.Default
    private List<String> coveredCapabilities = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetCapabilityGap> missingCapabilities = new ArrayList<>();
    @Builder.Default
    private List<String> resultOperations = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetBusinessQuestion> businessQuestions = new ArrayList<>();
    private boolean canConfirm;
    private String confirmDisabledReason;
    @Builder.Default
    private List<SemanticAssetRouteAction> allowedActions = new ArrayList<>();
    @Builder.Default
    private List<String> technicalEvidence = new ArrayList<>();
    private Integer analysisVersion;
    private Integer lockVersion;
    private SemanticAssetRouteAction confirmedAction;
    private String confirmedCandidateHandle;
    @Builder.Default
    private Map<String, Object> businessAnswers = new LinkedHashMap<>();
    private String overrideReason;
    private String confirmedBy;
    private Date confirmedAt;
    private String failureCode;
    private String failureMessage;
    private Date expiresAt;
    private boolean idempotentReplay;

    /**
     * 不含正式 ID 和内部得分的候选摘要。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateSummary {
        private String candidateHandle;
        private String assetType;
        private String name;
        private String bizName;
        private String description;
        @Builder.Default
        private List<String> grain = new ArrayList<>();
        @Builder.Default
        private List<String> coveredCapabilities = new ArrayList<>();
        @Builder.Default
        private List<String> missingCapabilities = new ArrayList<>();
        private String coverageDescription;
        private boolean manageable;
        @Builder.Default
        private List<String> evidenceSources = new ArrayList<>();
    }
}
