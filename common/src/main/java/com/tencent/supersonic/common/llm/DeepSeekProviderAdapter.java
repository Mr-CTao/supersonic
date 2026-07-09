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

/**
 * DeepSeek 官方 API 适配器。
 *
 * <p>
 * 职责说明：将 Gateway 统一请求转换为 DeepSeek `/chat/completions` 非流式请求，并把响应中的
 * `content`、`reasoning_content`、`tool_calls`、usage 和错误码归一化。该实现明确区分普通 Chat Completion base URL 与
 * Beta base URL：阶段 1 的调试接口只调用普通 `/chat/completions`；对话前缀续写、FIM `/completions` 和 strict tool calling
 * 仅在能力表中记录，不在本类中混入普通对话路径。
 * </p>
 *
 * <p>
 * 并发说明：本类由 Spring 单例管理，仅持有线程安全的 {@link HttpClient} 和 Jackson {@link ObjectMapper}；每次调用的请求体、 API
 * Key、响应内容都使用方法内局部变量，因此无需额外锁。
 * </p>
 */
@Slf4j
@Service
public class DeepSeekProviderAdapter implements LlmProviderAdapter {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_BETA_BASE_URL = "https://api.deepseek.com/beta";
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    public static final String FIM_COMPLETIONS_PATH = "/completions";

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_PAYMENT_REQUIRED = 402;
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int DEFAULT_TIMEOUT_MS = 60000;
    private static final int RAW_RESPONSE_SUMMARY_LIMIT = 1000;

    private final ObjectMapper objectMapper = JsonUtil.INSTANCE.getObjectMapper();
    private final HttpClient httpClient;

