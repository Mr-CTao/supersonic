package com.tencent.supersonic.headless.server.semantic.modeling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 多轮修订语义建模草稿请求。
 *
 * <p>
 * 职责说明：携带管理员本轮自然语言修订指令及其读取到的基础版本号。应用层必须把完整当前草稿和该指令交给现有 LLM Gateway，并在保存前再次校验基础版本。
 * 并发说明：{@code baseVersionNo} 是跨实例乐观并发条件；请求头幂等键由 Controller 和业务服务另行处理。
 * </p>
 */
@Data
public class ModelingDraftAiReviseReq {

    /** 管理员本轮修订指令，不得包含密码、Token 或未脱敏样例数据。 */
    @NotBlank
    @Size(max = 2000)
    private String instruction;

    /** 本轮修订所基于的不可变草稿版本号。 */
    @NotNull
    @Min(1)
    private Integer baseVersionNo;
}
