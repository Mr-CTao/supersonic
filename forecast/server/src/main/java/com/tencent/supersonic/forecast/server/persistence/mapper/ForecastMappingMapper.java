package com.tencent.supersonic.forecast.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastMappingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * 版本化预测映射持久化接口。
 */
@Mapper
public interface ForecastMappingMapper extends BaseMapper<ForecastMappingDO> {

    /** 映射版本查询固定投影。 */
    String MAPPING_COLUMNS = "id,stream_id,mapping_version,status,config_json,config_checksum,"
            + "valid,validation_summary,created_by,created_at,published_at";

    /**
     * 查询数据流全部映射版本。
     *
     * @param streamId 数据流 ID。
     * @return 版本降序列表。
     */
    @Select("SELECT " + MAPPING_COLUMNS + " FROM s2_forecast_mapping WHERE stream_id=#{streamId} "
            + "ORDER BY mapping_version DESC")
    List<ForecastMappingDO> selectByStream(@Param("streamId") Long streamId);

    /**
     * 读取下一个版本号。
     *
     * @param streamId 数据流 ID。
     * @return 当前最大版本，未创建时为 0。
     */
    @Select("SELECT COALESCE(MAX(mapping_version), 0) FROM s2_forecast_mapping "
            + "WHERE stream_id=#{streamId}")
    int selectMaxVersion(@Param("streamId") Long streamId);

    /**
     * 保存最近一次服务端结构与样例校验结论。
     *
     * @param id 映射 ID。
     * @param valid 是否通过。
     * @param summary 不含源数据值的摘要。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_mapping SET valid=#{valid}, validation_summary=#{summary} "
            + "WHERE id=#{id} AND status='DRAFT'")
    int updateValidation(@Param("id") Long id, @Param("valid") boolean valid,
            @Param("summary") String summary);

    /**
     * 仅允许从 DRAFT 发布有效映射。
     *
     * @param id 映射 ID。
     * @param publishedAt 发布时间。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_mapping SET status='PUBLISHED', published_at=#{publishedAt} "
            + "WHERE id=#{id} AND status='DRAFT' AND valid=TRUE")
    int publish(@Param("id") Long id, @Param("publishedAt") Date publishedAt);

    /**
     * 归档旧发布版本。
     *
     * @param streamId 数据流 ID。
     * @param keepId 仍需保留为发布态的映射 ID。
     * @return 更新行数。
     */
    @Update("UPDATE s2_forecast_mapping SET status='ARCHIVED' WHERE stream_id=#{streamId} "
            + "AND id<>#{keepId} AND status='PUBLISHED'")
    int archiveOtherPublished(@Param("streamId") Long streamId, @Param("keepId") Long keepId);
}
