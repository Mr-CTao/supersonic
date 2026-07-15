package com.tencent.supersonic.headless.server.semantic.routing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认或覆盖语义资产路由请求。
 *
 * <p>
 * 职责：携带客户端读取到的分析版本、服务端动作枚举、候选 handle、结构化业务答案及覆盖原因。 正式资产 ID 和版本不得由客户端提交。DTO 仅在单次请求内使用。
 * </p>
 */
@Data
public class SemanticAssetRouteConfirmReq {
    @NotNull
    private Integer analysisVersion;
    @NotNull
    private SemanticAssetRouteAction action;
    @Size(max = 64)
    private String candidateHandle;
    @Size(max = 50)
    private Map<@NotBlank @Size(max = 64) String, Object> businessAnswers =
            new LinkedHashMap<>();
    @Size(max = 1000)
    private String overrideReason;
}
