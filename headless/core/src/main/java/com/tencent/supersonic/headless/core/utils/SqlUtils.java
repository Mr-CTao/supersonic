package com.tencent.supersonic.headless.core.utils;

import javax.sql.DataSource;

import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.headless.api.pojo.enums.DataType;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.core.pojo.JdbcDataSource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.rmi.ServerException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.tencent.supersonic.common.pojo.Constants.AT_SYMBOL;

/** tools functions about sql query */
@Slf4j
@Component
public class SqlUtils {

    @Getter
    private DatabaseResp database;

    @Autowired
    private JdbcDataSource jdbcDataSource;

    @Value("${s2.source.result-limit:1000000}")
    private int resultLimit;

    @Value("${s2.source.enable-query-log:false}")
    private boolean isQueryLogEnable;

    @Getter
    private DataType dataTypeEnum;

    @Getter
    private JdbcDataSourceUtils jdbcDataSourceUtils;

    public SqlUtils() {}

    public SqlUtils(DatabaseResp database) {
        this.database = database;
        this.dataTypeEnum = DataType.urlOf(database.getUrl());
    }

    public SqlUtils init(DatabaseResp database) {
        return SqlUtilsBuilder.getBuilder()
                .withName(database.getId() + AT_SYMBOL + database.getName())
                .withType(database.getType()).withJdbcUrl(database.getUrl())
                .withUsername(database.getUsername()).withPassword(database.getPassword())
                .withJdbcDataSource(this.jdbcDataSource).withResultLimit(this.resultLimit)
                .withIsQueryLogEnable(this.isQueryLogEnable).build();
    }

    public List<Map<String, Object>> execute(String sql) throws ServerException {
        try {
            List<Map<String, Object>> list = jdbcTemplate().queryForList(sql);
            log.info("list:{}", list);
            return list;
        } catch (Exception e) {
            log.error(e.toString(), e);
            throw new ServerException(e.getMessage());
        }
    }

    public void execute(String sql, SemanticQueryResp queryResultWithColumns) {
        getResult(sql, queryResultWithColumns, jdbcTemplate());
    }

    public JdbcTemplate jdbcTemplate() throws RuntimeException {
        Connection connection = null;
        try {
            connection = jdbcDataSourceUtils.getConnection(database);
        } catch (Exception e) {
            log.warn("e:", e);
        } finally {
            JdbcDataSourceUtils.releaseConnection(connection);
        }
        DataSource dataSource = jdbcDataSourceUtils.getDataSource(database);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setDatabaseProductName(database.getName());
        jdbcTemplate.setFetchSize(500);
        return jdbcTemplate;
    }

    public void queryInternal(String sql, SemanticQueryResp queryResultWithColumns) {
        getResult(sql, queryResultWithColumns, jdbcTemplate());
    }

