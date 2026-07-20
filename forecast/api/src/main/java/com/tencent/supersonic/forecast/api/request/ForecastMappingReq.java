package com.tencent.supersonic.forecast.api.request;

import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存映射草稿的请求。
 */
@Data
public class ForecastMappingReq {
    @Valid
    @NotNull
    private ForecastMappingConfig config;
}
