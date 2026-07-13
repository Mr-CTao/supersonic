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

    /** 阶段 4 单轮 Provider 最长等待三十分钟，避免错误配置制造无界 HTTP 占用。 */
    private static final long MAX_REVISION_PROVIDER_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    /** 两轮 Provider 调用之外预留五分钟用于上下文读取、校验、锁等待和完成事务。 */
    private static final long REVISION_LEASE_SAFETY_MILLIS = 5L * 60L * 1000L;

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
    /** RUNNING 验证报告租约；查询端使用数据库时间懒恢复，默认十分钟。 */
    private long validationLeaseSeconds = 600;
    /** 静态预览最大安全 OFFSET；超过该值返回性能 WARNING，默认十万行。 */
    private long maxSafePreviewOffset = 100_000L;

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

    /**
     * 解析阶段 4 单轮 AI 修订的 Provider 超时。
     *
     * <p>
     * 修订最多会执行首轮和一次结构修复。这里把异常大的部署配置约束为三十分钟，既保留长模型调用 空间，也让对应数据库租约可以有明确上界；阶段 3 生成任务仍沿用原始总超时语义。
     * </p>
     *
     * @return 1 秒至 30 分钟之间的单轮超时毫秒数。
     */
    public long resolveRevisionProviderTimeoutMillis() {
        return Math.min(resolveGenerationTimeoutMillis(), MAX_REVISION_PROVIDER_TIMEOUT_MILLIS);
    }

    /**
     * 解析阶段 4 AI 修订租约时长。
     *
     * @return 两轮 Provider 超时之和再加五分钟安全余量；因单轮已设上界，不存在乘法溢出。
     */
    public long resolveRevisionLeaseMillis() {
        return resolveRevisionProviderTimeoutMillis() * 2L + REVISION_LEASE_SAFETY_MILLIS;
    }

    /**
     * 解析验证租约毫秒数。
     *
     * @return 至少一秒的验证租约；溢出时饱和为 Long 最大值。
     */
    public long resolveValidationLeaseMillis() {
        try {
            return Math.multiplyExact(Math.max(1L, validationLeaseSeconds), 1000L);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 解析静态预览最大安全 OFFSET。
     *
     * @return 非负阈值；负数错误配置收紧为零，避免静态门禁被意外关闭。
     */
    public long resolveMaxSafePreviewOffset() {
        return Math.max(0L, maxSafePreviewOffset);
    }
}