    /**
     * 从服务端确认过的物理表读取有限样例行。
     *
     * <p>
     * 调用示例：{@code sqlUtils.querySampleRows(List.of("catalog", "schema", "orders"), 3,
     * 5)}。方法只生成 {@code SELECT * FROM <quoted identifiers>}，不接受任意 SQL；JDBC Statement 同时设置
     * maxRows、fetchSize 和 queryTimeout。LOB 与二进制列会被跳过，避免大对象进入 LLM 上下文。
     * </p>
     *
     * @param identifierParts 已由上层元数据确认的 catalog/schema/table 标识符。
     * @param maxRows 最大返回行数，必须大于 0。
     * @param timeoutSeconds 查询超时秒数，必须大于 0。
     * @return 按列顺序保存的样例行。
     * @throws SQLException 当连接、标识符或查询失败时抛出，由上层安全降级为 schema-only。
     */
    public List<Map<String, Object>> querySampleRows(List<String> identifierParts, int maxRows,
            int timeoutSeconds) throws SQLException {
        if (identifierParts == null || identifierParts.isEmpty() || maxRows <= 0
                || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Invalid safe sample query parameters");
        }
        JdbcTemplate template = jdbcTemplate();
        return template.execute((Connection connection) -> {
            boolean previousReadOnly = connection.isReadOnly();
            try {
                connection.setReadOnly(true);
                String qualifiedTable =
                        quoteQualifiedIdentifier(connection.getMetaData(), identifierParts);
                String sql = "SELECT * FROM " + qualifiedTable;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setMaxRows(maxRows);
                    statement.setFetchSize(maxRows);
                    statement.setQueryTimeout(timeoutSeconds);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return readSampleRows(resultSet, maxRows);
                    }
                }
            } finally {
                // 连接来自池，必须恢复只读状态，避免影响后续正常业务查询。
                if (connection.isReadOnly() != previousReadOnly) {
                    connection.setReadOnly(previousReadOnly);
                }
            }
        });
    }

    /** 使用驱动声明的引用符安全拼接已验证标识符。 */
    private String quoteQualifiedIdentifier(DatabaseMetaData metadata, List<String> parts)
            throws SQLException {
        String quote = StringUtils.trimToEmpty(metadata.getIdentifierQuoteString());
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.isBlank(part)) {
                continue;
            }
            if (part.indexOf('\0') >= 0 || part.contains(";") || part.contains("--")
                    || part.contains("/*")) {
                throw new SQLException("Unsafe identifier");
            }
            if (StringUtils.isBlank(quote)) {
                if (!part.matches("[\\p{L}\\p{N}_$-]+")) {
                    throw new SQLException("Database does not support quoting this identifier");
                }
                quoted.add(part);
            } else {
                quoted.add(quote + part.replace(quote, quote + quote) + quote);
            }
        }
        if (quoted.isEmpty()) {
            throw new SQLException("Empty table identifier");
        }
        return String.join(".", quoted);
    }

    /** 只读取要求的行数，并跳过 LOB/二进制列。 */
    private List<Map<String, Object>> readSampleRows(ResultSet resultSet, int maxRows)
            throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rows.size() < maxRows && resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                int jdbcType = metadata.getColumnType(index);
                if (isLargeOrBinaryType(jdbcType)) {
                    continue;
                }
                Object value = resultSet.getObject(index);
                if (value instanceof Blob || value instanceof Clob || value instanceof byte[]) {
                    continue;
                }
                row.put(metadata.getColumnLabel(index), getValue(value));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 判断 JDBC 类型是否不应进入样例上下文。 */
    private boolean isLargeOrBinaryType(int jdbcType) {
        return jdbcType == Types.BLOB || jdbcType == Types.CLOB || jdbcType == Types.NCLOB
                || jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.LONGNVARCHAR;
    }

    private SemanticQueryResp getResult(String sql, SemanticQueryResp queryResultWithColumns,
            JdbcTemplate jdbcTemplate) {
        jdbcTemplate.query(sql, rs -> {
            if (null == rs) {
                return queryResultWithColumns;
            }

            ResultSetMetaData metaData = rs.getMetaData();
            List<QueryColumn> queryColumns = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String key = metaData.getColumnLabel(i);
                queryColumns.add(new QueryColumn(key, metaData.getColumnTypeName(i)));
            }
            queryResultWithColumns.setColumns(queryColumns);

            List<Map<String, Object>> resultList = getAllData(rs, queryColumns);
            queryResultWithColumns.setResultList(resultList);
            return queryResultWithColumns;
        });
        return queryResultWithColumns;
    }

    private List<Map<String, Object>> getAllData(ResultSet rs, List<QueryColumn> queryColumns) {
        List<Map<String, Object>> data = new ArrayList<>();
        try {
            while (rs.next()) {
                data.add(getLineData(rs, queryColumns));
            }
        } catch (Exception e) {
            log.warn("error in getAllData, e:", e);
        }
        return data;
    }

    private Map<String, Object> getLineData(ResultSet rs, List<QueryColumn> queryColumns)
            throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        for (QueryColumn queryColumn : queryColumns) {
            String colName = queryColumn.getBizName();
            Object value = rs.getObject(colName);
            map.put(colName, getValue(value));
        }
        return map;
    }

    private Object getValue(Object value) {
        if (value instanceof LocalDate) {
            LocalDate localDate = (LocalDate) value;
            return localDate.format(DateTimeFormatter.ofPattern(DateUtils.DEFAULT_DATE_FORMAT));
        } else if (value instanceof LocalDateTime) {
            LocalDateTime localDateTime = (LocalDateTime) value;
            return localDateTime.format(DateTimeFormatter.ofPattern(DateUtils.DEFAULT_TIME_FORMAT));
        } else if (value instanceof Date) {
            Date date = (Date) value;
            return DateUtils.format(date);
        } else if (value instanceof byte[]) {
            return new String((byte[]) value);
        }
        return value;
    }

    public static final class SqlUtilsBuilder {

        private JdbcDataSource jdbcDataSource;
        private int resultLimit;
        private boolean isQueryLogEnable;
        private String name;
        private String type;
        private String jdbcUrl;
        private String username;
        private String password;

        private SqlUtilsBuilder() {}

        public static SqlUtilsBuilder getBuilder() {
            return new SqlUtilsBuilder();
        }

        SqlUtilsBuilder withJdbcDataSource(JdbcDataSource jdbcDataSource) {
            this.jdbcDataSource = jdbcDataSource;
            return this;
        }

        SqlUtilsBuilder withResultLimit(int resultLimit) {
            this.resultLimit = resultLimit;
            return this;
        }

        SqlUtilsBuilder withIsQueryLogEnable(boolean isQueryLogEnable) {
            this.isQueryLogEnable = isQueryLogEnable;
            return this;
        }

        SqlUtilsBuilder withName(String name) {
            this.name = name;
            return this;
        }

        SqlUtilsBuilder withType(String type) {
            this.type = type;
            return this;
        }

        SqlUtilsBuilder withJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        SqlUtilsBuilder withUsername(String username) {
            this.username = username;
            return this;
        }

        SqlUtilsBuilder withPassword(String password) {
            this.password = password;
            return this;
        }

        public SqlUtils build() {
            DatabaseResp database = DatabaseResp.builder().name(this.name)
                    .type(this.type.toUpperCase()).url(this.jdbcUrl).username(this.username)
                    .password(this.password).build();

            SqlUtils sqlUtils = new SqlUtils(database);
            sqlUtils.jdbcDataSource = this.jdbcDataSource;
            sqlUtils.resultLimit = this.resultLimit;
            sqlUtils.isQueryLogEnable = this.isQueryLogEnable;
            sqlUtils.jdbcDataSourceUtils = new JdbcDataSourceUtils(this.jdbcDataSource);

            return sqlUtils;
        }
    }
}
