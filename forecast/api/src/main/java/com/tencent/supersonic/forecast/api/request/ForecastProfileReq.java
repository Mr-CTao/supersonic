package com.tencent.supersonic.forecast.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建或更新预测 Profile 的请求。
 *
 * <p>
 * Profile 绑定一个客户源数据库和一个 PostgreSQL 决策库；并发更新使用 lockVersion 防止覆盖。
 * </p>
 */
@Data
public class ForecastProfileReq {
    @NotBlank
    @Size(max = 255)
    private String name;
    @NotNull
    @Positive
    private Long sourceDatabaseId;
    @NotNull
    @Positive
    private Long decisionDatabaseId;
    @NotBlank
    @Size(max = 64)
    private String timeZone = "Asia/Shanghai";
    @NotBlank
    @Size(max = 64)
    private String syncCron = "0 0 1 * * *";
    @NotBlank
    @Size(max = 64)
    private String forecastCron = "0 30 2 * * *";
    @NotBlank
    @Size(max = 64)
    private String reconcileCron = "0 30 3 * * SUN";
    @Min(90)
    @Max(730)
    private int historyDays = 180;
    private boolean enabled = true;
    private Integer lockVersion;
}
