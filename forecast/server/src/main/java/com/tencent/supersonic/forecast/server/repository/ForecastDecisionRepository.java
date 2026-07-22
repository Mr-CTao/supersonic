package com.tencent.supersonic.forecast.server.repository;

import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.model.ForecastCanonicalEvent;
import com.tencent.supersonic.forecast.api.model.ForecastModelSelection;
import com.tencent.supersonic.forecast.api.model.ForecastPoint;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PostgreSQL 决策库的唯一数据访问边界。
 *
 * <p>
 * 写入采用“持久化页批次 + staging + UPSERT + dirty-key 集合重算”。同一批次成功提交后重复执行
 * 会直接返回，因此元库水位更新失败时无需分布式事务也能安全重放。类本身只持有不可变 Schema 名称， 不复用 JDBC 连接，可由多个 Worker 线程并发调用。
 * </p>
 */
@Repository
public class ForecastDecisionRepository {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final int EXPECTED_SCHEMA_VERSION = 2;

    private final String schema;
    private final int queryTimeoutSeconds;

    /**
     * 创建决策库访问层。
     *
     * @param properties Forecast 配置。
     * @throws IllegalArgumentException Schema 名称不是安全标识符。
     */
    public ForecastDecisionRepository(ForecastProperties properties) {
        String configured = properties.getDecision().getSchema();
        if (configured == null || !SAFE_SCHEMA.matcher(configured).matches()) {
            throw new IllegalArgumentException("forecast.decision.schema 不是安全 PostgreSQL 标识符");
        }
        this.schema = '"' + configured + '"';
        this.queryTimeoutSeconds = properties.getDecision().getQueryTimeoutSeconds();
    }