    /**
     * 创建 DeepSeek Adapter。
     */
    public DeepSeekProviderAdapter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /**
     * 创建可注入 HTTP 客户端的 DeepSeek Adapter，供测试替换网络行为。
     *
     * @param httpClient JDK HTTP 客户端。
     */
    DeepSeekProviderAdapter(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 判断供应商配置是否应由 DeepSeek Adapter 处理。
     *
     * @param providerType 供应商类型。
     * @param baseUrl base URL。
     * @param modelName 模型名。
     * @return provider 为 DEEPSEEK，或 baseUrl/modelName 显示 DeepSeek 特征时返回 true。
     */
    @Override
    public boolean supports(String providerType, String baseUrl, String modelName) {
        if (LlmConstants.PROVIDER_DEEPSEEK.equalsIgnoreCase(providerType)) {
            return true;
        }
        String lowerBaseUrl = StringUtils.defaultString(baseUrl).toLowerCase(Locale.ROOT);
        String lowerModelName = StringUtils.defaultString(modelName).toLowerCase(Locale.ROOT);
        return lowerBaseUrl.contains("deepseek.com") || lowerModelName.startsWith("deepseek-");
    }

    /**
     * 调用 DeepSeek 非流式对话补全。
     *
     * @param request Gateway 已拼接完整 messages 的统一请求。
     * @return 统一响应对象。
     */
    @Override
    public LlmChatResponse chat(LlmChatRequest request) {
        long timeoutMs =
                Objects.requireNonNullElse(request.getTimeoutMs(), (long) DEFAULT_TIMEOUT_MS);
        try {
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
        } catch (java.net.http.HttpTimeoutException exception) {
            return LlmChatResponse.builder().success(false).errorCode(LlmConstants.ERROR_TIMEOUT)
                    .errorMessage("DeepSeek request timed out").build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LlmChatResponse.builder().success(false)
                    .errorCode(LlmConstants.ERROR_PROVIDER_UNAVAILABLE)
                    .errorMessage("DeepSeek request interrupted").build();
        } catch (Exception exception) {
            return LlmChatResponse.builder().success(false).errorCode(LlmConstants.ERROR_UNKNOWN)
                    .errorMessage(sanitize(exception.getMessage())).build();
        }
    }

    /**
     * 根据 DeepSeek 官方错误码归一化错误。
     *
     * @param httpStatus HTTP 状态码。
     * @param responseBody DeepSeek 错误体。
     * @return 统一错误码。
     */
    @Override
    public String normalizeError(int httpStatus, String responseBody) {
        return switch (httpStatus) {
            case HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE_ENTITY -> LlmConstants.ERROR_BAD_REQUEST;
            case HTTP_UNAUTHORIZED -> LlmConstants.ERROR_AUTH_FAILED;
            case HTTP_PAYMENT_REQUIRED -> LlmConstants.ERROR_INSUFFICIENT_BALANCE;
            case HTTP_TOO_MANY_REQUESTS -> LlmConstants.ERROR_RATE_LIMITED;
            case HTTP_INTERNAL_ERROR, HTTP_SERVICE_UNAVAILABLE -> LlmConstants.ERROR_PROVIDER_UNAVAILABLE;
            default -> LlmConstants.ERROR_UNKNOWN;
        };
    }

    /**
     * 发送 HTTP 请求。测试可覆盖该方法来避免真实网络调用。
     *
     * @param httpRequest HTTP 请求。
     * @return HTTP 响应。
     * @throws IOException 网络异常。
     * @throws InterruptedException 线程中断。
     */
    protected HttpResponse<String> send(HttpRequest httpRequest)
            throws IOException, InterruptedException {
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 构造 DeepSeek `/chat/completions` 请求体。
     */
    private ObjectNode buildChatRequestBody(LlmChatRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.getModelName());
        body.set("messages", buildMessages(request));
        body.put("stream", false);

        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (LlmConstants.FORMAT_JSON.equalsIgnoreCase(request.getResponseFormat())) {
            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            body.set("response_format", responseFormat);
        }

        Boolean thinkingEnabled = request.getThinkingEnabled();
        if (thinkingEnabled != null) {
            ObjectNode thinking = objectMapper.createObjectNode();
            thinking.put("type", thinkingEnabled ? "enabled" : "disabled");
            body.set("thinking", thinking);
            if (StringUtils.isNotBlank(request.getReasoningEffort())) {
                body.put("reasoning_effort", request.getReasoningEffort());
            }
        }

        // DeepSeek 官方说明 thinking 模式下 temperature/top_p 等采样参数不生效；这里主动省略，避免调用方误以为参数已生效。
        if (!Boolean.TRUE.equals(thinkingEnabled) && request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        return body;
    }

    /**
     * 按 DeepSeek 官方 message 结构转换完整上下文。
     */
    private ArrayNode buildMessages(LlmChatRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (LlmChatMessage message : request.getMessages()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", message.getRole());
            if (message.getContent() != null) {
                node.put("content", message.getContent());
            }
            if (StringUtils.isNotBlank(message.getToolCallId())) {
                node.put("tool_call_id", message.getToolCallId());
            }
            if (Boolean.TRUE.equals(message.getPrefix())) {
                node.put("prefix", true);
            }
            if (shouldSendReasoningContent(message)) {
                node.put("reasoning_content", message.getReasoningContent());
            }
            if (StringUtils.isNotBlank(message.getToolCalls())) {
                node.set("tool_calls", JsonUtil.readTree(message.getToolCalls()));
            }
            messages.add(node);
        }
        return messages;
    }

    /**
     * 判断是否需要把 reasoning_content 拼回 DeepSeek 上下文。
     *
     * <p>
     * 设计意图：官方说明普通非工具调用多轮可不回传 reasoning_content；工具调用链路必须完整回传，否则可能 400。阶段 1 不做工具编排， 但如果历史 assistant
     * 消息已经保存了 toolCalls，则认为它来自工具调用场景并回传 reasoning_content。
     * </p>
     */
    private boolean shouldSendReasoningContent(LlmChatMessage message) {
        return LlmConstants.ROLE_ASSISTANT.equals(message.getRole())
                && StringUtils.isNotBlank(message.getReasoningContent())
                && StringUtils.isNotBlank(message.getToolCalls());
    }

    /**
     * 解析 DeepSeek 成功响应。
     */
    private LlmChatResponse parseSuccessResponse(String responseBody, LlmChatRequest request)
            throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                ? root.path("choices").get(0)
                : objectMapper.createObjectNode();
        JsonNode message = choice.path("message");
        String content =
                message.path("content").isMissingNode() || message.path("content").isNull() ? null
                        : message.path("content").asText();
        String toolCalls =
                message.path("tool_calls").isMissingNode() || message.path("tool_calls").isNull()
                        ? null
                        : objectMapper.writeValueAsString(message.path("tool_calls"));
        JsonNode parsedJson = null;
        if (LlmConstants.FORMAT_JSON.equalsIgnoreCase(request.getResponseFormat())) {
            parsedJson = parseJsonContent(content);
            if (parsedJson == null) {
                return LlmChatResponse.builder().success(false)
                        .errorCode(LlmConstants.ERROR_JSON_PARSE_FAILED)
                        .errorMessage("DeepSeek returned empty or invalid JSON content")
                        .rawResponseRef(summarize(responseBody)).build();
            }
        }
        JsonNode usage = root.path("usage");
        return LlmChatResponse.builder().success(true).content(content).parsedJson(parsedJson)
                .reasoningContent(message.path("reasoning_content").isMissingNode()
                        || message.path("reasoning_content").isNull() ? null
                                : message.path("reasoning_content").asText())
                .toolCalls(toolCalls).finishReason(choice.path("finish_reason").asText(null))
                .providerRequestId(root.path("id").asText(null))
                .rawResponseRef(summarize(responseBody))
                .promptTokens(readInteger(usage, "prompt_tokens"))
                .completionTokens(readInteger(usage, "completion_tokens"))
                .totalTokens(readInteger(usage, "total_tokens")).build();
    }

    /**
     * 构造统一错误响应。
     */
    private LlmChatResponse buildErrorResponse(int statusCode, String responseBody) {
        return LlmChatResponse.builder().success(false)
                .errorCode(normalizeError(statusCode, responseBody))
                .errorMessage(sanitize(extractProviderErrorMessage(responseBody)))
                .rawResponseRef(summarize(responseBody)).build();
    }

    /**
     * 解析 JSON mode 响应内容。
     */
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

    /**
     * 提取 DeepSeek 错误摘要。
     */
    private String extractProviderErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                return error.path("message").asText(responseBody);
            }
        } catch (Exception ignored) {
            // 厂商错误体不一定是 JSON；回退到原始摘要即可。
        }
        return responseBody;
    }

    /**
     * 读取 usage 整数字段。
     */
    private Integer readInteger(JsonNode node, String fieldName) {
        return node.path(fieldName).isNumber() ? node.path(fieldName).asInt() : null;
    }

    /**
     * 构造普通 Chat Completion URL。
     */
    private String buildChatCompletionUrl(String baseUrl) {
        String normalizedBaseUrl = StringUtils.defaultIfBlank(baseUrl, DEFAULT_BASE_URL);
        return trimTrailingSlash(normalizedBaseUrl) + CHAT_COMPLETIONS_PATH;
    }

    /**
     * 去掉 URL 尾部斜杠。
     */
    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 生成脱敏响应摘要。
     */
    private String summarize(String value) {
        String sanitized = sanitize(value);
        if (sanitized == null || sanitized.length() <= RAW_RESPONSE_SUMMARY_LIMIT) {
            return sanitized;
        }
        return sanitized.substring(0, RAW_RESPONSE_SUMMARY_LIMIT);
    }

    /**
     * 对可能含有 Bearer/API Key 的文本做最小脱敏。
     */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***")
                .replaceAll("(?i)(api[_-]?key\"?\\s*[:=]\\s*\"?)[^\",\\s]+", "$1***");
    }
}
