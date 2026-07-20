package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * Forecast 任务幂等读取、租约和状态转换的原子 SQL。
 */
@Mapper
public interface ForecastJobMapper extends BaseMapper<ForecastJobDO> {

    /** 任务查询固定投影，避免表扩展后把非运行必需字段隐式加载到 Worker。 */
    String JOB_COLUMNS = "id,parent_job_id,profile_id,stream_id,mapping_id,job_type,status,"
            + "active_concurrency_key,idempotency_key,request_fingerprint,parameters_json,"
            + "progress_percent,rows_read,"
            + "rows_written,rows_aggregated,checkpoint_updated_at,checkpoint_record_id,"
            + "retry_count,max_retries,worker_id,lease_expires_at,heartbeat_at,error_code,"
            + "error_message,lock_version,created_by,created_at,started_at,finished_at,updated_at";

    /**
     * 按发起人与幂等键查询已有任务。
     *
     * @param createdBy 发起人。
     * @param idempotencyKey 幂等键。
     * @return 已有任务或 null。
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM s2_forecast_job WHERE created_by=#{createdBy} "
            + "AND idempotency_key=#{idempotencyKey}")
    ForecastJobDO selectIdempotent(@Param("createdBy") String createdBy,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 按非终态业务并发键查询占用任务。
     *
     * @param activeConcurrencyKey 服务端生成的并发键。
     * @return 当前占用任务或 null。
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM s2_forecast_job "
            + "WHERE active_concurrency_key=#{activeConcurrencyKey}")
    ForecastJobDO selectActiveByConcurrencyKey(
            @Param("activeConcurrencyKey") String activeConcurrencyKey);

    /**
     * 查询数据流上尚未结束的首次同步任务，兼容并发键迁移前创建的历史排队记录。
     *
     * @param streamId 数据流 ID。
     * @return 最早创建的非终态首次同步任务或 null。
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM s2_forecast_job WHERE stream_id=#{streamId} "
            + "AND job_type='INITIAL_SYNC' AND status IN ('QUEUED','RUNNING','CANCELLING') "
            + "ORDER BY created_at, id LIMIT 1")
    ForecastJobDO selectActiveInitialSync(@Param("streamId") Long streamId);

    /**
     * 批量查询每个数据流当前占用激活槽或最近一次首次同步任务。
     *
     * <p>
     * 升级前可能已经存在多个非终态任务，因此优先返回最早的非终态任务，与 {@link #selectActiveInitialSync(Long)}
     * 的阻塞裁决保持一致；全部进入终态后才返回最新任务。
     * </p>
     *
     * @param streamIds 数据流 ID 集合。
     * @return 每个数据流最多一条当前阻塞任务或最近终态任务。
     */
    @Select({"<script>", "SELECT " + JOB_COLUMNS + " FROM s2_forecast_job WHERE id IN (",
                    "SELECT COALESCE(MIN(CASE WHEN status IN ('QUEUED','RUNNING','CANCELLING') ",
                    "THEN id END), MAX(id)) FROM s2_forecast_job ",
                    "WHERE job_type='INITIAL_SYNC' AND stream_id IN",
                    "<foreach collection='streamIds' item='streamId' open='(' separator=',' close=')'>",
                    "#{streamId}", "</foreach>", "GROUP BY stream_id)", "</script>"})
    List<ForecastJobDO> selectLatestInitialSyncs(@Param("streamIds") List<Long> streamIds);

    /**
     * 查询所有待认领任务；调用方按批次上限截取后逐条 CAS。
     *
     * @param limit 最大返回数。
     * @return 创建时间升序的待认领任务。
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM s2_forecast_job WHERE status='QUEUED' "
            + "ORDER BY created_at, id LIMIT #{limit}")
    List<ForecastJobDO> selectQueued(@Param("limit") int limit);

    /**
     * 查询租约已过期的运行任务。
     *
     * @param now 数据库当前时间。
     * @return 过期任务。
     */
    @Select("SELECT " + JOB_COLUMNS + " FROM s2_forecast_job WHERE status='RUNNING' "
            + "AND lease_expires_at < #{now} ORDER BY lease_expires_at")
    List<ForecastJobDO> selectExpired(@Param("now") Date now);

