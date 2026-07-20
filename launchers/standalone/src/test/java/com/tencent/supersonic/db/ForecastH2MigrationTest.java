package com.tencent.supersonic.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastJobMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastProfileMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastStreamMapper;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Forecast H2 元数据库正向、唯一约束和回滚脚本验收测试。
 */
class ForecastH2MigrationTest {

    private static final String FORWARD =
            "config.update/sql-update-h2-20260718-forecast-mvp.sql";
    private static final String ROLLBACK =
            "config.update/sql-rollback-h2-20260718-forecast-mvp.sql";
    private static final String ACTIVATION_GUARD_FORWARD =
            "config.update/sql-update-h2-20260720-forecast-activation-guard.sql";
    private static final String ACTIVATION_GUARD_ROLLBACK =
            "config.update/sql-rollback-h2-20260720-forecast-activation-guard.sql";

    @Test
    @DisplayName("H2 正向迁移可执行且回滚后不残留 Forecast 控制表")
    void shouldApplyConstraintsAndRollbackMigration() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:forecast_migration;DB_CLOSE_DELAY=-1", "sa", "")) {
            execute(connection, FORWARD);
            assertEquals(8, forecastTableCount(connection));
            assertEquals("2", schemaVersion(connection));
            insertJob(connection, "same-key");
            assertThrows(SQLException.class, () -> insertJob(connection, "same-key"));

