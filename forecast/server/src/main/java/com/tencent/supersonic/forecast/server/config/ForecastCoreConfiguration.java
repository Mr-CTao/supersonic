package com.tencent.supersonic.forecast.server.config;

import com.tencent.supersonic.forecast.api.spi.ForecastConnector;
import com.tencent.supersonic.forecast.core.algorithm.ForecastModelSelector;
import com.tencent.supersonic.forecast.core.connector.ForecastConnectorRegistry;
import com.tencent.supersonic.forecast.core.connector.MySqlForecastConnector;
import com.tencent.supersonic.forecast.core.connector.PostgresForecastConnector;
import com.tencent.supersonic.forecast.core.connector.SqlServerForecastConnector;
import com.tencent.supersonic.forecast.core.mapping.ForecastMappingValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Forecast 无状态核心组件的 Spring 装配入口。
 *
 * <p>
 * 这些 Bean 均不保存任务级可变状态，可由 Standalone API 和 Worker 线程安全共享。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(ForecastProperties.class)
public class ForecastCoreConfiguration {

    /**
     * 注册首版三种源数据库 Connector。
     *
     * @return 不可变 Connector 注册表。
     */
    @Bean
    @ConditionalOnMissingBean
    public ForecastConnectorRegistry forecastConnectorRegistry() {
        List<ForecastConnector> connectors = List.of(new MySqlForecastConnector(),
                new PostgresForecastConnector(), new SqlServerForecastConnector());
        return new ForecastConnectorRegistry(connectors);
    }

    /**
     * 创建白名单映射校验器。
     *
     * @return 无状态校验器。
     */
    @Bean
    @ConditionalOnMissingBean
    public ForecastMappingValidator forecastMappingValidator() {
        return new ForecastMappingValidator();
    }

    /**
     * 创建固定候选集的滚动回测选模器。
     *
     * @return 线程安全选模器。
     */
    @Bean
    @ConditionalOnMissingBean
    public ForecastModelSelector forecastModelSelector() {
        return new ForecastModelSelector();
    }
}
