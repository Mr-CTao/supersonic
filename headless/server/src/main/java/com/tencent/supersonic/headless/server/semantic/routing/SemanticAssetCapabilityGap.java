package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缺失语义能力说明。
 *
 * <p>
 * 职责：以结构化、非 SQL 形式解释候选尚缺的字段、指标、关系或业务能力。对象按请求创建，不进入 共享缓存。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetCapabilityGap {
    private String type;
    private String name;
    private String reason;
}
