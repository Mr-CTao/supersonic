package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 语义建模草稿错误响应。
 *
 * <p>
 * 职责说明：仅暴露稳定错误码、友好消息和结构化校验问题，不包含内部异常栈或 LLM 原文。
 * </p>
 */
@Data
@Builder
public class ModelingDraftErrorResp {
    private String code;
    private String message;
    private List<ModelingValidationIssue> issues;
}
