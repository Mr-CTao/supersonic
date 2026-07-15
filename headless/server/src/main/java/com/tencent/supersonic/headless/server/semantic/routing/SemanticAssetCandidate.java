package com.tencent.supersonic.headless.server.semantic.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端内部语义资产候选快照。
 *
 * <p>
 * 职责：保存已通过 ACL 的模型事实、版本、粒度、能力和证据。正式资产 ID 仅持久化并用于服务端 复核，API 会映射为不含 ID 的安全摘要。对象在单次分析内构造，不跨线程修改。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetCandidate {
    private String candidateHandle;
    private String assetType;
    private Long assetId;
    private Long assetVersion;
    private String name;
    private String bizName;
    private String description;
    private Long domainId;
    private Long dataSourceId;
    @Builder.Default
    private List<String> baseTables = new ArrayList<>();
    @Builder.Default
    private List<String> grain = new ArrayList<>();
    @Builder.Default
    private List<String> dimensionCapabilities = new ArrayList<>();
    @Builder.Default
    private List<String> metricCapabilities = new ArrayList<>();
    @Builder.Default
    private List<String> timeCapabilities = new ArrayList<>();
    private boolean manageable;
    @Builder.Default
    private List<String> evidenceSources = new ArrayList<>();
    private int tracePriority;
}
