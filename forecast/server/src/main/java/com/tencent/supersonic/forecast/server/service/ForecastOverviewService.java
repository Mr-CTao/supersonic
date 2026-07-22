package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastActualComparisonType;
import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastAnchorMode;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.response.ForecastBreakdownResp;
import com.tencent.supersonic.forecast.api.response.ForecastHealthResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSnapshotResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSummaryResp;
import com.tencent.supersonic.forecast.api.response.ForecastSeriesPointResp;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWorkerNodeDO;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.ActualDateRange;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.Breakdown;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.DailyActual;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.ForecastWindow;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.OverviewPoint;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.OverviewSummary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Forecast 看板和健康状态只读服务。
 *
 * <p>
 * 查询仅访问当前激活映射服务视图，API 的 7/14/30 天由同一组三十天结果裁剪，日常查询不触发 训练、同步、字典或向量索引更新。
 * </p>
 */
@Service
public class ForecastOverviewService {

    private static final Set<Integer> SUPPORTED_HORIZONS = Set.of(7, 14, 30);
    private static final int HISTORY_DAYS = 30;

    private final ForecastControlStore controlStore;
    private final ForecastAccessService accessService;
    private final ForecastDatabaseRegistry databaseRegistry;
    private final ForecastDecisionRepository decisionRepository;
    private final ForecastOverviewCalculator overviewCalculator;
    private final ForecastProperties properties;

    /**
     * 创建看板服务。
     *
     * @param controlStore 控制面服务。
     * @param accessService ACL 服务。
     * @param databaseRegistry 决策库连接注册表。
     * @param decisionRepository 决策库查询层。
     * @param overviewCalculator 历史回测与今日预览计算器。
     * @param properties Forecast 配置。
     */
    public ForecastOverviewService(ForecastControlStore controlStore,
            ForecastAccessService accessService, ForecastDatabaseRegistry databaseRegistry,
            ForecastDecisionRepository decisionRepository,
            ForecastOverviewCalculator overviewCalculator, ForecastProperties properties) {
        this.controlStore = controlStore;
        this.accessService = accessService;
        this.databaseRegistry = databaseRegistry;
        this.decisionRepository = decisionRepository;
        this.overviewCalculator = overviewCalculator;
        this.properties = properties;
    }

    /**
     * 查询指定预测基准下的一致性看板快照。
     *
     * <p>
     * 跟随数据模式优先复用锚点一致的正式发布结果；旧版本结果仍以“今天”为锚点时，临时使用 只读计算兜底，用户无需等待下一次定时预测即可看到历史数据尾部的效果。历史回测和今日模式
     * 始终只读计算，不改变发布指针。
     *
     * @param profileId Profile ID。
     * @param horizon 7、14 或 30 天。
     * @param metric 数量或任务数。
     * @param anchorMode 预测基准模式。
     * @param forecastStartDate 历史回测首日；其他模式可为空。
     * @param direction 可选的出入库方向；为空表示全部方向。
     * @param requestedTrainingStart 用户自定义训练起始日；必须与截止日同时提供。
     * @param requestedTrainingEnd 用户自定义训练截止日；必须与起始日同时提供。
     * @param user 当前用户。
     * @return 同一日期语义下的上下文、KPI、趋势和仓库拆分。
     * @throws SQLException 决策库查询失败。
     * @throws InvalidArgumentException 日期范围或模式参数非法。
     */
    public ForecastOverviewSnapshotResp snapshot(Long profileId, int horizon, ForecastMetric metric,
            ForecastAnchorMode anchorMode, LocalDate forecastStartDate, ForecastDirection direction,
            LocalDate requestedTrainingStart, LocalDate requestedTrainingEnd, User user)
            throws SQLException {
        ForecastProfileDO profile = requireVisibleProfile(profileId, horizon, user);
        if (anchorMode == null) {
            throw new InvalidArgumentException("预测基准不能为空");
        }
        boolean customTrainingWindow = ForecastTrainingWindowResolver
                .hasCustomWindow(requestedTrainingStart, requestedTrainingEnd);
        LocalDate today = LocalDate.now(ZoneId.of(profile.getTimeZone()));
        try (Connection connection =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            decisionRepository.validateSchema(connection);
            ActualDateRange dataRange =
                    decisionRepository.findActualDateRange(connection, profileId, direction, today);
            if (dataRange.latestDate() == null || dataRange.firstDate() == null) {
                connection.rollback();
                if (anchorMode != ForecastAnchorMode.LATEST_DATA) {
                    throw new InvalidArgumentException("当前统计口径尚无可用于预测的完整业务日数据");
                }
                return emptySnapshot(profile, metric, direction, horizon, today);
            }
            ForecastOverviewSnapshotResp response;
            if (anchorMode == ForecastAnchorMode.LATEST_DATA && direction == null
                    && !customTrainingWindow) {
                ForecastWindow window =
                        decisionRepository.findPublishedForecastWindow(connection, profileId);
                LocalDate expectedStart = dataRange.latestDate().plusDays(1);
                response = window != null && expectedStart.equals(window.forecastStart())
                        ? publishedSnapshot(connection, profile, metric, horizon, dataRange, window,
                                today)
                        : previewSnapshot(connection, profile, metric, horizon, anchorMode,
                                expectedStart, direction, requestedTrainingStart,
                                requestedTrainingEnd, dataRange, today);
            } else {
                response = previewSnapshot(connection, profile, metric, horizon, anchorMode,
                        forecastStartDate, direction, requestedTrainingStart, requestedTrainingEnd,
                        dataRange, today);
            }
            connection.rollback();
            return response;
        }
    }

