package com.tencent.supersonic.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.supersonic.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek Provider Adapter 验证用例。
 *
 * <p>
 * 职责说明：验证阶段 1 最关键的官方协议差异，包括 DeepSeek JSON Output、reasoning_content、完整 messages 拼接入参、
 * 错误码归一化和超时处理。并发说明：测试中每个 Adapter 实例只在单测试线程中使用，不涉及共享状态。
 * </p>
 */
class DeepSeekProviderAdapterTest {

    @Test
    void chatShouldInjectTransientSchemaContractAndParseJsonReasoningUsage() {
        AtomicReference<HttpRequest> requestRef = new AtomicReference<>();
        AtomicReference<String> requestBodyRef = new AtomicReference<>();
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(HttpClient.newHttpClient()) {
            @Override
            protected HttpResponse<String> send(HttpRequest httpRequest) {
                requestRef.set(httpRequest);
                requestBodyRef.set(readRequestBody(httpRequest));
                return response(200, """
                        {
                          "id": "deepseek-request-1",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {
                              "role": "assistant",
                              "content": "{\\"answer\\":\\"ok\\"}",
                              "reasoning_content": "short reasoning"
                            }
                          }],
                          "usage": {
                            "prompt_tokens": 11,
                            "completion_tokens": 7,
                            "total_tokens": 18
                          }
                        }
                        """);
            }
        };

        JsonNode jsonSchema = JsonUtil.readTree("""
                {
                  "type": "object",
                  "required": ["answer"],
                  "properties": {"answer": {"type": "string"}},
                  "additionalProperties": false
                }
                """);
        List<LlmChatMessage> persistedMessages = List.of(
                LlmChatMessage.builder().role(LlmConstants.ROLE_SYSTEM).content("return json")
                        .build(),
                LlmChatMessage.builder().role(LlmConstants.ROLE_USER).content("first question")
                        .build(),
                LlmChatMessage.builder().role(LlmConstants.ROLE_ASSISTANT).content("first answer")
                        .build(),
                LlmChatMessage.builder().role(LlmConstants.ROLE_USER).content("second question")
                        .build());

        LlmChatResponse response =
                adapter.chat(LlmChatRequest.builder().providerType(LlmConstants.PROVIDER_DEEPSEEK)
                        .baseUrl("https://api.deepseek.com/").apiKey("test-key")
                        .modelName("deepseek-v4-pro").responseFormat(LlmConstants.FORMAT_JSON)
                        .jsonSchema(jsonSchema).thinkingEnabled(true).reasoningEffort("high")
                        .messages(persistedMessages)
                        .build());

        assertTrue(response.isSuccess());
        assertEquals("ok", response.getParsedJson().path("answer").asText());
        assertEquals("short reasoning", response.getReasoningContent());
        assertEquals(18, response.getTotalTokens());
        assertNotNull(response.getRawResponseRef());
        assertFalse(response.getRawResponseRef().contains("answer"));
        assertFalse(response.getRawResponseRef().contains("short reasoning"));
        assertEquals(URI.create("https://api.deepseek.com/chat/completions"),
                requestRef.get().uri());
        JsonNode providerBody = JsonUtil.readTree(requestBodyRef.get());
        assertEquals("json_object", providerBody.path("response_format").path("type").asText());
        assertEquals(5, providerBody.path("messages").size());
        JsonNode contractMessage = providerBody.path("messages").get(0);
        assertEquals(LlmConstants.ROLE_SYSTEM, contractMessage.path("role").asText());
        assertTrue(contractMessage.path("content").asText().contains(jsonSchema.toString()));
        assertTrue(contractMessage.path("content").asText().contains("exactly one valid JSON"));
        // 临时 contract 只存在于 HTTP 请求体，不能污染 Gateway 传入的持久化消息集合。
        assertEquals(4, persistedMessages.size());
        assertEquals("return json", persistedMessages.get(0).getContent());
        assertEquals(LlmJsonOutputMode.JSON_OBJECT, adapter.jsonOutputMode());
    }

