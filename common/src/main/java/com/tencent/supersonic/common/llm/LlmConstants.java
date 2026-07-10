package com.tencent.supersonic.common.llm;

/**
 * LLM Conversation Gateway 的统一常量定义。
 *
 * <p>
 * 职责说明：集中管理阶段 1 网关使用的 provider、消息角色、响应格式、状态和归一化错误码，避免在 Adapter、Service 和 Controller
 * 中散落魔法字符串。并发说明：本类只包含不可变常量，没有共享可变状态，因此天然线程安全。
 * </p>
 */
public final class LlmConstants {

    public static final String PROVIDER_DEEPSEEK = "DEEPSEEK";
    public static final String PROVIDER_OPEN_AI = "OPEN_AI";

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static final String FORMAT_TEXT = "text";
    public static final String FORMAT_JSON = "json";

    public static final String CONTENT_TYPE_TEXT = "text";
    public static final String CONTENT_TYPE_JSON = "json";
    public static final String CONTENT_TYPE_TOOL_RESULT = "tool_result";
    public static final String CONTENT_TYPE_INTERNAL_JSON_ERROR = "internal_json_error";

    public static final String CONVERSATION_ACTIVE = "ACTIVE";
    public static final String CONVERSATION_FAILED = "FAILED";
    public static final String CONVERSATION_COMPLETED = "COMPLETED";

    public static final String INVOCATION_SUCCESS = "SUCCESS";
    public static final String INVOCATION_FAILED = "FAILED";
    public static final String INVOCATION_TIMEOUT = "TIMEOUT";
    public static final String INVOCATION_RATE_LIMITED = "RATE_LIMITED";

    public static final String ERROR_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERROR_AUTH_FAILED = "AUTH_FAILED";
    public static final String ERROR_INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    public static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_MODEL_NOT_FOUND = "MODEL_NOT_FOUND";
    public static final String ERROR_CONTENT_FILTERED = "CONTENT_FILTERED";
    public static final String ERROR_JSON_PARSE_FAILED = "JSON_PARSE_FAILED";
    public static final String ERROR_JSON_OUTPUT_UNSUPPORTED = "JSON_OUTPUT_UNSUPPORTED";
    public static final String ERROR_TOOL_CALLING_UNSUPPORTED = "TOOL_CALLING_UNSUPPORTED";
    public static final String ERROR_PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String ERROR_UNKNOWN = "UNKNOWN_ERROR";

    private LlmConstants() {}
}
