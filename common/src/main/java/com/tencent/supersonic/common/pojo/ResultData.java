package com.tencent.supersonic.common.pojo;

import com.tencent.supersonic.common.pojo.enums.ReturnCode;
import com.tencent.supersonic.common.util.TraceIdUtil;
import lombok.Data;
import org.slf4j.MDC;

/**
 * 平台统一 API 返回包装。
 *
 * <p>职责：统一 code、msg、data 和 traceId；结构化错误通过 data 返回安全诊断，不包含服务端堆栈。
 */
@Data
public class ResultData<T> {
    private int code;
    private String msg;
    private T data;
    private long timestamp;
    private String traceId;

    public ResultData() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> ResultData<T> success(T data) {
        ResultData<T> resultData = new ResultData<>();
        resultData.setCode(ReturnCode.SUCCESS.getCode());
        resultData.setMsg(ReturnCode.SUCCESS.getMessage());
        resultData.setData(data);
        resultData.setTraceId(MDC.get(TraceIdUtil.TRACE_ID));
        return resultData;
    }

    public static <T> ResultData<T> fail(int code, String message) {
        ResultData<T> resultData = new ResultData<>();
        resultData.setCode(code);
        resultData.setMsg(message);
        resultData.setTraceId(MDC.get(TraceIdUtil.TRACE_ID));
        return resultData;
    }

    /**
     * 构造包含安全结构化数据的失败响应。
     *
     * @param code 业务错误码。
     * @param message 普通用户可读消息。
     * @param data 已脱敏的可选诊断数据。
     * @param <T> 诊断数据类型。
     * @return 统一失败响应。
     */
    public static <T> ResultData<T> fail(int code, String message, T data) {
        ResultData<T> resultData = fail(code, message);
        resultData.setData(data);
        return resultData;
    }
}
