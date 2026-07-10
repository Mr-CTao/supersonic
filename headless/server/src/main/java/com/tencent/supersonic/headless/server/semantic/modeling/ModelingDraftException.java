package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.List;

/**
 * AI 语义建模草稿业务异常。
 *
 * <p>
 * 职责说明：显式携带 HTTP 状态、稳定错误码和脱敏校验问题，由专用异常处理器转换响应，避免把 Provider 堆栈、Prompt 或数据库细节返回前端。异常对象不持有共享状态。
 * </p>
 */
@Getter
public class ModelingDraftException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<ModelingValidationIssue> issues;

    /**
     * 创建不带字段问题的业务异常。
     *
     * @param status HTTP 状态。
     * @param errorCode 稳定错误码。
     * @param message 脱敏后的用户提示。
     */
    public ModelingDraftException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, Collections.emptyList());
    }

    /**
     * 创建带结构化校验问题的业务异常。
     *
     * @param status HTTP 状态。
     * @param errorCode 稳定错误码。
     * @param message 脱敏后的用户提示。
     * @param issues 字段级问题列表。
     */
    public ModelingDraftException(HttpStatus status, String errorCode, String message,
            List<ModelingValidationIssue> issues) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.issues = issues == null ? Collections.emptyList() : List.copyOf(issues);
    }
}
