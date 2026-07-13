package com.tencent.supersonic.headless.server.semantic.modeling;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 执行指定草稿版本验证的请求。
 *
 * <p>
 * 职责说明：显式绑定待验证的不可变版本和受限验证选项，避免验证运行期间草稿更新导致报告错绑。业务服务必须再次确认版本属于路径中的草稿。
 * 并发说明：同一草稿的运行中验证互斥由验证报告表唯一键保证，前端 loading 不能替代后端约束。
 * </p>
 */
@Data
public class ModelingDraftValidationReq {

    /** 待验证的草稿版本号。 */
    @NotNull
    @Min(1)
    private Integer versionNo;

    /** 可选验证参数；为空时业务层使用安全默认值。 */
    @Valid
    private ModelingValidationOptions validationOptions = new ModelingValidationOptions();
}
