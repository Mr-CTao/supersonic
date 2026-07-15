package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端策略裁决结果。
 *
 * <p>
 * 职责：汇总最终动作、主候选、解释和可审计证据，是写入路由快照的唯一决策对象。实例只读使用， 不作为单例共享状态。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetRoutingDecision {
    private SemanticAssetRouteAction action;
    private String candidateHandle;
    private SemanticAssetDecisionSource decisionSource;
    private String explanation;
    @Builder.Default
    private List<String> coveredCapabilities = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetCapabilityGap> missingCapabilities = new ArrayList<>();
    @Builder.Default
    private List<String> resultOperations = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetBusinessQuestion> businessQuestions = new ArrayList<>();
    @Builder.Default
    private List<String> technicalEvidence = new ArrayList<>();
}
