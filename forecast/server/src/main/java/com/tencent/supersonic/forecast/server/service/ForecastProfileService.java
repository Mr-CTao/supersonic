package com.tencent.supersonic.forecast.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastJobStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastMappingStatus;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingValidation;
import com.tencent.supersonic.forecast.api.model.ForecastMetadata;
import com.tencent.supersonic.forecast.api.request.ForecastMappingReq;
import com.tencent.supersonic.forecast.api.request.ForecastProfileReq;
import com.tencent.supersonic.forecast.api.request.ForecastStreamReq;
import com.tencent.supersonic.forecast.api.response.ForecastActivationSummaryResp;
import com.tencent.supersonic.forecast.api.response.ForecastMappingResp;
import com.tencent.supersonic.forecast.api.response.ForecastProfileResp;
import com.tencent.supersonic.forecast.api.response.ForecastStreamResp;
import com.tencent.supersonic.forecast.api.spi.ForecastConnector;
import com.tencent.supersonic.forecast.core.connector.ForecastConnectorRegistry;
import com.tencent.supersonic.forecast.core.mapping.ForecastMappingValidator;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry.DatabaseDescriptor;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastMappingDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastStreamDO;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastMappingMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastProfileMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastStreamMapper;
import com.tencent.supersonic.forecast.server.util.ForecastJson;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Forecast Profile、数据流、不可变映射和元数据发现的应用服务。
 *
 * <p>
 * 本服务只操作小型控制表；源数据预览通过强制只读 Connector 执行。所有更新使用客户端 lockVersion 乐观锁，避免多个管理页面相互覆盖。
 * </p>
 */
@Service
public class ForecastProfileService {

    private static final Set<String> SOURCE_TYPES = Set.of("mysql", "postgresql", "sqlserver");

    private final ForecastProfileMapper profileMapper;
    private final ForecastStreamMapper streamMapper;
    private final ForecastMappingMapper mappingMapper;
    private final ForecastAccessService accessService;
    private final ForecastDatabaseRegistry databaseRegistry;
    private final ForecastConnectorRegistry connectorRegistry;
    private final ForecastMappingValidator mappingValidator;
    private final ForecastControlStore controlStore;
    private final ForecastJson json;

    /**
     * 创建 Profile 应用服务。
     *
     * @param profileMapper Profile Mapper。
     * @param streamMapper 数据流 Mapper。
     * @param mappingMapper 映射 Mapper。
     * @param accessService ACL 服务。
     * @param databaseRegistry 数据库连接注册表。
     * @param connectorRegistry Connector 注册表。
     * @param mappingValidator 映射白名单校验器。
     * @param controlStore 控制面读取服务。
     * @param json JSON 工具。
     */
    public ForecastProfileService(ForecastProfileMapper profileMapper,
            ForecastStreamMapper streamMapper, ForecastMappingMapper mappingMapper,
            ForecastAccessService accessService, ForecastDatabaseRegistry databaseRegistry,
            ForecastConnectorRegistry connectorRegistry, ForecastMappingValidator mappingValidator,
            ForecastControlStore controlStore, ForecastJson json) {
        this.profileMapper = profileMapper;
        this.streamMapper = streamMapper;
        this.mappingMapper = mappingMapper;
        this.accessService = accessService;
        this.databaseRegistry = databaseRegistry;
        this.connectorRegistry = connectorRegistry;
        this.mappingValidator = mappingValidator;
        this.controlStore = controlStore;
        this.json = json;
    }

    /**
     * 创建 Profile。
     *
     * @param request 配置请求。
     * @param user 当前用户。
     * @return Profile 响应。
     */
    @Transactional
    public ForecastProfileResp createProfile(ForecastProfileReq request, User user) {
        validateProfileRequest(request, user);
        Date now = controlStore.databaseTime();
        ForecastProfileDO profile = new ForecastProfileDO();
        copy(request, profile);
        profile.setDeleted(false);
        profile.setLockVersion(0);
        profile.setCreatedBy(user.getName());
        profile.setCreatedAt(now);
        profile.setUpdatedBy(user.getName());
        profile.setUpdatedAt(now);
        profileMapper.insert(profile);
        return toProfileResponse(profile, Map.of(), List.of());
    }

