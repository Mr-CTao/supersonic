package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 多轮修订草稿响应。
 *
 * <p>
 * 职责说明：返回修订后的完整结构化草稿、新版本、乐观锁版本、差异摘要及仍待管理员确认的不确定项。响应不包含 Prompt、样例原值、Provider 原始输出或密钥。 并发说明：响应中的
 * {@code lockVersion} 是后续人工保存的基线，客户端不得自行递增。
 * </p>
 */
@Data
@Builder
public class ModelingDraftAiReviseResp {

    /** 被修订的逻辑草稿 ID。 */
    private Long draftId;

    /** 本轮请求所基于的版本号。 */
    private Integer baseVersionNo;

    /** AI 修订成功后创建的不可变版本号。 */
    private Integer newVersionNo;

    /** 主记录更新后的乐观锁版本。 */
    private Integer lockVersion;

    /** 已通过结构和字段校验的完整草稿 JSON。 */
    private JsonNode draftJson;

    /** 面向管理员的脱敏变更摘要。 */
    private String changeSummary;

    /** 基础版本与新版本之间的结构化差异。 */
    private List<ModelingDraftDiffItem> changes;

    /** 新版本仍需管理员处理的不确定项。 */
    private List<ModelingDraftPayload.UncertaintyDraft> uncertaintyItems;

    /** 是否由相同幂等键安全重放既有版本。 */
    private Boolean idempotentReplay;
}
