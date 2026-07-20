package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.enums.ForecastRelationMode;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import com.tencent.supersonic.forecast.api.model.ForecastCanonicalEvent;
import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ColumnRef;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ValueMapping;
import com.tencent.supersonic.forecast.api.model.ForecastMappingValidation;
import com.tencent.supersonic.forecast.api.model.ForecastMetadata;
import com.tencent.supersonic.forecast.api.model.ForecastMetadata.ColumnMetadata;
import com.tencent.supersonic.forecast.api.model.ForecastMetadata.TableMetadata;
import com.tencent.supersonic.forecast.api.model.ForecastPage;
import com.tencent.supersonic.forecast.api.model.ForecastReadContext;
import com.tencent.supersonic.forecast.api.spi.ForecastConnector;
import com.tencent.supersonic.forecast.core.mapping.ForecastColumnAliases;
import com.tencent.supersonic.forecast.core.mapping.ForecastMappingValidator;
import com.tencent.supersonic.forecast.core.mapping.ForecastRowTransformer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL、PostgreSQL 和 SQL Server 共享的只读 JDBC Connector。
 *
 * <p>
 * SQL 只由已验证的映射标识符和固定模板构造，所有水位与时间值均使用绑定参数。实例不持有连接、 水位或任务状态，因此可安全被多个 Worker 线程复用。
 * </p>
 */
public abstract class JdbcForecastConnector implements ForecastConnector {

    private static final int MAX_DISCOVERED_TABLES = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 120;
    private static final int VALIDATION_PREVIEW_DAYS = 180;

    private final ForecastSqlDialect dialect;
    private final ForecastMappingValidator validator = new ForecastMappingValidator();
    private final ForecastRowTransformer transformer = new ForecastRowTransformer();

