package com.tencent.supersonic.headless.server.semantic.modeling;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 语义建模草稿 PostgreSQL 迁移与并发保护集成测试。
 *
 * <p>
 * 职责：在显式启用时连接真实 PostgreSQL，验证 attempt 回填、唯一索引、默认值和旧 Worker 条件更新。 所有测试写入都位于单一 JDBC
 * 事务并最终回滚；正式模型、维度、指标和术语表只读取计数，不执行写操作。
 * </p>
 *
 * <p>
 * 运行示例：
 * {@code RUN_POSTGRES_INTEGRATION_TESTS=true mvn -pl headless/server -Dtest=SemanticModelingDraftPostgresIntegrationTest test}
 * </p>
 */
@Tag("postgres-integration")
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_INTEGRATION_TESTS", matches = "true")
class SemanticModelingDraftPostgresIntegrationTest {

    private static final String POSTGRES_UNIQUE_VIOLATION = "23505";

    /**
     * 验证真实 PostgreSQL 迁移结果和数据库级并发门禁。
     *
     * @throws Exception 数据库不可达、迁移未执行或约束不符合预期时抛出。
     */
    @Test
    void shouldEnforceAttemptHistoryAndOldWorkerGuardsOnPostgres() throws Exception {
        Map<String, Long> formalCountsBefore;
        try (Connection connection = openConnection()) {
            formalCountsBefore = queryFormalAssetCounts(connection);
            assertMigrationShape(connection);
            assertEquals(0L, queryLong(connection, """
                    SELECT count(*)
                    FROM s2_semantic_modeling_draft draft
                    LEFT JOIN s2_semantic_modeling_draft_attempt attempt
                      ON attempt.draft_id = draft.id AND attempt.attempt_no = 1
                    WHERE attempt.id IS NULL
                    """), "每个历史草稿都必须已回填 attempt 1");

            connection.setAutoCommit(false);
            try {
                long draftId = insertTemporaryDraft(connection);
                String operator = "codex_pg_" + UUID.randomUUID().toString().replace("-", "");
                String idempotencyKey = "attempt_" + UUID.randomUUID();
                insertAttempt(connection, draftId, 1, operator, idempotencyKey);

                assertUniqueViolation(connection, () -> insertAttempt(connection, draftId, 1,
                        operator, "other_" + UUID.randomUUID()));
                assertUniqueViolation(connection,
                        () -> insertAttempt(connection, draftId, 2, operator, idempotencyKey));

                assertEquals(0, executeUpdate(connection, """
                        UPDATE s2_semantic_modeling_draft
                        SET status = 'DRAFT'
                        WHERE id = ? AND status = 'GENERATING' AND current_attempt_no = 1
                        """, draftId), "旧 attempt 的 Worker 不得覆盖当前 attempt 2");
                assertEquals(1, executeUpdate(connection, """
                        UPDATE s2_semantic_modeling_draft
                        SET status = 'DRAFT'
                        WHERE id = ? AND status = 'GENERATING' AND current_attempt_no = 2
                        """, draftId), "当前 attempt 的条件更新必须成功");
            } finally {
                connection.rollback();
            }
        }

        try (Connection verification = openConnection()) {
            assertEquals(formalCountsBefore, queryFormalAssetCounts(verification),
                    "PostgreSQL 集成测试不得改变正式语义资产行数");
        }
    }

