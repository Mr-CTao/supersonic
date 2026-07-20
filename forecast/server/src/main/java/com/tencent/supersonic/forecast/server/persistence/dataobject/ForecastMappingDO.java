package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 不可变的数据流映射版本。
 */
@Data
@TableName("s2_forecast_mapping")
public class ForecastMappingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long streamId;
    private Integer mappingVersion;
    private String status;
    private String configJson;
    private String configChecksum;
    private Boolean valid;
    private String validationSummary;
    private String createdBy;
    private Date createdAt;
    private Date publishedAt;
}
