package com.tencent.supersonic.headless.server.semantic.routing;

/**
 * 语义资产路由分析状态。
 *
 * <p>
 * 职责：描述异步分析生命周期；状态只由服务端短事务推进，调用方不得直接写入。枚举不可变且 不包含共享状态。
 * </p>
 */
public enum SemanticAssetRouteStatus {
    PENDING, ANALYZING, SUCCEEDED, FAILED, EXPIRED
}
