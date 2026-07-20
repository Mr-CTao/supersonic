package com.tencent.supersonic.forecast.server.connector;

import com.tencent.supersonic.forecast.api.enums.ForecastQuantityTransform;
import com.tencent.supersonic.forecast.api.enums.ForecastRelationMode;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ColumnRef;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.JoinDefinition;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ValueMapping;
import com.tencent.supersonic.forecast.api.model.ForecastPage;
import com.tencent.supersonic.forecast.api.model.ForecastReadContext;
import com.tencent.supersonic.forecast.api.spi.ForecastConnector;
import com.tencent.supersonic.forecast.core.connector.MySqlForecastConnector;
import com.tencent.supersonic.forecast.core.connector.PostgresForecastConnector;
import com.tencent.supersonic.forecast.core.connector.SqlServerForecastConnector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三种正式源库方言的真实 JDBC、视图和主从关联集成测试。
 *
 * <p>
 * 测试在没有 Docker 的开发机上自动跳过；CI 有 Docker 时会逐个启动容器，验证固定上界、 复合水位分页和一次等值关联，不读取或写入开发者本地数据库。
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ForecastConnectorTestcontainersTest {

    private static final Instant WINDOW_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant WINDOW_END = WINDOW_START.plus(10, ChronoUnit.DAYS);

    @Test
    @DisplayName("MySQL 单表使用复合水位分页且不重复")
    void shouldReadMySqlSingleTableWithCompositeCursor() throws Exception {
        try (MySQLContainer<?> container =
                new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))) {
            container.start();
            verifySingleSource(container, new MySqlForecastConnector(), "forecast_task");
        }
    }

    @Test
    @DisplayName("PostgreSQL 视图可作为受控单关系来源")
    void shouldReadPostgresView() throws Exception {
        try (PostgreSQLContainer<?> container =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            container.start();
            createSingleFixture(container, true);
            verifyPages(container, new PostgresForecastConnector(),
                    singleMapping("forecast_task_view"));
        }
    }

    @Test
    @DisplayName("SQL Server 支持一次主表明细表等值关联和双更新时间")
    void shouldReadSqlServerHeaderDetailJoin() throws Exception {
        try (MSSQLServerContainer<?> container = new MSSQLServerContainer<>(
                DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                        .acceptLicense()) {
            container.start();
            createJoinFixture(container);
            verifyPages(container, new SqlServerForecastConnector(), headerDetailMapping());
        }
    }

    /** 创建单表夹具并验证两页读取。 */
    private void verifySingleSource(JdbcDatabaseContainer<?> container, ForecastConnector connector,
            String relation) throws Exception {
        createSingleFixture(container, false);
        verifyPages(container, connector, singleMapping(relation));
    }

    /** 验证固定上界、两页 keyset 和标准方向转换。 */
    private void verifyPages(JdbcDatabaseContainer<?> container, ForecastConnector connector,
            ForecastMappingConfig mapping) throws Exception {
        try (Connection connection = container.createConnection("")) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            ForecastCursor upper =
                    connector.captureUpperBound(connection, mapping, WINDOW_START, WINDOW_END);
            ForecastPage first =
                    connector.readPage(connection, mapping, new ForecastReadContext(WINDOW_START,
                            WINDOW_END, ForecastCursor.empty(), upper, 2));
            ForecastPage second =
                    connector.readPage(connection, mapping, new ForecastReadContext(WINDOW_START,
                            WINDOW_END, first.nextCursor(), upper, 2));
            assertEquals(2, first.events().size());
            assertEquals(1, second.events().size());
            assertTrue(first.events().stream().noneMatch(
                    firstEvent -> second.events().stream().anyMatch(secondEvent -> secondEvent
                            .getSourceRecordId().equals(firstEvent.getSourceRecordId()))));
            assertEquals("3", second.events().get(0).getSourceRecordId());
            connection.rollback();
        }
    }

    /** 创建三行单表和可选视图，时间参数全部绑定避免方言字面量差异。 */
    private void createSingleFixture(JdbcDatabaseContainer<?> container, boolean createView)
            throws Exception {
        try (Connection connection = container.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forecast_task (id VARCHAR(64) PRIMARY KEY, "
                    + "quantity DECIMAL(18,2) NOT NULL, occurred_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL, warehouse_code VARCHAR(64) NOT NULL, "
                    + "direction VARCHAR(16) NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO forecast_task "
                    + "(id,quantity,occurred_at,updated_at,warehouse_code,direction) "
                    + "VALUES (?,?,?,?,?,?)")) {
                for (int index = 1; index <= 3; index++) {
                    insert.setString(1, Integer.toString(index));
                    insert.setBigDecimal(2, java.math.BigDecimal.valueOf(index * 10L));
                    insert.setTimestamp(3,
                            Timestamp.from(WINDOW_START.plus(index, ChronoUnit.DAYS)));
                    insert.setTimestamp(4,
                            Timestamp.from(WINDOW_START.plus(index, ChronoUnit.HOURS)));
                    insert.setString(5, "WH-1");
                    insert.setString(6, index % 2 == 0 ? "INBOUND" : "OUTBOUND");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            if (createView) {
                statement.execute("CREATE VIEW forecast_task_view AS SELECT id, quantity, "
                        + "occurred_at, updated_at, warehouse_code, direction FROM forecast_task");
            }
        }
    }

    /** 创建三条主从记录，覆盖主表和明细表更新时间合并。 */
    private void createJoinFixture(JdbcDatabaseContainer<?> container) throws Exception {
        try (Connection connection = container.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forecast_header (task_id VARCHAR(64) PRIMARY KEY, "
                    + "occurred_at DATETIME2 NOT NULL, updated_at DATETIME2 NOT NULL, "
                    + "warehouse_code VARCHAR(64) NOT NULL, direction VARCHAR(16) NOT NULL)");
            statement.execute("CREATE TABLE forecast_detail (line_id VARCHAR(64) PRIMARY KEY, "
                    + "task_id VARCHAR(64) NOT NULL, quantity DECIMAL(18,2) NOT NULL, "
                    + "updated_at DATETIME2 NOT NULL)");
            try (PreparedStatement header =
                    connection.prepareStatement("INSERT INTO forecast_header VALUES (?,?,?,?,?)");
                    PreparedStatement detail = connection
                            .prepareStatement("INSERT INTO forecast_detail VALUES (?,?,?,?)")) {
                for (int index = 1; index <= 3; index++) {
                    String id = Integer.toString(index);
                    header.setString(1, "T" + id);
                    header.setTimestamp(2,
                            Timestamp.from(WINDOW_START.plus(index, ChronoUnit.DAYS)));
                    header.setTimestamp(3,
                            Timestamp.from(WINDOW_START.plus(index, ChronoUnit.HOURS)));
                    header.setString(4, "WH-1");
                    header.setString(5, index % 2 == 0 ? "INBOUND" : "OUTBOUND");
                    header.addBatch();
                    detail.setString(1, id);
                    detail.setString(2, "T" + id);
                    detail.setBigDecimal(3, java.math.BigDecimal.valueOf(index * 10L));
                    detail.setTimestamp(4,
                            Timestamp.from(WINDOW_START.plus(index + 1L, ChronoUnit.HOURS)));
                    detail.addBatch();
                }
                header.executeBatch();
                detail.executeBatch();
            }
        }
    }

    /** 创建单关系增量映射。 */
    private ForecastMappingConfig singleMapping(String tableName) {
        ForecastMappingConfig config = baseMapping();
        config.setRelationMode(ForecastRelationMode.SINGLE);
        RelationTable table = relation(tableName, "s");
        config.getSource().setSingle(table);
        config.getFields().setSourceRecordId(column("s", "id"));
        config.getFields().setQuantity(column("s", "quantity"));
        config.getFields().setOccurredAt(column("s", "occurred_at"));
        config.getFields().setSourceUpdatedAt(column("s", "updated_at"));
        config.getFields().setWarehouseCode(column("s", "warehouse_code"));
        config.getFields().setDirection(column("s", "direction"));
        return config;
    }

    /** 创建一次主从关联映射。 */
    private ForecastMappingConfig headerDetailMapping() {
        ForecastMappingConfig config = baseMapping();
        config.setRelationMode(ForecastRelationMode.HEADER_DETAIL);
        config.getSource().setHeader(relation("forecast_header", "h"));
        config.getSource().setDetail(relation("forecast_detail", "d"));
        JoinDefinition join = new JoinDefinition();
        join.setType("INNER");
        join.setLeft(ref("h", "task_id"));
        join.setRight(ref("d", "task_id"));
        config.getSource().setJoin(join);
        config.getFields().setSourceRecordId(column("d", "line_id"));
        config.getFields().setQuantity(column("d", "quantity"));
        config.getFields().setOccurredAt(column("h", "occurred_at"));
        ValueMapping updatedAt = column("h", "updated_at");
        updatedAt.setSecondaryColumn(ref("d", "updated_at"));
        config.getFields().setSourceUpdatedAt(updatedAt);
        config.getFields().setWarehouseCode(column("h", "warehouse_code"));
        config.getFields().setDirection(column("h", "direction"));
        return config;
    }

    /** 创建各方言共享的增量基础配置。 */
    private ForecastMappingConfig baseMapping() {
        ForecastMappingConfig config = new ForecastMappingConfig();
        config.setSourceTimeZone("UTC");
        config.setSyncMode(ForecastSyncMode.INCREMENTAL);
        return config;
    }

    /** 创建物理关系。 */
    private RelationTable relation(String tableName, String alias) {
        RelationTable table = new RelationTable();
        table.setTable(tableName);
        table.setAlias(alias);
        return table;
    }

    /** 创建列映射。 */
    private ValueMapping column(String alias, String columnName) {
        ValueMapping mapping = new ValueMapping();
        mapping.setSourceType(ForecastValueSource.COLUMN);
        mapping.setColumn(ref(alias, columnName));
        mapping.setTransform(ForecastQuantityTransform.NONE);
        return mapping;
    }

    /** 创建受控列引用。 */
    private ColumnRef ref(String alias, String columnName) {
        ColumnRef ref = new ColumnRef();
        ref.setTableAlias(alias);
        ref.setColumn(columnName);
        return ref;
    }
}