    /** 使用正式发布视图构建跟随数据快照。 */
    private ForecastOverviewSnapshotResp publishedSnapshot(Connection connection,
            ForecastProfileDO profile, ForecastMetric metric, int horizon,
            ActualDateRange dataRange, ForecastWindow window, LocalDate today) throws SQLException {
        OverviewSummary summary = decisionRepository.findOverviewSummary(connection,
                profile.getId(), metric, horizon, window.forecastStart());
        List<ForecastSeriesPointResp> series = decisionRepository
                .findOverviewSeries(connection, profile.getId(), metric, horizon, HISTORY_DAYS,
                        window.forecastStart())
                .stream().map(value -> new ForecastSeriesPointResp(value.date(), value.actual(),
                        value.forecast(), value.lower(), value.upper()))
                .toList();
        List<ForecastBreakdownResp> breakdown = toBreakdown(
                decisionRepository.findBreakdown(connection, profile.getId(), metric, horizon));
        LocalDate actualStart = window.forecastStart().minusDays(horizon);
        LocalDate actualEnd = window.forecastStart().minusDays(1);
        return ForecastOverviewSnapshotResp.builder().profileId(profile.getId()).metric(metric)
                .direction(null).horizon(horizon).anchorMode(ForecastAnchorMode.LATEST_DATA)
                .actualComparisonType(ForecastActualComparisonType.PREVIOUS_PERIOD)
                .dataStartDate(dataRange.firstDate()).latestActualDate(dataRange.latestDate())
                .trainingStartDate(window.trainingStart()).trainingEndDate(window.trainingEnd())
                .forecastStartDate(window.forecastStart())
                .forecastEndDate(window.forecastStart().plusDays(horizon - 1L))
                .actualStartDate(actualStart).actualEndDate(actualEnd)
                .businessDataLagDays(
                        Math.max(0L, ChronoUnit.DAYS.between(dataRange.latestDate(), today)))
                .predictedTotal(summary.predictedTotal()).actualTotal(summary.previousActualTotal())
                .dataStatus(parseStatus(summary.dataStatus()))
                .algorithm(parseAlgorithm(summary.algorithm())).wape(summary.wape())
                .mae(summary.mae()).bias(summary.bias())
                .lastSyncAt(toInstant(profile.getLastSyncAt()))
                .lastForecastAt(toInstant(profile.getLastForecastAt())).series(series)
                .breakdown(breakdown).build();
    }

