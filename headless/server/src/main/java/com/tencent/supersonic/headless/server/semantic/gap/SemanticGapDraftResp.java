package com.tencent.supersonic.headless.server.semantic.gap;

import lombok.Builder;
import lombok.Data;

/**
 * 从语义缺口发起 AI 建模草稿的阶段 2 占位响应。
 *
 * <p>职责说明：在阶段 2 明确返回“草稿能力未启用”，让前端可以保留入口但不调用 LLM、不创建草稿、不发布语义资产。并发说明：
 * 响应对象不可变构建后只读返回。</p>
 */
@Data
@Builder
public class SemanticGapDraftResp {

    private Long gapId;

    private Boolean enabled;

    private String message;
}
