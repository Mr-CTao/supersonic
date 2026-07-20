package com.tencent.supersonic.forecast.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastJobStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.api.response.ForecastJobResp;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastMappingDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastResourceLeaseDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastStreamDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWatermarkDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWorkerNodeDO;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastJobMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastMappingMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastProfileMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastResourceLeaseMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastStreamMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastWatermarkMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastWorkerNodeMapper;
import com.tencent.supersonic.forecast.server.util.ForecastJson;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Forecast 元数据库控制面的事务服务。
 *
 * <p>
 * 所有跨 Worker 共享状态都由数据库 CAS/唯一键保护，不依赖 JVM 锁。决策库事件事务不在本服务 的事务范围内；Worker 必须先提交决策库页批次，再调用
 * {@link #advanceWatermark}。
 * </p>
 */
@Service
public class ForecastControlStore {

    private static final String INITIAL_SYNC_CONCURRENCY_PREFIX = "INITIAL_SYNC:STREAM:";

    private final ForecastProfileMapper profileMapper;
    private final ForecastStreamMapper streamMapper;
    private final ForecastMappingMapper mappingMapper;
    private final ForecastJobMapper jobMapper;
    private final ForecastWatermarkMapper watermarkMapper;
    private final ForecastWorkerNodeMapper workerNodeMapper;
    private final ForecastResourceLeaseMapper resourceLeaseMapper;
    private final ForecastProperties properties;
    private final ForecastJson json;

    /**
     * 创建控制面事务服务。
     *
     * @param profileMapper Profile Mapper。
     * @param streamMapper 数据流 Mapper。
     * @param mappingMapper 映射 Mapper。
     * @param jobMapper 任务 Mapper。
     * @param watermarkMapper 水位 Mapper。
     * @param workerNodeMapper Worker Mapper。
     * @param properties Forecast 配置。
     * @param json JSON/摘要工具。
     */
    public ForecastControlStore(ForecastProfileMapper profileMapper,
            ForecastStreamMapper streamMapper, ForecastMappingMapper mappingMapper,
            ForecastJobMapper jobMapper, ForecastWatermarkMapper watermarkMapper,
            ForecastWorkerNodeMapper workerNodeMapper,
            ForecastResourceLeaseMapper resourceLeaseMapper, ForecastProperties properties,
            ForecastJson json) {
        this.profileMapper = profileMapper;
        this.streamMapper = streamMapper;
        this.mappingMapper = mappingMapper;
        this.jobMapper = jobMapper;
        this.watermarkMapper = watermarkMapper;
        this.workerNodeMapper = workerNodeMapper;
        this.resourceLeaseMapper = resourceLeaseMapper;
        this.properties = properties;
        this.json = json;
    }

    /**
     * 幂等创建任务。
     *
     * @param request 任务请求。
     * @param idempotencyKey 客户端幂等键。
     * @param createdBy 发起人。
     * @return 新建或已存在任务。
     * @throws InvalidArgumentException 键为空、复用键但请求不同。
     */
    public ForecastJobDO createJob(ForecastJobReq request, String idempotencyKey,
            String createdBy) {
        return createJob(request, idempotencyKey, createdBy, null);
    }

    /**
     * 幂等创建任务并关联原任务。
     *
     * @param request 任务请求。
     * @param idempotencyKey 幂等键。
     * @param createdBy 发起人。
     * @param parentJobId 重试来源任务，可空。
     * @return 新建或已存在任务。
     */
    public ForecastJobDO createJob(ForecastJobReq request, String idempotencyKey, String createdBy,
            Long parentJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new InvalidArgumentException("Idempotency-Key 必填且不能超过 128 字符");
        }
        String parameters = json.write(request);
        String fingerprint = json.sha256(parameters);
        ForecastJobDO existing = jobMapper.selectIdempotent(createdBy, idempotencyKey);
        if (existing != null) {
            assertSameFingerprint(existing, fingerprint);
            return existing;
        }
        ForecastJobDO existingActive = findActiveInitialSync(request);
        if (existingActive != null) {
            return resolveActiveInitialSync(existingActive, request);
        }
        String activeConcurrencyKey = activeConcurrencyKey(request);
        Date now = databaseTime();
        ForecastJobDO job = new ForecastJobDO();
        job.setParentJobId(parentJobId);
        job.setProfileId(request.getProfileId());
        job.setStreamId(request.getStreamId());
        job.setMappingId(request.getMappingId());
        job.setJobType(request.getType().name());
        job.setStatus(ForecastJobStatus.QUEUED.name());
        job.setActiveConcurrencyKey(activeConcurrencyKey);
        job.setIdempotencyKey(idempotencyKey);
        job.setRequestFingerprint(fingerprint);
        job.setParametersJson(parameters);
        job.setProgressPercent(0);
        job.setRowsRead(0L);
        job.setRowsWritten(0L);
        job.setRowsAggregated(0L);
        job.setRetryCount(0);
        job.setMaxRetries(properties.getWorker().getMaxRetries());
        job.setLockVersion(0);
        job.setCreatedBy(createdBy);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        try {
            // 故意不包裹外层事务：PostgreSQL 唯一键冲突会中止当前事务，独立语句提交后才能安全读取胜出者。
            jobMapper.insert(job);
            return job;
        } catch (DuplicateKeyException exception) {
            // 幂等键与业务并发键都由数据库唯一约束裁决，避免多个 API 实例查询后同时插入。
            ForecastJobDO winner = jobMapper.selectIdempotent(createdBy, idempotencyKey);
            if (winner != null) {
                assertSameFingerprint(winner, fingerprint);
                return winner;
            }
            if (activeConcurrencyKey != null) {
                ForecastJobDO concurrentWinner =
                        jobMapper.selectActiveByConcurrencyKey(activeConcurrencyKey);
                if (concurrentWinner != null) {
                    return resolveActiveInitialSync(concurrentWinner, request);
                }
            }
            throw exception;
        }
    }

    /**
     * 为首次同步准备数据库唯一业务并发槽。
     *
     * <p>
     * 迁移前创建的非终态任务可能没有并发键，因此插入前仍查询一次；迁移后的并发请求最终由 唯一索引裁决，不能把这次查询当作并发保护。
     * </p>
     *
     * @param request 任务请求。
     * @return 已有非终态首次同步任务；不存在或其他任务类型返回 null。
     */
    private ForecastJobDO findActiveInitialSync(ForecastJobReq request) {
        if (request.getType() != ForecastJobType.INITIAL_SYNC) {
            return null;
        }
        if (request.getStreamId() == null || request.getMappingId() == null) {
            throw new InvalidArgumentException("首次同步必须指定数据流和已发布映射");
        }
        ForecastStreamDO stream = requireStream(request.getProfileId(), request.getStreamId());
        if (Objects.equals(stream.getActiveMappingId(), request.getMappingId())) {
            throw new InvalidArgumentException("该映射已经是当前活动版本，如需重扫请创建对账任务");
        }
        return jobMapper.selectActiveInitialSync(request.getStreamId());
    }

    /** 为首次同步生成数据库唯一并发键；其他任务不占用该业务槽。 */
    private String activeConcurrencyKey(ForecastJobReq request) {
        return request.getType() == ForecastJobType.INITIAL_SYNC
                ? INITIAL_SYNC_CONCURRENCY_PREFIX + request.getStreamId()
                : null;
    }

    /**
     * 解析首次同步业务冲突。
     *
     * @param active 已占用数据流的任务。
     * @param request 新请求。
     * @return 同映射时复用的已有任务。
     * @throws InvalidArgumentException 不同映射试图同时激活时抛出。
     */
    private ForecastJobDO resolveActiveInitialSync(ForecastJobDO active, ForecastJobReq request) {
        if (Objects.equals(active.getMappingId(), request.getMappingId())) {
            return active;
        }
        throw new InvalidArgumentException("数据流已有首次同步任务 #" + active.getId() + "（状态 "
                + active.getStatus() + "），请等待完成或取消后再激活其他映射");
    }

    /**
     * 读取并裁剪待认领任务。
     *
     * @param limit 最大任务数。
     * @return 待认领快照。
     */
    public List<ForecastJobDO> queued(int limit) {
        int safeLimit = Math.max(0, limit);
        if (safeLimit == 0) {
            return List.of();
        }
        return jobMapper.selectQueued(safeLimit);
    }

    /**
     * 通过 CAS 认领任务。
     *
     * @param job 排队快照。
     * @param workerId Worker ID。
     * @return 是否认领成功。
     */
    public boolean claim(ForecastJobDO job, String workerId) {
        Date now = databaseTime();
        Date expiresAt =
                Date.from(now.toInstant().plusSeconds(properties.getWorker().getLeaseSeconds()));
        return jobMapper.claim(job.getId(), job.getLockVersion(), workerId, now, expiresAt) == 1;
    }

    /** 资源繁忙时把刚认领任务无损让回队列。 */
    public boolean yieldClaim(Long jobId, String workerId) {
        return jobMapper.yieldClaim(jobId, workerId, databaseTime()) == 1;
    }

    /**
     * 逐项 CAS 获取一组服务端生成的资源租约；任一失败会补偿释放本次已获取资源。
     *
     * <p>
     * 不使用跨行长事务，确保其他 Worker 能及时观察已获取租约，并允许 PostgreSQL 主键冲突后 在新事务继续 CAS。短暂部分持有窗口由失败补偿和 90 秒失效恢复共同兜底。
     * </p>
     *
     * @param leaseKeys SOURCE/STREAM/PROFILE 键列表。
     * @param jobId 任务 ID。
     * @param workerId Worker ID。
     * @return 是否全部获取。
     */
    public boolean acquireResources(List<String> leaseKeys, Long jobId, String workerId) {
        List<String> acquired = new java.util.ArrayList<>();
        for (String leaseKey : leaseKeys) {
            if (!acquireResource(leaseKey, jobId, workerId)) {
                releaseResources(acquired, jobId, workerId);
                return false;
            }
            acquired.add(leaseKey);
        }
        return true;
    }

    /** 续租任务持有的资源；失败表示锁已被过期接管。 */
    public boolean renewResources(List<String> leaseKeys, Long jobId, String workerId) {
        for (String leaseKey : leaseKeys) {
            if (!acquireResource(leaseKey, jobId, workerId)) {
                return false;
            }
        }
        return true;
    }

    /** 释放任务持有的全部资源。 */
    public void releaseResources(List<String> leaseKeys, Long jobId, String workerId) {
        Date now = databaseTime();
        for (String leaseKey : leaseKeys) {
            resourceLeaseMapper.release(leaseKey, jobId, workerId, now);
        }
    }

    /** 获取或创建单个 CAS 资源租约。 */
    private boolean acquireResource(String leaseKey, Long jobId, String workerId) {
        Date now = databaseTime();
        Date expiresAt =
                Date.from(now.toInstant().plusSeconds(properties.getWorker().getLeaseSeconds()));
        if (resourceLeaseMapper.acquire(leaseKey, jobId, workerId, now, expiresAt) == 1) {
            return true;
        }
        ForecastResourceLeaseDO lease = new ForecastResourceLeaseDO();
        lease.setLeaseKey(leaseKey);
        lease.setOwnerJobId(jobId);
        lease.setWorkerId(workerId);
        lease.setLeaseExpiresAt(expiresAt);
        lease.setLockVersion(0);
        lease.setUpdatedAt(now);
        try {
            resourceLeaseMapper.insert(lease);
            return true;
        } catch (DuplicateKeyException ignored) {
            return resourceLeaseMapper.acquire(leaseKey, jobId, workerId, now, expiresAt) == 1;
        }
    }

    /**
     * 延长任务租约。
     *
     * @param jobId 任务 ID。
     * @param workerId Worker ID。
     * @return 是否仍持有任务。
     */
    public boolean heartbeat(Long jobId, String workerId) {
        Date now = databaseTime();
        Date expiresAt =
                Date.from(now.toInstant().plusSeconds(properties.getWorker().getLeaseSeconds()));
        return jobMapper.heartbeat(jobId, workerId, now, expiresAt) == 1;
    }

    /**
     * 恢复租约已失效任务；达到上限的任务转失败。
     *
     * @return 被检查的过期任务数。
     */
    @Transactional
    public int recoverExpired() {
        Date now = databaseTime();
        List<ForecastJobDO> expired = jobMapper.selectExpired(now);
        for (ForecastJobDO job : expired) {
            if (job.getRetryCount() < job.getMaxRetries()) {
                jobMapper.requeueExpired(job.getId(), job.getLockVersion(), now);
            } else {
                jobMapper.failExpired(job.getId(), job.getLockVersion(), now);
            }
        }
        return expired.size();
    }

    /**
     * 更新页边界进度。
     *
     * @param jobId 任务 ID。
     * @param workerId Worker ID。
     * @param progressPercent 0-99 进度。
     * @param rowsRead 累计读取。
     * @param rowsWritten 累计写入。
     * @param rowsAggregated 累计聚合。
     * @param checkpoint 检查点。
     * @return 是否更新成功。
     */
    public boolean updateProgress(Long jobId, String workerId, int progressPercent, long rowsRead,
            long rowsWritten, long rowsAggregated, ForecastCursor checkpoint) {
        ForecastJobDO snapshot = new ForecastJobDO();
        snapshot.setId(jobId);
        snapshot.setProgressPercent(Math.max(0, Math.min(progressPercent, 99)));
        snapshot.setRowsRead(rowsRead);
        snapshot.setRowsWritten(rowsWritten);
        snapshot.setRowsAggregated(rowsAggregated);
        if (checkpoint != null) {
            snapshot.setCheckpointUpdatedAt(toDate(checkpoint.updatedAt()));
            snapshot.setCheckpointRecordId(checkpoint.recordId());
        }
        snapshot.setUpdatedAt(databaseTime());
        return jobMapper.updateProgress(snapshot, workerId) == 1;
    }

    /** 标记任务成功。 */
    public boolean complete(Long jobId, String workerId) {
        return jobMapper.complete(jobId, workerId, databaseTime()) == 1;
    }

    /** 在额度内自动重试，额度耗尽或已取消时原子收口终态。 */
    public boolean fail(Long jobId, String workerId, String code, String safeMessage) {
        Date now = databaseTime();
        if (jobMapper.requeueFailure(jobId, workerId, code, safeMessage, now) == 1) {
            return true;
        }
        return jobMapper.failTerminal(jobId, workerId, code, safeMessage, now) == 1;
    }

    /** 请求取消任务。 */
    public boolean requestCancel(Long jobId) {
        return jobMapper.requestCancel(jobId, databaseTime()) == 1;
    }

    /** Worker 确认页边界取消。 */
    public boolean confirmCancelled(Long jobId, String workerId) {
        return jobMapper.confirmCancelled(jobId, workerId, databaseTime()) == 1;
    }

    /** 判断运行任务是否已收到取消请求。 */
    public boolean isCancelling(Long jobId) {
        ForecastJobDO job = jobMapper.selectById(jobId);
        return job != null && ForecastJobStatus.CANCELLING.name().equals(job.getStatus());
    }

    /** 按 ID 读取任务。 */
    public ForecastJobDO requireJob(Long id) {
        ForecastJobDO job = jobMapper.selectById(id);
        if (job == null) {
            throw new InvalidArgumentException("预测任务不存在");
        }
        return job;
    }

    /** 按 ID 读取 Profile。 */
    public ForecastProfileDO requireProfile(Long id) {
        ForecastProfileDO profile = profileMapper.selectById(id);
        if (profile == null || Boolean.TRUE.equals(profile.getDeleted())) {
            throw new InvalidArgumentException("预测 Profile 不存在");
        }
        return profile;
    }

    /** 按 ID 读取数据流并校验归属。 */
    public ForecastStreamDO requireStream(Long profileId, Long streamId) {
        ForecastStreamDO stream = streamMapper.selectById(streamId);
        if (stream == null || Boolean.TRUE.equals(stream.getDeleted())
                || !Objects.equals(profileId, stream.getProfileId())) {
            throw new InvalidArgumentException("预测数据流不存在");
        }
        return stream;
    }

    /** 按 ID 读取映射并校验归属。 */
    public ForecastMappingDO requireMapping(Long streamId, Long mappingId) {
        ForecastMappingDO mapping = mappingMapper.selectById(mappingId);
        if (mapping == null || !Objects.equals(streamId, mapping.getStreamId())) {
            throw new InvalidArgumentException("预测映射版本不存在");
        }
        return mapping;
    }

    /** 查询全部启用 Profile，供 Worker 的轻量调度扫描使用。 */
    public List<ForecastProfileDO> enabledProfiles() {
        LambdaQueryWrapper<ForecastProfileDO> query = new LambdaQueryWrapper<>();
        query.eq(ForecastProfileDO::getEnabled, true).eq(ForecastProfileDO::getDeleted, false)
                .orderByAsc(ForecastProfileDO::getId);
        return profileMapper.selectList(query);
    }

    /**
     * 按用户可见源库批量查询全部未删除 Profile ID。
     *
     * @param sourceDatabaseIds 已通过现有 ACL 的源数据库 ID。
     * @return Profile ID 列表，包含已停用 Profile，供历史任务查询使用。
     */
    public List<Long> profileIdsBySourceDatabases(Set<Long> sourceDatabaseIds) {
        if (sourceDatabaseIds == null || sourceDatabaseIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<ForecastProfileDO> query = new LambdaQueryWrapper<>();
        query.select(ForecastProfileDO::getId)
                .in(ForecastProfileDO::getSourceDatabaseId, sourceDatabaseIds)
                .eq(ForecastProfileDO::getDeleted, false).orderByAsc(ForecastProfileDO::getId);
        return profileMapper.selectList(query).stream().map(ForecastProfileDO::getId).toList();
    }

    /** 查询 Profile 下启用数据流。 */
    public List<ForecastStreamDO> enabledStreams(Long profileId) {
        return streamMapper.selectByProfile(profileId).stream()
                .filter(stream -> Boolean.TRUE.equals(stream.getEnabled())).toList();
    }

    /**
     * 批量读取每个数据流当前占用槽或最近一次首次同步任务。
     *
     * @param streamIds 数据流 ID；空集合不会访问数据库。
     * @return 每个数据流最多一条当前阻塞任务或最近终态快照。
     */
    public List<ForecastJobDO> latestInitialSyncJobs(List<Long> streamIds) {
        if (streamIds == null || streamIds.isEmpty()) {
            return List.of();
        }
        return jobMapper.selectLatestInitialSyncs(streamIds);
    }

    /**
     * 在回填与预测成功后，以元数据库本地事务切换活动映射、归档旧版本并刷新预测时间。
     *
     * @param profile 最终切换前的 Profile 快照。
     * @param stream 最终切换前的数据流快照。
     * @param mapping 新映射。
     * @param updatedBy 操作者或 Worker 标识。
     */
    @Transactional
    public void activateMapping(ForecastProfileDO profile, ForecastStreamDO stream,
            ForecastMappingDO mapping, String updatedBy) {
        Date now = databaseTime();
        if (streamMapper.activateMapping(stream.getId(), mapping.getId(),
                mapping.getMappingVersion(), stream.getLockVersion(), updatedBy, now) != 1) {
            throw new IllegalStateException("数据流在回填期间被修改，活动映射未切换");
        }
        mappingMapper.archiveOtherPublished(stream.getId(), mapping.getId());
        // 两个完成时间与映射切换同事务；Profile CAS 还能阻止并发停用或编辑后继续上线候选版本。
        if (profileMapper.touchInitialActivation(profile.getId(), profile.getLockVersion(),
                now) != 1) {
            throw new IllegalStateException("Profile 在回填期间被修改，候选映射未切换");
        }
    }

    /**
     * 读取或创建映射水位。
     *
     * @param streamId 数据流 ID。
     * @param mappingId 映射 ID。
     * @return 水位记录。
     */
    public ForecastWatermarkDO getOrCreateWatermark(Long streamId, Long mappingId) {
        ForecastWatermarkDO existing = watermarkMapper.selectForMapping(streamId, mappingId);
        if (existing != null) {
            return existing;
        }
        Date now = databaseTime();
        ForecastWatermarkDO watermark = new ForecastWatermarkDO();
        watermark.setStreamId(streamId);
        watermark.setMappingId(mappingId);
        watermark.setLockVersion(0);
        watermark.setCreatedAt(now);
        watermark.setUpdatedAt(now);
        try {
            // 插入保持单语句提交，保证 PostgreSQL 唯一键冲突后可在新事务读取并发胜出记录。
            watermarkMapper.insert(watermark);
            return watermark;
        } catch (DuplicateKeyException exception) {
            ForecastWatermarkDO winner = watermarkMapper.selectForMapping(streamId, mappingId);
            if (winner == null) {
                throw exception;
            }
            return winner;
        }
    }

    /**
     * 在决策库提交后 CAS 推进正式水位。
     *
     * @param streamId 数据流 ID。
     * @param mappingId 映射 ID。
     * @param cursor 新水位。
     * @param batchId 已提交决策库批次 ID。
     */
    public void advanceWatermark(Long streamId, Long mappingId, ForecastCursor cursor,
            String batchId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ForecastWatermarkDO current = getOrCreateWatermark(streamId, mappingId);
            if (Objects.equals(batchId, current.getLastBatchId())) {
                return;
            }
            Date now = databaseTime();
            current.setWatermarkUpdatedAt(toDate(cursor.updatedAt()));
            current.setWatermarkRecordId(cursor.recordId());
            current.setCheckpointUpdatedAt(toDate(cursor.updatedAt()));
            current.setCheckpointRecordId(cursor.recordId());
            current.setLastBatchId(batchId);
            current.setLastSuccessAt(now);
            current.setUpdatedAt(now);
            if (watermarkMapper.advance(current, current.getLockVersion()) == 1) {
                return;
            }
        }
        throw new IllegalStateException("预测水位 CAS 冲突，请重试任务");
    }

    /**
     * 注册或刷新 Worker 心跳。
     *
     * @param workerId Worker ID。
     * @param version Worker 版本。
     * @param activeJobs 活跃任务数。
     * @param startedAt Worker 启动时间。
     */
    public void heartbeatWorker(String workerId, String version, int activeJobs,
            Instant startedAt) {
        Date now = databaseTime();
        if (workerNodeMapper.heartbeat(workerId, activeJobs, now) == 1) {
            return;
        }
        ForecastWorkerNodeDO node = new ForecastWorkerNodeDO();
        node.setWorkerId(workerId);
        node.setWorkerVersion(version);
        node.setActiveJobs(activeJobs);
        node.setStartedAt(Date.from(startedAt));
        node.setHeartbeatAt(now);
        try {
            // 多 Worker 同 ID 注册由主键裁决；不使用包围事务，避免冲突后事务不可继续执行。
            workerNodeMapper.insert(node);
        } catch (DuplicateKeyException ignored) {
            workerNodeMapper.heartbeat(workerId, activeJobs, now);
        }
    }

    /** 查询健康 Worker。 */
    public List<ForecastWorkerNodeDO> healthyWorkers() {
        Date threshold = Date.from(
                databaseTime().toInstant().minusSeconds(properties.getWorker().getLeaseSeconds()));
        return workerNodeMapper.selectHealthy(threshold);
    }

    /** 记录 Profile/Stream 同步成功。 */
    @Transactional
    public void touchSync(Long profileId, Long streamId) {
        Date now = databaseTime();
        profileMapper.touchSync(profileId, now);
        streamMapper.touchSync(streamId, now);
    }

    /** 记录 Profile 预测成功。 */
    public void touchForecast(Long profileId) {
        profileMapper.touchForecast(profileId, databaseTime());
    }

    /**
     * 分页查询任务；只选控制表，不加载决策库事实。
     *
     * @param profileId 可选 Profile ID。
     * @param pageNum 页码。
     * @param pageSize 页大小。
     * @return 分页任务响应。
     */
    public PageInfo<ForecastJobResp> listJobs(Long profileId, int pageNum, int pageSize) {
        return listJobs(profileId == null ? List.of() : List.of(profileId), pageNum, pageSize);
    }

    /**
     * 分页查询一组已授权 Profile 的任务。
     *
     * @param profileIds 已授权 Profile ID；为空返回空页。
     * @param pageNum 页码。
     * @param pageSize 页大小。
     * @return 任务分页。
     */
    public PageInfo<ForecastJobResp> listJobs(List<Long> profileIds, int pageNum, int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        if (profileIds == null || profileIds.isEmpty()) {
            PageInfo<ForecastJobResp> empty = new PageInfo<>();
            empty.setPageNum(safePage);
            empty.setPageSize(safeSize);
            empty.setTotal(0);
            empty.setPages(0);
            empty.setList(List.of());
            return empty;
        }
        LambdaQueryWrapper<ForecastJobDO> query = new LambdaQueryWrapper<>();
        query.in(ForecastJobDO::getProfileId, profileIds).orderByDesc(ForecastJobDO::getCreatedAt)
                .orderByDesc(ForecastJobDO::getId);
        PageInfo<ForecastJobDO> page = PageHelper.startPage(safePage, safeSize)
                .doSelectPageInfo(() -> jobMapper.selectList(query));
        PageInfo<ForecastJobResp> response = new PageInfo<>();
        response.setPageNum(safePage);
        response.setPageSize(safeSize);
        response.setTotal(page.getTotal());
        response.setPages(page.getPages());
        response.setList(page.getList().stream().map(this::toResponse).toList());
        return response;
    }

    /** 将任务 DO 转换为公共响应。 */
    public ForecastJobResp toResponse(ForecastJobDO job) {
        return ForecastJobResp.builder().id(job.getId()).parentJobId(job.getParentJobId())
                .profileId(job.getProfileId()).streamId(job.getStreamId())
                .mappingId(job.getMappingId()).type(ForecastJobType.valueOf(job.getJobType()))
                .status(ForecastJobStatus.valueOf(job.getStatus()))
                .progressPercent(defaultInt(job.getProgressPercent()))
                .rowsRead(defaultLong(job.getRowsRead()))
                .rowsWritten(defaultLong(job.getRowsWritten()))
                .rowsAggregated(defaultLong(job.getRowsAggregated()))
                .checkpoint(new ForecastCursor(toInstant(job.getCheckpointUpdatedAt()),
                        job.getCheckpointRecordId()))
                .retryCount(defaultInt(job.getRetryCount())).workerId(job.getWorkerId())
                .errorCode(job.getErrorCode()).errorMessage(job.getErrorMessage())
                .createdBy(job.getCreatedBy()).createdAt(toInstant(job.getCreatedAt()))
                .startedAt(toInstant(job.getStartedAt())).finishedAt(toInstant(job.getFinishedAt()))
                .heartbeatAt(toInstant(job.getHeartbeatAt())).build();
    }

    /** 获取元数据库当前时间，统一租约时钟。 */
    public Date databaseTime() {
        return jobMapper.selectDatabaseTime();
    }

    /** 校验重复幂等键对应同一请求。 */
    private void assertSameFingerprint(ForecastJobDO existing, String fingerprint) {
        if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw new InvalidArgumentException("Idempotency-Key 已被不同请求使用");
        }
    }

    /** 可空 Instant 转 Date。 */
    private Date toDate(Instant value) {
        return value == null ? null : Date.from(value);
    }

    /** 可空 Date 转 Instant。 */
    private Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }

    /** 可空整数归零。 */
    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** 可空长整数归零。 */
    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
