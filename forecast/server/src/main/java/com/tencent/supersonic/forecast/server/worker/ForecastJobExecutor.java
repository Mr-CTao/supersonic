package com.tencent.supersonic.forecast.server.worker;

import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.enums.ForecastMetric;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastModelSelection;
import com.tencent.supersonic.forecast.api.model.ForecastPage;
import com.tencent.supersonic.forecast.api.model.ForecastReadContext;
import com.tencent.supersonic.forecast.api.model.ForecastSeries;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.api.spi.ForecastConnector;
import com.tencent.supersonic.forecast.core.algorithm.ForecastModelSelector;
import com.tencent.supersonic.forecast.core.connector.ForecastConnectorRegistry;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry.DatabaseDescriptor;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastMappingDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastStreamDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWatermarkDO;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.DailyActual;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.IngestResult;
import com.tencent.supersonic.forecast.server.service.ForecastControlStore;
import com.tencent.supersonic.forecast.server.util.ForecastJson;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Worker 中单个 Forecast 任务的同步、聚合、选模和发布执行器。
 *
 * <p>
 * 实例不保存运行状态；任务统计均为方法局部变量，可由固定线程池并发调用。每页先提交决策库， 再推进元库水位，避免跨库分布式事务。取消与租约丢失只在安全页边界生效。
 * </p>
 */
@Component
public class ForecastJobExecutor {

    private static final int FORECAST_HORIZON = 30;

    private final ForecastControlStore controlStore;
    private final ForecastDatabaseRegistry databaseRegistry;
    private final ForecastConnectorRegistry connectorRegistry;
    private final ForecastDecisionRepository decisionRepository;
    private final ForecastModelSelector modelSelector;
    private final ForecastProperties properties;
    private final ForecastJson json;

    /**
     * 创建任务执行器。
     *
     * @param controlStore 元数据库控制面。
     * @param databaseRegistry 数据库连接注册表。
     * @param connectorRegistry Connector 注册表。
     * @param decisionRepository 决策库访问层。
     * @param modelSelector Java 选模器。
     * @param properties Worker 配置。
     * @param json JSON 工具。
     */
    public ForecastJobExecutor(ForecastControlStore controlStore,
            ForecastDatabaseRegistry databaseRegistry, ForecastConnectorRegistry connectorRegistry,
            ForecastDecisionRepository decisionRepository, ForecastModelSelector modelSelector,
            ForecastProperties properties, ForecastJson json) {
        this.controlStore = controlStore;
        this.databaseRegistry = databaseRegistry;
        this.connectorRegistry = connectorRegistry;
        this.decisionRepository = decisionRepository;
        this.modelSelector = modelSelector;
        this.properties = properties;
        this.json = json;
    }

    /**
     * 计算任务所需跨进程资源键。
     *
     * @param job 任务快照。
     * @return 固定顺序资源键，避免不同任务反向获取形成死锁。
     */
    public List<String> resourceKeys(ForecastJobDO job) {
        ForecastProfileDO profile = controlStore.requireProfile(job.getProfileId());
        ForecastJobType type = ForecastJobType.valueOf(job.getJobType());
        List<String> keys = new ArrayList<>();
        if (type == ForecastJobType.INITIAL_SYNC || type == ForecastJobType.INCREMENTAL_SYNC
                || type == ForecastJobType.RECONCILE) {
            keys.add("SOURCE:" + profile.getSourceDatabaseId());
            // 同步逐页提交期间禁止同 Profile 预测读取中间态；统一顺序为 SOURCE -> PROFILE -> STREAM。
            keys.add("PROFILE:" + profile.getId());
            if (job.getStreamId() != null) {
                keys.add("STREAM:" + job.getStreamId());
            } else {
                controlStore.enabledStreams(profile.getId()).stream().map(ForecastStreamDO::getId)
                        .sorted().forEach(id -> keys.add("STREAM:" + id));
            }
        } else {
            keys.add("PROFILE:" + profile.getId());
        }
        return List.copyOf(keys);
    }