            execute(connection, ROLLBACK);
            assertEquals(0, forecastTableCount(connection));
        }
    }

    @Test
    @DisplayName("首次同步业务并发槽会拒绝重复排队并在取消后释放")
    void shouldGuardActiveInitialSyncAndReleaseSlotOnCancel() throws Exception {
        String url = "jdbc:h2:mem:forecast_activation_guard;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            execute(connection, FORWARD);
            long firstJobId = insertInitialSync(connection, "initial-a", 11L,
                    "INITIAL_SYNC:STREAM:7");
            assertThrows(SQLException.class, () -> insertInitialSync(connection, "initial-b", 12L,
                    "INITIAL_SYNC:STREAM:7"));

            try (SqlSession session = jobMapperFactory(url).openSession(true)) {
                ForecastJobMapper mapper = session.getMapper(ForecastJobMapper.class);
                assertEquals(1, mapper.requestCancel(firstJobId, new Date()));
            }

            assertNull(stringValue(connection,
                    "SELECT active_concurrency_key FROM s2_forecast_job WHERE id=?", firstJobId));
            // 排队任务取消后并发槽已释放，后续映射可以重新发起首次同步。
            long latestJobId = insertInitialSync(connection, "initial-c", 12L,
                    "INITIAL_SYNC:STREAM:7");
            assertTrue(latestJobId > firstJobId);
            long newerTerminalJobId =
                    insertInitialSync(connection, "initial-terminal", 13L, null);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE s2_forecast_job SET status='CANCELLED' WHERE id=?")) {
                statement.setLong(1, newerTerminalJobId);
                statement.executeUpdate();
            }
            try (SqlSession session = jobMapperFactory(url).openSession(true)) {
                ForecastJobMapper mapper = session.getMapper(ForecastJobMapper.class);
                // 历史重复任务中较新的终态不能遮住仍会被 Worker 执行的排队任务。
                assertEquals(List.of(latestJobId), mapper.selectLatestInitialSyncs(List.of(7L))
                        .stream().map(job -> job.getId()).toList());
            }
        }
    }

    @Test
    @DisplayName("H2 激活保护增量迁移可从版本 1 前进并完整回滚")
    void shouldApplyAndRollbackActivationGuardIncrementally() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:forecast_activation_incremental;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE s2_forecast_job ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, stream_id BIGINT, "
                        + "job_type VARCHAR(32))");
                statement.executeUpdate("CREATE TABLE s2_forecast_schema_version ("
                        + "component VARCHAR(64) PRIMARY KEY, version INT NOT NULL, "
                        + "installed_at TIMESTAMP NOT NULL)");
                statement.executeUpdate("INSERT INTO s2_forecast_schema_version VALUES "
                        + "('forecast_meta', 1, CURRENT_TIMESTAMP)");
            }

            execute(connection, ACTIVATION_GUARD_FORWARD);
            assertEquals("2", schemaVersion(connection));
            assertTrue(columnExists(connection, "S2_FORECAST_JOB", "ACTIVE_CONCURRENCY_KEY"));

            execute(connection, ACTIVATION_GUARD_ROLLBACK);
            assertEquals("1", schemaVersion(connection));
            assertFalse(columnExists(connection, "S2_FORECAST_JOB", "ACTIVE_CONCURRENCY_KEY"));
        }
    }

    @Test
    @DisplayName("任务失败在额度内重排队且 CAS 认领会清理旧错误")
    void shouldRetryAndClaimJobWithCompareAndSet() throws Exception {
        String url = "jdbc:h2:mem:forecast_job_cas;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            execute(connection, FORWARD);
            long jobId = insertRunningJob(connection, "worker-a");
            SqlSessionFactory factory = jobMapperFactory(url);

            try (SqlSession session = factory.openSession(true)) {
                ForecastJobMapper mapper = session.getMapper(ForecastJobMapper.class);
                Date now = new Date();
                assertEquals(1,
                        mapper.requeueFailure(jobId, "worker-a", "SOURCE_TIMEOUT",
                                "源库暂时不可用", now));
                assertEquals("QUEUED", jobStatus(connection, jobId));
                assertEquals(1, jobRetryCount(connection, jobId));

                assertEquals(1, mapper.claim(jobId, 1, "worker-b", now,
                        new Date(now.getTime() + 90_000L)));
                assertEquals("RUNNING", jobStatus(connection, jobId));
                assertNull(jobErrorCode(connection, jobId));
                // 旧 lockVersion 已失效，第二个 Worker 不能重复认领同一任务。
                assertEquals(0, mapper.claim(jobId, 1, "worker-c", now,
                        new Date(now.getTime() + 90_000L)));
            }
        }
    }

    @Test
    @DisplayName("H2 布尔列和首次映射激活使用 Profile/Stream 双 CAS")
    void shouldActivateInitialMappingWithBooleanAndOptimisticLocks() throws Exception {
        String url = "jdbc:h2:mem:forecast_initial_activation;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            execute(connection, FORWARD);
            insertProfileAndStream(connection);
            SqlSessionFactory factory = activationMapperFactory(url);
            Date now = new Date();

            try (SqlSession session = factory.openSession(true)) {
                ForecastStreamMapper streamMapper = session.getMapper(ForecastStreamMapper.class);
                ForecastProfileMapper profileMapper = session.getMapper(ForecastProfileMapper.class);
                assertEquals(1, streamMapper.activateMapping(2L, 3L, 1, 0,
                        "forecast-worker", now));
                assertEquals(0, streamMapper.activateMapping(2L, 4L, 2, 0,
                        "stale-worker", now));
                assertEquals(1, profileMapper.touchInitialActivation(1L, 0, now));
                assertEquals(0, profileMapper.touchInitialActivation(1L, 0, now));
            }

            assertEquals("3", stringValue(connection,
                    "SELECT active_mapping_id FROM s2_forecast_stream WHERE id=?", 2L));
            assertNotNull(stringValue(connection,
                    "SELECT last_forecast_at FROM s2_forecast_profile WHERE id=?", 1L));
        }
    }

    /** 执行 classpath 中的 UTF-8 SQL 脚本。 */
    private void execute(Connection connection, String resource) throws Exception {
        InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource);
        assertNotNull(stream, "迁移脚本必须进入 Launcher classpath: " + resource);
        try (InputStream input = stream;
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }
    }

    /** 查询当前 schema 中 Forecast 控制表数量。 */
    private int forecastTableCount(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME LIKE 'S2_FORECAST_%'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    /** 判断指定控制表列是否存在，供增量回滚完整性断言使用。 */
    private boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=? AND COLUMN_NAME=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    /** 读取迁移最后写入的元数据库版本，验证半完成 DDL 不会伪装成可用版本。 */
    private String schemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM s2_forecast_schema_version WHERE component=?")) {
            statement.setString(1, "forecast_meta");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    /** 插入最小任务记录，用数据库唯一约束验证幂等键竞争裁决。 */
    private void insertJob(Connection connection, String key) throws SQLException {
        String sql = "INSERT INTO s2_forecast_job "
                + "(profile_id, job_type, status, idempotency_key, request_fingerprint, "
                + "parameters_json, created_by, created_at, updated_at) "
                + "VALUES (1, 'FORECAST', 'QUEUED', ?, 'fingerprint', '{}', 'tester', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }

    /** 插入占用数据流业务并发槽的首次同步任务并返回自增 ID。 */
    private long insertInitialSync(Connection connection, String idempotencyKey, long mappingId,
            String concurrencyKey) throws SQLException {
        String sql = "INSERT INTO s2_forecast_job "
                + "(profile_id, stream_id, mapping_id, job_type, status, "
                + "active_concurrency_key, idempotency_key, request_fingerprint, "
                + "parameters_json, created_by, created_at, updated_at) VALUES "
                + "(1, 7, ?, 'INITIAL_SYNC', 'QUEUED', ?, ?, 'fingerprint', '{}', "
                + "'tester', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, mappingId);
            statement.setString(2, concurrencyKey);
            statement.setString(3, idempotencyKey);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** 插入由指定 Worker 持有的运行任务并返回自增 ID。 */
    private long insertRunningJob(Connection connection, String workerId) throws SQLException {
        String sql = "INSERT INTO s2_forecast_job "
                + "(profile_id, job_type, status, idempotency_key, request_fingerprint, "
                + "parameters_json, retry_count, max_retries, worker_id, lock_version, "
                + "created_by, created_at, updated_at) VALUES "
                + "(1, 'FORECAST', 'RUNNING', 'retry-key', 'fingerprint', '{}', 0, 3, ?, 0, "
                + "'tester', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, workerId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** 插入启用 Profile 和数据流，布尔字面量验证迁移字段与 Mapper 条件兼容。 */
    private void insertProfileAndStream(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO s2_forecast_profile "
                    + "(id,name,source_database_id,decision_database_id,time_zone,sync_cron,"
                    + "forecast_cron,reconcile_cron,history_days,enabled,deleted,lock_version,"
                    + "created_by,created_at,updated_by,updated_at) VALUES "
                    + "(1,'test',10,11,'UTC','0 0 1 * * *','0 30 2 * * *','0 30 3 * * 0',"
                    + "180,TRUE,FALSE,0,'tester',CURRENT_TIMESTAMP,'tester',CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO s2_forecast_stream "
                    + "(id,profile_id,name,enabled,deleted,lock_version,created_by,created_at,"
                    + "updated_by,updated_at) VALUES (2,1,'stream',TRUE,FALSE,0,'tester',"
                    + "CURRENT_TIMESTAMP,'tester',CURRENT_TIMESTAMP)");
        }
    }

    /** 创建只加载 ForecastJobMapper 注解 SQL 的轻量 MyBatis 工厂。 */
    private SqlSessionFactory jobMapperFactory(String url) {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        Environment environment = new Environment("forecast-test", new JdbcTransactionFactory(),
                dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ForecastJobMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 创建只加载 Profile/Stream 自定义 CAS SQL 的轻量 MyBatis 工厂。 */
    private SqlSessionFactory activationMapperFactory(String url) {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        Environment environment = new Environment("forecast-activation-test",
                new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(ForecastProfileMapper.class);
        configuration.addMapper(ForecastStreamMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 读取任务状态。 */
    private String jobStatus(Connection connection, long jobId) throws SQLException {
        return stringValue(connection, "SELECT status FROM s2_forecast_job WHERE id=?", jobId);
    }

    /** 读取最后安全错误码。 */
    private String jobErrorCode(Connection connection, long jobId) throws SQLException {
        return stringValue(connection, "SELECT error_code FROM s2_forecast_job WHERE id=?", jobId);
    }

    /** 读取任务重试次数。 */
    private int jobRetryCount(Connection connection, long jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT retry_count FROM s2_forecast_job WHERE id=?")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** 执行测试代码内部固定、外部不可控的单列参数化查询。 */
    private String stringValue(Connection connection, String sql, long jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
