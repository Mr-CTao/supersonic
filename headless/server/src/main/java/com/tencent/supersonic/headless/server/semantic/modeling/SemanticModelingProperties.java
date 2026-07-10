package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 语义建模草稿运行参数。
 *
 * <p>
 * 职责说明：集中声明上下文、采样、生成超时和有界执行器限制。默认值即阶段 3 的安全边界；部署方 可以在配置文件中收紧但不应绕过业务层硬校验。该 Bean 在启动完成后按只读配置使用。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "s2.semantic-modeling")
public class SemanticModelingProperties {

    private int maxTables = 10;
    private int maxColumns = 300;
    private int maxContextCharacters = 100_000;
    private int sampleRowsPerTable = 3;
    private int sampleTimeoutSeconds = 5;
    private int repairAttempts = 1;
    /** 首次生成和修复轮共用的单次最大输出 token；上下文预算会预留两倍该值。 */
    private int maxOutputTokens = 12_000;
    /** 人工重新生成上限，不包含首次自动生成。 */
    private int maxManualRegenerations = 3;
    private long generationTimeoutSeconds = 180;
    private int executorCorePoolSize = 2;
    private int executorMaxPoolSize = 4;
    private int executorQueueCapacity = 100;

    /**
     * 解析一次 attempt 的总超时毫秒数。
     *
     * @return 至少 1 秒的安全毫秒值；极端大配置饱和为 {@link Long#MAX_VALUE}。
     */
    public long resolveGenerationTimeoutMillis() {
        try {
            return Math.multiplyExact(Math.max(1L, generationTimeoutSeconds), 1000L);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
