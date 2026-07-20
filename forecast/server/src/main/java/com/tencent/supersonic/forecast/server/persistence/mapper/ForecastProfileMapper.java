package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * Forecast Profile 持久化与乐观锁更新。
 */
@Mapper
public interface ForecastProfileMapper extends BaseMapper<ForecastProfileDO> {

    /**
     * 使用 lockVersion 原子更新可配置字段。
     *
     * @param profile 新值。
     * @param expectedVersion 客户端读取的版本。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_profile SET name=#{profile.name}, "
            + "source_database_id=#{profile.sourceDatabaseId}, "
            + "decision_database_id=#{profile.decisionDatabaseId}, time_zone=#{profile.timeZone}, "
            + "sync_cron=#{profile.syncCron}, forecast_cron=#{profile.forecastCron}, "
            + "reconcile_cron=#{profile.reconcileCron}, history_days=#{profile.historyDays}, "
            + "enabled=#{profile.enabled}, updated_by=#{profile.updatedBy}, "
            + "updated_at=#{profile.updatedAt}, lock_version=lock_version+1 "
            + "WHERE id=#{profile.id} AND deleted=FALSE AND lock_version=#{expectedVersion}")
    int updateOptimistic(@Param("profile") ForecastProfileDO profile,
            @Param("expectedVersion") int expectedVersion);

    /**
     * 记录最近成功同步时间。
     *
     * @param id Profile ID。
     * @param time 数据库任务完成时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_profile SET last_sync_at=#{time}, updated_at=#{time}, "
            + "lock_version=lock_version+1 WHERE id=#{id} AND deleted=FALSE")
    int touchSync(@Param("id") Long id, @Param("time") Date time);

    /**
     * 记录最近成功预测时间。
     *
     * @param id Profile ID。
     * @param time 数据库任务完成时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_profile SET last_forecast_at=#{time}, updated_at=#{time}, "
            + "lock_version=lock_version+1 WHERE id=#{id} AND deleted=FALSE")
    int touchForecast(@Param("id") Long id, @Param("time") Date time);

    /**
     * 首次版本激活时同时提交同步和预测时间，并以 Profile 版本阻止并发配置覆盖。
     *
     * @param id Profile ID。
     * @param expectedVersion 激活前读取的版本。
     * @param time 同步和预测完成时间。
     * @return 1 表示 Profile 仍启用且版本未变化。
     */
    @Update("UPDATE s2_forecast_profile SET last_sync_at=#{time}, last_forecast_at=#{time}, "
            + "updated_at=#{time}, lock_version=lock_version+1 WHERE id=#{id} "
            + "AND enabled=TRUE AND deleted=FALSE AND lock_version=#{expectedVersion}")
    int touchInitialActivation(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
            @Param("time") Date time);
}