    /** 解析日期窗口并调用无状态计算器生成非持久化预览。 */
    private ForecastOverviewSnapshotResp previewSnapshot(Connection connection,
            ForecastProfileDO profile, ForecastMetric metric, int horizon,
            ForecastAnchorMode anchorMode, LocalDate requestedStart, ForecastDirection direction,
            LocalDate requestedTrainingStart, LocalDate requestedTrainingEnd,
            ActualDateRange dataRange, LocalDate today) throws SQLException {
        LocalDate forecastStart = switch (anchorMode) {
            case LATEST_DATA -> dataRange.latestDate().plusDays(1);
            case TODAY -> today;
            case BACKTEST -> {
                if (requestedStart == null) {
                    throw new InvalidArgumentException("请选择历史回测的预测起始日");
                }
                yield requestedStart;
            }
        };
        LocalDate forecastEnd = forecastStart.plusDays(horizon - 1L);
        if (anchorMode == ForecastAnchorMode.BACKTEST
                && forecastEnd.isAfter(dataRange.latestDate())) {
            throw new InvalidArgumentException("回测区间必须全部落在已有实际数据范围内");
        }
        ForecastTrainingWindowResolver.TrainingWindow trainingWindow =
                ForecastTrainingWindowResolver.resolve(dataRange, forecastStart,
                        profile.getHistoryDays(), requestedTrainingStart, requestedTrainingEnd);
        LocalDate trainingStart = trainingWindow.startInclusive();
        LocalDate trainingEnd = trainingWindow.endInclusive();
        ForecastActualComparisonType comparisonType = anchorMode == ForecastAnchorMode.BACKTEST
                ? ForecastActualComparisonType.FORECAST_PERIOD
                : ForecastActualComparisonType.PREVIOUS_PERIOD;
        LocalDate actualStart =
                comparisonType == ForecastActualComparisonType.FORECAST_PERIOD ? forecastStart
                        : trainingEnd.minusDays(horizon - 1L);
        LocalDate actualEnd =
                comparisonType == ForecastActualComparisonType.FORECAST_PERIOD ? forecastEnd
                        : trainingEnd;
        LocalDate queryEnd = comparisonType == ForecastActualComparisonType.FORECAST_PERIOD
                ? forecastEnd.plusDays(1)
                : trainingEnd.plusDays(1);
        List<DailyActual> actuals = decisionRepository.findDailyActuals(connection, profile.getId(),
                direction, trainingStart, queryEnd);
        return overviewCalculator.calculate(new ForecastOverviewCalculator.Input(profile.getId(),
                metric, direction, horizon, anchorMode, comparisonType, dataRange.firstDate(),
                dataRange.latestDate(), trainingStart, trainingEnd, forecastStart, forecastEnd,
                actualStart, actualEnd, today, toInstant(profile.getLastSyncAt()),
                toInstant(profile.getLastForecastAt()), actuals));
    }

    /** 构建无完整业务日时的可解释空快照。 */
    private ForecastOverviewSnapshotResp emptySnapshot(ForecastProfileDO profile,
            ForecastMetric metric, ForecastDirection direction, int horizon, LocalDate today) {
        return ForecastOverviewSnapshotResp.builder().profileId(profile.getId()).metric(metric)
                .direction(direction).horizon(horizon).anchorMode(ForecastAnchorMode.LATEST_DATA)
                .actualComparisonType(ForecastActualComparisonType.PREVIOUS_PERIOD)
                .forecastStartDate(today).forecastEndDate(today.plusDays(horizon - 1L))
                .actualStartDate(today.minusDays(horizon)).actualEndDate(today.minusDays(1))
                .predictedTotal(BigDecimal.ZERO).actualTotal(BigDecimal.ZERO)
                .dataStatus(ForecastDataStatus.INSUFFICIENT_DATA)
                .lastSyncAt(toInstant(profile.getLastSyncAt()))
                .lastForecastAt(toInstant(profile.getLastForecastAt())).series(List.of())
                .breakdown(List.of()).build();
    }

    /**
     * 查询 KPI 汇总。
     *
     * @param profileId Profile ID。
     * @param horizon 7、14、30。
     * @param metric 数量或任务数。
     * @param user 当前用户。
     * @return 汇总响应。
     * @throws SQLException 决策库查询失败。
     */
    public ForecastOverviewSummaryResp summary(Long profileId, int horizon, ForecastMetric metric,
            User user) throws SQLException {
        ForecastProfileDO profile = requireVisibleProfile(profileId, horizon, user);
        LocalDate today = LocalDate.now(ZoneId.of(profile.getTimeZone()));
        try (Connection connection =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            decisionRepository.validateSchema(connection);
            OverviewSummary value = decisionRepository.findOverviewSummary(connection, profileId,
                    metric, horizon, today);
            connection.rollback();
            return ForecastOverviewSummaryResp.builder().profileId(profileId).metric(metric)
                    .horizon(horizon).predictedTotal(value.predictedTotal())
                    .previousActualTotal(value.previousActualTotal())
                    .dataStatus(parseStatus(value.dataStatus()))
                    .algorithm(parseAlgorithm(value.algorithm())).wape(value.wape())
                    .mae(value.mae()).bias(value.bias())
                    .lastSyncAt(toInstant(profile.getLastSyncAt()))
                    .lastForecastAt(toInstant(profile.getLastForecastAt())).build();
        }
    }

