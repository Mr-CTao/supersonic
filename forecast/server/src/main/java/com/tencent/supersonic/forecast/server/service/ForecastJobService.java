package com.tencent.supersonic.forecast.server.service;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastJobStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.enums.ForecastMappingStatus;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.api.response.ForecastJobResp;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastMappingDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastStreamDO;
import com.tencent.supersonic.forecast.server.util.ForecastJson;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Forecast 手动任务、取消、重试和运行中心查询服务。
 *
 * <p>
 * 任务提交只写元数据库队列，不在 HTTP 线程访问源库。所有创建操作使用 {@code Idempotency-Key}，同键不同请求会被拒绝。
 * </p>
 */
@Service
public class ForecastJobService {

    private static final Set<ForecastJobStatus> RETRYABLE =
            Set.of(ForecastJobStatus.FAILED, ForecastJobStatus.CANCELLED);

    private final ForecastControlStore controlStore;
    private final ForecastAccessService accessService;
    private final ForecastJson json;

    /**
     * 创建任务服务。
     *
     * @param controlStore 控制面服务。
     * @param accessService ACL 服务。
     * @param json JSON 工具。
     */
    public ForecastJobService(ForecastControlStore controlStore,
            ForecastAccessService accessService, ForecastJson json) {
        this.controlStore = controlStore;
        this.accessService = accessService;
        this.json = json;
    }

    /**
     * 幂等提交手动任务。
     *
     * @param request 任务请求。
     * @param idempotencyKey 幂等键。
     * @param user 当前用户。
     * @return 任务快照。
     */
    public ForecastJobResp create(ForecastJobReq request, String idempotencyKey, User user) {
        ForecastProfileDO profile = controlStore.requireProfile(request.getProfileId());
        accessService.requireAdmin(profile.getSourceDatabaseId(), user);
        validateTarget(request, profile);
        ForecastJobDO job = controlStore.createJob(request, idempotencyKey, user.getName());
        return controlStore.toResponse(job);
    }

    /**
     * 请求取消任务；运行任务在页边界结束。
     *
     * @param jobId 任务 ID。
     * @param idempotencyKey 请求幂等键。
     * @param user 当前用户。
     * @return 最新任务快照。
     */
    public ForecastJobResp cancel(Long jobId, String idempotencyKey, User user) {
        validateIdempotencyKey(idempotencyKey);
        ForecastJobDO job = requireManageableJob(jobId, user);
        ForecastJobStatus status = ForecastJobStatus.valueOf(job.getStatus());
        if (status == ForecastJobStatus.CANCELLING || status == ForecastJobStatus.CANCELLED) {
            return controlStore.toResponse(job);
        }
        if (!controlStore.requestCancel(jobId)) {
            // CAS 未命中可能是并发取消已经生效，重新读取后按幂等语义返回终态。
            ForecastJobDO current = controlStore.requireJob(jobId);
            ForecastJobStatus currentStatus = ForecastJobStatus.valueOf(current.getStatus());
            if (currentStatus == ForecastJobStatus.CANCELLING
                    || currentStatus == ForecastJobStatus.CANCELLED) {
                return controlStore.toResponse(current);
            }
            throw new InvalidArgumentException("当前任务状态不能取消");
        }
        return controlStore.toResponse(controlStore.requireJob(jobId));
    }

    /**
     * 使用新幂等键重试失败或已取消任务。
     *
     * @param jobId 原任务 ID。
     * @param idempotencyKey 新幂等键。
     * @param user 当前用户。
     * @return 新任务。
     */
    public ForecastJobResp retry(Long jobId, String idempotencyKey, User user) {
        ForecastJobDO original = requireManageableJob(jobId, user);
        ForecastJobStatus status = ForecastJobStatus.valueOf(original.getStatus());
        if (!RETRYABLE.contains(status)) {
            throw new InvalidArgumentException("只有失败或已取消任务可以重试");
        }
        ForecastJobReq request = json.read(original.getParametersJson(), ForecastJobReq.class);
        validateTarget(request, controlStore.requireProfile(original.getProfileId()));
        ForecastJobDO retry =
                controlStore.createJob(request, idempotencyKey, user.getName(), original.getId());
        return controlStore.toResponse(retry);
    }

