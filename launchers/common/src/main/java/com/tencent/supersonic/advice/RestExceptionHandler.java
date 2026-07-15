package com.tencent.supersonic.advice;

import com.tencent.supersonic.common.pojo.ResultData;
import com.tencent.supersonic.common.pojo.enums.ReturnCode;
import com.tencent.supersonic.common.pojo.exception.AccessException;
import com.tencent.supersonic.common.pojo.exception.CommonException;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.pojo.exception.StructuredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 平台 REST 异常统一处理器。
 *
 * <p>职责：服务端记录完整堆栈，对客户端仅返回友好消息；实现 StructuredException 的异常额外返回
 * 安全结构化 data，避免 Controller 重复拼接错误字符串。
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    /** default global exception handler */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ResultData<?> exception(Exception e) {
        log.error("default global exception", e);
        if (e instanceof StructuredException structuredException) {
            return ResultData.fail(structuredException.getStructuredCode(), e.getMessage(),
                    structuredException.getStructuredData());
        }
        return ResultData.fail(ReturnCode.SYSTEM_ERROR.getCode(), e.getMessage());
    }

    @ExceptionHandler(AccessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ResultData<String> accessException(Exception e) {
        return ResultData.fail(ReturnCode.ACCESS_ERROR.getCode(), e.getMessage());
    }

    @ExceptionHandler(InvalidPermissionException.class)
    @ResponseStatus(HttpStatus.OK)
    public ResultData<String> invalidPermissionException(Exception e) {
        log.error("default global exception", e);
        return ResultData.fail(ReturnCode.INVALID_PERMISSION.getCode(), e.getMessage());
    }

    @ExceptionHandler(InvalidArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ResultData<String> invalidArgumentException(Exception e) {
        log.error("default global exception", e);
        return ResultData.fail(ReturnCode.INVALID_REQUEST.getCode(), e.getMessage());
    }

    @ExceptionHandler(CommonException.class)
    @ResponseStatus(HttpStatus.OK)
    public ResultData<String> commonException(CommonException e) {
        log.error("default global exception", e);
        return ResultData.fail(e.getCode(), e.getMessage());
    }
}