    @Test
    void chatShouldReturnJsonParseFailedForInvalidJsonOutput() {
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(HttpClient.newHttpClient()) {
            @Override
            protected HttpResponse<String> send(HttpRequest httpRequest) {
                return response(200, """
                        {
                          "id": "deepseek-request-2",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {
                              "role": "assistant",
                              "content": "not-json",
                              "reasoning_content": "invalid-json reasoning"
                            }
                          }],
                          "usage": {
                            "prompt_tokens": 13,
                            "completion_tokens": 5,
                            "total_tokens": 18
                          }
                        }
                        """);
            }
        };

        LlmChatResponse response = adapter.chat(LlmChatRequest.builder()
                .providerType(LlmConstants.PROVIDER_DEEPSEEK).baseUrl("https://api.deepseek.com")
                .apiKey("test-key").modelName("deepseek-v4-pro")
                .responseFormat(LlmConstants.FORMAT_JSON).messages(List.of(LlmChatMessage.builder()
                        .role(LlmConstants.ROLE_USER).content("return json").build()))
                .build());

        assertFalse(response.isSuccess());
        assertEquals(LlmConstants.ERROR_JSON_PARSE_FAILED, response.getErrorCode());
        assertEquals("not-json", response.getContent());
        assertEquals("invalid-json reasoning", response.getReasoningContent());
        assertEquals("deepseek-request-2", response.getProviderRequestId());
        assertEquals(13, response.getPromptTokens());
        assertEquals(5, response.getCompletionTokens());
        assertEquals(18, response.getTotalTokens());
        assertNotNull(response.getRawResponseRef());
        assertFalse(response.getRawResponseRef().contains("not-json"));
    }

    @Test
    void normalizeErrorShouldFollowDeepSeekOfficialStatusCodes() {
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter();

        assertEquals(LlmConstants.ERROR_BAD_REQUEST, adapter.normalizeError(400, ""));
        assertEquals(LlmConstants.ERROR_AUTH_FAILED, adapter.normalizeError(401, ""));
        assertEquals(LlmConstants.ERROR_INSUFFICIENT_BALANCE, adapter.normalizeError(402, ""));
        assertEquals(LlmConstants.ERROR_BAD_REQUEST, adapter.normalizeError(422, ""));
        assertEquals(LlmConstants.ERROR_RATE_LIMITED, adapter.normalizeError(429, ""));
        assertEquals(LlmConstants.ERROR_PROVIDER_UNAVAILABLE, adapter.normalizeError(500, ""));
        assertEquals(LlmConstants.ERROR_PROVIDER_UNAVAILABLE, adapter.normalizeError(503, ""));
    }

    /** Provider 响应引用只保留 HTTP 定位信息，不复制可能回显 Prompt 的错误体。 */
    @Test
    void chatErrorShouldNotCopyProviderBodyIntoRawResponseReference() {
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(HttpClient.newHttpClient()) {
            @Override
            protected HttpResponse<String> send(HttpRequest httpRequest) {
                return response(400, """
                        {"error":{"message":"invalid prompt owner@example.com token=secret-value"}}
                        """);
            }
        };

        LlmChatResponse response = adapter.chat(LlmChatRequest.builder()
                .providerType(LlmConstants.PROVIDER_DEEPSEEK)
                .baseUrl("https://api.deepseek.com").apiKey("test-key")
                .modelName("deepseek-v4-pro").messages(List.of(LlmChatMessage.builder()
                        .role(LlmConstants.ROLE_USER).content("sensitive prompt").build()))
                .build());

        assertFalse(response.isSuccess());
        assertEquals("httpStatus=400", response.getRawResponseRef());
        assertFalse(response.getRawResponseRef().contains("owner@example.com"));
        assertFalse(response.getRawResponseRef().contains("secret-value"));
    }

    @Test
    void chatShouldNormalizeTimeout() {
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(HttpClient.newHttpClient()) {
            @Override
            protected HttpResponse<String> send(HttpRequest httpRequest) throws IOException {
                throw new java.net.http.HttpTimeoutException("timeout");
            }
        };

        LlmChatResponse response = adapter.chat(LlmChatRequest.builder()
                .providerType(LlmConstants.PROVIDER_DEEPSEEK).baseUrl("https://api.deepseek.com")
                .apiKey("test-key").modelName("deepseek-v4-pro").messages(List.of(LlmChatMessage
                        .builder().role(LlmConstants.ROLE_USER).content("hello").build()))
                .timeoutMs(1L).build());

        assertFalse(response.isSuccess());
        assertEquals(LlmConstants.ERROR_TIMEOUT, response.getErrorCode());
    }

    /**
     * 构造简化 HTTP 响应。
     */
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
                return HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
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
                return URI.create("https://api.deepseek.com/chat/completions");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /** 订阅 JDK BodyPublisher 并读取真实发往 Provider 的 UTF-8 请求体。 */
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
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
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
