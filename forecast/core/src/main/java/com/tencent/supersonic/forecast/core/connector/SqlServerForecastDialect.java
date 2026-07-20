package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 预测读取方言。
 */
public class SqlServerForecastDialect implements ForecastSqlDialect {

    /** {@inheritDoc} */
    @Override
    public String databaseType() {
        return "sqlserver";
    }

    /** {@inheritDoc} */
    @Override
    public String quote(String identifier) {
        return "[" + identifier + "]";
    }

    /** {@inheritDoc} */
    @Override
    public String qualify(RelationTable table) {
        List<String> parts = new ArrayList<>();
        if (hasText(table.getCatalog())) {
            parts.add(quote(table.getCatalog()));
        }
        if (hasText(table.getSchema())) {
            parts.add(quote(table.getSchema()));
        }
        parts.add(quote(table.getTable()));
        return String.join(".", parts);
    }

    /** {@inheritDoc} */
    @Override
    public String castText(String expression) {
        return "CAST(" + expression + " AS NVARCHAR(4000))";
    }

    /** {@inheritDoc} */
    @Override
    public String greatestTimestamp(String left, String right) {
        // 不依赖 SQL Server 2022 的 GREATEST，保证旧客户版本也可运行。
        return "CASE WHEN " + left + " IS NULL THEN " + right + " WHEN " + right + " IS NULL THEN "
                + left + " WHEN " + left + " >= " + right + " THEN " + left + " ELSE " + right
                + " END";
    }

    /** 判断可选限定名是否有值。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
