package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.spi.ForecastConnector;

import java.util.List;

/**
 * 按数据源类型选择 Connector 的只读注册表。
 *
 * <p>
 * 构造时复制实现列表，运行期间不允许修改，因此线程安全。
 * </p>
 */
public class ForecastConnectorRegistry {

    private final List<ForecastConnector> connectors;

    /**
     * 创建 Connector 注册表。
     *
     * @param connectors 可用实现。
     * @throws IllegalArgumentException 实现列表为空。
     */
    public ForecastConnectorRegistry(List<ForecastConnector> connectors) {
        if (connectors == null || connectors.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 ForecastConnector");
        }
        this.connectors = List.copyOf(connectors);
    }

    /**
     * 查找正式支持的数据源 Connector。
     *
     * @param databaseType SuperSonic 数据源类型。
     * @return 对应 Connector。
     * @throws IllegalArgumentException 类型不在首版支持范围。
     */
    public ForecastConnector require(String databaseType) {
        return connectors.stream().filter(connector -> connector.supports(databaseType)).findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("预测 MVP 暂不支持数据源类型: " + databaseType));
    }
}
