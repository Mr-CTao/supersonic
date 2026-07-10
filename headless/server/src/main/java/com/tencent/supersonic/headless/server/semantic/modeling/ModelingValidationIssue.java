package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 结构化草稿校验问题。
 *
 * <p>
 * 职责说明：使用稳定路径和错误码向 Worker、保存接口及测试说明失败位置，不包含样例值或 Prompt。 并发说明：实例为单次校验结果，不共享。
 * </p>
 */
@Data
@AllArgsConstructor
public class ModelingValidationIssue {
    private String path;
    private String code;
    private String message;
}