    /**
     * 执行已持有任务与资源租约的任务。
     *
     * @param job 任务快照。
     * @param workerId Worker ID。
     * @param shouldStop 租约丢失或用户取消判断。
     * @throws Exception 源库、决策库或配置失败；外层统一脱敏并更新任务状态。
     */
    public void execute(ForecastJobDO job, String workerId, BooleanSupplier shouldStop)
            throws Exception {
        ForecastJobType type = ForecastJobType.valueOf(job.getJobType());
        switch (type) {
            case INITIAL_SYNC -> executeInitialSync(job, workerId, shouldStop);
            case INCREMENTAL_SYNC -> executeSync(job, workerId, false, shouldStop);
            case RECONCILE -> executeSync(job, workerId, true, shouldStop);
            case FORECAST -> runForecast(job, workerId, shouldStop, null, null, true);
            case AGGREGATE -> {
                // 聚合已在每页事务内集合式修正，独立 AGGREGATE 作为显式对账后的兼容任务无额外写入。
                controlStore.updateProgress(job.getId(), workerId, 99, 0, 0, 0, null);
            }
            case PUBLISH_SEMANTIC_MODEL -> throw new InvalidArgumentException(
                    "语义模型发布需由 Standalone 管理端执行，Worker 不直接写元数据表");
        }
    }

