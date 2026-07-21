package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Kimi Chat Completions 供应商适配器。
 *
 * <p>
 * 职责说明：将 Gateway 的统一请求转换为 Kimi OpenAI-compatible `/chat/completions` 协议，并归一化 Kimi K3
 * 的正文、{@code reasoning_content}、Tool Calls、严格 JSON Schema、usage 和错误码。K3 固定的
 * {@code temperature/top_p/n/penalty} 参数不会写入请求，避免页面通用默认值触发厂商参数校验失败。
 * </p>
 *
 * <p>
 * 调用示例：
 * {@code adapter.chat(LlmChatRequest.builder().providerType("KIMI").modelName("kimi-k3").messages(messages).build())}。
 * </p>
 *
 * <p>
 * 并发说明：本类由 Spring 单例管理，仅持有线程安全的 {@link HttpClient} 和只读配置；每次请求的 API Key、
 * messages、请求体与响应均保存在方法局部变量中，不需要额外并发锁。
 * </p>
 */
@Slf4j
@Service
public class KimiProviderAdapter implements LlmProviderAdapter {

    public static final String DEFAULT_BASE_URL = "https://api.moonshot.cn/v1";
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_PAYMENT_REQUIRED = 402;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_BAD_GATEWAY = 502;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int HTTP_GATEWAY_TIMEOUT = 504;
    private static final int DEFAULT_TIMEOUT_MS = 180_000;
    private static final String JSON_SCHEMA_NAME = "supersonic_response";
    private static final Set<String> SUPPORTED_REASONING_EFFORTS = Set.of("low", "high", "max");

    private final ObjectMapper objectMapper = JsonUtil.INSTANCE.getObjectMapper();
    private final HttpClient httpClient;

    /**
     * 创建生产环境 Kimi Adapter。
     */
    public KimiProviderAdapter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /**
     * 创建可注入 HTTP 客户端的 Kimi Adapter，供测试隔离真实网络。
     *
     * @param httpClient 线程安全的 JDK HTTP 客户端。
     */
    KimiProviderAdapter(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    /**
     * 判断供应商配置是否应由 Kimi Adapter 处理。
     *
     * @param providerType 供应商类型。
     * @param baseUrl Chat Completions base URL。
     * @param modelName 模型名称。
     * @return provider、Moonshot 域名或 kimi 模型名任一匹配时返回 true。
     */
    @Override
    public boolean supports(String providerType, String baseUrl, String modelName) {
        if (LlmConstants.PROVIDER_KIMI.equalsIgnoreCase(providerType)) {
            return true;
        }
        String normalizedBaseUrl = StringUtils.defaultString(baseUrl).toLowerCase(Locale.ROOT);
        String normalizedModelName = StringUtils.defaultString(modelName).toLowerCase(Locale.ROOT);
        return normalizedBaseUrl.contains("moonshot.cn")
                || normalizedBaseUrl.contains("moonshot.ai")
                || normalizedModelName.startsWith("kimi-");
    }

    /**
     * 声明 Kimi K3 原生支持严格 JSON Schema。
     *
     * @return 固定返回 {@link LlmJsonOutputMode#JSON_SCHEMA_STRICT}。
     */
    @Override
    public LlmJsonOutputMode jsonOutputMode() {
        return LlmJsonOutputMode.JSON_SCHEMA_STRICT;
    }

    /**
     * 调用 Kimi 非流式 Chat Completions。
     *
     * @param request Gateway 已完成上下文拼接和能力校验的统一请求。
     * @return 统一响应；异常和 HTTP 错误以错误码返回，不泄露 API Key。
     */
    @Override
    public LlmChatResponse chat(LlmChatRequest request) {
        try {
            validateRequest(request);
            long timeoutMs =
                    Objects.requireNonNullElse(request.getTimeoutMs(), (long) DEFAULT_TIMEOUT_MS);
            ObjectNode requestBody = buildChatRequestBody(request);
            HttpRequest httpRequest =
                    HttpRequest.newBuilder()
                            .uri(URI.create(buildChatCompletionUrl(request.getBaseUrl())))
                            .timeout(Duration.ofMillis(timeoutMs))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + request.getApiKey())
                            .POST(HttpRequest.BodyPublishers
                                    .ofString(objectMapper.writeValueAsString(requestBody)))
                            .build();
            HttpResponse<String> response = send(httpRequest);
            if (response.statusCode() < HTTP_BAD_REQUEST) {
                return parseSuccessResponse(response.body(), request);
            }
            return buildErrorResponse(response.statusCode(), response.body());
        } catch (IllegalArgumentException exception) {
            return LlmChatResponse.builder().success(false)
                    .errorCode(LlmConstants.ERROR_BAD_REQUEST)
                    .errorMessage(sanitize(exception.getMessage())).build();
        } catch (java.net.http.HttpTimeoutException exception) {
            return LlmChatResponse.builder().success(false).errorCode(LlmConstants.ERROR_TIMEOUT)
                    .errorMessage("Kimi request timed out").build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LlmChatResponse.builder().success(false)
                    .errorCode(LlmConstants.ERROR_PROVIDER_UNAVAILABLE)
                    .errorMessage("Kimi request interrupted").build();
        } catch (Exception exception) {
            log.debug("Kimi request failed before a normalized provider response was available",
                    exception);
            return LlmChatResponse.builder().success(false).errorCode(LlmConstants.ERROR_UNKNOWN)
                    .errorMessage(sanitize(exception.getMessage())).build();
        }
    }

