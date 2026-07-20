package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastAlgorithmType;
import com.tencent.supersonic.forecast.api.enums.ForecastDataStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.response.ForecastBreakdownResp;
import com.tencent.supersonic.forecast.api.response.ForecastHealthResp;
import com.tencent.supersonic.forecast.api.response.ForecastOverviewSummaryResp;
import com.tencent.supersonic.forecast.api.response.ForecastSeriesPointResp;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWorkerNodeDO;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.Breakdown;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.OverviewPoint;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.OverviewSummary;
import org.springframework.stereotype.Service;

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
    private final ForecastProperties properties;

    /**
     * 创建看板服务。
     *
     * @param controlStore 控制面服务。
     * @param accessService ACL 服务。
     * @param databaseRegistry 决策库连接注册表。
     * @param decisionRepository 决策库查询层。
     * @param properties Forecast 配置。
     */
    public ForecastOverviewService(ForecastControlStore controlStore,
            ForecastAccessService accessService, ForecastDatabaseRegistry databaseRegistry,
            ForecastDecisionRepository decisionRepository, ForecastProperties properties) {
        this.controlStore = controlStore;
        this.accessService = accessService;
        this.databaseRegistry = databaseRegistry;
        this.decisionRepository = decisionRepository;
        this.properties = properties;
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
            return values.stream()
                    .map(value -> ForecastBreakdownResp.builder()
                            .warehouseCode(value.warehouseCode()).direction(value.direction())
                            .predictedTotal(value.predictedTotal()).dataStatus(value.dataStatus())
                            .algorithm(parseAlgorithm(value.algorithm())).wape(value.wape())
                            .mae(value.mae()).bias(value.bias()).build())
                    .toList();
        }
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
