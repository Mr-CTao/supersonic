package com.tencent.supersonic.forecast.api.response;

import com.tencent.supersonic.forecast.api.enums.ForecastJobStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 运行中心展示的任务快照。
 */
@Data
@Builder
public class ForecastJobResp {
    private Long id;
    private Long parentJobId;
    private Long profileId;
    private Long streamId;
    private Long mappingId;
    private ForecastJobType type;
    private ForecastJobStatus status;
    private int progressPercent;
    private long rowsRead;
    private long rowsWritten;
    private long rowsAggregated;
    private ForecastCursor checkpoint;
    private int retryCount;
    private String workerId;
    private String errorCode;
    private String errorMessage;
    private String createdBy;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant heartbeatAt;
}