    /**
     * 创建基于指定方言的 Connector。
     *
     * @param dialect 受控 SQL 方言。
     */
    protected JdbcForecastConnector(ForecastSqlDialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /** {@inheritDoc} */
    @Override
    public boolean supports(String databaseType) {
        return databaseType != null && dialect.databaseType().equalsIgnoreCase(databaseType);
    }

    /** {@inheritDoc} */
    @Override
    public ForecastMetadata discover(Connection connection, String catalog, String schema,
            String tablePattern) throws SQLException {
        requireReadOnly(connection);
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> catalogs = readCatalogs(metadata);
        List<String> schemas = readSchemas(metadata, catalog);
        List<TableMetadata> tables = new ArrayList<>();
        try (ResultSet resultSet = metadata.getTables(blankToNull(catalog), blankToNull(schema),
                blankToNull(tablePattern), new String[] {"TABLE", "VIEW"})) {
            while (resultSet.next() && tables.size() < MAX_DISCOVERED_TABLES) {
                String tableCatalog = resultSet.getString("TABLE_CAT");
                String tableSchema = resultSet.getString("TABLE_SCHEM");
                String tableName = resultSet.getString("TABLE_NAME");
                String tableType = resultSet.getString("TABLE_TYPE");
                List<ColumnMetadata> columns = hasText(tablePattern)
                        ? readColumns(metadata, tableCatalog, tableSchema, tableName)
                        : List.of();
                tables.add(new TableMetadata(tableCatalog, tableSchema, tableName, tableType,
                        columns));
            }
        }
        return new ForecastMetadata(catalogs, schemas, List.copyOf(tables));
    }

    /** {@inheritDoc} */
    @Override
    public ForecastMappingValidation validate(Connection connection, ForecastMappingConfig config,
            int sampleLimit) throws SQLException {
        int boundedLimit = Math.max(1, Math.min(100, sampleLimit));
        ForecastMappingValidator.ValidationReport report = validator.validate(config);
        List<String> errors = new ArrayList<>(report.errors());
        List<String> warnings = new ArrayList<>(report.warnings());
        if (!errors.isEmpty()) {
            return new ForecastMappingValidation(false, errors, warnings, List.of());
        }
        Instant now = Instant.now();
        // 预览不应为了找一百行而排序客户全历史表，使用与首次同步默认值一致的有界窗口。
        Instant historyStart = now.minus(VALIDATION_PREVIEW_DAYS, ChronoUnit.DAYS);
        Instant historyEnd = now.plus(1, ChronoUnit.DAYS);
        try {
            ForecastCursor upper = captureUpperBound(connection, config, historyStart, historyEnd);
            if (upper.isEmpty()) {
                warnings.add("映射结构有效，但当前业务时间窗口没有可预览数据");
                return new ForecastMappingValidation(true, List.of(), warnings, List.of());
            }
            ForecastPage page = readPage(connection, config, new ForecastReadContext(historyStart,
                    historyEnd, ForecastCursor.empty(), upper, boundedLimit));
            List<Map<String, Object>> samples = page.events().stream().map(this::sample).toList();
            return new ForecastMappingValidation(true, List.of(), warnings, samples);
        } catch (IllegalArgumentException exception) {
            errors.add(exception.getMessage());
            return new ForecastMappingValidation(false, errors, warnings, List.of());
        }
    }

    /** {@inheritDoc} */
    @Override
    public ForecastCursor captureUpperBound(Connection connection, ForecastMappingConfig config,
            Instant historyStart, Instant historyEnd) throws SQLException {
        validateExecutable(connection, config, historyStart, historyEnd);
        QueryExpressions expressions = expressions(config);
        StringBuilder sql = new StringBuilder("SELECT ");
        if (expressions.updatedAt() != null) {
            sql.append(expressions.updatedAt()).append(" AS ")
                    .append(dialect.quote(ForecastColumnAliases.SOURCE_UPDATED_AT)).append(", ");
        }
        sql.append(expressions.recordId()).append(" AS ")
                .append(dialect.quote(ForecastColumnAliases.SOURCE_RECORD_ID)).append(" FROM ")
                .append(fromClause(config)).append(" WHERE ").append(expressions.occurredAt())
                .append(" >= ? AND ").append(expressions.occurredAt()).append(" < ? ORDER BY ");
        if (expressions.updatedAt() != null) {
            sql.append(expressions.updatedAt()).append(" DESC, ");
        }
        sql.append(expressions.recordId()).append(" DESC");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            configure(statement, 1);
            ZoneId sourceZone = ZoneId.of(config.getSourceTimeZone());
            statement.setTimestamp(1, sourceTimestamp(historyStart, sourceZone));
            statement.setTimestamp(2, sourceTimestamp(historyEnd, sourceZone));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return ForecastCursor.empty();
                }
                Instant updatedAt = expressions.updatedAt() == null ? null
                        : instant(resultSet.getObject(ForecastColumnAliases.SOURCE_UPDATED_AT),
                                sourceZone);
                String recordId = resultSet.getString(ForecastColumnAliases.SOURCE_RECORD_ID);
                return new ForecastCursor(updatedAt, recordId);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public ForecastPage readPage(Connection connection, ForecastMappingConfig config,
            ForecastReadContext context) throws SQLException {
        if (context == null || context.pageSize() < 1 || context.pageSize() > 20_000) {
            throw new IllegalArgumentException("pageSize 必须位于 1 到 20000");
        }
        validateExecutable(connection, config, context.historyStart(), context.historyEnd());
        QueryExpressions expressions = expressions(config);
        ZoneId sourceZone = ZoneId.of(config.getSourceTimeZone());
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(projection(config, expressions)).append(" FROM ").append(fromClause(config))
                .append(" WHERE ").append(expressions.occurredAt()).append(" >= ? AND ")
                .append(expressions.occurredAt()).append(" < ?");
        parameters.add(sourceTimestamp(context.historyStart(), sourceZone));
        parameters.add(sourceTimestamp(context.historyEnd(), sourceZone));
        appendBounds(sql, parameters, expressions, context, sourceZone);
        sql.append(" ORDER BY ");
        if (expressions.updatedAt() != null) {
            sql.append(expressions.updatedAt()).append(" ASC, ");
        }
        sql.append(expressions.recordId()).append(" ASC");

        List<ForecastCanonicalEvent> events = new ArrayList<>(context.pageSize());
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            configure(statement, context.pageSize());
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(transformer.transform(row(resultSet), config));
                }
            }
        }
        ForecastCursor next = events.isEmpty() ? context.lowerExclusive()
                : cursor(events.get(events.size() - 1), config.getSyncMode());
        boolean reachedUpper =
                context.upperInclusive() != null && Objects.equals(next, context.upperInclusive());
        return new ForecastPage(List.copyOf(events), next,
                events.size() < context.pageSize() || reachedUpper);
    }

    /** 在执行前保证只读连接、时间边界和结构白名单。 */
    private void validateExecutable(Connection connection, ForecastMappingConfig config,
            Instant historyStart, Instant historyEnd) throws SQLException {
        requireReadOnly(connection);
        ForecastMappingValidator.ValidationReport report = validator.validate(config);
        if (!report.valid()) {
            throw new IllegalArgumentException(String.join("; ", report.errors()));
        }
        if (historyStart == null || historyEnd == null || !historyStart.isBefore(historyEnd)) {
            throw new IllegalArgumentException("业务时间窗口无效");
        }
    }

    /** 只接受显式只读连接，避免调用方配置失误写入源库。 */
    private void requireReadOnly(Connection connection) throws SQLException {
        if (connection == null || !connection.isReadOnly()) {
            throw new IllegalArgumentException("ForecastConnector 只接受只读 JDBC 连接");
        }
    }

    /** 构造所有标准字段的投影列表。 */
    private String projection(ForecastMappingConfig config, QueryExpressions expressions) {
        List<String> items = new ArrayList<>();
        addProjection(items, config.getFields().getSourceRecordId(), expressions.recordId(),
                ForecastColumnAliases.SOURCE_RECORD_ID);
        addProjection(items, config.getFields().getTaskId(), null, ForecastColumnAliases.TASK_ID);
        addProjection(items, config.getFields().getQuantity(), null,
                ForecastColumnAliases.QUANTITY);
        addProjection(items, config.getFields().getOccurredAt(), expressions.occurredAt(),
                ForecastColumnAliases.OCCURRED_AT);
        addProjection(items, config.getFields().getSourceUpdatedAt(), expressions.updatedAt(),
                ForecastColumnAliases.SOURCE_UPDATED_AT);
        addProjection(items, config.getFields().getWarehouseCode(), null,
                ForecastColumnAliases.WAREHOUSE_CODE);
        addProjection(items, config.getFields().getDirection(), null,
                ForecastColumnAliases.DIRECTION);
        addProjection(items, config.getFields().getStatus(), null, ForecastColumnAliases.STATUS);
        addProjection(items, config.getFields().getDeleted(), null, ForecastColumnAliases.DELETED);
        return String.join(", ", items);
    }

    /** 仅为列映射添加 SQL 投影，常量由转换器在内存赋值。 */
    private void addProjection(List<String> items, ValueMapping mapping, String override,
            String alias) {
        if (mapping == null || mapping.getSourceType() != ForecastValueSource.COLUMN) {
            return;
        }
        String expression = override == null ? column(mapping.getColumn()) : override;
        items.add(expression + " AS " + dialect.quote(alias));
    }

    /** 生成 SINGLE 或 HEADER_DETAIL 的唯一 FROM 形态。 */
    private String fromClause(ForecastMappingConfig config) {
        if (config.getRelationMode() == ForecastRelationMode.SINGLE) {
            return relation(config.getSource().getSingle());
        }
        String joinType = config.getSource().getJoin().getType().toUpperCase(Locale.ROOT);
        return relation(config.getSource().getHeader()) + " " + joinType + " JOIN "
                + relation(config.getSource().getDetail()) + " ON "
                + column(config.getSource().getJoin().getLeft()) + " = "
                + column(config.getSource().getJoin().getRight());
    }

    /** 生成限定表名和映射别名。 */
    private String relation(RelationTable table) {
        return dialect.qualify(table) + " " + dialect.quote(table.getAlias());
    }

    /** 提取游标、业务时间和更新时间表达式。 */
    private QueryExpressions expressions(ForecastMappingConfig config) {
        String recordId =
                dialect.castText(column(config.getFields().getSourceRecordId().getColumn()));
        String occurredAt = column(config.getFields().getOccurredAt().getColumn());
        String updatedAt = null;
        ValueMapping updatedMapping = config.getFields().getSourceUpdatedAt();
        if (updatedMapping != null) {
            updatedAt = column(updatedMapping.getColumn());
            if (updatedMapping.getSecondaryColumn() != null) {
                updatedAt = dialect.greatestTimestamp(updatedAt,
                        column(updatedMapping.getSecondaryColumn()));
            }
            // 游标第一分量必须非空；异常空更新时间退回业务时间，保证分页仍是完整全序。
            updatedAt = "COALESCE(" + updatedAt + ", " + occurredAt + ")";
        }
        return new QueryExpressions(recordId, occurredAt, updatedAt);
    }

    /** 生成已验证的别名列引用。 */
    private String column(ColumnRef reference) {
        return dialect.quote(reference.getTableAlias()) + "."
                + dialect.quote(reference.getColumn());
    }

    /** 添加下界和固定上界参数化条件。 */
    private void appendBounds(StringBuilder sql, List<Object> parameters,
            QueryExpressions expressions, ForecastReadContext context, ZoneId sourceZone) {
        ForecastCursor lower = context.lowerExclusive();
        ForecastCursor upper = context.upperInclusive();
        if (expressions.updatedAt() != null) {
            if (lower != null && !lower.isEmpty()) {
                sql.append(" AND ((").append(expressions.updatedAt()).append(" > ?) OR (")
                        .append(expressions.updatedAt()).append(" = ? AND ")
                        .append(expressions.recordId()).append(" > ?))");
                parameters.add(sourceTimestamp(lower.updatedAt(), sourceZone));
                parameters.add(sourceTimestamp(lower.updatedAt(), sourceZone));
                parameters.add(lower.recordId());
            }
            if (upper != null && !upper.isEmpty()) {
                sql.append(" AND ((").append(expressions.updatedAt()).append(" < ?) OR (")
                        .append(expressions.updatedAt()).append(" = ? AND ")
                        .append(expressions.recordId()).append(" <= ?))");
                parameters.add(sourceTimestamp(upper.updatedAt(), sourceZone));
                parameters.add(sourceTimestamp(upper.updatedAt(), sourceZone));
                parameters.add(upper.recordId());
            }
            return;
        }
        if (lower != null && !lower.isEmpty()) {
            sql.append(" AND ").append(expressions.recordId()).append(" > ?");
            parameters.add(lower.recordId());
        }
        if (upper != null && !upper.isEmpty()) {
            sql.append(" AND ").append(expressions.recordId()).append(" <= ?");
            parameters.add(upper.recordId());
        }
    }

    /** 配置有界读取，避免驱动一次拉取全部结果。 */
    private void configure(PreparedStatement statement, int maxRows) throws SQLException {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        statement.setFetchSize(maxRows);
        statement.setMaxRows(maxRows);
    }

    /** 依次绑定所有外部参数。 */
    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    /** 以小写固定别名读取当前行。 */
    private Map<String, Object> row(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            values.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT),
                    resultSet.getObject(index));
        }
        return values;
    }

    /** 从最后事件生成下一复合水位。 */
    private ForecastCursor cursor(ForecastCanonicalEvent event, ForecastSyncMode mode) {
        return new ForecastCursor(
                mode == ForecastSyncMode.INCREMENTAL ? event.getSourceUpdatedAt() : null,
                event.getSourceRecordId());
    }

    /** 将样例事件转换为不含内部类信息的 JSON 友好 Map。 */
    private Map<String, Object> sample(ForecastCanonicalEvent event) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sourceRecordId", event.getSourceRecordId());
        sample.put("taskId", event.getTaskId());
        sample.put("quantity", event.getQuantity());
        sample.put("occurredAt", event.getOccurredAt());
        sample.put("sourceUpdatedAt", event.getSourceUpdatedAt());
        sample.put("warehouseCode", event.getWarehouseCode());
        sample.put("direction", event.getDirection());
        sample.put("sourceStatus", event.getSourceStatus());
        sample.put("canonicalStatus", event.getCanonicalStatus());
        sample.put("deleted", event.isDeleted());
        return sample;
    }

    /** 读取 catalog 列表。 */
    private List<String> readCatalogs(DatabaseMetaData metadata) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet resultSet = metadata.getCatalogs()) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return List.copyOf(values);
    }

    /** 读取 schema 列表，驱动不支持 catalog 过滤时自动回退。 */
    private List<String> readSchemas(DatabaseMetaData metadata, String catalog)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet resultSet = metadata.getSchemas(blankToNull(catalog), null)) {
            while (resultSet.next()) {
                values.add(resultSet.getString("TABLE_SCHEM"));
            }
        }
        return List.copyOf(values);
    }

    /** 按选中表一次性读取列，避免列表页 N+1。 */
    private List<ColumnMetadata> readColumns(DatabaseMetaData metadata, String catalog,
            String schema, String table) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet resultSet = metadata.getColumns(catalog, schema, table, null)) {
            while (resultSet.next()) {
                columns.add(new ColumnMetadata(resultSet.getString("COLUMN_NAME"),
                        resultSet.getInt("DATA_TYPE"), resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
        }
        return List.copyOf(columns);
    }

    /**
     * 将常见 JDBC 时间对象转换为 Instant。
     *
     * <p>
     * 无时区的 JDBC 时间必须沿用映射声明的源库时区，否则上界游标会与标准化事件的游标产生 偏移，进而导致同一页重复或漏读。
     * </p>
     */
    private Instant instant(Object raw, ZoneId sourceZone) {
        if (raw instanceof Instant value) {
            return value;
        }
        if (raw instanceof Timestamp value) {
            return value.toLocalDateTime().atZone(sourceZone).toInstant();
        }
        if (raw instanceof OffsetDateTime value) {
            return value.toInstant();
        }
        if (raw instanceof LocalDateTime value) {
            return value.atZone(sourceZone).toInstant();
        }
        throw new IllegalArgumentException("sourceUpdatedAt 不是受支持的 JDBC 时间类型");
    }

    /** 将标准 Instant 转为源库声明时区下的无时区 JDBC 时间。 */
    private Timestamp sourceTimestamp(Instant value, ZoneId sourceZone) {
        return Timestamp.valueOf(value.atZone(sourceZone).toLocalDateTime());
    }

    /** 将空白参数转为 JDBC 元数据 API 所需的 null。 */
    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    /** 判断字符串是否有值。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Connector 内部固定的排序表达式集合。 */
    private record QueryExpressions(String recordId, String occurredAt, String updatedAt) {}
}
