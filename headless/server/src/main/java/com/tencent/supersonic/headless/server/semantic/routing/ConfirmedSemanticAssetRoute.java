package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已确认且可被单个建模草稿消费的路由快照。
 *
 * <p>
 * 职责：把持久化路由中的来源上下文、确认动作和目标资产版本传递给草稿应用服务。正式资产 ID 只保留在服务端字段中；{@code promptContext} 只包含
 * candidateHandle、可展示名称和能力摘要，避免把 内部 ID 交给 LLM。实例构造后按请求局部使用，不进入跨线程共享缓存。
 * </p>
 */
@Value
@Builder
public class ConfirmedSemanticAssetRoute {

    Long routeAnalysisId;
    String sourceType;
    Long sourceId;
    String businessGoal;
    Long domainId;
    Long dataSourceId;
    String catalogName;
    String databaseName;
    @Builder.Default
    List<String> selectedTables = new ArrayList<>();
    Integer chatModelId;
    Boolean includeSampleData;
    SemanticAssetRouteAction action;
    String targetAssetType;
    Long targetAssetId;
    Long targetAssetVersion;
    @Builder.Default
    Map<String, Object> promptContext = new LinkedHashMap<>();
}
