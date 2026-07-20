package com.tencent.supersonic.forecast.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建或更新 Profile 下数据流的请求。
 */
@Data
public class ForecastStreamReq {
    @NotBlank
    @Size(max = 255)
    private String name;
    private boolean enabled = true;
    private Integer lockVersion;
}
