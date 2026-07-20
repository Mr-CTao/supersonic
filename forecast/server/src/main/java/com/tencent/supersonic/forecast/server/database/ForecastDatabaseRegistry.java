package com.tencent.supersonic.forecast.server.database;

import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.enums.DataType;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.DatabaseDO;
import com.tencent.supersonic.headless.server.persistence.mapper.DatabaseDOMapper;
import com.tencent.supersonic.headless.server.utils.DatabaseConverter;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * 复用 SuperSonic 数据源凭据并按任务创建短生命周期 JDBC 连接。
 *
 * <p>
 * 职责边界：本类只在内存中解密现有 {@code s2_database} 凭据，不复制密码到 Forecast 表， 也不记录 URL、用户名或异常中的连接属性。连接由调用方使用
 * try-with-resources 关闭。
 * </p>
 */
@Component
public class ForecastDatabaseRegistry {

    private static final Set<String> SOURCE_TYPES = Set.of("mysql", "postgresql", "sqlserver");

    private final DatabaseDOMapper databaseMapper;

    /**
     * 创建数据库注册表。
     *
     * @param databaseMapper 现有数据源 Mapper。
     */
    public ForecastDatabaseRegistry(DatabaseDOMapper databaseMapper) {
        this.databaseMapper = databaseMapper;
    }

    /**
     * 打开强制只读的源库连接。
     *
     * @param databaseId SuperSonic 数据源 ID。
     * @return 已开启只读和手工事务模式的 JDBC 连接。
     * @throws SQLException 建连或设置只读失败。
     * @throws InvalidArgumentException 数据源不存在或类型不受支持。
     */
    public Connection openSource(Long databaseId) throws SQLException {
        DatabaseResp database = require(databaseId);
        String type = normalize(database.getType());
        if (!SOURCE_TYPES.contains(type)) {
            throw new InvalidArgumentException("预测 MVP 暂不支持源数据库类型: " + type);
        }
        Connection connection = open(database, type);
        try {
            // 源库边界必须由 JDBC 连接自身再次约束，避免上层误执行写语句。
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    /**
     * 打开 PostgreSQL 决策库连接。
     *
     * @param databaseId SuperSonic 数据源 ID。
     * @return 手工事务模式的可写连接。
     * @throws SQLException 建连失败。
     * @throws InvalidArgumentException 目标不是 PostgreSQL。
     */
    public Connection openDecision(Long databaseId) throws SQLException {
        DatabaseResp database = require(databaseId);
        String type = normalize(database.getType());
        if (!"postgresql".equals(type)) {
            throw new InvalidArgumentException("Forecast 决策库首版必须使用 PostgreSQL");
        }
        Connection connection = open(database, type);
        try {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    /**
     * 打开只读 PostgreSQL 决策库连接，供看板查询使用。
     *
     * @param databaseId 数据源 ID。
     * @return 只读手工事务连接。
     * @throws SQLException 建连失败。
     */
    public Connection openDecisionReadOnly(Long databaseId) throws SQLException {
        DatabaseResp database = require(databaseId);
        String type = normalize(database.getType());
        if (!"postgresql".equals(type)) {
            throw new InvalidArgumentException("Forecast 决策库首版必须使用 PostgreSQL");
        }
        Connection connection = open(database, type);
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    /**
     * 返回不含密码的数据库描述。
     *
     * @param databaseId 数据源 ID。
     * @return 数据源描述。
     * @throws InvalidArgumentException 数据源不存在。
     */
    public DatabaseDescriptor describe(Long databaseId) {
        DatabaseResp database = require(databaseId);
        return new DatabaseDescriptor(database.getId(), database.getName(),
                normalize(database.getType()));
    }

    /** 读取现有数据源并在内存恢复连接属性。 */
    private DatabaseResp require(Long databaseId) {
        if (databaseId == null) {
            throw new InvalidArgumentException("数据库 ID 不能为空");
        }
        DatabaseDO database = databaseMapper.selectById(databaseId);
        if (database == null) {
            throw new InvalidArgumentException("数据库不存在");
        }
        return DatabaseConverter.convertWithPassword(database);
    }

    /** 创建连接并显式加载对应驱动。 */
    private Connection open(DatabaseResp database, String type) throws SQLException {
        DataType dataType = switch (type) {
            case "mysql" -> DataType.MYSQL;
            case "postgresql" -> DataType.POSTGRESQL;
            case "sqlserver" -> DataType.SQLSERVER;
            default -> throw new InvalidArgumentException("不支持的数据库类型: " + type);
        };
        try {
            Class.forName(dataType.getDriver());
        } catch (ClassNotFoundException exception) {
            throw new SQLException("Forecast JDBC 驱动未安装: " + type, exception);
        }
        return DriverManager.getConnection(database.getUrl(), database.getUsername(),
                database.passwordDecrypt());
    }

    /** 标准化 SuperSonic 数据源类型。 */
    private String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 前端与服务层可安全展示的数据源最小描述。
     *
     * @param id 数据源 ID。
     * @param name 数据源名称。
     * @param type 数据源类型。
     */
    public record DatabaseDescriptor(Long id, String name, String type) {}
}
