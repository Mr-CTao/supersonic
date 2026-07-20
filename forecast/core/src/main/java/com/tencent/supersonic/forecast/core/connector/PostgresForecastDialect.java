package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;

/**
 * PostgreSQL 预测读取方言。
 */
public class PostgresForecastDialect implements ForecastSqlDialect {

    /** {@inheritDoc} */
    @Override
    public String databaseType() {
        return "postgresql";
    }

    /** {@inheritDoc} */
    @Override
    public String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** {@inheritDoc} */
    @Override
    public String qualify(RelationTable table) {
        return hasText(table.getSchema()) ? quote(table.getSchema()) + "." + quote(table.getTable())
                : quote(table.getTable());
    }

    /** {@inheritDoc} */
    @Override
    public String castText(String expression) {
        return "CAST(" + expression + " AS TEXT)";
    }

    /** {@inheritDoc} */
    @Override
    public String greatestTimestamp(String left, String right) {
        return "GREATEST(COALESCE(" + left + ", " + right + "), COALESCE(" + right + ", " + left
                + "))";
    }

    /** 判断 schema 是否有值。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
