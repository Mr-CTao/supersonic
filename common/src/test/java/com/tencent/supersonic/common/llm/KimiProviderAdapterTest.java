package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.supersonic.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kimi Provider Adapter 协议验证用例。
 *
 * <p>
 * 职责说明：验证 Kimi K3 strict JSON Schema、Preserved Thinking 多轮回传、固定采样参数省略、usage 解析、
 * 供应商识别和错误归一化。每个测试使用独立 Adapter 与局部请求引用，不共享可变状态。
 * </p>
 */
class KimiProviderAdapterTest {

    /** 验证 K3 请求参数、完整思考上下文和成功响应归一化。 */
    @Test
    void chatShouldUseStrictSchemaPreserveReasoningAndOmitFixedSamplingParameters() {
        AtomicReference<HttpRequest> requestRef = new AtomicReference<>();
        AtomicReference<String> requestBodyRef = new AtomicReference<>();
        KimiProviderAdapter adapter = new KimiProviderAdapter(HttpClient.newHttpClient()) {
            @Override
            protected HttpResponse<String> send(HttpRequest httpRequest) {
                requestRef.set(httpRequest);
                requestBodyRef.set(readRequestBody(httpRequest));
                return response(200, """
                        {
                          "id": "kimi-request-1",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {
                              "role": "assistant",
                              "content": "{\\\"answer\\\":\\\"ok\\\"}",
                              "reasoning_content": "current reasoning"
                            }
                          }],
                          "usage": {
                            "prompt_tokens": 21,
                            "completion_tokens": 9,
                            "total_tokens": 30
                          }
                        }
                        """);
            }
        };

        JsonNode schema = JsonUtil.readTree("""
                {
                  "type": "object",
                  "required": ["answer"],
                  "properties": {"answer": {"type": "string"}},
                  "additionalProperties": false
                }
                """);
        List<LlmChatMessage> messages = List.of(
                LlmChatMessage.builder().role(LlmConstants.ROLE_USER).content("first").build(),
                LlmChatMessage.builder().role(LlmConstants.ROLE_ASSISTANT).content("first answer")
                        .reasoningContent("prior reasoning").build(),
                LlmChatMessage.builder().role(LlmConstants.ROLE_USER).content("second").build());

        LlmChatResponse result = adapter.chat(LlmChatRequest.builder()
                .providerType(LlmConstants.PROVIDER_KIMI).baseUrl("https://api.moonshot.cn/v1/")
                .apiKey("test-key").modelName("kimi-k3").messages(messages)
                .responseFormat(LlmConstants.FORMAT_JSON).jsonSchema(schema).temperature(0.0d)
                .thinkingEnabled(false).reasoningEffort("high").maxTokens(4096).build());

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getParsedJson().path("answer").asText());
        assertEquals("current reasoning", result.getReasoningContent());
        assertEquals(30, result.getTotalTokens());
        assertNotNull(result.getRawResponseRef());
        assertFalse(result.getRawResponseRef().contains("current reasoning"));
        assertEquals(URI.create("https://api.moonshot.cn/v1/chat/completions"),
                requestRef.get().uri());

        JsonNode providerBody = JsonUtil.readTree(requestBodyRef.get());
        assertEquals("kimi-k3", providerBody.path("model").asText());
        assertEquals("high", providerBody.path("reasoning_effort").asText());
        assertEquals(4096, providerBody.path("max_completion_tokens").asInt());
        assertEquals("json_schema", providerBody.path("response_format").path("type").asText());
        assertTrue(providerBody.path("response_format").path("json_schema").path("strict")
                .asBoolean());
        assertEquals(schema,
                providerBody.path("response_format").path("json_schema").path("schema"));
        assertEquals("prior reasoning",
                providerBody.path("messages").get(1).path("reasoning_content").asText());
        assertFalse(providerBody.has("temperature"));
        assertFalse(providerBody.has("top_p"));
        assertFalse(providerBody.has("thinking"));
        assertEquals(LlmJsonOutputMode.JSON_SCHEMA_STRICT, adapter.jsonOutputMode());
    }

    /** 验证显式供应商、Moonshot 域名和 Kimi 模型名均能正确路由。 */
    @Test
    void supportsShouldRecognizeKimiConfigurations() {
        KimiProviderAdapter adapter = new KimiProviderAdapter();

        assertTrue(adapter.supports(LlmConstants.PROVIDER_KIMI, null, null));
        assertTrue(adapter.supports(LlmConstants.PROVIDER_OPEN_AI, "https://api.moonshot.ai/v1",
                "custom-model"));
        assertTrue(adapter.supports(LlmConstants.PROVIDER_OPEN_AI, "https://example.com/v1",
                "kimi-k3"));
        assertFalse(adapter.supports(LlmConstants.PROVIDER_OPEN_AI, "https://api.openai.com/v1",
                "gpt-4o-mini"));
    }

    /** 验证 Kimi 常见 HTTP 状态映射到稳定的 Gateway 错误码。 */
    @Test
    void normalizeErrorShouldCoverAuthenticationModelRateLimitAndAvailability() {
        KimiProviderAdapter adapter = new KimiProviderAdapter();

        assertEquals(LlmConstants.ERROR_BAD_REQUEST, adapter.normalizeError(400, ""));
        assertEquals(LlmConstants.ERROR_AUTH_FAILED, adapter.normalizeError(401, ""));
        assertEquals(LlmConstants.ERROR_AUTH_FAILED, adapter.normalizeError(403, ""));
        assertEquals(LlmConstants.ERROR_MODEL_NOT_FOUND, adapter.normalizeError(404, ""));
        assertEquals(LlmConstants.ERROR_RATE_LIMITED, adapter.normalizeError(429, ""));
        assertEquals(LlmConstants.ERROR_PROVIDER_UNAVAILABLE, adapter.normalizeError(502, ""));
        assertEquals(LlmConstants.ERROR_PROVIDER_UNAVAILABLE, adapter.normalizeError(503, ""));
        assertEquals(LlmConstants.ERROR_PROVIDER_UNAVAILABLE, adapter.normalizeError(504, ""));
    }


    /** 验证空请求和非法超时在访问网络前按 BAD_REQUEST 拒绝。 */
    @Test
    void chatShouldRejectInvalidRequestBeforeNetworkAccess() {
        KimiProviderAdapter adapter = new KimiProviderAdapter();

        assertEquals(LlmConstants.ERROR_BAD_REQUEST, adapter.chat(null).getErrorCode());
        LlmChatResponse response =
                adapter.chat(LlmChatRequest.builder().providerType(LlmConstants.PROVIDER_KIMI)
                        .apiKey("test-key").modelName("kimi-k3").messages(List.of(LlmChatMessage
                                .builder().role(LlmConstants.ROLE_USER).content("hello").build()))
                        .timeoutMs(0L).build());
        assertEquals(LlmConstants.ERROR_BAD_REQUEST, response.getErrorCode());
    }

    /** 构造不访问网络的简化 HTTP 响应。 */
    private static HttpResponse<String> response(int statusCode, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (left, right) -> true);
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public Optional<javax.net.ssl.SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://api.moonshot.cn/v1/chat/completions");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /** 订阅 JDK BodyPublisher 并读取真实发送给 Kimi 的 UTF-8 请求体。 */
    private static String readRequestBody(HttpRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(output.toString(StandardCharsets.UTF_8));
            }
        });
        return result.join();
    }
}
