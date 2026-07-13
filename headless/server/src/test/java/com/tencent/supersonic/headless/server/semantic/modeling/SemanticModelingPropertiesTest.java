package com.tencent.supersonic.headless.server.semantic.modeling;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 4 AI 修订超时与数据库租约配置单元测试。
 *
 * <p>
 * 职责说明：验证修订租约始终覆盖首轮与一次修复 Provider 调用，并验证异常大的部署配置被限制在明确 上界内，避免合法调用中途失去租约或毫秒换算溢出。测试不访问数据库和 Provider。
 * </p>
 */
class SemanticModelingPropertiesTest {

    /** 默认 180 秒单轮超时应得到两轮加五分钟余量的 660 秒租约。 */
    @Test
    void shouldCoverTwoDefaultProviderCallsWithSafetyMargin() {
        SemanticModelingProperties properties = new SemanticModelingProperties();

        assertThat(properties.resolveRevisionProviderTimeoutMillis()).isEqualTo(180_000L);
        assertThat(properties.resolveRevisionLeaseMillis()).isEqualTo(660_000L);
    }

    /** 极端配置必须先限制单轮超时，再使用同一受控值计算无溢出租约。 */
    @Test
    void shouldBoundExtremeRevisionTimeoutAndAvoidOverflow() {
        SemanticModelingProperties properties = new SemanticModelingProperties();
        properties.setGenerationTimeoutSeconds(Long.MAX_VALUE);

        assertThat(properties.resolveRevisionProviderTimeoutMillis()).isEqualTo(1_800_000L);
        assertThat(properties.resolveRevisionLeaseMillis()).isEqualTo(3_900_000L);
    }

    /** 非正配置仍必须使用至少一秒的 Provider 超时并保留完整安全余量。 */
    @Test
    void shouldApplyMinimumTimeoutBeforeCalculatingLease() {
        SemanticModelingProperties properties = new SemanticModelingProperties();
        properties.setGenerationTimeoutSeconds(0L);

        assertThat(properties.resolveRevisionProviderTimeoutMillis()).isEqualTo(1_000L);
        assertThat(properties.resolveRevisionLeaseMillis()).isEqualTo(302_000L);
    }
}
