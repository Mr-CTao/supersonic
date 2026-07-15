package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 传给可插拔路由建议 Provider 的脱敏请求。
 *
 * <p>
 * 职责：仅包含业务目标、查询层操作和有界候选 handle 摘要，不包含正式资产 ID、样例行、SQL、Token 或未授权资产。Provider 实现不得修改传入集合。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetRoutingAdvisorRequest {
    private String businessGoal;
    @Builder.Default
    private List<String> requestedCapabilities = new ArrayList<>();
    @Builder.Default
    private List<String> resultOperations = new ArrayList<>();
    @Builder.Default
    private List<AdvisorCandidate> candidates = new ArrayList<>();

    /**
     * Provider 可见的候选摘要，只使用服务端 handle。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdvisorCandidate {
        private String candidateHandle;
        private String name;
        private String bizName;
        private String description;
        @Builder.Default
        private List<String> grain = new ArrayList<>();
        @Builder.Default
        private List<String> capabilities = new ArrayList<>();
    }
}
