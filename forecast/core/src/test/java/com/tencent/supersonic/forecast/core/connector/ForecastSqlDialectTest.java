package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MySQL、PostgreSQL、SQL Server 的引用、限定名、文本键和时间合并方言。
 */
class ForecastSqlDialectTest {

    @Test
    @DisplayName("MySQL 使用反引号和 CHAR 复合水位键")
    void shouldRenderMySqlDialect() {
        ForecastSqlDialect dialect = new MySqlForecastDialect();

        assertEquals("`wms`.`task`", dialect.qualify(table("catalog", "wms", "task")));
        assertEquals("CAST(`id` AS CHAR)", dialect.castText("`id`"));
        assertTrue(dialect.greatestTimestamp("a", "b").startsWith("GREATEST"));
    }

    @Test
    @DisplayName("PostgreSQL 使用双引号和 TEXT 复合水位键")
    void shouldRenderPostgresDialect() {
        ForecastSqlDialect dialect = new PostgresForecastDialect();

        assertEquals("\"public\".\"task\"", dialect.qualify(table(null, "public", "task")));
        assertEquals("CAST(\"id\" AS TEXT)", dialect.castText("\"id\""));
    }

    @Test
    @DisplayName("SQL Server 使用三段方括号名称且兼容旧版 CASE 时间合并")
    void shouldRenderSqlServerDialect() {
        ForecastSqlDialect dialect = new SqlServerForecastDialect();

        assertEquals("[wmsdb].[dbo].[task]", dialect.qualify(table("wmsdb", "dbo", "task")));
        assertEquals("CAST([id] AS NVARCHAR(4000))", dialect.castText("[id]"));
        assertTrue(dialect.greatestTimestamp("a", "b").startsWith("CASE WHEN"));
    }

    /** 创建方言限定名测试关系。 */
    private RelationTable table(String catalog, String schema, String name) {
        RelationTable table = new RelationTable();
        table.setCatalog(catalog);
        table.setSchema(schema);
        table.setTable(name);
        table.setAlias("s");
        return table;
    }
}
