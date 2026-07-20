package com.tencent.supersonic.forecast.server.repository;

import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastCanonicalStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.model.ForecastCanonicalEvent;
import com.tencent.supersonic.forecast.api.model.ForecastModelSelection;
import com.tencent.supersonic.forecast.api.model.ForecastPoint;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.IngestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 决策库真实事务、幂等重放、聚合修正和预测发布指针集成测试。
 *
 * <p>
 * 该测试模拟“决策库已提交但调用方尚未推进元库水位”后的同批次重放，并验证候选预测在 publication 指针切换前不可见。没有 Docker 时由 Testcontainers
 * 条件自动跳过。
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ForecastDecisionRepositoryTestcontainersTest {

    private static final Long PROFILE_ID = 10L;
    private static final Long STREAM_ID = 20L;
    private static final Long MAPPING_ID = 30L;
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T08:00:00Z");

    @Test
    @DisplayName("批次重放不重复事实且更新旧聚合键，预测按 Profile 原子发布")
    void shouldReplayAndPublishAtomically() throws Exception {
        try (PostgreSQLContainer<?> container =
                new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            container.start();
            migrate(container);
            ForecastDecisionRepository repository = repository();

            try (Connection connection = writable(container)) {
                repository.validateSchema(connection);
                IngestResult first = repository.ingestPage(connection, "batch-1", 100L, PROFILE_ID,
                        STREAM_ID, MAPPING_ID, ZoneId.of("UTC"),
                        List.of(event("WH-1", new BigDecimal("10"), OCCURRED_AT)));
                assertFalse(first.replayed());
            }

            // 模拟决策库提交后进程退出：相同确定性 batchId 重放必须直接复用成功批次。
            try (Connection connection = writable(container)) {
                IngestResult replay = repository.ingestPage(connection, "batch-1", 100L, PROFILE_ID,
                        STREAM_ID, MAPPING_ID, ZoneId.of("UTC"),
                        List.of(event("WH-1", new BigDecimal("10"), OCCURRED_AT)));
                assertTrue(replay.replayed());
                assertEquals(1L,
                        scalarLong(connection, "SELECT COUNT(*) FROM forecast.flow_event"));
            }

            // 同一源记录移动仓库且数量变化时，旧 WH-1 聚合必须删除，新 WH-2 聚合必须重算。
            try (Connection connection = writable(container)) {
                repository.ingestPage(connection, "batch-2", 101L, PROFILE_ID, STREAM_ID,
                        MAPPING_ID, ZoneId.of("UTC"),
                        List.of(event("WH-2", new BigDecimal("20"), OCCURRED_AT.plusSeconds(60))));
                assertEquals(0L, scalarLong(connection,
                        "SELECT COUNT(*) FROM forecast.daily_fact WHERE warehouse_code='WH-1'"));
                assertEquals(new BigDecimal("20.00000000"), scalarDecimal(connection,
                        "SELECT quantity_sum FROM forecast.daily_fact WHERE warehouse_code='WH-2'"));
            }

            saveAndPublish(repository, container, 200L, new BigDecimal("11"));
            assertEquals(new BigDecimal("11.00000000"), latestForecast(container));

            // 新任务模型逐序列提交后，在 publication 切换前仍只能看到旧任务的完整结果。
            saveOnly(repository, container, 201L, new BigDecimal("22"));
            assertEquals(new BigDecimal("11.00000000"), latestForecast(container));
            try (Connection connection = writable(container)) {
                repository.publishForecastJob(connection, PROFILE_ID, 201L);
            }
            assertEquals(new BigDecimal("22.00000000"), latestForecast(container));

            try (Connection connection = writable(container)) {
                assertFalse(repository.prepareForecastJob(connection, PROFILE_ID, 201L));
            }
        }
    }

    /** 应用决策库 V1/V2 正向脚本。 */
    private void migrate(PostgreSQLContainer<?> container) throws Exception {
        try (Connection connection = writable(container)) {
            executeScript(connection, "db/decision-postgres/V1__forecast_decision.sql");
            executeScript(connection, "db/decision-postgres/V2__forecast_publication.sql");
            connection.commit();
        }
    }

    /** 创建使用默认 forecast Schema 的仓储。 */
    private ForecastDecisionRepository repository() {
        ForecastProperties properties = new ForecastProperties();
        properties.getDecision().setSchema("forecast");
        return new ForecastDecisionRepository(properties);
    }

    /** 创建标准完成事件；第二次调用通过更晚更新时间覆盖第一版。 */
    private ForecastCanonicalEvent event(String warehouse, BigDecimal quantity, Instant updatedAt) {
        return ForecastCanonicalEvent.builder().sourceRecordId("SRC-1").taskId("TASK-1")
                .direction(ForecastDirection.OUTBOUND).warehouseCode(warehouse).quantity(quantity)
                .occurredAt(OCCURRED_AT).sourceStatus("DONE")
                .canonicalStatus(ForecastCanonicalStatus.COMPLETED).sourceUpdatedAt(updatedAt)
                .deleted(false).build();
    }

    /** 保存单点模型并切换发布指针。 */
    private void saveAndPublish(ForecastDecisionRepository repository,
            PostgreSQLContainer<?> container, Long jobId, BigDecimal value) throws Exception {
        saveOnly(repository, container, jobId, value);
        try (Connection connection = writable(container)) {
            repository.publishForecastJob(connection, PROFILE_ID, jobId);
        }
    }

    /** 保存未发布模型，模拟多序列任务执行中的候选结果。 */
    private void saveOnly(ForecastDecisionRepository repository, PostgreSQLContainer<?> container,
            Long jobId, BigDecimal value) throws Exception {
        ForecastModelSelection selection = new ForecastModelSelection(
                ForecastAlgorithmType.MOVING_AVERAGE_7, ForecastDataStatus.READY,
                List.of(new ForecastPoint(1, value, value.subtract(BigDecimal.ONE),
                        value.add(BigDecimal.ONE))),
                new BigDecimal("0.1"), BigDecimal.ONE, BigDecimal.ZERO, 30, 7);
        try (Connection connection = writable(container)) {
            repository.saveForecast(connection, jobId, PROFILE_ID, "WH-2",
                    ForecastDirection.OUTBOUND, ForecastMetric.QUANTITY, LocalDate.of(2025, 12, 1),
                    LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 1), selection);
        }
    }

    /** 查询当前发布视图的唯一预测值。 */
    private BigDecimal latestForecast(PostgreSQLContainer<?> container) throws Exception {
        try (Connection connection = writable(container)) {
            return scalarDecimal(connection,
                    "SELECT forecast_value FROM forecast.v_forecast_latest_result");
        }
    }

    /** 打开手工事务连接。 */
    private Connection writable(PostgreSQLContainer<?> container) throws Exception {
        Connection connection = container.createConnection("");
        connection.setAutoCommit(false);
        return connection;
    }

    /** 逐语句执行仓库内固定 PostgreSQL 迁移；脚本不包含过程块或动态 SQL。 */
    private void executeScript(Connection connection, String resource) throws Exception {
        String sql = readResource(resource);
        try (Statement statement = connection.createStatement()) {
            for (String fragment : sql.split(";")) {
                if (!fragment.isBlank()) {
                    statement.execute(fragment);
                }
            }
        }
    }

    /** 读取 UTF-8 classpath 资源。 */
    private String readResource(String resource) throws IOException {
        try (InputStream input =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("测试迁移资源不存在: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 执行测试内固定单值长整型查询。 */
    private long scalarLong(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /** 执行测试内固定单值数值查询。 */
    private BigDecimal scalarDecimal(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getBigDecimal(1);
        }
    }
}
