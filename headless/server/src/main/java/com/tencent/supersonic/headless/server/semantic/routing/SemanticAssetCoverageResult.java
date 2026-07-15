package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性候选覆盖分析结果。
 *
 * <p>
 * 职责：保存请求能力、查询层操作、候选排序及业务风险，供策略引擎裁决；分数仅在服务端使用， 不由前端解释。实例按分析创建，不共享可变集合。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetCoverageResult {
    @Builder.Default
    private List<CandidateCoverage> candidateCoverages = new ArrayList<>();
    @Builder.Default
    private List<String> requestedCapabilities = new ArrayList<>();
    @Builder.Default
    private List<String> resultOperations = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetBusinessQuestion> businessQuestions = new ArrayList<>();
    private boolean businessBoundaryClear;
    private boolean closeCandidates;

    /**
     * 返回最高优先级候选覆盖证据。
     *
     * @return 主候选；无候选时返回 null。
     */
    public CandidateCoverage primaryCandidate() {
        return candidateCoverages == null || candidateCoverages.isEmpty() ? null
                : candidateCoverages.get(0);
    }

    /**
     * 单个候选的确定性覆盖证据。
     *
     * <p>
     * score 只用于服务端候选差距计算，不作为 LLM 自报置信度。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateCoverage {
        private SemanticAssetCandidate candidate;
        private int score;
        private boolean completeCoverage;
        private boolean subjectCompatible;
        private boolean grainCompatible;
        @Builder.Default
        private List<String> coveredCapabilities = new ArrayList<>();
        @Builder.Default
        private List<String> missingCapabilities = new ArrayList<>();
        @Builder.Default
        private List<String> technicalEvidence = new ArrayList<>();
    }
}
