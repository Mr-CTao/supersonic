package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 受限 LLM 路由建议。
 *
 * <p>
 * 职责：仅表达业务意图、候选 handle、覆盖与缺口解释；不包含正式资产 ID、SQL 或可执行变更。 建议必须经过 Advisor 结构校验和 Policy 强规则裁决后才可持久化为推荐动作。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetRoutingAdvice {
    private SemanticAssetRouteAction recommendedAction;
    private String candidateHandle;
    private Intent intent;
    @Builder.Default
    private List<String> coveredCapabilities = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetCapabilityGap> missingCapabilities = new ArrayList<>();
    @Builder.Default
    private List<SemanticAssetBusinessQuestion> businessQuestions = new ArrayList<>();
    private String explanation;

    /**
     * LLM 解析出的非执行型业务意图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Intent {
        private String subject;
        @Builder.Default
        private List<String> grain = new ArrayList<>();
        @Builder.Default
        private List<String> dimensions = new ArrayList<>();
        @Builder.Default
        private List<String> measures = new ArrayList<>();
        @Builder.Default
        private List<String> resultOperations = new ArrayList<>();
    }
}
