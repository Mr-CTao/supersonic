package com.tencent.supersonic.forecast.core.connector;

/**
 * 正式支持 SQL Server 的预测 Connector。
 */
public class SqlServerForecastConnector extends JdbcForecastConnector {

    /**
     * 创建 SQL Server Connector。
     */
    public SqlServerForecastConnector() {
        super(new SqlServerForecastDialect());
    }
}