    /**
     * 校验决策库 Schema 版本，不在生产启动时隐式建表。
     *
     * @param connection PostgreSQL 决策库连接。
     * @throws SQLException Schema 缺失或版本不匹配。
     */
    public void validateSchema(Connection connection) throws SQLException {
        String sql = "SELECT version FROM " + table("forecast_schema_version")
                + " ORDER BY version DESC LIMIT 1";
        try (PreparedStatement statement = prepare(connection, sql);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next() || resultSet.getInt(1) != EXPECTED_SCHEMA_VERSION) {
                throw new SQLException("Forecast 决策库 Schema 版本不匹配，期望版本 " + EXPECTED_SCHEMA_VERSION);
            }
        }
    }

    /**
     * 原子持久化一页标准事件并重算受影响聚合。
     *
     * <p>
     * 事务边界由本方法控制：成功时提交，任何异常均回滚。调用者应在返回后再推进元库水位。
     * </p>
     *
     * @param connection 可写决策库连接。
     * @param batchId 页级确定性批次 ID。
     * @param jobId Worker 任务 ID。
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param mappingId 映射版本记录 ID。
     * @param zone Profile 业务时区。
     * @param events 本页标准事件。
     * @return 写入与聚合统计；已成功批次返回 {@code replayed=true}。
     * @throws SQLException 决策库操作失败。
     */
    public IngestResult ingestPage(Connection connection, String batchId, Long jobId,
            Long profileId, Long streamId, Long mappingId, ZoneId zone,
            List<ForecastCanonicalEvent> events) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(zone, "zone");
        List<ForecastCanonicalEvent> safeEvents = events == null ? List.of() : events;
        try {
            if (isBatchSucceeded(connection, batchId)) {
                connection.rollback();
                return new IngestResult(0, 0, true);
            }
            insertBatch(connection, batchId, jobId, profileId, streamId, mappingId);
            stageEvents(connection, batchId, profileId, streamId, mappingId, zone, safeEvents);
            captureDirtyKeys(connection, batchId, profileId, streamId, mappingId);
            int written = mergeEvents(connection, batchId);
            int aggregated = recomputeDirtyAggregates(connection, batchId);
            completeBatch(connection, batchId, safeEvents.size(), written, aggregated);
            cleanupBatchWork(connection, batchId);
            connection.commit();
            return new IngestResult(written, aggregated, false);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        }
    }

    /**
     * 读取 Profile 当前已发布预测任务，用于版本切换失败补偿。
     *
     * @param connection 决策库只读连接。
     * @param profileId Profile ID。
     * @return 已发布任务 ID；尚无成功预测时为空。
     * @throws SQLException 查询失败。
     */
    public Long findPublishedForecastJob(Connection connection, Long profileId)
            throws SQLException {
        String sql = "SELECT job_id FROM " + table("forecast_publication") + " WHERE profile_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    /**
     * 读取决策服务视图当前使用的数据流映射。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @return 活动映射 ID；首次发布前为空。
     * @throws SQLException 查询失败。
     */
    public Long findActiveMapping(Connection connection, Long profileId, Long streamId)
            throws SQLException {
        String sql = "SELECT mapping_id FROM " + table("stream_activation")
                + " WHERE profile_id=? AND stream_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setLong(2, streamId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    /**
     * 为一次预测尝试清理同任务遗留的未发布模型；已发布任务表示上次在收口前崩溃，可直接复用。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param jobId 当前任务 ID。
     * @return true 表示应重新计算，false 表示该任务结果已经发布。
     * @throws SQLException 清理或提交失败。
     */
    public boolean prepareForecastJob(Connection connection, Long profileId, Long jobId)
            throws SQLException {
        try {
            if (Objects.equals(findPublishedForecastJob(connection, profileId), jobId)) {
                connection.rollback();
                return false;
            }
            String sql = "DELETE FROM " + table("model_run") + " WHERE profile_id=? AND job_id=?";
            try (PreparedStatement statement = prepare(connection, sql)) {
                statement.setLong(1, profileId);
                statement.setLong(2, jobId);
                statement.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        }
    }

    /**
     * 在一个决策库事务中同时切换活动映射与整批预测发布指针。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param mappingId 新映射 ID。
     * @param jobId 已完整生成预测的任务 ID。
     * @throws SQLException 任一写入失败时回滚整个切换。
     */
    public void activateMappingAndPublishForecast(Connection connection, Long profileId,
            Long streamId, Long mappingId, Long jobId) throws SQLException {
        try {
            writeActivation(connection, profileId, streamId, mappingId);
            writePublication(connection, profileId, jobId);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        }
    }

    /**
     * 原子发布普通预测任务的一整组仓库/方向结果。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param jobId 已完整生成预测的任务 ID。
     * @throws SQLException 发布失败并回滚。
     */
    public void publishForecastJob(Connection connection, Long profileId, Long jobId)
            throws SQLException {
        try {
            writePublication(connection, profileId, jobId);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        }
    }

    /**
     * 原子恢复之前的映射与预测发布指针；旧值为空时删除临时激活或发布记录。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param previousMappingId 旧映射 ID，可空。
     * @param previousForecastJobId 旧预测任务 ID，可空。
     * @throws SQLException 恢复失败。
     */
    public void restoreMappingAndPublication(Connection connection, Long profileId, Long streamId,
            Long previousMappingId, Long previousForecastJobId) throws SQLException {
        try {
            restoreActivation(connection, profileId, streamId, previousMappingId);
            restorePublication(connection, profileId, previousForecastJobId);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        }
    }

    /** 写入或替换数据流活动映射，不在内部提交事务。 */
    private void writeActivation(Connection connection, Long profileId, Long streamId,
            Long mappingId) throws SQLException {
        String sql = "INSERT INTO " + table("stream_activation")
                + " (profile_id, stream_id, mapping_id, activated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (profile_id, stream_id) DO UPDATE SET mapping_id=EXCLUDED.mapping_id, "
                + "activated_at=EXCLUDED.activated_at";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setLong(2, streamId);
            statement.setLong(3, mappingId);
            statement.executeUpdate();
        }
    }

    /** 写入或替换 Profile 级预测发布指针，不在内部提交事务。 */
    private void writePublication(Connection connection, Long profileId, Long jobId)
            throws SQLException {
        String sql = "INSERT INTO " + table("forecast_publication")
                + " (profile_id, job_id, published_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT (profile_id) DO UPDATE SET job_id=EXCLUDED.job_id, "
                + "published_at=EXCLUDED.published_at";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setLong(2, jobId);
            statement.executeUpdate();
        }
    }

    /** 恢复或删除活动映射，不在内部提交事务。 */
    private void restoreActivation(Connection connection, Long profileId, Long streamId,
            Long previousMappingId) throws SQLException {
        if (previousMappingId != null) {
            writeActivation(connection, profileId, streamId, previousMappingId);
            return;
        }
        String sql =
                "DELETE FROM " + table("stream_activation") + " WHERE profile_id=? AND stream_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setLong(2, streamId);
            statement.executeUpdate();
        }
    }

    /** 恢复或删除预测发布指针，不在内部提交事务。 */
    private void restorePublication(Connection connection, Long profileId, Long previousJobId)
            throws SQLException {
        if (previousJobId != null) {
            writePublication(connection, profileId, previousJobId);
            return;
        }
        String sql = "DELETE FROM " + table("forecast_publication") + " WHERE profile_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.executeUpdate();
        }
    }

    /**
     * 读取指定窗口内所有已激活仓库/方向的日实际量。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param startInclusive 开始日期。
     * @param endExclusive 结束日期。
     * @return 按仓库、方向、日期排序的日值。
     * @throws SQLException 查询失败。
     */
    public List<DailyActual> findDailyActuals(Connection connection, Long profileId,
            LocalDate startInclusive, LocalDate endExclusive) throws SQLException {
        return findDailyActuals(connection, profileId, null, startInclusive, endExclusive);
    }

    /**
     * 读取指定方向和窗口内已激活仓库的日实际量。
     *
     * <p>
     * 方向为空时保留原有“入库 + 出库”口径；指定方向时在数据库聚合前过滤，避免先加载全部 数据再在 JVM 中筛选造成不必要的传输与内存占用。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param direction 可选的出入库方向；为空表示全部方向。
     * @param startInclusive 开始日期。
     * @param endExclusive 结束日期。
     * @return 按仓库、方向、日期排序的日值。
     * @throws SQLException 查询失败。
     */
    public List<DailyActual> findDailyActuals(Connection connection, Long profileId,
            ForecastDirection direction, LocalDate startInclusive, LocalDate endExclusive)
            throws SQLException {
        String directionPredicate = direction == null ? "" : " AND direction=?";
        String sql =
                "SELECT business_date, warehouse_code, direction, SUM(quantity_sum) quantity_sum, "
                        + "SUM(task_count) task_count FROM " + table("v_forecast_daily_fact")
                        + " WHERE profile_id=?" + directionPredicate
                        + " AND business_date>=? AND business_date<? "
                        + "GROUP BY business_date, warehouse_code, direction "
                        + "ORDER BY warehouse_code, direction, business_date";
        try (PreparedStatement statement = prepare(connection, sql)) {
            int parameterIndex = 1;
            statement.setLong(parameterIndex++, profileId);
            if (direction != null) {
                statement.setString(parameterIndex++, direction.name());
            }
            statement.setDate(parameterIndex++, Date.valueOf(startInclusive));
            statement.setDate(parameterIndex, Date.valueOf(endExclusive));
            return readDailyActuals(statement);
        }
    }

    /**
     * 查询 Profile 在指定日期之前的实际数据边界。
     *
     * <p>
     * 结束日期使用开区间，正式预测可据此排除尚未完整结束的“今天”，避免将部分日数据当作 完整训练样本。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param endExclusive 业务日期开区间上界。
     * @return 最早和最新完整业务日；没有数据时两个字段均为空。
     * @throws SQLException 查询失败。
     */
    public ActualDateRange findActualDateRange(Connection connection, Long profileId,
            LocalDate endExclusive) throws SQLException {
        return findActualDateRange(connection, profileId, null, endExclusive);
    }

    /**
     * 查询 Profile 指定方向在目标日期之前的实际数据边界。
     *
     * <p>
     * 方向为空时返回全部方向的合并边界；指定方向时返回该方向自己的最早和最新完整业务日， 供训练区间控件与后端校验共同使用。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param direction 可选的出入库方向；为空表示全部方向。
     * @param endExclusive 业务日期开区间上界。
     * @return 最早和最新完整业务日；没有数据时两个字段均为空。
     * @throws SQLException 查询失败。
     */
    public ActualDateRange findActualDateRange(Connection connection, Long profileId,
            ForecastDirection direction, LocalDate endExclusive) throws SQLException {
        String directionPredicate = direction == null ? "" : " AND direction=?";
        String sql = "SELECT MIN(business_date), MAX(business_date) FROM "
                + table("v_forecast_daily_fact") + " WHERE profile_id=?" + directionPredicate
                + " AND business_date<?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            int parameterIndex = 1;
            statement.setLong(parameterIndex++, profileId);
            if (direction != null) {
                statement.setString(parameterIndex++, direction.name());
            }
            statement.setDate(parameterIndex, Date.valueOf(endExclusive));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                Date first = resultSet.getDate(1);
                Date latest = resultSet.getDate(2);
                return new ActualDateRange(first == null ? null : first.toLocalDate(),
                        latest == null ? null : latest.toLocalDate());
            }
        }
    }

    /**
     * 查询候选映射快照在指定日期之前的最新完整业务日。
     *
     * <p>
     * 首次激活映射时目标数据流尚未进入正式视图，因此必须以候选映射替换该流、其余流继续 使用当前激活映射，确保预测基准与随后发布的候选快照一致。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param candidateStreamId 候选数据流 ID。
     * @param candidateMappingId 候选映射 ID。
     * @param endExclusive 业务日期开区间上界。
     * @return 最新完整业务日；没有数据时为空。
     * @throws SQLException 查询失败。
     */
    public LocalDate findLatestActualDateForCandidate(Connection connection, Long profileId,
            Long candidateStreamId, Long candidateMappingId, LocalDate endExclusive)
            throws SQLException {
        String sql = "SELECT MAX(business_date) FROM (SELECT f.business_date FROM "
                + table("daily_fact") + " f JOIN " + table("stream_activation")
                + " a ON a.profile_id=f.profile_id AND a.stream_id=f.stream_id "
                + "AND a.mapping_id=f.mapping_id WHERE f.profile_id=? AND f.stream_id<>? "
                + "UNION ALL SELECT f.business_date FROM " + table("daily_fact")
                + " f WHERE f.profile_id=? AND f.stream_id=? AND f.mapping_id=?) selected_facts "
                + "WHERE business_date<?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setLong(2, candidateStreamId);
            statement.setLong(3, profileId);
            statement.setLong(4, candidateStreamId);
            statement.setLong(5, candidateMappingId);
            statement.setDate(6, Date.valueOf(endExclusive));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                Date latest = resultSet.getDate(1);
                return latest == null ? null : latest.toLocalDate();
            }
        }
    }

    /**
     * 查询当前发布任务使用的训练与预测日期窗口。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @return 发布窗口；尚未发布任何模型运行时为空。
     * @throws SQLException 查询失败。
     */
    public ForecastWindow findPublishedForecastWindow(Connection connection, Long profileId)
            throws SQLException {
        String sql = "SELECT MIN(m.training_start), MAX(m.training_end), "
                + "MIN(r.forecast_date), MAX(r.forecast_date) FROM " + table("model_run")
                + " m JOIN " + table("forecast_publication")
                + " p ON p.profile_id=m.profile_id AND p.job_id=m.job_id LEFT JOIN "
                + table("forecast_result") + " r ON r.model_run_id=m.id WHERE m.profile_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                Date trainingStartValue = resultSet.getDate(1);
                Date trainingEndValue = resultSet.getDate(2);
                if (trainingStartValue == null || trainingEndValue == null) {
                    return null;
                }
                LocalDate trainingStart = trainingStartValue.toLocalDate();
                LocalDate trainingEnd = trainingEndValue.toLocalDate();
                Date forecastStartValue = resultSet.getDate(3);
                Date forecastEndValue = resultSet.getDate(4);
                LocalDate forecastStart = forecastStartValue == null ? trainingEnd.plusDays(1)
                        : forecastStartValue.toLocalDate();
                LocalDate forecastEnd = forecastEndValue == null ? forecastStart.plusDays(29)
                        : forecastEndValue.toLocalDate();
                return new ForecastWindow(trainingStart, trainingEnd, forecastStart, forecastEnd);
            }
        }
    }

    /**
     * 使用候选映射替换目标数据流，其余数据流仍读取当前激活版本，供发布前训练使用。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param candidateStreamId 待切换数据流 ID。
     * @param candidateMappingId 已回填候选映射 ID。
     * @param startInclusive 开始日期。
     * @param endExclusive 结束日期。
     * @return 候选 Profile 快照下的聚合日值。
     * @throws SQLException 查询失败。
     */
    public List<DailyActual> findDailyActualsForCandidate(Connection connection, Long profileId,
            Long candidateStreamId, Long candidateMappingId, LocalDate startInclusive,
            LocalDate endExclusive) throws SQLException {
        String columns =
                "f.business_date, f.warehouse_code, f.direction, f.quantity_sum, " + "f.task_count";
        String sql = "SELECT business_date, warehouse_code, direction, "
                + "SUM(quantity_sum) quantity_sum, SUM(task_count) task_count FROM (SELECT "
                + columns + " FROM " + table("daily_fact") + " f JOIN " + table("stream_activation")
                + " a ON a.profile_id=f.profile_id AND a.stream_id=f.stream_id "
                + "AND a.mapping_id=f.mapping_id WHERE f.profile_id=? AND f.stream_id<>? "
                + "UNION ALL SELECT " + columns + " FROM " + table("daily_fact")
                + " f WHERE f.profile_id=? AND f.stream_id=? AND f.mapping_id=?) selected_facts "
                + "WHERE business_date>=? AND business_date<? "
                + "GROUP BY business_date, warehouse_code, direction "
                + "ORDER BY warehouse_code, direction, business_date";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setLong(2, candidateStreamId);
            statement.setLong(3, profileId);
            statement.setLong(4, candidateStreamId);
            statement.setLong(5, candidateMappingId);
            statement.setDate(6, Date.valueOf(startInclusive));
            statement.setDate(7, Date.valueOf(endExclusive));
            return readDailyActuals(statement);
        }
    }

    /** 将标准日值查询结果转换为不可变记录列表。 */
    private List<DailyActual> readDailyActuals(PreparedStatement statement) throws SQLException {
        List<DailyActual> values = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(new DailyActual(resultSet.getDate("business_date").toLocalDate(),
                        resultSet.getString("warehouse_code"),
                        ForecastDirection.valueOf(resultSet.getString("direction")),
                        resultSet.getBigDecimal("quantity_sum"), resultSet.getLong("task_count")));
            }
        }
        return List.copyOf(values);
    }

    /**
     * 保存一次序列选模及未来结果；模型运行历史只追加，不覆盖审计记录。
     *
     * @param connection 决策库连接。
     * @param jobId 任务 ID。
     * @param profileId Profile ID。
     * @param warehouseCode 仓库编码。
     * @param direction 出入库方向。
     * @param metric 指标。
     * @param trainingStart 训练起始日期。
     * @param trainingEnd 训练截止日期。
     * @param forecastStart 预测首日。
     * @param selection 选模结果。
     * @return 新模型运行 ID。
     * @throws SQLException 保存失败。
     */
    public long saveForecast(Connection connection, Long jobId, Long profileId,
            String warehouseCode, ForecastDirection direction, ForecastMetric metric,
            LocalDate trainingStart, LocalDate trainingEnd, LocalDate forecastStart,
            ForecastModelSelection selection) throws SQLException {
        try {
            long modelRunId = insertModelRun(connection, jobId, profileId, warehouseCode, direction,
                    metric, trainingStart, trainingEnd, selection);
            insertForecastPoints(connection, modelRunId, profileId, warehouseCode, direction,
                    metric, forecastStart, selection.points());
            connection.commit();
            return modelRunId;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        }
    }

    /**
     * 查询未来预测汇总和上一等长窗口实际量。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param metric 指标。
     * @param horizon 7、14 或 30 天。
     * @param today Profile 当前日期。
     * @return 看板汇总。
     * @throws SQLException 查询失败。
     */
    public OverviewSummary findOverviewSummary(Connection connection, Long profileId,
            ForecastMetric metric, int horizon, LocalDate today) throws SQLException {
        String forecastSql = "SELECT COALESCE(SUM(forecast_value),0) predicted_total, "
                + "CASE MIN(CASE data_status WHEN 'INSUFFICIENT_DATA' THEN 0 "
                + "WHEN 'LOW_CONFIDENCE' THEN 1 ELSE 2 END) WHEN 0 THEN 'INSUFFICIENT_DATA' "
                + "WHEN 1 THEN 'LOW_CONFIDENCE' WHEN 2 THEN 'READY' END data_status, "
                + "CASE WHEN COUNT(DISTINCT algorithm)=1 THEN MAX(algorithm) END algorithm, "
                + "AVG(wape) wape, " + "AVG(mae) mae, AVG(bias) bias FROM "
                + table("v_forecast_latest_result")
                + " WHERE profile_id=? AND metric=? AND horizon_day<=?";
        ForecastAggregate forecast;
        try (PreparedStatement statement = prepare(connection, forecastSql)) {
            statement.setLong(1, profileId);
            statement.setString(2, metric.name());
            statement.setInt(3, horizon);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                forecast = new ForecastAggregate(resultSet.getBigDecimal("predicted_total"),
                        resultSet.getString("data_status"), resultSet.getString("algorithm"),
                        resultSet.getBigDecimal("wape"), resultSet.getBigDecimal("mae"),
                        resultSet.getBigDecimal("bias"));
            }
        }
        String actualColumn = metric == ForecastMetric.QUANTITY ? "quantity_sum" : "task_count";
        String actualSql = "SELECT COALESCE(SUM(" + actualColumn + "),0) FROM "
                + table("v_forecast_daily_fact")
                + " WHERE profile_id=? AND business_date>=? AND business_date<?";
        BigDecimal actual;
        try (PreparedStatement statement = prepare(connection, actualSql)) {
            statement.setLong(1, profileId);
            statement.setDate(2, Date.valueOf(today.minusDays(horizon)));
            statement.setDate(3, Date.valueOf(today));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                actual = resultSet.getBigDecimal(1);
            }
        }
        return new OverviewSummary(forecast.predictedTotal(), actual, forecast.dataStatus(),
                forecast.algorithm(), forecast.wape(), forecast.mae(), forecast.bias());
    }

    /**
     * 查询看板实际/预测/区间时间序列。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param metric 指标。
     * @param horizon 未来天数。
     * @param historyDays 历史展示天数。
     * @param today Profile 当前日期。
     * @return 日期点列表。
     * @throws SQLException 查询失败。
     */
    public List<OverviewPoint> findOverviewSeries(Connection connection, Long profileId,
            ForecastMetric metric, int horizon, int historyDays, LocalDate today)
            throws SQLException {
        List<OverviewPoint> points = new ArrayList<>();
        String actualColumn = metric == ForecastMetric.QUANTITY ? "quantity_sum" : "task_count";
        String actualSql = "SELECT business_date, SUM(" + actualColumn + ") actual_value FROM "
                + table("v_forecast_daily_fact")
                + " WHERE profile_id=? AND business_date>=? AND business_date<? "
                + "GROUP BY business_date ORDER BY business_date";
        try (PreparedStatement statement = prepare(connection, actualSql)) {
            statement.setLong(1, profileId);
            statement.setDate(2, Date.valueOf(today.minusDays(historyDays)));
            statement.setDate(3, Date.valueOf(today));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    points.add(new OverviewPoint(resultSet.getDate(1).toLocalDate(),
                            resultSet.getBigDecimal(2), null, null, null));
                }
            }
        }
        String forecastSql = "SELECT forecast_date, SUM(forecast_value), "
                + "CASE WHEN COUNT(lower_value)=COUNT(*) THEN SUM(lower_value) END, "
                + "CASE WHEN COUNT(upper_value)=COUNT(*) THEN SUM(upper_value) END FROM "
                + table("v_forecast_latest_result")
                + " WHERE profile_id=? AND metric=? AND horizon_day<=? "
                + "GROUP BY forecast_date ORDER BY forecast_date";
        try (PreparedStatement statement = prepare(connection, forecastSql)) {
            statement.setLong(1, profileId);
            statement.setString(2, metric.name());
            statement.setInt(3, horizon);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    points.add(new OverviewPoint(resultSet.getDate(1).toLocalDate(), null,
                            resultSet.getBigDecimal(2), resultSet.getBigDecimal(3),
                            resultSet.getBigDecimal(4)));
                }
            }
        }
        return List.copyOf(points);
    }

    /**
     * 查询仓库与方向预测拆分。
     *
     * @param connection 决策库连接。
     * @param profileId Profile ID。
     * @param metric 指标。
     * @param horizon 未来天数。
     * @return 拆分结果。
     * @throws SQLException 查询失败。
     */
    public List<Breakdown> findBreakdown(Connection connection, Long profileId,
            ForecastMetric metric, int horizon) throws SQLException {
        String sql = "SELECT warehouse_code, direction, SUM(forecast_value) predicted_total, "
                + "MAX(data_status) data_status, MAX(algorithm) algorithm, AVG(wape) wape, "
                + "AVG(mae) mae, AVG(bias) bias FROM " + table("v_forecast_latest_result")
                + " WHERE profile_id=? AND metric=? AND horizon_day<=? "
                + "GROUP BY warehouse_code, direction ORDER BY predicted_total DESC";
        List<Breakdown> values = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setLong(1, profileId);
            statement.setString(2, metric.name());
            statement.setInt(3, horizon);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(new Breakdown(resultSet.getString("warehouse_code"),
                            ForecastDirection.valueOf(resultSet.getString("direction")),
                            resultSet.getBigDecimal("predicted_total"),
                            parseStatus(resultSet.getString("data_status")),
                            resultSet.getString("algorithm"), resultSet.getBigDecimal("wape"),
                            resultSet.getBigDecimal("mae"), resultSet.getBigDecimal("bias")));
                }
            }
        }
        return List.copyOf(values);
    }

    /** 判断批次是否已经完整提交。 */
    private boolean isBatchSucceeded(Connection connection, String batchId) throws SQLException {
        String sql = "SELECT status FROM " + table("sync_batch") + " WHERE batch_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setString(1, batchId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "SUCCEEDED".equals(resultSet.getString(1));
            }
        }
    }

    /** 创建页批次，重试时复用已有非成功记录。 */
    private void insertBatch(Connection connection, String batchId, Long jobId, Long profileId,
            Long streamId, Long mappingId) throws SQLException {
        String sql = "INSERT INTO " + table("sync_batch")
                + " (batch_id, job_id, profile_id, stream_id, mapping_id, status, started_at) "
                + "VALUES (?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP) ON CONFLICT (batch_id) "
                + "DO UPDATE SET status='RUNNING', started_at=CURRENT_TIMESTAMP, completed_at=NULL";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setString(1, batchId);
            statement.setLong(2, jobId);
            statement.setLong(3, profileId);
            statement.setLong(4, streamId);
            statement.setLong(5, mappingId);
            statement.executeUpdate();
        }
    }

    /** 批量写 staging；批内不逐条查询。 */
    private void stageEvents(Connection connection, String batchId, Long profileId, Long streamId,
            Long mappingId, ZoneId zone, List<ForecastCanonicalEvent> events) throws SQLException {
        String sql = "INSERT INTO " + table("event_stage")
                + " (batch_id, profile_id, stream_id, mapping_id, source_record_id, task_id, "
                + "direction, warehouse_code, quantity, occurred_at, business_date, source_status, "
                + "canonical_status, source_updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (batch_id, source_record_id) DO UPDATE SET task_id=EXCLUDED.task_id, "
                + "direction=EXCLUDED.direction, warehouse_code=EXCLUDED.warehouse_code, "
                + "quantity=EXCLUDED.quantity, occurred_at=EXCLUDED.occurred_at, "
                + "business_date=EXCLUDED.business_date, source_status=EXCLUDED.source_status, "
                + "canonical_status=EXCLUDED.canonical_status, source_updated_at=EXCLUDED.source_updated_at, "
                + "deleted=EXCLUDED.deleted";
        try (PreparedStatement statement = prepare(connection, sql)) {
            for (ForecastCanonicalEvent event : events) {
                int index = 1;
                statement.setString(index++, batchId);
                statement.setLong(index++, profileId);
                statement.setLong(index++, streamId);
                statement.setLong(index++, mappingId);
                statement.setString(index++, event.getSourceRecordId());
                statement.setString(index++, event.getTaskId());
                statement.setString(index++, event.getDirection().name());
                statement.setString(index++, event.getWarehouseCode());
                statement.setBigDecimal(index++, event.getQuantity());
                statement.setTimestamp(index++, Timestamp.from(event.getOccurredAt()));
                statement.setDate(index++,
                        Date.valueOf(event.getOccurredAt().atZone(zone).toLocalDate()));
                statement.setString(index++, event.getSourceStatus());
                statement.setString(index++, event.getCanonicalStatus().name());
                if (event.getSourceUpdatedAt() == null) {
                    statement.setTimestamp(index++, null);
                } else {
                    statement.setTimestamp(index++, Timestamp.from(event.getSourceUpdatedAt()));
                }
                statement.setBoolean(index, event.isDeleted());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 同时捕获更新前旧键和 staging 新键，保证日期/仓库/方向变化都能修正。 */
    private void captureDirtyKeys(Connection connection, String batchId, Long profileId,
            Long streamId, Long mappingId) throws SQLException {
        String oldSql = "INSERT INTO " + table("aggregate_dirty")
                + " (batch_id, profile_id, stream_id, mapping_id, business_date, warehouse_code, direction) "
                + "SELECT ?, e.profile_id, e.stream_id, e.mapping_id, e.business_date, e.warehouse_code, e.direction "
                + "FROM " + table("flow_event") + " e JOIN " + table("event_stage")
                + " s ON s.batch_id=? AND e.profile_id=s.profile_id AND e.stream_id=s.stream_id "
                + "AND e.mapping_id=s.mapping_id AND e.source_record_id=s.source_record_id "
                + "ON CONFLICT DO NOTHING";
        try (PreparedStatement statement = prepare(connection, oldSql)) {
            statement.setString(1, batchId);
            statement.setString(2, batchId);
            statement.executeUpdate();
        }
        String newSql = "INSERT INTO " + table("aggregate_dirty")
                + " (batch_id, profile_id, stream_id, mapping_id, business_date, warehouse_code, direction) "
                + "SELECT ?, profile_id, stream_id, mapping_id, business_date, warehouse_code, direction FROM "
                + table("event_stage") + " WHERE batch_id=? ON CONFLICT DO NOTHING";
        try (PreparedStatement statement = prepare(connection, newSql)) {
            statement.setString(1, batchId);
            statement.setString(2, batchId);
            statement.executeUpdate();
        }
    }

    /** 集合式 UPSERT 标准事件。 */
    private int mergeEvents(Connection connection, String batchId) throws SQLException {
        String sql = "INSERT INTO " + table("flow_event") + " AS target"
                + " (profile_id, stream_id, mapping_id, source_record_id, task_id, direction, "
                + "warehouse_code, quantity, occurred_at, business_date, source_status, canonical_status, "
                + "source_updated_at, deleted, ingested_at) SELECT profile_id, stream_id, mapping_id, "
                + "source_record_id, task_id, direction, warehouse_code, quantity, occurred_at, business_date, "
                + "source_status, canonical_status, source_updated_at, deleted, CURRENT_TIMESTAMP FROM "
                + table("event_stage") + " WHERE batch_id=? ON CONFLICT "
                + "(profile_id, stream_id, mapping_id, source_record_id) DO UPDATE SET "
                + "task_id=EXCLUDED.task_id, direction=EXCLUDED.direction, warehouse_code=EXCLUDED.warehouse_code, "
                + "quantity=EXCLUDED.quantity, occurred_at=EXCLUDED.occurred_at, business_date=EXCLUDED.business_date, "
                + "source_status=EXCLUDED.source_status, canonical_status=EXCLUDED.canonical_status, "
                + "source_updated_at=EXCLUDED.source_updated_at, deleted=EXCLUDED.deleted, "
                + "ingested_at=CURRENT_TIMESTAMP WHERE target.source_updated_at IS NULL "
                + "OR EXCLUDED.source_updated_at IS NULL "
                + "OR EXCLUDED.source_updated_at >= target.source_updated_at";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setString(1, batchId);
            return statement.executeUpdate();
        }
    }

    /** 对 dirty key 先删后插，集合式修正聚合且不产生 N+1。 */
    private int recomputeDirtyAggregates(Connection connection, String batchId)
            throws SQLException {
        String deleteSql = "DELETE FROM " + table("daily_fact") + " f USING "
                + table("aggregate_dirty") + " d WHERE d.batch_id=? AND f.profile_id=d.profile_id "
                + "AND f.stream_id=d.stream_id AND f.mapping_id=d.mapping_id "
                + "AND f.business_date=d.business_date AND f.warehouse_code=d.warehouse_code "
                + "AND f.direction=d.direction";
        try (PreparedStatement statement = prepare(connection, deleteSql)) {
            statement.setString(1, batchId);
            statement.executeUpdate();
        }
        String insertSql = "INSERT INTO " + table("daily_fact")
                + " (profile_id, stream_id, mapping_id, business_date, warehouse_code, direction, "
                + "quantity_sum, task_count, updated_at) SELECT e.profile_id, e.stream_id, e.mapping_id, "
                + "e.business_date, e.warehouse_code, e.direction, COALESCE(SUM(e.quantity),0), "
                + "COUNT(DISTINCT e.task_id), CURRENT_TIMESTAMP FROM " + table("flow_event")
                + " e JOIN " + table("aggregate_dirty")
                + " d ON d.batch_id=? AND e.profile_id=d.profile_id AND e.stream_id=d.stream_id "
                + "AND e.mapping_id=d.mapping_id AND e.business_date=d.business_date "
                + "AND e.warehouse_code=d.warehouse_code AND e.direction=d.direction "
                + "WHERE e.deleted=FALSE AND e.canonical_status='COMPLETED' GROUP BY e.profile_id, "
                + "e.stream_id, e.mapping_id, e.business_date, e.warehouse_code, e.direction";
        try (PreparedStatement statement = prepare(connection, insertSql)) {
            statement.setString(1, batchId);
            return statement.executeUpdate();
        }
    }

    /** 标记批次成功。 */
    private void completeBatch(Connection connection, String batchId, int read, int written,
            int aggregated) throws SQLException {
        String sql = "UPDATE " + table("sync_batch")
                + " SET status='SUCCEEDED', rows_read=?, rows_written=?, rows_aggregated=?, "
                + "completed_at=CURRENT_TIMESTAMP WHERE batch_id=?";
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setInt(1, read);
            statement.setInt(2, written);
            statement.setInt(3, aggregated);
            statement.setString(4, batchId);
            statement.executeUpdate();
        }
    }

    /** 清理可重建 staging/dirty 工作集，批次审计和标准事件永久保留。 */
    private void cleanupBatchWork(Connection connection, String batchId) throws SQLException {
        for (String tableName : List.of("event_stage", "aggregate_dirty")) {
            try (PreparedStatement statement =
                    prepare(connection, "DELETE FROM " + table(tableName) + " WHERE batch_id=?")) {
                statement.setString(1, batchId);
                statement.executeUpdate();
            }
        }
    }

    /** 插入模型运行并返回数据库生成 ID。 */
    private long insertModelRun(Connection connection, Long jobId, Long profileId,
            String warehouseCode, ForecastDirection direction, ForecastMetric metric,
            LocalDate trainingStart, LocalDate trainingEnd, ForecastModelSelection selection)
            throws SQLException {
        String sql = "INSERT INTO " + table("model_run")
                + " (job_id, profile_id, warehouse_code, direction, metric, algorithm, data_status, "
                + "training_start, training_end, training_size, validation_size, wape, mae, bias, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            int index = 1;
            statement.setLong(index++, jobId);
            statement.setLong(index++, profileId);
            statement.setString(index++, warehouseCode);
            statement.setString(index++, direction.name());
            statement.setString(index++, metric.name());
            statement.setString(index++,
                    selection.algorithm() == null ? null : selection.algorithm().name());
            statement.setString(index++, selection.dataStatus().name());
            statement.setDate(index++, Date.valueOf(trainingStart));
            statement.setDate(index++, Date.valueOf(trainingEnd));
            statement.setInt(index++, selection.trainingSize());
            statement.setInt(index++, selection.validationSize());
            statement.setBigDecimal(index++, selection.wape());
            statement.setBigDecimal(index++, selection.mae());
            statement.setBigDecimal(index, selection.bias());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("决策库未返回 model_run ID");
                }
                return keys.getLong(1);
            }
        }
    }

    /** 批量插入未来预测点；数据不足时 points 为空。 */
    private void insertForecastPoints(Connection connection, long modelRunId, Long profileId,
            String warehouseCode, ForecastDirection direction, ForecastMetric metric,
            LocalDate forecastStart, List<ForecastPoint> points) throws SQLException {
        String sql = "INSERT INTO " + table("forecast_result")
                + " (model_run_id, profile_id, warehouse_code, direction, metric, forecast_date, "
                + "horizon_day, forecast_value, lower_value, upper_value, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = prepare(connection, sql)) {
            for (ForecastPoint point : points) {
                statement.setLong(1, modelRunId);
                statement.setLong(2, profileId);
                statement.setString(3, warehouseCode);
                statement.setString(4, direction.name());
                statement.setString(5, metric.name());
                statement.setDate(6, Date.valueOf(forecastStart.plusDays(point.offset() - 1L)));
                statement.setInt(7, point.offset());
                statement.setBigDecimal(8, point.point());
                statement.setBigDecimal(9, point.lower());
                statement.setBigDecimal(10, point.upper());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 创建带统一超时的参数化语句。 */
    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    /** 返回引用安全 Schema 的表名。 */
    private String table(String name) {
        return schema + ".\"" + name + "\"";
    }

    /** 安全解析可空数据状态。 */
    private ForecastDataStatus parseStatus(String value) {
        return value == null ? ForecastDataStatus.INSUFFICIENT_DATA
                : ForecastDataStatus.valueOf(value);
    }

    /** 页级写入结果。 */
    public record IngestResult(int rowsWritten, int rowsAggregated, boolean replayed) {}

    /** 训练和看板复用的日实际值。 */
    public record DailyActual(LocalDate date, String warehouseCode, ForecastDirection direction,
            BigDecimal quantity, long taskCount) {}

    /** Profile 当前激活实际数据的日期边界。 */
    public record ActualDateRange(LocalDate firstDate, LocalDate latestDate) {}

    /** 当前发布任务的训练与预测窗口。 */
    public record ForecastWindow(LocalDate trainingStart, LocalDate trainingEnd,
            LocalDate forecastStart, LocalDate forecastEnd) {}

    /** 看板汇总结果。 */
    public record OverviewSummary(BigDecimal predictedTotal, BigDecimal previousActualTotal,
            String dataStatus, String algorithm, BigDecimal wape, BigDecimal mae,
            BigDecimal bias) {}

    /** 看板日期点。 */
    public record OverviewPoint(LocalDate date, BigDecimal actual, BigDecimal forecast,
            BigDecimal lower, BigDecimal upper) {}

    /** 仓库/方向拆分。 */
    public record Breakdown(String warehouseCode, ForecastDirection direction,
            BigDecimal predictedTotal, ForecastDataStatus dataStatus, String algorithm,
            BigDecimal wape, BigDecimal mae, BigDecimal bias) {}

    /** 查询内部的预测聚合。 */
    private record ForecastAggregate(BigDecimal predictedTotal, String dataStatus, String algorithm,
            BigDecimal wape, BigDecimal mae, BigDecimal bias) {}
}