    /** 首次同步完成后进行可回退的决策视图激活、预测和元库原子切换。 */
    private void executeInitialSync(ForecastJobDO job, String workerId, BooleanSupplier shouldStop)
            throws Exception {
        ForecastProfileDO profile = controlStore.requireProfile(job.getProfileId());
        ForecastStreamDO stream = controlStore.requireStream(profile.getId(), job.getStreamId());
        ForecastMappingDO mapping = controlStore.requireMapping(stream.getId(), job.getMappingId());
        syncOne(job, workerId, profile, stream, mapping, false, true, shouldStop);
        ensureRunning(shouldStop);
        Long previousMappingId;
        Long previousForecastJobId;
        try (Connection decision =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            // 决策服务视图以决策库发布指针为准，不能用可能尚未完成补偿的元库状态推断旧版本。
            previousMappingId =
                    decisionRepository.findActiveMapping(decision, profile.getId(), stream.getId());
            previousForecastJobId =
                    decisionRepository.findPublishedForecastJob(decision, profile.getId());
            decision.rollback();
        }
        try {
            // 候选映射尚不可见，先用候选快照生成整批未发布模型结果。
            runForecast(job, workerId, shouldStop, stream.getId(), mapping.getId(), false);
            ensureRunning(shouldStop);
            try (Connection decision =
                    databaseRegistry.openDecision(profile.getDecisionDatabaseId())) {
                decisionRepository.activateMappingAndPublishForecast(decision, profile.getId(),
                        stream.getId(), mapping.getId(), job.getId());
            }
            ensureRunning(shouldStop);
            ForecastProfileDO activationProfile = controlStore.requireProfile(profile.getId());
            ForecastStreamDO activationStream =
                    controlStore.requireStream(profile.getId(), stream.getId());
            if (!Boolean.TRUE.equals(activationProfile.getEnabled())
                    || !Boolean.TRUE.equals(activationStream.getEnabled())) {
                throw new IllegalStateException("Profile 或数据流已停用，候选映射不再激活");
            }
            // 页同步会刷新 Stream 并递增 lockVersion，激活必须使用提交后的最新快照执行 CAS。
            controlStore.activateMapping(activationProfile, activationStream, mapping, workerId);
        } catch (Exception exception) {
            // 决策库和元库不能组成分布式事务，因此失败时同时恢复旧映射与旧预测发布指针。
            try (Connection decision =
                    databaseRegistry.openDecision(profile.getDecisionDatabaseId())) {
                decisionRepository.restoreMappingAndPublication(decision, profile.getId(),
                        stream.getId(), previousMappingId, previousForecastJobId);
            } catch (SQLException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
    }

    /** 执行单流或 Profile 全流增量/对账同步。 */
    private void executeSync(ForecastJobDO job, String workerId, boolean reconcile,
            BooleanSupplier shouldStop) throws Exception {
        ForecastProfileDO profile = controlStore.requireProfile(job.getProfileId());
        List<ForecastStreamDO> streams =
                job.getStreamId() == null ? controlStore.enabledStreams(profile.getId())
                        : List.of(controlStore.requireStream(profile.getId(), job.getStreamId()));
        for (ForecastStreamDO stream : streams) {
            ensureRunning(shouldStop);
            Long mappingId =
                    job.getMappingId() != null ? job.getMappingId() : stream.getActiveMappingId();
            if (mappingId == null) {
                continue;
            }
            ForecastMappingDO mapping = controlStore.requireMapping(stream.getId(), mappingId);
            syncOne(job, workerId, profile, stream, mapping, reconcile, false, shouldStop);
        }
    }

    /**
     * 使用复合水位与固定上界同步一个数据流。
     *
     * <p>
     * 连接不跨线程共享；源库事务只读，决策库每页独立事务，保证大任务不会持有一个超长写事务。
     * </p>
     */
    private void syncOne(ForecastJobDO job, String workerId, ForecastProfileDO profile,
            ForecastStreamDO stream, ForecastMappingDO mapping, boolean reconcile, boolean initial,
            BooleanSupplier shouldStop) throws Exception {
        ForecastMappingConfig config =
                json.read(mapping.getConfigJson(), ForecastMappingConfig.class);
        DatabaseDescriptor source = databaseRegistry.describe(profile.getSourceDatabaseId());
        ForecastConnector connector = connectorRegistry.require(source.type());
        ZoneId zone = ZoneId.of(profile.getTimeZone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        ForecastJobReq parameters = json.read(job.getParametersJson(), ForecastJobReq.class);
        int requestedHistory = parameters.getHistoryDays() == null ? profile.getHistoryDays()
                : parameters.getHistoryDays();
        // 首次回填和对账必须遵循 Profile/任务指定的历史窗口；仅日常无水位增量任务使用较小的重扫窗口。
        // 否则没有更新时间字段的老系统会在首次接入时静默丢失 lookbackDays 之前的历史事实。
        int windowDays = initial || reconcile ? requestedHistory
                : config.getSyncMode() == ForecastSyncMode.SNAPSHOT_LOOKBACK
                        ? config.getLookbackDays()
                        : requestedHistory;
        Instant historyStart = now.minusDays(windowDays).toInstant();
        Instant historyEnd = now.plusDays(1).toLocalDate().atStartOfDay(zone).toInstant();
        try (Connection decision =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            // 即使源窗口为空也必须验证决策库，避免把未迁移的数据源误报为同步成功。
            decisionRepository.validateSchema(decision);
            decision.rollback();
        }
        ForecastWatermarkDO watermark =
                controlStore.getOrCreateWatermark(stream.getId(), mapping.getId());
        ForecastCursor lower =
                initial || reconcile || config.getSyncMode() == ForecastSyncMode.SNAPSHOT_LOOKBACK
                        ? ForecastCursor.empty()
                        : new ForecastCursor(toInstant(watermark.getWatermarkUpdatedAt()),
                                watermark.getWatermarkRecordId());
        long rowsRead = defaultLong(job.getRowsRead());
        long rowsWritten = defaultLong(job.getRowsWritten());
        long rowsAggregated = defaultLong(job.getRowsAggregated());
        int pageIndex = 0;
        try (Connection sourceConnection =
                databaseRegistry.openSource(profile.getSourceDatabaseId())) {
            ForecastCursor upper =
                    connector.captureUpperBound(sourceConnection, config, historyStart, historyEnd);
            while (!upper.isEmpty()) {
                ensureRunning(shouldStop);
                ForecastPage page = connector.readPage(sourceConnection, config,
                        new ForecastReadContext(historyStart, historyEnd, lower, upper,
                                properties.getWorker().getBatchSize()));
                if (page.events().isEmpty()) {
                    break;
                }
                String batchId = batchId(job.getId(), mapping.getId(), lower, page.nextCursor());
                IngestResult result;
                try (Connection decision =
                        databaseRegistry.openDecision(profile.getDecisionDatabaseId())) {
                    decisionRepository.validateSchema(decision);
                    result = decisionRepository.ingestPage(decision, batchId, job.getId(),
                            profile.getId(), stream.getId(), mapping.getId(), zone, page.events());
                }
                // 决策库提交已完成；即使下一行失败，重跑相同 batchId 也不会产生重复事实。
                controlStore.advanceWatermark(stream.getId(), mapping.getId(), page.nextCursor(),
                        batchId);
                rowsRead += page.events().size();
                rowsWritten += result.rowsWritten();
                rowsAggregated += result.rowsAggregated();
                pageIndex++;
                int progress = Math.min(90, 5 + pageIndex * 5);
                if (!controlStore.updateProgress(job.getId(), workerId, progress, rowsRead,
                        rowsWritten, rowsAggregated, page.nextCursor())) {
                    throw new IllegalStateException("任务租约已丢失，停止推进水位");
                }
                lower = page.nextCursor();
                if (page.exhausted()) {
                    break;
                }
            }
            sourceConnection.rollback();
        }
        if (!initial) {
            controlStore.touchSync(profile.getId(), stream.getId());
        }
    }

    /** 按已激活或候选映射快照补零、选模，并在完整成功后选择性发布整批结果。 */
    private void runForecast(ForecastJobDO job, String workerId, BooleanSupplier shouldStop,
            Long candidateStreamId, Long candidateMappingId, boolean publishAtEnd)
            throws Exception {
        ForecastProfileDO profile = controlStore.requireProfile(job.getProfileId());
        boolean shouldCalculate;
        try (Connection decision = databaseRegistry.openDecision(profile.getDecisionDatabaseId())) {
            // 模型按序列逐笔提交以避免超长事务；重试前必须删除同任务半成品，已发布任务则直接复用。
            decisionRepository.validateSchema(decision);
            shouldCalculate =
                    decisionRepository.prepareForecastJob(decision, profile.getId(), job.getId());
        }
        if (!shouldCalculate) {
            if (publishAtEnd) {
                controlStore.touchForecast(profile.getId());
            }
            return;
        }
        ZoneId zone = ZoneId.of(profile.getTimeZone());
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(profile.getHistoryDays());
        List<DailyActual> actuals;
        try (Connection decision =
                databaseRegistry.openDecisionReadOnly(profile.getDecisionDatabaseId())) {
            decisionRepository.validateSchema(decision);
            actuals = candidateStreamId == null || candidateMappingId == null
                    ? decisionRepository.findDailyActuals(decision, profile.getId(), start, today)
                    : decisionRepository.findDailyActualsForCandidate(decision, profile.getId(),
                            candidateStreamId, candidateMappingId, start, today);
            decision.rollback();
        }
        Map<SeriesKey, Map<LocalDate, DailyActual>> grouped = new LinkedHashMap<>();
        for (DailyActual actual : actuals) {
            SeriesKey key = new SeriesKey(actual.warehouseCode(), actual.direction());
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(actual.date(),
                    actual);
        }
        ForecastJobDO progressSnapshot = controlStore.requireJob(job.getId());
        long rowsRead = defaultLong(progressSnapshot.getRowsRead());
        long rowsWritten = defaultLong(progressSnapshot.getRowsWritten());
        long rowsAggregated = defaultLong(progressSnapshot.getRowsAggregated());
        int completed = 0;
        int total = Math.max(1,
                grouped.values().stream().mapToInt(values -> hasTaskCount(values) ? 2 : 1).sum());
        for (Map.Entry<SeriesKey, Map<LocalDate, DailyActual>> entry : grouped.entrySet()) {
            LocalDate seriesStart = entry.getValue().keySet().stream().min(LocalDate::compareTo)
                    .map(first -> first.isBefore(start) ? start : first).orElse(start);
            List<ForecastMetric> metrics = hasTaskCount(entry.getValue())
                    ? List.of(ForecastMetric.QUANTITY, ForecastMetric.TASK_COUNT)
                    : List.of(ForecastMetric.QUANTITY);
            for (ForecastMetric metric : metrics) {
                ensureRunning(shouldStop);
                // 从第一条真实业务日开始计样本，避免把接入窗口前的未知日期误当作零销量。
                List<BigDecimal> values = fillSeries(seriesStart, today, entry.getValue(), metric);
                ForecastModelSelection selection = modelSelector.select(
                        new ForecastSeries(entry.getKey().stableKey(metric), values),
                        FORECAST_HORIZON);
                try (Connection decision =
                        databaseRegistry.openDecision(profile.getDecisionDatabaseId())) {
                    decisionRepository.saveForecast(decision, job.getId(), profile.getId(),
                            entry.getKey().warehouseCode(), entry.getKey().direction(), metric,
                            seriesStart, today.minusDays(1), today, selection);
                }
                completed++;
                int progress = 90 + Math.min(9, (completed * 9) / total);
                controlStore.updateProgress(job.getId(), workerId, progress, rowsRead, rowsWritten,
                        rowsAggregated, null);
            }
        }
        if (publishAtEnd) {
            ensureRunning(shouldStop);
            try (Connection decision =
                    databaseRegistry.openDecision(profile.getDecisionDatabaseId())) {
                decisionRepository.publishForecastJob(decision, profile.getId(), job.getId());
            }
            controlStore.touchForecast(profile.getId());
        }
    }

    /** 任务数是可选指标；仅在源映射确实产生过任务 ID 时创建对应模型。 */
    private boolean hasTaskCount(Map<LocalDate, DailyActual> actuals) {
        return actuals.values().stream().anyMatch(actual -> actual.taskCount() > 0L);
    }

    /** 连续日期补零，确保周周期和移动窗口不会被缺失日期压缩。 */
    private List<BigDecimal> fillSeries(LocalDate start, LocalDate end,
            Map<LocalDate, DailyActual> actuals, ForecastMetric metric) {
        List<BigDecimal> values = new ArrayList<>();
        for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
            DailyActual actual = actuals.get(date);
            if (actual == null) {
                values.add(BigDecimal.ZERO);
            } else if (metric == ForecastMetric.QUANTITY) {
                values.add(actual.quantity());
            } else {
                values.add(BigDecimal.valueOf(actual.taskCount()));
            }
        }
        return List.copyOf(values);
    }

    /** 在页边界响应取消或租约丢失。 */
    private void ensureRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new ForecastJobCancelledException();
        }
    }

    /** 生成同一任务/映射/页游标稳定的批次 ID。 */
    private String batchId(Long jobId, Long mappingId, ForecastCursor lower, ForecastCursor next) {
        String raw = jobId + "|" + mappingId + "|" + Objects.toString(lower) + "|"
                + Objects.toString(next);
        return json.sha256(raw);
    }

    /** Date 转 Instant。 */
    private Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }

    /** 可空 long 归零。 */
    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    /** 预测序列维度键。 */
    private record SeriesKey(String warehouseCode,
            com.tencent.supersonic.forecast.api.enums.ForecastDirection direction) {
        /** 返回包含指标的稳定日志/算法键。 */
        private String stableKey(ForecastMetric metric) {
            return warehouseCode + "|" + direction + "|" + metric;
        }
    }

    /** 取消是受控终止，不应记录为系统错误。 */
    public static class ForecastJobCancelledException extends RuntimeException {
        /** 创建无敏感上下文的取消异常。 */
        public ForecastJobCancelledException() {
            super("预测任务已取消");
        }
    }
}
