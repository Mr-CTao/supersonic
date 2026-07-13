package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 追加式草稿恢复结果。
 *
 * <p>
 * 职责说明：返回新版本和新锁版本；历史版本本身不会被修改，也不会触碰正式语义对象。
 * </p>
 */
@Data
@Builder
public class ModelingDraftRestoreResp {

    private Long draftId;
    private Integer targetVersionNo;
    private Integer baseVersionNo;
    private Integer newVersionNo;
    private Integer lockVersion;
    private JsonNode currentDraft;
    private boolean idempotentReplay;
}
