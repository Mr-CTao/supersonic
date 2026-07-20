package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastWatermarkDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 数据流正式水位与检查点的乐观锁持久化接口。
 */
@Mapper
public interface ForecastWatermarkMapper extends BaseMapper<ForecastWatermarkDO> {

    /** 水位查询固定投影。 */
    String WATERMARK_COLUMNS = "id,stream_id,mapping_id,watermark_updated_at,"
            + "watermark_record_id,checkpoint_updated_at,checkpoint_record_id,last_batch_id,"
            + "last_success_at,lock_version,created_at,updated_at";

    /**
     * 查询指定映射的水位。
     *
     * @param streamId 数据流 ID。
     * @param mappingId 映射 ID。
     * @return 水位或 null。
     */
    @Select("SELECT " + WATERMARK_COLUMNS + " FROM s2_forecast_watermark "
            + "WHERE stream_id=#{streamId} AND mapping_id=#{mappingId}")
    ForecastWatermarkDO selectForMapping(@Param("streamId") Long streamId,
            @Param("mappingId") Long mappingId);

    /**
     * 在决策库批次提交后推进检查点和正式水位。
     *
     * @param watermark 新水位。
     * @param expectedVersion 期望版本。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_watermark SET watermark_updated_at=#{watermark.watermarkUpdatedAt}, "
            + "watermark_record_id=#{watermark.watermarkRecordId}, "
            + "checkpoint_updated_at=#{watermark.checkpointUpdatedAt}, "
            + "checkpoint_record_id=#{watermark.checkpointRecordId}, "
            + "last_batch_id=#{watermark.lastBatchId}, last_success_at=#{watermark.lastSuccessAt}, "
            + "updated_at=#{watermark.updatedAt}, lock_version=lock_version+1 "
            + "WHERE id=#{watermark.id} AND lock_version=#{expectedVersion}")
    int advance(@Param("watermark") ForecastWatermarkDO watermark,
            @Param("expectedVersion") int expectedVersion);
}