    /**
     * 分页查询当前用户有权查看的任务。
     *
     * @param profileId 可选 Profile 过滤。
     * @param pageNum 页码。
     * @param pageSize 页大小。
     * @param user 当前用户。
     * @return 任务分页。
     */
    public PageInfo<ForecastJobResp> list(Long profileId, int pageNum, int pageSize, User user) {
        if (profileId != null) {
            ForecastProfileDO profile = controlStore.requireProfile(profileId);
            accessService.requireViewer(profile.getSourceDatabaseId(), user);
            return controlStore.listJobs(profileId, pageNum, pageSize);
        }
        List<DatabaseResp> visible = accessService.visibleDatabases(user);
        Set<Long> visibleDatabaseIds =
                visible.stream().map(DatabaseResp::getId).collect(Collectors.toSet());
        List<Long> profileIds = controlStore.profileIdsBySourceDatabases(visibleDatabaseIds);
        return controlStore.listJobs(profileIds, pageNum, pageSize);
    }

    /**
     * 由 Worker 调度器幂等创建系统任务。
     *
     * @param request 任务请求。
     * @param scheduleDate Profile 本地调度日期。
     * @param slot 调度类型标识。
     * @return 系统任务。
     */
    public ForecastJobDO createScheduled(ForecastJobReq request, LocalDate scheduleDate,
            String slot) {
        String key = "schedule:" + request.getProfileId() + ":" + slot + ":" + scheduleDate;
        validateTarget(request, controlStore.requireProfile(request.getProfileId()));
        return controlStore.createJob(request, key, "forecast-scheduler");
    }

    /** 校验任务的 Profile/Stream/Mapping 归属和生命周期。 */
    private void validateTarget(ForecastJobReq request, ForecastProfileDO profile) {
        if (!Boolean.TRUE.equals(profile.getEnabled())) {
            throw new InvalidArgumentException("Profile 已停用，不能创建新任务");
        }
        ForecastJobType type = request.getType();
        if (type == ForecastJobType.AGGREGATE || type == ForecastJobType.PUBLISH_SEMANTIC_MODEL) {
            throw new InvalidArgumentException("该任务类型仅允许预测服务内部创建");
        }
        if (type == ForecastJobType.INITIAL_SYNC) {
            if (request.getStreamId() == null || request.getMappingId() == null) {
                throw new InvalidArgumentException("首次同步必须指定数据流和已发布映射");
            }
            ForecastStreamDO stream =
                    controlStore.requireStream(profile.getId(), request.getStreamId());
            ForecastMappingDO mapping =
                    controlStore.requireMapping(stream.getId(), request.getMappingId());
            if (!ForecastMappingStatus.PUBLISHED.name().equals(mapping.getStatus())) {
                throw new InvalidArgumentException("首次同步只能使用已发布映射");
            }
            return;
        }
        if (type == ForecastJobType.FORECAST
                && (request.getStreamId() != null || request.getMappingId() != null)) {
            throw new InvalidArgumentException("预测任务按整个 Profile 执行，不能指定数据流或映射");
        }
        if (request.getStreamId() != null) {
            ForecastStreamDO stream =
                    controlStore.requireStream(profile.getId(), request.getStreamId());
            if (request.getMappingId() != null) {
                controlStore.requireMapping(stream.getId(), request.getMappingId());
                if (!request.getMappingId().equals(stream.getActiveMappingId())) {
                    throw new InvalidArgumentException("增量或对账任务只能使用当前活动映射");
                }
            }
        } else if (request.getMappingId() != null) {
            throw new InvalidArgumentException("指定映射时必须同时指定所属数据流");
        }
    }

    /** 读取任务并校验源数据库管理员权限。 */
    private ForecastJobDO requireManageableJob(Long jobId, User user) {
        ForecastJobDO job = controlStore.requireJob(jobId);
        ForecastProfileDO profile = controlStore.requireProfile(job.getProfileId());
        accessService.requireAdmin(profile.getSourceDatabaseId(), user);
        return job;
    }

    /** 校验非任务创建类写操作也必须携带合法幂等键。 */
    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new InvalidArgumentException("Idempotency-Key 必填且不能超过 128 字符");
        }
    }
}
