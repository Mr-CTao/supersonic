package com.tencent.supersonic.headless.server.semantic.modeling;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 追加式恢复草稿历史版本请求。
 *
 * <p>
 * 职责说明：同时携带客户端确认的当前版本号和乐观锁版本，防止恢复覆盖并发保存、修订或其他恢复。
 * </p>
 */
@Data
public class ModelingDraftRestoreReq {

    @NotNull
    private Integer currentVersionNo;

    @NotNull
    private Integer lockVersion;
}
