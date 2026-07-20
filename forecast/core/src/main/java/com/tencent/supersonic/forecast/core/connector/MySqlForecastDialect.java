package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;

/**
 * MySQL 预测读取方言。
 */
public class MySqlForecastDialect implements ForecastSqlDialect {

    /** {@inheritDoc} */
    @Override
    public String databaseType() {
        return "mysql";
    }

    /** {@inheritDoc} */
    @Override
    public String quote(String identifier) {
        return "`" + identifier + "`";
    }

    /** {@inheritDoc} */
    @Override
    public String qualify(RelationTable table) {
        String database = hasText(table.getSchema()) ? table.getSchema() : table.getCatalog();
        return hasText(database) ? quote(database) + "." + quote(table.getTable())
                : quote(table.getTable());
    }

    /** {@inheritDoc} */
    @Override
    public String castText(String expression) {
        return "CAST(" + expression + " AS CHAR)";
    }

    /** {@inheritDoc} */
    @Override
    public String greatestTimestamp(String left, String right) {
        return "GREATEST(COALESCE(" + left + ", " + right + "), COALESCE(" + right + ", " + left
                + "))";
    }

    /** 判断可选限定名是否有值。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