    /** 读取连接配置；只有显式环境开关启用时本测试才会执行。 */
    private Connection openConnection() throws SQLException {
        String host = environment("S2_DB_HOST", "localhost");
        String port = environment("S2_DB_PORT", "5432");
        String database = environment("S2_DB_DATABASE", "supersonic");
        String user = environment("S2_DB_USER", "postgres");
        String password = environment("S2_DB_PASSWORD", "123456");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + database, user, password);
    }

    /** 验证新增列默认值、关键索引和表类型均已落到 PostgreSQL。 */
    private void assertMigrationShape(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_default, is_nullable, data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 's2_semantic_modeling_draft'
                  AND column_name = 'current_attempt_no'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "主表必须包含 current_attempt_no");
                assertTrue(resultSet.getString("column_default").contains("1"));
                assertEquals("NO", resultSet.getString("is_nullable"));
                assertEquals("integer", resultSet.getString("data_type"));
            }
        }
        assertEquals(4L, queryLong(connection, """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'uk_semantic_draft_attempt_no',
                    'uk_semantic_draft_attempt_idempotency',
                    'idx_semantic_draft_attempt_list',
                    'idx_semantic_draft_attempt_recovery'
                  )
                """), "缺少 PostgreSQL attempt 唯一或查询索引");
    }

    /** 插入 current_attempt_no=2 的临时主记录，用于验证旧 Worker 条件更新。 */
    private long insertTemporaryDraft(Connection connection) throws SQLException {
        String sql = """
                INSERT INTO s2_semantic_modeling_draft (
                    source_type, business_goal, data_source_id, selected_tables, chat_model_id,
                    include_sample, idempotency_key, status, current_version_no,
                    current_attempt_no, lock_version, created_by, created_at, updated_by, updated_at
                ) VALUES ('DATA_SOURCE', 'postgres integration smoke', 1, '[]', 1, false, ?,
                          'GENERATING', 0, 2, 0, 'codex_pg_test', ?, 'codex_pg_test', ?)
                RETURNING id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, "draft_" + UUID.randomUUID());
            statement.setTimestamp(2, now);
            statement.setTimestamp(3, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    /** 插入一个 attempt；调用方通过 savepoint 验证两个唯一约束。 */
    private void insertAttempt(Connection connection, long draftId, int attemptNo, String operator,
            String idempotencyKey) throws SQLException {
        String sql =
                """
                        INSERT INTO s2_semantic_modeling_draft_attempt (
                            draft_id, attempt_no, trigger_type, status, chat_model_id, include_sample,
                            idempotency_key, request_fingerprint, created_by, created_at, updated_by, updated_at
                        ) VALUES (?, ?, 'MANUAL_REGENERATION', 'QUEUED', 1, false, ?, 'fingerprint', ?, ?, ?, ?)
                        """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setLong(1, draftId);
            statement.setInt(2, attemptNo);
            statement.setString(3, idempotencyKey);
            statement.setString(4, operator);
            statement.setTimestamp(5, now);
            statement.setString(6, operator);
            statement.setTimestamp(7, now);
            statement.executeUpdate();
        }
    }

    /** 在 savepoint 内断言 PostgreSQL 唯一约束，随后恢复事务继续验证其他分支。 */
    private void assertUniqueViolation(Connection connection, SqlAction action)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        SQLException exception = assertThrows(SQLException.class, action::run);
        assertEquals(POSTGRES_UNIQUE_VIOLATION, exception.getSQLState());
        connection.rollback(savepoint);
    }

    /** 执行带单个 long 参数的条件更新。 */
    private int executeUpdate(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate();
        }
    }

    /** 查询正式语义资产计数，确保迁移和测试没有触碰发布表。 */
    private Map<String, Long> queryFormalAssetCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("model", queryLong(connection, "SELECT count(*) FROM s2_model"));
        counts.put("dimension", queryLong(connection, "SELECT count(*) FROM s2_dimension"));
        counts.put("metric", queryLong(connection, "SELECT count(*) FROM s2_metric"));
        counts.put("term", queryLong(connection, "SELECT count(*) FROM s2_term"));
        return counts;
    }

    /** 执行只返回一个 long 的只读查询。 */
    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    /** 获取环境变量并应用本地 PostgreSQL 默认值。 */
    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 允许测试 lambda 抛出 SQLException。 */
    @FunctionalInterface
    private interface SqlAction {

        /** 执行一次预期可能违反约束的数据库动作。 */
        void run() throws SQLException;
    }
}
