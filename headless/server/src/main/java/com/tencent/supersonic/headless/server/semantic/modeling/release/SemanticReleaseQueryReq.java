package com.tencent.supersonic.headless.server.semantic.modeling.release;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 阶段 5 待审批与发布记录分页查询参数。
 *
 * <p>
 * 职责说明：统一承载状态、关键词和分页条件；服务端仍会规范化状态并限制最大页大小，避免 管理端请求无界加载。实例为请求级对象，无线程安全风险。
 * </p>
 */
@Data
public class SemanticReleaseQueryReq {

    @Size(max = 32, message = "状态长度不能超过 32 个字符")
    private String status;

    @Size(max = 128, message = "关键词不能超过 128 个字符")
    private String keyword;

    @Min(value = 1, message = "页码必须大于 0")
    private int page = 1;

    @Min(value = 1, message = "每页数量必须大于 0")
    @Max(value = SemanticReleaseConstants.MAX_PAGE_SIZE, message = "每页数量不能超过 100")
    private int pageSize = SemanticReleaseConstants.DEFAULT_PAGE_SIZE;
}