    /**
     * 原子认领排队任务。
     *
     * @param id 任务 ID。
     * @param expectedVersion 期望版本。
     * @param workerId Worker ID。
     * @param now 开始时间。
     * @param leaseExpiresAt 租约截止时间。
     * @return 1 表示认领成功。
     */
    @Update("UPDATE s2_forecast_job SET status='RUNNING', worker_id=#{workerId}, "
            + "started_at=COALESCE(started_at, #{now}), heartbeat_at=#{now}, "
            + "lease_expires_at=#{leaseExpiresAt}, error_code=NULL, error_message=NULL, "
            + "finished_at=NULL, updated_at=#{now}, lock_version=lock_version+1 "
            + "WHERE id=#{id} AND status='QUEUED' AND lock_version=#{expectedVersion}")
    int claim(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
            @Param("workerId") String workerId, @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt);

    /**
     * 资源租约未获取时把刚认领任务让回队列，不消耗失败重试次数。
     *
     * @param id 任务 ID。
     * @param workerId Worker ID。
     * @param now 更新时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status='QUEUED', worker_id=NULL, lease_expires_at=NULL, "
            + "heartbeat_at=NULL, updated_at=#{now}, lock_version=lock_version+1 WHERE id=#{id} "
            + "AND worker_id=#{workerId} AND status='RUNNING'")
    int yieldClaim(@Param("id") Long id, @Param("workerId") String workerId,
            @Param("now") Date now);

    /**
     * 延长当前 Worker 持有的任务租约。
     *
     * @param id 任务 ID。
     * @param workerId Worker ID。
     * @param now 心跳时间。
     * @param leaseExpiresAt 新租约截止时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET heartbeat_at=#{now}, lease_expires_at=#{leaseExpiresAt}, "
            + "updated_at=#{now}, lock_version=lock_version+1 WHERE id=#{id} "
            + "AND worker_id=#{workerId} AND status='RUNNING'")
    int heartbeat(@Param("id") Long id, @Param("workerId") String workerId, @Param("now") Date now,
            @Param("leaseExpiresAt") Date leaseExpiresAt);

    /**
     * 持久化页级进度和检查点。
     *
     * @param job 进度快照。
     * @param workerId 当前 Worker。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET progress_percent=#{job.progressPercent}, "
            + "rows_read=#{job.rowsRead}, rows_written=#{job.rowsWritten}, "
            + "rows_aggregated=#{job.rowsAggregated}, "
            + "checkpoint_updated_at=#{job.checkpointUpdatedAt}, "
            + "checkpoint_record_id=#{job.checkpointRecordId}, updated_at=#{job.updatedAt}, "
            + "lock_version=lock_version+1 WHERE id=#{job.id} AND worker_id=#{workerId} "
            + "AND status IN ('RUNNING','CANCELLING')")
    int updateProgress(@Param("job") ForecastJobDO job, @Param("workerId") String workerId);

    /**
     * 成功完成任务。
     *
     * @param id 任务 ID。
     * @param workerId Worker ID。
     * @param now 完成时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status='SUCCEEDED', progress_percent=100, "
            + "active_concurrency_key=NULL, "
            + "finished_at=#{now}, lease_expires_at=NULL, updated_at=#{now}, "
            + "lock_version=lock_version+1 WHERE id=#{id} AND worker_id=#{workerId} "
            + "AND status='RUNNING'")
    int complete(@Param("id") Long id, @Param("workerId") String workerId, @Param("now") Date now);

    /**
     * 在自动重试额度内把运行失败任务原子放回队列。
     *
     * @param id 任务 ID。
     * @param workerId Worker ID。
     * @param code 安全错误码。
     * @param message 脱敏消息。
     * @param now 更新时间。
     * @return 1 表示已重新排队，0 表示任务已取消、租约丢失或额度耗尽。
     */
    @Update("UPDATE s2_forecast_job SET status='QUEUED', retry_count=retry_count+1, "
            + "error_code=#{code}, error_message=#{message}, worker_id=NULL, heartbeat_at=NULL, "
            + "lease_expires_at=NULL, finished_at=NULL, updated_at=#{now}, "
            + "lock_version=lock_version+1 WHERE id=#{id} AND worker_id=#{workerId} "
            + "AND status='RUNNING' AND retry_count < max_retries")
    int requeueFailure(@Param("id") Long id, @Param("workerId") String workerId,
            @Param("code") String code, @Param("message") String message, @Param("now") Date now);