    /**
     * 乐观锁更新 Profile。
     *
     * @param id Profile ID。
     * @param request 配置请求，必须携带 lockVersion。
     * @param user 当前用户。
     * @return 更新后 Profile。
     */
    @Transactional
    public ForecastProfileResp updateProfile(Long id, ForecastProfileReq request, User user) {
        ForecastProfileDO current = requireManageableProfile(id, user);
        if (request.getLockVersion() == null) {
            throw new InvalidArgumentException("更新 Profile 必须携带 lockVersion");
        }
        validateProfileRequest(request, user);
        ForecastProfileDO update = new ForecastProfileDO();
        update.setId(id);
        copy(request, update);
        update.setUpdatedBy(user.getName());
        update.setUpdatedAt(controlStore.databaseTime());
        if (profileMapper.updateOptimistic(update, request.getLockVersion()) != 1) {
            throw new InvalidArgumentException("Profile 已被其他用户修改，请刷新后重试");
        }
        return getProfile(current.getId(), user);
    }

    /**
     * 停用 Profile，不删除历史任务和决策数据。
     *
     * @param id Profile ID。
     * @param lockVersion 期望版本。
     * @param user 当前用户。
     * @return 停用后 Profile。
     */
    @Transactional
    public ForecastProfileResp disableProfile(Long id, int lockVersion, User user) {
        ForecastProfileDO current = requireManageableProfile(id, user);
        ForecastProfileReq request = new ForecastProfileReq();
        request.setName(current.getName());
        request.setSourceDatabaseId(current.getSourceDatabaseId());
        request.setDecisionDatabaseId(current.getDecisionDatabaseId());
        request.setTimeZone(current.getTimeZone());
        request.setSyncCron(current.getSyncCron());
        request.setForecastCron(current.getForecastCron());
        request.setReconcileCron(current.getReconcileCron());
        request.setHistoryDays(current.getHistoryDays());
        request.setEnabled(false);
        request.setLockVersion(lockVersion);
        return updateProfile(id, request, user);
    }

    /**
     * 分页查询用户可见 Profile。
     *
     * @param pageNum 页码。
     * @param pageSize 页大小。
     * @param user 当前用户。
     * @return Profile 分页。
     */
    public PageInfo<ForecastProfileResp> listProfiles(int pageNum, int pageSize, User user) {
        List<DatabaseResp> visible = accessService.visibleDatabases(user);
        Map<Long, String> names = visible.stream().collect(Collectors.toMap(DatabaseResp::getId,
                DatabaseResp::getName, (left, right) -> left, LinkedHashMap::new));
        Set<Long> databaseIds = names.keySet();
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        PageInfo<ForecastProfileResp> response = new PageInfo<>();
        response.setPageNum(safePage);
        response.setPageSize(safeSize);
        if (databaseIds.isEmpty()) {
            response.setList(List.of());
            response.setTotal(0);
            response.setPages(0);
            return response;
        }
        LambdaQueryWrapper<ForecastProfileDO> query = new LambdaQueryWrapper<>();
        query.in(ForecastProfileDO::getSourceDatabaseId, databaseIds)
                .orderByDesc(ForecastProfileDO::getUpdatedAt).orderByDesc(ForecastProfileDO::getId);
        PageInfo<ForecastProfileDO> page = PageHelper.startPage(safePage, safeSize)
                .doSelectPageInfo(() -> profileMapper.selectList(query));
        List<Long> profileIds = page.getList().stream().map(ForecastProfileDO::getId).toList();
        Map<Long, List<ForecastStreamResp>> streams = loadStreams(profileIds);
        response.setList(page.getList().stream().map(profile -> toProfileResponse(profile, names,
                streams.getOrDefault(profile.getId(), List.of()))).toList());
        response.setTotal(page.getTotal());
        response.setPages(page.getPages());
        return response;
    }

