package com.tencent.supersonic.forecast.server.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 可幂等、可租约恢复的 Worker 任务记录。
 *
 * <p>
 * 任务并发由数据库条件更新和 lockVersion 保护，Java 进程内锁仅用于限制线程池吞吐。
 * </p>
 */
@Data
@TableName("s2_forecast_job")
public class ForecastJobDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentJobId;
    private Long profileId;
    private Long streamId;
    private Long mappingId;
    private String jobType;
    private String status;
    /** 非终态业务任务持有的数据库唯一并发槽；进入终态时必须清空。 */
    private String activeConcurrencyKey;
    private String idempotencyKey;
    private String requestFingerprint;
    private String parametersJson;
    private Integer progressPercent;
    private Long rowsRead;
    private Long rowsWritten;
    private Long rowsAggregated;
    private Date checkpointUpdatedAt;
    private String checkpointRecordId;
    private Integer retryCount;
    private Integer maxRetries;
    private String workerId;
    private Date leaseExpiresAt;
    private Date heartbeatAt;
    private String errorCode;
    private String errorMessage;
    private Integer lockVersion;
    private String createdBy;
    private Date createdAt;
    private Date startedAt;
    private Date finishedAt;
    private Date updatedAt;
}