    /**
     * 将耗尽额度的运行任务置为失败；并发取消优先收敛为已取消。
     *
     * @param id 任务 ID。
     * @param workerId Worker ID。
     * @param code 安全错误码。
     * @param message 脱敏消息。
     * @param now 完成时间。
     * @return CAS 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status=CASE WHEN status='CANCELLING' "
            + "THEN 'CANCELLED' ELSE 'FAILED' END, error_code=#{code}, "
            + "error_message=#{message}, finished_at=#{now}, worker_id=NULL, "
            + "heartbeat_at=NULL, lease_expires_at=NULL, active_concurrency_key=NULL, "
            + "updated_at=#{now}, "
            + "lock_version=lock_version+1 WHERE id=#{id} AND worker_id=#{workerId} "
            + "AND status IN ('RUNNING','CANCELLING')")
    int failTerminal(@Param("id") Long id, @Param("workerId") String workerId,
            @Param("code") String code, @Param("message") String message, @Param("now") Date now);

    /**
     * 请求运行任务在页边界取消，或直接取消排队任务。
     *
     * @param id 任务 ID。
     * @param now 更新时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status=CASE WHEN status='QUEUED' THEN 'CANCELLED' "
            + "ELSE 'CANCELLING' END, finished_at=CASE WHEN status='QUEUED' THEN #{now} "
            + "ELSE finished_at END, active_concurrency_key=CASE WHEN status='QUEUED' "
            + "THEN NULL ELSE active_concurrency_key END, updated_at=#{now}, "
            + "lock_version=lock_version+1 " + "WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int requestCancel(@Param("id") Long id, @Param("now") Date now);

    /**
     * Worker 确认取消完成。
     *
     * @param id 任务 ID。
     * @param workerId Worker ID。
     * @param now 完成时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status='CANCELLED', finished_at=#{now}, "
            + "lease_expires_at=NULL, active_concurrency_key=NULL, updated_at=#{now}, "
            + "lock_version=lock_version+1 "
            + "WHERE id=#{id} AND worker_id=#{workerId} AND status='CANCELLING'")
    int confirmCancelled(@Param("id") Long id, @Param("workerId") String workerId,
            @Param("now") Date now);

    /**
     * 将过期任务放回队列并增加重试次数。
     *
     * @param id 任务 ID。
     * @param expectedVersion 期望版本。
     * @param now 更新时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status='QUEUED', retry_count=retry_count+1, "
            + "worker_id=NULL, lease_expires_at=NULL, heartbeat_at=NULL, error_code=NULL, "
            + "error_message=NULL, updated_at=#{now}, lock_version=lock_version+1 "
            + "WHERE id=#{id} AND status='RUNNING' AND lock_version=#{expectedVersion} "
            + "AND retry_count < max_retries")
    int requeueExpired(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
            @Param("now") Date now);

    /**
     * 标记超过重试上限的过期任务失败。
     *
     * @param id 任务 ID。
     * @param expectedVersion 期望版本。
     * @param now 完成时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_job SET status='FAILED', error_code='LEASE_EXPIRED', "
            + "error_message='Worker 租约过期且已达到最大重试次数', finished_at=#{now}, "
            + "worker_id=NULL, lease_expires_at=NULL, active_concurrency_key=NULL, "
            + "updated_at=#{now}, "
            + "lock_version=lock_version+1 WHERE id=#{id} AND status='RUNNING' "
            + "AND lock_version=#{expectedVersion} AND retry_count >= max_retries")
    int failExpired(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
            @Param("now") Date now);

    /**
     * 获取元数据库时间，作为租约唯一时钟。
     *
     * @return 数据库当前时间。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    Date selectDatabaseTime();
}