    /**
     * 获取 Profile 详情。
     *
     * @param id Profile ID。
     * @param user 当前用户。
     * @return Profile 详情。
     */
    public ForecastProfileResp getProfile(Long id, User user) {
        ForecastProfileDO profile = controlStore.requireProfile(id);
        DatabaseResp source = accessService.requireViewer(profile.getSourceDatabaseId(), user);
        DatabaseDescriptor decision = databaseRegistry.describe(profile.getDecisionDatabaseId());
        Map<Long, String> names = new LinkedHashMap<>();
        names.put(source.getId(), source.getName());
        names.put(decision.id(), decision.name());
        List<ForecastStreamResp> streams = toStreamResponses(streamMapper.selectByProfile(id));
        return toProfileResponse(profile, names, streams);
    }

    /**
     * 新建数据流。
     *
     * @param profileId Profile ID。
     * @param request 数据流请求。
     * @param user 当前用户。
     * @return 新数据流。
     */
    @Transactional
    public ForecastStreamResp createStream(Long profileId, ForecastStreamReq request, User user) {
        requireManageableProfile(profileId, user);
        Date now = controlStore.databaseTime();
        ForecastStreamDO stream = new ForecastStreamDO();
        stream.setProfileId(profileId);
        stream.setName(request.getName().trim());
        stream.setEnabled(request.isEnabled());
        stream.setDeleted(false);
        stream.setLockVersion(0);
        stream.setCreatedBy(user.getName());
        stream.setCreatedAt(now);
        stream.setUpdatedBy(user.getName());
        stream.setUpdatedAt(now);
        streamMapper.insert(stream);
        return toStreamResponse(stream, null);
    }

    /**
     * 更新数据流基础字段。
     *
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param request 请求。
     * @param user 当前用户。
     * @return 更新后数据流。
     */
    @Transactional
    public ForecastStreamResp updateStream(Long profileId, Long streamId, ForecastStreamReq request,
            User user) {
        requireManageableProfile(profileId, user);
        controlStore.requireStream(profileId, streamId);
        if (request.getLockVersion() == null) {
            throw new InvalidArgumentException("更新数据流必须携带 lockVersion");
        }
        ForecastStreamDO update = new ForecastStreamDO();
        update.setId(streamId);
        update.setName(request.getName().trim());
        update.setEnabled(request.isEnabled());
        update.setUpdatedBy(user.getName());
        update.setUpdatedAt(controlStore.databaseTime());
        if (streamMapper.updateOptimistic(update, request.getLockVersion()) != 1) {
            throw new InvalidArgumentException("数据流已被其他用户修改，请刷新后重试");
        }
        return toStreamResponse(controlStore.requireStream(profileId, streamId), null);
    }

    /**
     * 创建不可变映射草稿。
     *
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param request 映射请求。
     * @param user 当前用户。
     * @return 新版本映射。
     */
    @Transactional
    public ForecastMappingResp createMapping(Long profileId, Long streamId,
            ForecastMappingReq request, User user) {
        requireManageableProfile(profileId, user);
        ForecastStreamDO stream = streamMapper.selectForUpdate(streamId);
        if (stream == null || !Objects.equals(profileId, stream.getProfileId())) {
            throw new InvalidArgumentException("预测数据流不存在");
        }
        // 版本号是流内递增序列；锁住单个流行可跨 Standalone 实例串行分配，避免 max+1 竞态。
        ForecastMappingValidator.ValidationReport report =
                mappingValidator.validate(request.getConfig());
        String configJson = json.write(request.getConfig());
        ForecastMappingDO mapping = new ForecastMappingDO();
        mapping.setStreamId(streamId);
        mapping.setMappingVersion(mappingMapper.selectMaxVersion(streamId) + 1);
        mapping.setStatus(ForecastMappingStatus.DRAFT.name());
        mapping.setConfigJson(configJson);
        mapping.setConfigChecksum(json.sha256(configJson));
        mapping.setValid(report.valid());
        mapping.setValidationSummary(summary(report.errors(), report.warnings()));
        mapping.setCreatedBy(user.getName());
        mapping.setCreatedAt(controlStore.databaseTime());
        mappingMapper.insert(mapping);
        return toMappingResponse(mapping);
    }

