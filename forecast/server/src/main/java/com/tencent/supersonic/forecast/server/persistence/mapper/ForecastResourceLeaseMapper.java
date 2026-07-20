package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastResourceLeaseDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 跨进程资源租约的条件更新接口。
 */
@Mapper
public interface ForecastResourceLeaseMapper extends BaseMapper<ForecastResourceLeaseDO> {

    /**
     * 仅允许原持有者续租，或在租约过期后由新任务接管。
     *
     * @param leaseKey 资源键。
     * @param jobId 新持有任务。
     * @param workerId Worker ID。
     * @param now 元数据库时间。
     * @param expiresAt 新失效时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_resource_lease SET owner_job_id=#{jobId}, worker_id=#{workerId}, "
            + "lease_expires_at=#{expiresAt}, updated_at=#{now}, lock_version=lock_version+1 "
            + "WHERE lease_key=#{leaseKey} AND (lease_expires_at < #{now} "
            + "OR (owner_job_id=#{jobId} AND worker_id=#{workerId}))")
    int acquire(@Param("leaseKey") String leaseKey, @Param("jobId") Long jobId,
            @Param("workerId") String workerId, @Param("now") Date now,
            @Param("expiresAt") Date expiresAt);

    /**
     * 原持有者释放资源。
     *
     * @param leaseKey 资源键。
     * @param jobId 任务 ID。
     * @param workerId Worker ID。
     * @param now 更新时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_resource_lease SET owner_job_id=NULL, worker_id=NULL, "
            + "lease_expires_at=#{now}, updated_at=#{now}, lock_version=lock_version+1 "
            + "WHERE lease_key=#{leaseKey} AND owner_job_id=#{jobId} AND worker_id=#{workerId}")
    int release(@Param("leaseKey") String leaseKey, @Param("jobId") Long jobId,
            @Param("workerId") String workerId, @Param("now") Date now);
}
