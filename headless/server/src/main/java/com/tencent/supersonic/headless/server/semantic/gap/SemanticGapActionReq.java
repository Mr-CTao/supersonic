package com.tencent.supersonic.headless.server.semantic.gap;

import lombok.Data;

/**
 * 语义缺口状态操作请求。
 *
 * <p>职责说明：承载忽略、重新打开等管理动作的附加说明。并发说明：请求对象按 HTTP 调用单次消费，不需要线程保护。</p>
 */
@Data
public class SemanticGapActionReq {

    private String reason;
}