    /**
     * 查询历史实际和未来预测曲线。
     *
     * @param profileId Profile ID。
     * @param horizon 未来天数。
     * @param metric 指标。
     * @param user 当前用户。
     * @return 日期点。
     * @throws SQLException 查询失败。
     */
    public List<ForecastSeriesPointResp> series(Long profileId, int horizon, ForecastMetric metric,
            User user) throws SQLException {
        ForecastProfileDO profile = requireVisibleProfile(profileId, horizon, user);
        LocalDate today = LocalDate.now(ZoneId.of(profile.getTimeZone()));
        try (Connection connection =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            decisionRepository.validateSchema(connection);
            List<OverviewPoint> values = decisionRepository.findOverviewSeries(connection,
                    profileId, metric, horizon, HISTORY_DAYS, today);
            connection.rollback();
            return values.stream().map(value -> new ForecastSeriesPointResp(value.date(),
                    value.actual(), value.forecast(), value.lower(), value.upper())).toList();
        }
    }

    /**
     * 查询仓库/方向拆分和模型质量。
     *
     * @param profileId Profile ID。
     * @param horizon 未来天数。
     * @param metric 指标。
     * @param user 当前用户。
     * @return 拆分响应。
     * @throws SQLException 查询失败。
     */
    public List<ForecastBreakdownResp> breakdown(Long profileId, int horizon, ForecastMetric metric,
            User user) throws SQLException {
        ForecastProfileDO profile = requireVisibleProfile(profileId, horizon, user);
        try (Connection connection =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            decisionRepository.validateSchema(connection);
            List<Breakdown> values =
                    decisionRepository.findBreakdown(connection, profileId, metric, horizon);
            connection.rollback();
            return toBreakdown(values);
        }
    }

    /** 将仓储层拆分记录转换为稳定 API 响应。 */
    private List<ForecastBreakdownResp> toBreakdown(List<Breakdown> values) {
        return values.stream()
                .map(value -> ForecastBreakdownResp.builder().warehouseCode(value.warehouseCode())
                        .direction(value.direction()).predictedTotal(value.predictedTotal())
                        .dataStatus(value.dataStatus()).algorithm(parseAlgorithm(value.algorithm()))
                        .wape(value.wape()).mae(value.mae()).bias(value.bias()).build())
                .toList();
    }

    /**
     * 查询 Worker 心跳和 Profile 数据新鲜度。
     *
     * @param profileId Profile ID。
     * @param user 当前用户。
     * @return 健康状态。
     */
    public ForecastHealthResp health(Long profileId, User user) {
        ForecastProfileDO profile = controlStore.requireProfile(profileId);
        accessService.requireViewer(profile.getSourceDatabaseId(), user);
        List<ForecastWorkerNodeDO> workers = controlStore.healthyWorkers();
        Instant latestHeartbeat = workers.stream().map(ForecastWorkerNodeDO::getHeartbeatAt)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder())
                .map(Date::toInstant).orElse(null);
        Instant lastSync = toInstant(profile.getLastSyncAt());
        Instant threshold =
                Instant.now().minus(properties.getWorker().getStaleAfterHours(), ChronoUnit.HOURS);
        String freshness = lastSync == null ? "NEVER_SYNCED"
                : lastSync.isBefore(threshold) ? "STALE" : "FRESH";
        return ForecastHealthResp.builder().workerHealthy(!workers.isEmpty())
                .activeWorkers(workers.size()).latestHeartbeatAt(latestHeartbeat)
                .latestSyncAt(lastSync).latestForecastAt(toInstant(profile.getLastForecastAt()))
                .freshnessStatus(freshness).build();
    }

    /** 校验 ACL 和 horizon。 */
    private ForecastProfileDO requireVisibleProfile(Long profileId, int horizon, User user) {
        if (!SUPPORTED_HORIZONS.contains(horizon)) {
            throw new InvalidArgumentException("horizon 仅支持 7、14、30");
        }
        ForecastProfileDO profile = controlStore.requireProfile(profileId);
        accessService.requireViewer(profile.getSourceDatabaseId(), user);
        return profile;
    }

    /** 安全解析数据状态。 */
    private ForecastDataStatus parseStatus(String value) {
        return value == null ? ForecastDataStatus.INSUFFICIENT_DATA
                : ForecastDataStatus.valueOf(value);
    }

    /** 安全解析可空算法。 */
    private ForecastAlgorithmType parseAlgorithm(String value) {
        return value == null ? null : ForecastAlgorithmType.valueOf(value);
    }

    /** Date 转 Instant。 */
    private Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }
}
