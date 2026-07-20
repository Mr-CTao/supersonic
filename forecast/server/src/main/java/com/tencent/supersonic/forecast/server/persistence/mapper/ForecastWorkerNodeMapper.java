package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWorkerNodeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * Forecast Worker 心跳持久化接口。
 */
@Mapper
public interface ForecastWorkerNodeMapper extends BaseMapper<ForecastWorkerNodeDO> {

    /** Worker 健康查询固定投影。 */
    String WORKER_COLUMNS = "worker_id,worker_version,active_jobs,started_at,heartbeat_at";

    /**
     * 更新已注册 Worker 心跳。
     *
     * @param workerId Worker ID。
     * @param activeJobs 活跃任务数。
     * @param heartbeatAt 心跳时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_worker_node SET active_jobs=#{activeJobs}, "
            + "heartbeat_at=#{heartbeatAt} WHERE worker_id=#{workerId}")
    int heartbeat(@Param("workerId") String workerId, @Param("activeJobs") int activeJobs,
            @Param("heartbeatAt") Date heartbeatAt);

    /**
     * 查询指定时间后的健康 Worker。
     *
     * @param threshold 健康阈值。
     * @return Worker 列表。
     */
    @Select("SELECT " + WORKER_COLUMNS + " FROM s2_forecast_worker_node "
            + "WHERE heartbeat_at >= #{threshold} ORDER BY heartbeat_at DESC")
    List<ForecastWorkerNodeDO> selectHealthy(@Param("threshold") Date threshold);
}
