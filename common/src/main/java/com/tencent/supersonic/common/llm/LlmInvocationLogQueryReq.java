package com.tencent.supersonic.common.llm;

import lombok.Data;

/**
 * LLM 调用日志查询请求。
 *
 * <p>
 * 职责说明：承载调用日志 Tab 的筛选条件，包括供应商、模型、状态、错误码、时间范围和会话 ID。并发说明：请求 DTO 不含共享状态。
 * </p>
 */
@Data
public class LlmInvocationLogQueryReq {

    private String providerType;

    private String modelName;

    private String status;

    private String errorCode;

    private Long conversationId;

    private String startTime;

    private String endTime;

    private Integer pageNo = 1;

    private Integer pageSize = 20;
}
