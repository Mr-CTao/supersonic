package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 失败草稿人工重新生成请求。
 *
 * <p>
 * 职责说明：只允许调用方调整 JSON-capable 模型和是否使用脱敏样例；业务目标、主题域、数据源和
 * 选表始终从既有草稿主记录恢复，防止“重试”悄然改变业务范围。并发说明：{@code lockVersion} 与请求头幂等键共同保护重复点击和并发覆盖。
 * </p>
 */
@Data
public class ModelingDraftRegenerateReq {

    @NotNull
    @PositiveOrZero
    private Integer lockVersion;

    @NotNull
    @JsonAlias("providerId")
    private Integer chatModelId;

    @NotNull
    @JsonAlias("includeSample")
    private Boolean includeSampleData;
}
