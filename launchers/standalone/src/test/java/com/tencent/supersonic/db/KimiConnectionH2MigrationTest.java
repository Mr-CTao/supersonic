package com.tencent.supersonic.db;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kimi K3 默认连接 H2 迁移验证。
 *
 * <p>职责说明：验证正向脚本可重复执行、能力元数据正确，并确保回滚只删除 API Key 仍为空的种子连接。每个测试使用独立
 * 内存数据库，不共享连接或状态，不需要并发保护。</p>
 */
class KimiConnectionH2MigrationTest {

    private static final String FORWARD =
            "config.update/sql-update-h2-20260720-kimi-k3-connection.sql";
    private static final String ROLLBACK =
            "config.update/sql-rollback-h2-20260720-kimi-k3-connection.sql";

    /** 验证正向迁移幂等，并能完整回滚未填写 API Key 的种子数据。 */
    @Test
    @DisplayName("Kimi K3 空密钥连接可幂等初始化并回滚")
    void shouldApplyIdempotentlyAndRollbackUntouchedSeed() throws Exception {
        try (Connection connection = newDatabase("kimi_seed_rollback")) {
            execute(connection, FORWARD);
            execute(connection, FORWARD);

            assertEquals(1, count(connection, "s2_chat_model"));
            assertEquals(1, count(connection, "s2_llm_model_capability"));
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT config FROM s2_chat_model WHERE name='Kimi-K3'")) {
                assertTrue(resultSet.next());
                String config = resultSet.getString(1);
                assertTrue(config.contains("\"provider\":\"KIMI\""));
                assertTrue(config.contains("\"apiKey\":\"\""));
                assertTrue(config.contains("\"temperature\":1.0"));
            }

            execute(connection, ROLLBACK);
            assertEquals(0, count(connection, "s2_llm_model_capability"));
            assertEquals(0, count(connection, "s2_chat_model"));
        }
    }

    /** 验证管理员填写 API Key 后，回滚脚本不会删除用户拥有的连接及能力配置。 */
    @Test
    @DisplayName("Kimi K3 已填写密钥的连接不被自动回滚")
    void shouldPreserveSeedAfterApiKeyIsConfigured() throws Exception {
        try (Connection connection = newDatabase("kimi_seed_preserve")) {
            execute(connection, FORWARD);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE s2_chat_model SET config=REPLACE(config, "
                        + "'\"apiKey\":\"\"', '\"apiKey\":\"configured\"') "
                        + "WHERE name='Kimi-K3'");
            }

            execute(connection, ROLLBACK);
            assertEquals(1, count(connection, "s2_chat_model"));
            assertEquals(1, count(connection, "s2_llm_model_capability"));
        }
    }

    /** 创建只包含本迁移依赖表的独立 H2 数据库。 */
    private Connection newDatabase(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE s2_chat_model ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                    + "description VARCHAR(500), config VARCHAR(4000) NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL, created_by VARCHAR(100) NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL, updated_by VARCHAR(100) NOT NULL, "
                    + "admin VARCHAR(500), viewer VARCHAR(500), is_open TINYINT)");
            statement.executeUpdate("CREATE TABLE s2_llm_model_capability ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, chat_model_id INT NOT NULL, "
                    + "provider_type VARCHAR(64) NOT NULL, model_name VARCHAR(255) NOT NULL, "
                    + "max_context_tokens INT, support_stream BOOLEAN, support_json_mode BOOLEAN, "
                    + "support_tool_calling BOOLEAN, support_thinking BOOLEAN, "
                    + "support_chat_prefix_completion BOOLEAN, support_fim_completion BOOLEAN, "
                    + "support_context_cache BOOLEAN, support_system_prompt BOOLEAN, "
                    + "recommended_temperature DOUBLE, usage_scene VARCHAR(255), enabled BOOLEAN, "
                    + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, "
                    + "UNIQUE(chat_model_id, model_name))");
        }
        return connection;
    }

    /** 统计指定测试表行数。 */
    private int count(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
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
}
