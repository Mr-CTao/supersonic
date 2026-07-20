package com.tencent.supersonic.forecast.api.response;

import com.tencent.supersonic.forecast.api.enums.ForecastJobStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 数据流当前阻塞或最近一次首次同步激活任务摘要。
 *
 * <p>
 * 当前活动映射仍由 {@link ForecastStreamResp} 的 activeMapping 字段表达；本摘要只描述异步
 * 激活过程和最近结果，使页面刷新后仍能恢复排队、运行、失败或取消状态。
 * </p>
 */
@Data
@Builder
public class ForecastActivationSummaryResp {
    private Long jobId;
    private Long mappingId;
    private Integer mappingVersion;
    private ForecastJobStatus status;
    private int progressPercent;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
}
