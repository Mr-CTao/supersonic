package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastStreamDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * Forecast 数据流持久化接口。
 */
@Mapper
public interface ForecastStreamMapper extends BaseMapper<ForecastStreamDO> {

    /** 数据流查询固定投影。 */
    String STREAM_COLUMNS = "id,profile_id,name,enabled,deleted,active_mapping_id,"
            + "active_mapping_version,lock_version,last_sync_at,created_by,created_at,"
            + "updated_by,updated_at";

    /**
     * 查询 Profile 下未删除数据流。
     *
     * @param profileId Profile ID。
     * @return 按 ID 排序的数据流。
     */
    @Select("SELECT " + STREAM_COLUMNS + " FROM s2_forecast_stream "
            + "WHERE profile_id=#{profileId} AND deleted=FALSE ORDER BY id")
    List<ForecastStreamDO> selectByProfile(@Param("profileId") Long profileId);

    /**
     * 在映射版本号分配事务中锁定所属数据流。
     *
     * @param streamId 数据流 ID。
     * @return 被锁定的数据流；不存在时为空。
     */
    @Select("SELECT " + STREAM_COLUMNS + " FROM s2_forecast_stream "
            + "WHERE id=#{streamId} AND deleted=FALSE FOR UPDATE")
    ForecastStreamDO selectForUpdate(@Param("streamId") Long streamId);

    /**
     * 乐观锁更新数据流基础字段。
     *
     * @param stream 新值。
     * @param expectedVersion 期望版本。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_stream SET name=#{stream.name}, enabled=#{stream.enabled}, "
            + "updated_by=#{stream.updatedBy}, updated_at=#{stream.updatedAt}, "
            + "lock_version=lock_version+1 WHERE id=#{stream.id} AND deleted=FALSE "
            + "AND lock_version=#{expectedVersion}")
    int updateOptimistic(@Param("stream") ForecastStreamDO stream,
            @Param("expectedVersion") int expectedVersion);

    /**
     * 在新版本回填成功后原子切换活动映射。
     *
     * @param streamId 数据流 ID。
     * @param mappingId 映射 ID。
     * @param mappingVersion 映射版本。
     * @param expectedVersion 数据流期望版本。
     * @param updatedBy 操作者。
     * @param updatedAt 更新时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_stream SET active_mapping_id=#{mappingId}, "
            + "active_mapping_version=#{mappingVersion}, updated_by=#{updatedBy}, "
            + "last_sync_at=#{updatedAt}, updated_at=#{updatedAt}, "
            + "lock_version=lock_version+1 WHERE id=#{streamId} AND enabled=TRUE "
            + "AND deleted=FALSE AND lock_version=#{expectedVersion}")
    int activateMapping(@Param("streamId") Long streamId, @Param("mappingId") Long mappingId,
            @Param("mappingVersion") Integer mappingVersion,
            @Param("expectedVersion") int expectedVersion, @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Date updatedAt);

    /**
     * 记录数据流最近成功同步时间。
     *
     * @param streamId 数据流 ID。
     * @param time 完成时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_stream SET last_sync_at=#{time}, updated_at=#{time}, "
            + "lock_version=lock_version+1 WHERE id=#{streamId} AND deleted=FALSE")
    int touchSync(@Param("streamId") Long streamId, @Param("time") Date time);
}