    /**
     * 列出数据流全部映射版本。
     *
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param user 当前用户。
     * @return 版本降序列表。
     */
    public List<ForecastMappingResp> listMappings(Long profileId, Long streamId, User user) {
        ForecastProfileDO profile = controlStore.requireProfile(profileId);
        accessService.requireViewer(profile.getSourceDatabaseId(), user);
        controlStore.requireStream(profileId, streamId);
        return mappingMapper.selectByStream(streamId).stream().map(this::toMappingResponse)
                .toList();
    }

    /**
     * 使用源库最多一百行进行映射验证和标准化预览。
     *
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param mappingId 映射 ID。
     * @param sampleLimit 样例数。
     * @param user 当前用户。
     * @return 校验结论。
     * @throws SQLException 只读源库访问失败。
     */
    @Transactional
    public ForecastMappingValidation validateMapping(Long profileId, Long streamId, Long mappingId,
            int sampleLimit, User user) throws SQLException {
        ForecastProfileDO profile = requireManageableProfile(profileId, user);
        controlStore.requireStream(profileId, streamId);
        ForecastMappingDO mapping = controlStore.requireMapping(streamId, mappingId);
        ForecastMappingConfig config =
                json.read(mapping.getConfigJson(), ForecastMappingConfig.class);
        DatabaseDescriptor database = databaseRegistry.describe(profile.getSourceDatabaseId());
        ForecastConnector connector = connectorRegistry.require(database.type());
        ForecastMappingValidation validation;
        try (Connection connection = databaseRegistry.openSource(profile.getSourceDatabaseId())) {
            validation =
                    connector.validate(connection, config, Math.max(1, Math.min(100, sampleLimit)));
            connection.rollback();
        }
        mappingMapper.updateValidation(mappingId, validation.valid(),
                summary(validation.errors(), validation.warnings()));
        return validation;
    }

    /**
     * 发布已验证草稿；发布不等于激活，Worker 回填与预测成功后才切换活动版本。
     *
     * @param profileId Profile ID。
     * @param streamId 数据流 ID。
     * @param mappingId 映射 ID。
     * @param user 当前用户。
     * @return 发布后映射。
     */
    @Transactional
    public ForecastMappingResp publishMapping(Long profileId, Long streamId, Long mappingId,
            User user) {
        requireManageableProfile(profileId, user);
        controlStore.requireStream(profileId, streamId);
        ForecastMappingDO mapping = controlStore.requireMapping(streamId, mappingId);
        if (!Boolean.TRUE.equals(mapping.getValid())) {
            throw new InvalidArgumentException("映射必须先通过服务端预览校验才能发布");
        }
        if (ForecastMappingStatus.PUBLISHED.name().equals(mapping.getStatus())) {
            return toMappingResponse(mapping);
        }
        if (mappingMapper.publish(mappingId, controlStore.databaseTime()) != 1) {
            throw new InvalidArgumentException("只有有效草稿可以发布");
        }
        return toMappingResponse(mappingMapper.selectById(mappingId));
    }

    /**
     * 发现源数据库 catalog、schema、表/视图和可选列。
     *
     * @param profileId Profile ID。
     * @param catalog catalog 过滤。
     * @param schema schema 过滤。
     * @param tablePattern 精确表名时返回列；为空仅返回表清单。
     * @param user 当前用户。
     * @return 元数据快照。
     * @throws SQLException JDBC 元数据读取失败。
     */
    public ForecastMetadata discoverMetadata(Long profileId, String catalog, String schema,
            String tablePattern, User user) throws SQLException {
        ForecastProfileDO profile = controlStore.requireProfile(profileId);
        accessService.requireViewer(profile.getSourceDatabaseId(), user);
        DatabaseDescriptor database = databaseRegistry.describe(profile.getSourceDatabaseId());
        ForecastConnector connector = connectorRegistry.require(database.type());
        try (Connection connection = databaseRegistry.openSource(profile.getSourceDatabaseId())) {
            ForecastMetadata metadata =
                    connector.discover(connection, catalog, schema, tablePattern);
            connection.rollback();
            return metadata;
        }
    }

