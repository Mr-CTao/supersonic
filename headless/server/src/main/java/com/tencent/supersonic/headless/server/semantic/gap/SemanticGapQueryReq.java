package com.tencent.supersonic.headless.server.semantic.gap;

import lombok.Data;

/**
 * 语义缺口列表查询条件。
 *
 * <p>
 * 职责说明：封装管理端语义缺口池的筛选和分页参数，Controller 可直接从 GET query string 绑定。并发说明：请求对象无共享状态。
 * </p>
 */
@Data
public class SemanticGapQueryReq {

    private Integer assistantId;

    private Long domainId;

    private Long dataSourceId;

    private String failureType;

    private String status;

    private String keyword;

    private String startTime;

    private String endTime;

    private Integer page = 1;

    private Integer pageSize = 20;
}
