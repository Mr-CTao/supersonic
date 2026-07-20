package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Profile 下一个可独立同步的数据流。
 */
@Data
@TableName("s2_forecast_stream")
public class ForecastStreamDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long profileId;
    private String name;
    private Boolean enabled;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;
    private Long activeMappingId;
    private Integer activeMappingVersion;
    private Integer lockVersion;
    private Date lastSyncAt;
    private String createdBy;
    private Date createdAt;
    private String updatedBy;
    private Date updatedAt;
}
