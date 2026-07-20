package com.tencent.supersonic.forecast.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Forecast 控制面、决策库和独立 Worker 的集中配置。
 *
 * <p>
 * 所有可能影响源库压力的参数均有保守默认值和边界校验。Standalone 只加载查询与配置 API， {@code worker.enabled} 默认关闭，避免在线进程意外执行重任务。
 * </p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "forecast")
public class ForecastProperties {

    private boolean enabled = true;

    @Valid
    private Meta meta = new Meta();

    @Valid
    private Worker worker = new Worker();

    @Valid
    private Decision decision = new Decision();

    /** SuperSonic 元数据库版本门禁配置。 */
    @Data
    public static class Meta {
        private boolean validateSchemaOnStartup = true;
    }

    /** Worker 调度、租约和吞吐配置。 */
    @Data
    public static class Worker {
        private boolean enabled = false;
        @Min(1)
        @Max(16)
        private int concurrency = 2;
        @Min(500)
        @Max(20_000)
        private int batchSize = 5_000;
        @Min(1)
        @Max(60)
        private int pollSeconds = 5;
        @Min(5)
        @Max(60)
        private int heartbeatSeconds = 15;
        @Min(30)
        @Max(600)
        private int leaseSeconds = 90;
        @Min(0)
        @Max(10)
        private int maxRetries = 3;
        @Min(1)
        @Max(24)
        private int staleAfterHours = 6;
    }

    /** PostgreSQL 决策库固定 Schema 配置。 */
    @Data
    public static class Decision {
        @NotBlank
        private String schema = "forecast";
        private boolean validateSchemaOnStartup = true;
        private boolean initializeSchema = false;
        @Min(1)
        @Max(100)
        private int queryTimeoutSeconds = 30;
    }
}
