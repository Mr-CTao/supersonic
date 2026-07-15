package com.tencent.supersonic.headless.server.semantic.gap;

import lombok.Data;

/**
 * 语义缺口采集事件请求。
 *
 * <p>
 * 职责说明：承载 Chat BI 问答链路向缺口池上报的一次失败、回退或负反馈事件。该对象只描述已经发生的信号，不触发 AI
 * 草稿生成，也不修改正式语义资产。并发说明：请求对象按调用栈单次创建，随后提交给语义缺口异步任务消费；提交后调用线程不再读写该对象， 因此不需要额外锁保护。
 * </p>
 */
@Data
public class SemanticGapEventReq {

    private String question;

    private Long queryId;

    private Long chatId;

    private Integer assistantId;

    private Long userId;

    private String userName;

    private Long domainId;

    private Long dataSourceId;

    private SemanticGapFailureType failureType = SemanticGapFailureType.UNKNOWN;

    private String failureReason;

    private String matchedModelIds;

    private String matchedMetricIds;

    private String matchedDimensionIds;

    private String generatedSql;

    private String s2sql;

    private String feedback;

    private String diagnosticStage;

    private String errorCode;

    private String traceId;

    private Integer errorLine;

    private Integer errorColumn;

    private String errorToken;

    private String suggestion;
}