    /** 校验 Profile 数据源类型、时区和 Cron。 */
    private void validateProfileRequest(ForecastProfileReq request, User user) {
        DatabaseResp source = accessService.requireAdmin(request.getSourceDatabaseId(), user);
        DatabaseResp decision = accessService.requireAdmin(request.getDecisionDatabaseId(), user);
        if (Objects.equals(request.getSourceDatabaseId(), request.getDecisionDatabaseId())) {
            throw new InvalidArgumentException("源数据库和决策库必须使用不同的数据源配置");
        }
        if (!SOURCE_TYPES.contains(normalize(source.getType()))) {
            throw new InvalidArgumentException("源数据库仅支持 MySQL、PostgreSQL、SQL Server");
        }
        if (!"postgresql".equals(normalize(decision.getType()))) {
            throw new InvalidArgumentException("决策库首版必须使用 PostgreSQL");
        }
        try {
            ZoneId.of(request.getTimeZone());
            CronExpression.parse(request.getSyncCron());
            CronExpression.parse(request.getForecastCron());
            CronExpression.parse(request.getReconcileCron());
        } catch (RuntimeException exception) {
            throw new InvalidArgumentException("时区或 Cron 表达式无效");
        }
    }

    /** 读取并校验 Profile 管理权限。 */
    private ForecastProfileDO requireManageableProfile(Long id, User user) {
        ForecastProfileDO profile = controlStore.requireProfile(id);
        accessService.requireAdmin(profile.getSourceDatabaseId(), user);
        return profile;
    }

    /** 从请求复制允许更新的字段，避免 BeanUtils 意外覆盖审计字段。 */
    private void copy(ForecastProfileReq request, ForecastProfileDO target) {
        target.setName(request.getName().trim());
        target.setSourceDatabaseId(request.getSourceDatabaseId());
        target.setDecisionDatabaseId(request.getDecisionDatabaseId());
        target.setTimeZone(request.getTimeZone());
        target.setSyncCron(request.getSyncCron());
        target.setForecastCron(request.getForecastCron());
        target.setReconcileCron(request.getReconcileCron());
        target.setHistoryDays(request.getHistoryDays());
        target.setEnabled(request.isEnabled());
    }

