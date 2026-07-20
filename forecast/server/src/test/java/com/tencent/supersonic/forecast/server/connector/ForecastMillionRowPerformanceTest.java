package com.tencent.supersonic.forecast.server.connector;

import com.tencent.supersonic.forecast.api.enums.ForecastQuantityTransform;
import com.tencent.supersonic.forecast.api.enums.ForecastRelationMode;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ColumnRef;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ValueMapping;
import com.tencent.supersonic.forecast.api.model.ForecastPage;
import com.tencent.supersonic.forecast.api.model.ForecastReadContext;
import com.tencent.supersonic.forecast.core.connector.PostgresForecastConnector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百万行 keyset 分页性能验收。
 *
 * <p>
 * 该用例需要显式设置 {@code RUN_FORECAST_PERFORMANCE_TESTS=true}，避免普通开发测试拉取镜像和 占用较多磁盘。测试始终只保留一个 20,000
 * 行页面，记录端到端读取耗时与吞吐。
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_FORECAST_PERFORMANCE_TESTS", matches = "(?i)true")
class ForecastMillionRowPerformanceTest {

    private static final int TOTAL_ROWS = 1_000_000;
    private static final int PAGE_SIZE = 20_000;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    @DisplayName("一百万行通过固定上界 keyset 分页读取且无整表内存加载")
    void shouldReadOneMillionRowsWithBoundedPages() throws Exception {
        try (PostgreSQLContainer<?> container =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            container.start();
            createFixture(container);
            PostgresForecastConnector connector = new PostgresForecastConnector();
            ForecastMappingConfig mapping = mapping();
            long rows = 0;
            int pages = 0;
            long queryNanos = 0;
            Instant startedAt = Instant.now();

            try (Connection connection = container.createConnection("")) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                ForecastCursor upper = connector.captureUpperBound(connection, mapping, START, END);
                ForecastCursor lower = ForecastCursor.empty();
                while (!upper.isEmpty()) {
                    long queryStarted = System.nanoTime();
                    ForecastPage page = connector.readPage(connection, mapping,
                            new ForecastReadContext(START, END, lower, upper, PAGE_SIZE));
                    queryNanos += System.nanoTime() - queryStarted;
                    assertTrue(page.events().size() <= PAGE_SIZE);
                    rows += page.events().size();
                    pages++;
                    lower = page.nextCursor();
                    if (page.exhausted()) {
                        break;
                    }
                }
                connection.rollback();
            }

            long elapsedMillis =
                    Math.max(1L, Duration.between(startedAt, Instant.now()).toMillis());
            long rowsPerSecond = rows * 1_000L / elapsedMillis;
            System.out.printf(
                    "Forecast million-row benchmark rows=%d pages=%d elapsedMs=%d "
                            + "readPageMs=%d rowsPerSecond=%d%n",
                    rows, pages, elapsedMillis, queryNanos / 1_000_000L, rowsPerSecond);
            assertEquals(TOTAL_ROWS, rows);
            assertEquals(TOTAL_ROWS / PAGE_SIZE, pages);
        }
    }

    /** 使用 PostgreSQL generate_series 在服务端集合式生成一百万行。 */
    private void createFixture(PostgreSQLContainer<?> container) throws Exception {
        try (Connection connection = container.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forecast_large AS SELECT g::text AS id, "
                    + "((g % 100) + 1)::numeric(18,2) AS quantity, "
                    + "TIMESTAMP '2026-01-01 00:00:00' + ((g - 1) % 180) * INTERVAL '1 day' "
                    + "AS occurred_at, TIMESTAMP '2026-01-01 00:00:00' "
                    + "+ g * INTERVAL '1 millisecond' AS updated_at, "
                    + "('WH-' || ((g % 10) + 1))::varchar(64) AS warehouse_code, "
                    + "CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END::varchar(16) "
                    + "AS direction FROM generate_series(1, 1000000) g");
            statement.execute("ALTER TABLE forecast_large ADD PRIMARY KEY (id)");
            statement.execute("CREATE INDEX idx_forecast_large_cursor "
                    + "ON forecast_large(updated_at, id)");
            statement.execute(
                    "CREATE INDEX idx_forecast_large_occurred " + "ON forecast_large(occurred_at)");
        }
    }

    /** 创建百万行表的单关系增量映射。 */
    private ForecastMappingConfig mapping() {
        ForecastMappingConfig config = new ForecastMappingConfig();
        config.setRelationMode(ForecastRelationMode.SINGLE);
        config.setSourceTimeZone("UTC");
        config.setSyncMode(ForecastSyncMode.INCREMENTAL);
        RelationTable table = new RelationTable();
        table.setTable("forecast_large");
        table.setAlias("s");
        config.getSource().setSingle(table);
        config.getFields().setSourceRecordId(column("id"));
        config.getFields().setQuantity(column("quantity"));
        config.getFields().setOccurredAt(column("occurred_at"));
        config.getFields().setSourceUpdatedAt(column("updated_at"));
        config.getFields().setWarehouseCode(column("warehouse_code"));
        config.getFields().setDirection(column("direction"));
        return config;
    }

    /** 创建固定 s 别名列映射。 */
    private ValueMapping column(String name) {
        ColumnRef ref = new ColumnRef();
        ref.setTableAlias("s");
        ref.setColumn(name);
        ValueMapping mapping = new ValueMapping();
        mapping.setSourceType(ForecastValueSource.COLUMN);
        mapping.setColumn(ref);
        mapping.setTransform(ForecastQuantityTransform.NONE);
        return mapping;
    }
}
