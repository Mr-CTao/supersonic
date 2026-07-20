package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 预测 Profile 控制面记录。
 *
 * <p>
 * 重型事实不进入该表；lockVersion 用于跨实例乐观锁，deleted 提供可恢复软删除。
 * </p>
 */
@Data
@TableName("s2_forecast_profile")
public class ForecastProfileDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long sourceDatabaseId;
    private Long decisionDatabaseId;
    private String timeZone;
    private String syncCron;
    private String forecastCron;
    private String reconcileCron;
    private Integer historyDays;
    private Boolean enabled;
    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;
    private Integer lockVersion;
    private Date lastSyncAt;
    private Date lastForecastAt;
    private String createdBy;
    private Date createdAt;
    private String updatedBy;
    private Date updatedAt;
}
