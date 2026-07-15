package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.exception.AccessException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.server.rest.SemanticAssetRoutingController;
import com.tencent.supersonic.headless.server.rest.SemanticGapController;
import com.tencent.supersonic.headless.server.rest.SemanticGapModelingDraftController;
import com.tencent.supersonic.headless.server.rest.SemanticModelingDraftController;
import com.tencent.supersonic.headless.server.rest.SemanticModelingValidationReportController;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRoutingException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 语义建模草稿专用异常处理器。
 *
 * <p>
 * 职责说明：优先于项目通用异常处理器返回 4xx/5xx 语义，尤其保留乐观锁冲突的 HTTP 409。 只处理本模块异常，不改变其他接口的历史返回约定。
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {SemanticModelingDraftController.class,
                SemanticModelingValidationReportController.class, SemanticGapController.class,
                SemanticGapModelingDraftController.class, SemanticAssetRoutingController.class})
@Slf4j
public class ModelingDraftExceptionHandler {

    /**
     * 将草稿业务异常转换为脱敏响应。
     *
     * @param exception 草稿业务异常。
     * @return 带正确 HTTP 状态的错误响应。
     */
    @ExceptionHandler(ModelingDraftException.class)
    public ResponseEntity<ModelingDraftErrorResp> handle(ModelingDraftException exception) {
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(exception.getErrorCode())
                        .message(exception.getMessage()).issues(exception.getIssues()).build();
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    /**
     * 将资产路由的幂等、版本、权限和输出约束异常转换为稳定状态码。
     *
     * @param exception 只携带脱敏原因的路由业务异常。
     * @return 保留 HTTP 4xx/5xx 语义的脱敏错误响应。
     */
    @ExceptionHandler(SemanticAssetRoutingException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleRouting(
            SemanticAssetRoutingException exception) {
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(exception.getErrorCode())
                        .message(exception.getReason()).issues(List.of()).build();
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }

    /**
     * 将请求 DTO 校验失败转换为 HTTP 400 和稳定字段问题。
     *
     * @param exception 请求体校验异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleValidation(
            MethodArgumentNotValidException exception) {
        return badRequest(toIssues(exception.getBindingResult().getFieldErrors()));
    }

    /**
     * 将查询参数绑定校验失败转换为 HTTP 400。
     *
     * @param exception 参数绑定异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleBind(BindException exception) {
        return badRequest(toIssues(exception.getBindingResult().getFieldErrors()));
    }

    /**
     * 将路径或方法级约束失败转换为 HTTP 400。
     *
     * @param exception 约束异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleConstraint(
            ConstraintViolationException exception) {
        List<ModelingValidationIssue> issues = exception.getConstraintViolations().stream()
                .map(violation -> new ModelingValidationIssue(
                        violation.getPropertyPath().toString(), "INVALID_PARAMETER",
                        violation.getMessage()))
                .toList();
        return badRequest(issues);
    }

    /**
     * 将缺少 Idempotency-Key 等必填请求头转换为 HTTP 400。
     *
     * @param exception 缺失请求头异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleMissingHeader(
            MissingRequestHeaderException exception) {
        return badRequest(List.of(new ModelingValidationIssue(
                "$header." + exception.getHeaderName(), "MISSING_HEADER", "缺少必填请求头")));
    }

    /**
     * 将畸形 JSON 转换为稳定 HTTP 400，不回显解析器异常或原始请求片段。
     *
     * @param exception 请求体解析异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleUnreadableBody(
            HttpMessageNotReadableException exception) {
        return badRequest(
                List.of(new ModelingValidationIssue("$", "MALFORMED_JSON", "请求体不是合法 JSON")));
    }

    /**
     * 将路径、查询参数类型错误转换为稳定 HTTP 400。
     *
     * @param exception 参数类型异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return badRequest(List.of(new ModelingValidationIssue("$." + exception.getName(),
                "INVALID_PARAMETER", "参数类型不正确")));
    }

    /**
     * 将缺失查询参数转换为稳定 HTTP 400。
     *
     * @param exception 缺失参数异常。
     * @return 脱敏错误响应。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleMissingParameter(
            MissingServletRequestParameterException exception) {
        return badRequest(List.of(new ModelingValidationIssue("$." + exception.getParameterName(),
                "MISSING_PARAMETER", "缺少必填参数")));
    }

    /**
     * 保留未登录语义，避免高优先级模块 Advice 把鉴权失败误包装成系统错误。
     *
     * @param exception 登录校验异常。
     * @return HTTP 401 脱敏响应。
     */
    @ExceptionHandler(AccessException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleAuthentication(AccessException exception) {
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(ModelingDraftConstants.ERROR_ACCESS_DENIED)
                        .message("请先登录后再操作").issues(List.of()).build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * 保留权限不足语义，不向客户端回显内部权限对象或操作者信息。
     *
     * @param exception 权限异常。
     * @return HTTP 403 脱敏响应。
     */
    @ExceptionHandler(InvalidPermissionException.class)
    public ResponseEntity<ModelingDraftErrorResp> handlePermission(
            InvalidPermissionException exception) {
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(ModelingDraftConstants.ERROR_ACCESS_DENIED)
                        .message("无权执行该操作").issues(List.of()).build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * 将并发状态变化转换为 409，避免阶段 2 管理动作被误报为系统故障。
     *
     * @param exception 状态前置条件异常。
     * @return HTTP 409 脱敏响应。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ModelingDraftErrorResp> handleIllegalState(
            IllegalStateException exception) {
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(ModelingDraftConstants.ERROR_CONFLICT)
                        .message("资源状态已变化，请重新加载").issues(List.of()).build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 兜底隐藏数据库、Provider 或框架异常详情，同时保留脱敏后的异常类型和完整栈位置。
     *
     * @param exception 未预期系统异常。
     * @return HTTP 500 脱敏错误响应。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ModelingDraftErrorResp> handleUnexpected(Exception exception) {
        RuntimeException sanitized = new RuntimeException(
                "Unexpected exception type: " + exception.getClass().getName());
        sanitized.setStackTrace(exception.getStackTrace());
        log.error("unexpected semantic modeling request failure", sanitized);
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(ModelingDraftConstants.ERROR_INTERNAL)
                        .message("草稿服务暂时不可用，请稍后重试").issues(List.of()).build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** 将 Spring 字段错误转换为稳定问题结构，不回显 rejectedValue。 */
    private List<ModelingValidationIssue> toIssues(List<FieldError> fieldErrors) {
        List<ModelingValidationIssue> issues = new ArrayList<>();
        for (FieldError error : fieldErrors) {
            issues.add(new ModelingValidationIssue("$." + error.getField(), "INVALID_PARAMETER",
                    error.getDefaultMessage()));
        }
        return issues;
    }

    /** 构造统一 HTTP 400 响应。 */
    private ResponseEntity<ModelingDraftErrorResp> badRequest(
            List<ModelingValidationIssue> issues) {
        ModelingDraftErrorResp body =
                ModelingDraftErrorResp.builder().code(ModelingDraftConstants.ERROR_INVALID_REQUEST)
                        .message("请求参数校验失败").issues(issues).build();
        return ResponseEntity.badRequest().body(body);
    }
}
