package com.tencent.supersonic.forecast.core.connector;

/**
 * 正式支持 PostgreSQL 的预测 Connector。
 */
public class PostgresForecastConnector extends JdbcForecastConnector {

    /**
     * 创建 PostgreSQL Connector。
     */
    public PostgresForecastConnector() {
        super(new PostgresForecastDialect());
    }
}
