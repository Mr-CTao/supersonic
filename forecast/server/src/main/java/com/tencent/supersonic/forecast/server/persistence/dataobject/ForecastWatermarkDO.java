package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 按数据流和映射版本保存的正式水位与断点。
 */
@Data
@TableName("s2_forecast_watermark")
public class ForecastWatermarkDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long streamId;
    private Long mappingId;
    private Date watermarkUpdatedAt;
    private String watermarkRecordId;
    private Date checkpointUpdatedAt;
    private String checkpointRecordId;
    private String lastBatchId;
    private Date lastSuccessAt;
    private Integer lockVersion;
    private Date createdAt;
    private Date updatedAt;
}
