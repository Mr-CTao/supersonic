package com.tencent.supersonic.forecast.api.model;

import java.util.List;

/**
 * 数据源可供映射向导选择的元数据快照。
 *
 * @param catalogs 可见 catalog。
 * @param schemas 可见 schema/database。
 * @param tables 表和视图。
 */
public record ForecastMetadata(List<String> catalogs, List<String> schemas,
        List<TableMetadata> tables) {

    /**
     * 表或视图元数据。
     *
     * @param catalog catalog。
     * @param schema schema/database。
     * @param name 名称。
     * @param type TABLE 或 VIEW。
     * @param columns 列定义。
     */
    public record TableMetadata(String catalog, String schema, String name, String type,
            List<ColumnMetadata> columns) {
    }

    /**
     * 列元数据。
     *
     * @param name 列名。
     * @param jdbcType JDBC 类型。
     * @param typeName 数据库类型名称。
     * @param nullable 是否允许空值。
     */
    public record ColumnMetadata(String name, int jdbcType, String typeName, boolean nullable) {
    }
}
