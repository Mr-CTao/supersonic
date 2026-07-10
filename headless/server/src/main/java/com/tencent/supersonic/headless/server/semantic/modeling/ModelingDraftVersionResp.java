package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * AI 语义建模草稿版本只读响应。
 *
 * <p>
 * 职责说明：列表场景可只返回摘要，按版本加载时返回不可变 JSON 快照。并发说明：版本数据创建后 不再修改，因此无需额外锁。
 * </p>
 */
@Data
@Builder
public class ModelingDraftVersionResp {
    private Long id;
    private Long draftId;
    private Integer versionNo;
    private String changeSource;
    private String changeSummary;
    private String createdBy;
    private Date createdAt;
    private JsonNode snapshot;
    private String draftJson;
}
