package com.tencent.supersonic.headless.api.pojo.response;

/**
 * 缓存或知识索引刷新状态。
 *
 * <p>
 * 该状态只描述本节点已观测到的刷新过程；多实例部署时需由外部事件总线保证全局传播。
 */
public enum RefreshStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN
}