    /** 批量加载当前页 Profile 的数据流，避免 N+1。 */
    private Map<Long, List<ForecastStreamResp>> loadStreams(List<Long> profileIds) {
        if (profileIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<ForecastStreamDO> query = new LambdaQueryWrapper<>();
        query.in(ForecastStreamDO::getProfileId, profileIds).eq(ForecastStreamDO::getDeleted, false)
                .orderByAsc(ForecastStreamDO::getId);
        return toStreamResponses(streamMapper.selectList(query)).stream()
                .collect(Collectors.groupingBy(ForecastStreamResp::getProfileId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    /** 转换 Profile 响应。 */
    private ForecastProfileResp toProfileResponse(ForecastProfileDO profile,
            Map<Long, String> databaseNames, List<ForecastStreamResp> streams) {
        return ForecastProfileResp.builder().id(profile.getId()).name(profile.getName())
                .sourceDatabaseId(profile.getSourceDatabaseId())
                .sourceDatabaseName(databaseNames.get(profile.getSourceDatabaseId()))
                .decisionDatabaseId(profile.getDecisionDatabaseId())
                .decisionDatabaseName(databaseNames.get(profile.getDecisionDatabaseId()))
                .timeZone(profile.getTimeZone()).syncCron(profile.getSyncCron())
                .forecastCron(profile.getForecastCron()).reconcileCron(profile.getReconcileCron())
                .historyDays(profile.getHistoryDays())
                .enabled(Boolean.TRUE.equals(profile.getEnabled()))
                .lockVersion(profile.getLockVersion())
                .lastSyncAt(toInstant(profile.getLastSyncAt()))
                .lastForecastAt(toInstant(profile.getLastForecastAt()))
                .freshnessStatus(freshness(profile.getLastSyncAt()))
                .streams(new ArrayList<>(streams)).createdBy(profile.getCreatedBy())
                .createdAt(toInstant(profile.getCreatedAt())).updatedBy(profile.getUpdatedBy())
                .updatedAt(toInstant(profile.getUpdatedAt())).build();
    }

    /**
     * 批量转换数据流并附加当前阻塞或最近首次同步任务，避免 Profile 列表产生 N+1 查询。
     *
     * @param streams 数据流持久化快照。
     * @return 带最近激活状态的数据流响应。
     */
    private List<ForecastStreamResp> toStreamResponses(List<ForecastStreamDO> streams) {
        if (streams == null || streams.isEmpty()) {
            return List.of();
        }
        List<Long> streamIds = streams.stream().map(ForecastStreamDO::getId).toList();
        Map<Long, ForecastJobDO> latestJobs = controlStore.latestInitialSyncJobs(streamIds).stream()
                .collect(Collectors.toMap(ForecastJobDO::getStreamId, job -> job,
                        (left, right) -> left.getId() >= right.getId() ? left : right));
        Set<Long> mappingIds = latestJobs.values().stream().map(ForecastJobDO::getMappingId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Integer> mappingVersions = mappingIds.isEmpty() ? Map.of()
                : mappingMapper.selectBatchIds(mappingIds).stream().collect(Collectors
                        .toMap(ForecastMappingDO::getId, ForecastMappingDO::getMappingVersion));
        return streams.stream().map(stream -> {
            ForecastJobDO job = latestJobs.get(stream.getId());
            ForecastActivationSummaryResp activation = job == null ? null
                    : ForecastActivationSummaryResp.builder().jobId(job.getId())
                            .mappingId(job.getMappingId())
                            .mappingVersion(mappingVersions.get(job.getMappingId()))
                            .status(ForecastJobStatus.valueOf(job.getStatus()))
                            .progressPercent(
                                    job.getProgressPercent() == null ? 0 : job.getProgressPercent())
                            .errorCode(job.getErrorCode()).errorMessage(job.getErrorMessage())
                            .createdAt(toInstant(job.getCreatedAt()))
                            .startedAt(toInstant(job.getStartedAt()))
                            .finishedAt(toInstant(job.getFinishedAt())).build();
            return toStreamResponse(stream, activation);
        }).toList();
    }

    /** 转换单个数据流响应；新建/更新场景可不附加历史激活任务。 */
    private ForecastStreamResp toStreamResponse(ForecastStreamDO stream,
            ForecastActivationSummaryResp activation) {
        return ForecastStreamResp.builder().id(stream.getId()).profileId(stream.getProfileId())
                .name(stream.getName()).enabled(Boolean.TRUE.equals(stream.getEnabled()))
                .activeMappingId(stream.getActiveMappingId())
                .activeMappingVersion(stream.getActiveMappingVersion()).latestActivation(activation)
                .lockVersion(stream.getLockVersion()).lastSyncAt(toInstant(stream.getLastSyncAt()))
                .createdAt(toInstant(stream.getCreatedAt()))
                .updatedAt(toInstant(stream.getUpdatedAt())).build();
    }

    /** 转换映射响应。 */
    private ForecastMappingResp toMappingResponse(ForecastMappingDO mapping) {
        return ForecastMappingResp.builder().id(mapping.getId()).streamId(mapping.getStreamId())
                .version(mapping.getMappingVersion())
                .status(ForecastMappingStatus.valueOf(mapping.getStatus()))
                .config(json.read(mapping.getConfigJson(), ForecastMappingConfig.class))
                .configChecksum(mapping.getConfigChecksum())
                .valid(Boolean.TRUE.equals(mapping.getValid()))
                .validationSummary(mapping.getValidationSummary()).createdBy(mapping.getCreatedBy())
                .createdAt(toInstant(mapping.getCreatedAt()))
                .publishedAt(toInstant(mapping.getPublishedAt())).build();
    }

    /** 生成不包含样例值的校验摘要。 */
    private String summary(List<String> errors, List<String> warnings) {
        return "errors=" + String.join(" | ", errors == null ? List.of() : errors) + "; warnings="
                + String.join(" | ", warnings == null ? List.of() : warnings);
    }

    /** 简单数据新鲜度标签；精确阈值由健康接口统一计算。 */
    private String freshness(Date lastSyncAt) {
        return lastSyncAt == null ? "NEVER_SYNCED" : "AVAILABLE";
    }

    /** 标准化数据源类型。 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Date 转 Instant。 */
    private Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }
}
