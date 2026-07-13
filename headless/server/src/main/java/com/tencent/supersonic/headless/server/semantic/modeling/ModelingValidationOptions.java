package com.tencent.supersonic.headless.server.semantic.modeling;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 阶段 4 草稿验证选项。
 *
 * <p>
 * 职责说明：限定验证报告中 SQL 预览的最大行数。该对象只控制预览边界，不能关闭 SQL 只读校验、字段存在性或其他强制门禁。 并发说明：选项随单次验证请求创建并持久化为快照，不在请求间共享。
 * </p>
 */
@Data
public class ModelingValidationOptions {

    /** 隔离语义翻译 SQL 的 LIMIT，默认 20；阶段 4 只翻译和检查，不执行。 */
    @Min(1)
    @Max(100)
    private Integer sqlPreviewLimit = 20;
}
