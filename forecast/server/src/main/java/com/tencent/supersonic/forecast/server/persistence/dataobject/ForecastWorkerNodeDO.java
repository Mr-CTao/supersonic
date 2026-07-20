package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 独立 Forecast Worker 的脱敏心跳记录。
 */
@Data
@TableName("s2_forecast_worker_node")
public class ForecastWorkerNodeDO {
    @TableId
    private String workerId;
    private String workerVersion;
    private Integer activeJobs;
    private Date startedAt;
    private Date heartbeatAt;
}
