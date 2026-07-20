package com.tencent.supersonic.forecast.server.util;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 将 Worker 异常转换为不含 URL、账号、SQL 和驱动细节的任务错误。
 */
public final class ForecastErrorSanitizer {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private ForecastErrorSanitizer() {}

    /**
     * 生成稳定错误码。
     *
     * @param exception 原始异常。
     * @return 安全错误码。
     */
    public static String code(Throwable exception) {
        if (exception instanceof SQLException) {
            return "DATABASE_ERROR";
        }
        if (exception instanceof IllegalArgumentException) {
            return "INVALID_CONFIGURATION";
        }
        return "FORECAST_JOB_FAILED";
    }

    /**
     * 生成安全用户消息。
     *
     * @param exception 原始异常。
     * @return 不含连接信息的消息。
     */
    public static String message(Throwable exception) {
        if (exception instanceof SQLException) {
            return "数据库操作失败，请检查连接、映射和 Schema 版本";
        }
        String value = exception == null ? "预测任务执行失败" : exception.getMessage();
        if (value == null || value.isBlank()) {
            return "预测任务执行失败";
        }
        // JDBC URL 和常见凭据关键字一旦出现就整体降级，避免用不完备正则造成部分泄漏。
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.contains("jdbc:") || lowered.contains("password")
                || lowered.contains("username") || lowered.contains("select ")
                || lowered.contains("insert ") || lowered.contains("update ")) {
            return "预测任务执行失败，请查看服务端脱敏日志错误码";
        }
        return value.length() <= MAX_MESSAGE_LENGTH ? value
                : value.substring(0, MAX_MESSAGE_LENGTH);
    }
}
