package com.tencent.supersonic.forecast.core.connector;

/**
 * 正式支持 MySQL 的预测 Connector。
 */
public class MySqlForecastConnector extends JdbcForecastConnector {

    /**
     * 创建 MySQL Connector。
     */
    public MySqlForecastConnector() {
        super(new MySqlForecastDialect());
    }
}
