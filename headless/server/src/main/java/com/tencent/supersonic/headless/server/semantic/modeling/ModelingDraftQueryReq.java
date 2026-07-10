package com.tencent.supersonic.headless.server.semantic.modeling;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * AI 语义建模草稿分页筛选请求。
 *
 * <p>
 * 职责说明：承载来源、状态、数据源和关键词筛选，不接受排序 SQL 或字段名等危险输入。 并发说明：请求对象只在当前请求线程内使用。
 * </p>
 */
@Data
public class ModelingDraftQueryReq {

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(100)
    private Integer pageSize = 20;

    private String sourceType;

    private String status;

    private Long dataSourceId;

    private String keyword;
}