    /**
     * 根据 Kimi HTTP 状态码归一化错误。
     *
     * @param httpStatus HTTP 状态码。
     * @param responseBody Kimi 错误响应体；仅用于接口一致性，不写日志。
     * @return Gateway 统一错误码。
     */
    @Override
    public String normalizeError(int httpStatus, String responseBody) {
        return switch (httpStatus) {
            case HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE_ENTITY -> LlmConstants.ERROR_BAD_REQUEST;
            case HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> LlmConstants.ERROR_AUTH_FAILED;
            case HTTP_PAYMENT_REQUIRED -> LlmConstants.ERROR_INSUFFICIENT_BALANCE;
            case HTTP_NOT_FOUND -> LlmConstants.ERROR_MODEL_NOT_FOUND;
            case HTTP_TOO_MANY_REQUESTS -> LlmConstants.ERROR_RATE_LIMITED;
            case HTTP_INTERNAL_ERROR, HTTP_BAD_GATEWAY, HTTP_SERVICE_UNAVAILABLE, HTTP_GATEWAY_TIMEOUT -> LlmConstants.ERROR_PROVIDER_UNAVAILABLE;
            default -> LlmConstants.ERROR_UNKNOWN;
        };
    }

    /**
     * 发送 HTTP 请求，测试可覆盖该方法避免真实外部调用。
     *
     * @param httpRequest 已完成鉴权与超时配置的请求。
     * @return Kimi HTTP 响应。
     * @throws IOException 网络读写失败。
     * @throws InterruptedException 当前线程被中断。
     */
    protected HttpResponse<String> send(HttpRequest httpRequest)
            throws IOException, InterruptedException {
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    /** 校验 K3 必需字段和 reasoning_effort 白名单，避免无效请求进入外部网络。 */
    private void validateRequest(LlmChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kimi request is required");
        }
        if (StringUtils.isBlank(request.getApiKey())) {
            throw new IllegalArgumentException("Kimi API Key is required");
        }
        if (StringUtils.isBlank(request.getModelName())) {
            throw new IllegalArgumentException("Kimi modelName is required");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("Kimi messages are required");
        }
        if (request.getTimeoutMs() != null && request.getTimeoutMs() <= 0) {
            throw new IllegalArgumentException("Kimi timeoutMs must be greater than zero");
        }
        String reasoningEffort = StringUtils.trimToNull(request.getReasoningEffort());
        if (reasoningEffort != null && !SUPPORTED_REASONING_EFFORTS
                .contains(reasoningEffort.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Kimi reasoningEffort must be one of low, high, or max");
        }
    }

    /** 构造 K3 Chat Completions 请求体，并主动省略所有固定采样参数。 */
    private ObjectNode buildChatRequestBody(LlmChatRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.getModelName());
        body.set("messages", buildMessages(request));
        body.put("stream", false);

        if (request.getMaxTokens() != null) {
            body.put("max_completion_tokens", request.getMaxTokens());
        }
        String reasoningEffort = StringUtils.trimToNull(request.getReasoningEffort());
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort.toLowerCase(Locale.ROOT));
        }
        if (LlmConstants.FORMAT_JSON.equalsIgnoreCase(request.getResponseFormat())) {
            body.set("response_format", buildStrictJsonSchema(request.getJsonSchema()));
        }
        return body;
    }

    /** 将 Gateway 消息转换为 Kimi 格式，并完整回传 assistant 的思考与工具调用字段。 */
    private ArrayNode buildMessages(LlmChatRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (LlmChatMessage message : request.getMessages()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", message.getRole());
            if (message.getContent() != null) {
                node.put("content", message.getContent());
            }
            if (StringUtils.isNotBlank(message.getReasoningContent())) {
                // K3 Preserved Thinking 要求后续轮次完整回传 assistant reasoning_content。
                node.put("reasoning_content", message.getReasoningContent());
            }
            if (StringUtils.isNotBlank(message.getToolCalls())) {
                node.set("tool_calls", JsonUtil.readTree(message.getToolCalls()));
            }
            if (StringUtils.isNotBlank(message.getToolCallId())) {
                node.put("tool_call_id", message.getToolCallId());
            }
            messages.add(node);
        }
        return messages;
    }

    /** 构造 Kimi 原生 strict JSON Schema response_format。 */
    private ObjectNode buildStrictJsonSchema(JsonNode schema) {
        if (schema == null) {
            throw new IllegalArgumentException(
                    "jsonSchema is required for Kimi strict JSON output");
        }
        ObjectNode jsonSchema = objectMapper.createObjectNode();
        jsonSchema.put("name", JSON_SCHEMA_NAME);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.set("json_schema", jsonSchema);
        return responseFormat;
    }

    /** 解析 Kimi 成功响应并保留下一轮所需的完整思考上下文。 */
    private LlmChatResponse parseSuccessResponse(String responseBody, LlmChatRequest request)
            throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                ? root.path("choices").get(0)
                : objectMapper.createObjectNode();
        JsonNode message = choice.path("message");
        String content = readNullableText(message, "content");
        String reasoningContent = readNullableText(message, "reasoning_content");
        String toolCalls =
                message.path("tool_calls").isMissingNode() || message.path("tool_calls").isNull()
                        ? null
                        : objectMapper.writeValueAsString(message.path("tool_calls"));
        String finishReason = choice.path("finish_reason").asText(null);
        String providerRequestId = root.path("id").asText(null);
        JsonNode usage = root.path("usage");
        JsonNode parsedJson = null;

        if (LlmConstants.FORMAT_JSON.equalsIgnoreCase(request.getResponseFormat())) {
            parsedJson = parseJsonContent(content);
            if (parsedJson == null) {
                return responseBuilder(content, reasoningContent, toolCalls, finishReason,
                        providerRequestId, usage).success(false)
                                .errorCode(LlmConstants.ERROR_JSON_PARSE_FAILED)
                                .errorMessage("Kimi returned empty or invalid JSON content")
                                .build();
            }
        }
        return responseBuilder(content, reasoningContent, toolCalls, finishReason,
                providerRequestId, usage).success(true).parsedJson(parsedJson).build();
    }

    /** 创建只包含脱敏定位信息和 token usage 的统一响应 builder。 */
    private LlmChatResponse.LlmChatResponseBuilder responseBuilder(String content,
            String reasoningContent, String toolCalls, String finishReason,
            String providerRequestId, JsonNode usage) {
        return LlmChatResponse.builder().content(content).reasoningContent(reasoningContent)
                .toolCalls(toolCalls).finishReason(finishReason)
                .providerRequestId(providerRequestId)
                .rawResponseRef(
                        buildSafeResponseReference(providerRequestId, finishReason, content))
                .promptTokens(readInteger(usage, "prompt_tokens"))
                .completionTokens(readInteger(usage, "completion_tokens"))
                .totalTokens(readInteger(usage, "total_tokens"));
    }

    /** 构造统一错误响应，厂商错误体只提取消息并做敏感信息脱敏。 */
    private LlmChatResponse buildErrorResponse(int statusCode, String responseBody) {
        return LlmChatResponse.builder().success(false)
                .errorCode(normalizeError(statusCode, responseBody))
                .errorMessage(sanitize(extractProviderErrorMessage(responseBody)))
                .rawResponseRef("httpStatus=" + statusCode).build();
    }

    /** 读取允许为空的文本字段。 */
    private String readNullableText(JsonNode parent, String fieldName) {
        JsonNode value = parent.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 将 JSON 正文解析为节点；空白或非法 JSON 返回 null。 */
    private JsonNode parseJsonContent(String content) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        try {
            return objectMapper.readTree(content);
        } catch (IOException exception) {
            return null;
        }
    }

    /** 从标准 OpenAI-compatible 错误体提取消息，解析失败时返回安全通用描述。 */
    private String extractProviderErrorMessage(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return "Kimi request failed";
        }
        try {
            String message =
                    objectMapper.readTree(responseBody).path("error").path("message").asText(null);
            return StringUtils.defaultIfBlank(message, "Kimi request failed");
        } catch (IOException exception) {
            return "Kimi request failed";
        }
    }

    /** 从 usage 对象读取可选整数。 */
    private Integer readInteger(JsonNode usage, String fieldName) {
        JsonNode value = usage.path(fieldName);
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    /** 构造不包含业务正文的调用定位摘要。 */
    private String buildSafeResponseReference(String requestId, String finishReason,
            String content) {
        int contentLength = content == null ? 0 : content.length();
        return "requestId=" + StringUtils.defaultString(requestId, "-") + ",finishReason="
                + StringUtils.defaultString(finishReason, "-") + ",contentLength=" + contentLength;
    }

    /** 构造 Chat Completions URL，兼容用户填写带或不带结尾斜杠的 base URL。 */
    private String buildChatCompletionUrl(String baseUrl) {
        String normalizedBaseUrl = StringUtils.defaultIfBlank(baseUrl, DEFAULT_BASE_URL);
        return trimTrailingSlash(normalizedBaseUrl) + CHAT_COMPLETIONS_PATH;
    }

    /** 移除 URL 末尾斜杠，避免路径拼接产生双斜杠。 */
    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 对异常和厂商错误消息中的 Bearer/API Key 做最小脱敏。 */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***")
                .replaceAll("(?i)(api[_-]?key\"?\\s*[:=]\\s*\"?)[^\",\\s]+", "$1***");
    }
}
