package com.tencent.supersonic.common.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
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
    void chatShouldSendFullMessagesAndParseJsonReasoningUsage() {
        AtomicReference<HttpRequest> requestRef = new AtomicReference<>();
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(HttpClient.newHttpClient()) {
            @Override
            protected HttpResponse<String> send(HttpRequest httpRequest) {
                requestRef.set(httpRequest);
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

        LlmChatResponse response =
                adapter.chat(LlmChatRequest.builder().providerType(LlmConstants.PROVIDER_DEEPSEEK)
                        .baseUrl("https://api.deepseek.com/").apiKey("test-key")
                        .modelName("deepseek-v4-pro").responseFormat(LlmConstants.FORMAT_JSON)
                        .thinkingEnabled(true).reasoningEffort("high")
                        .messages(List.of(
                                LlmChatMessage.builder().role(LlmConstants.ROLE_SYSTEM)
                                        .content("return json").build(),
                                LlmChatMessage.builder().role(LlmConstants.ROLE_USER)
                                        .content("first question").build(),
                                LlmChatMessage.builder().role(LlmConstants.ROLE_ASSISTANT)
                                        .content("first answer").build(),
                                LlmChatMessage.builder().role(LlmConstants.ROLE_USER)
                                        .content("second question").build()))
                        .build());

        assertTrue(response.isSuccess());
        assertEquals("ok", response.getParsedJson().path("answer").asText());
        assertEquals("short reasoning", response.getReasoningContent());
        assertEquals(18, response.getTotalTokens());
        assertEquals(URI.create("https://api.deepseek.com/chat/completions"),
                requestRef.get().uri());
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
                            "message": {"role": "assistant", "content": "not-json"}
                          }]
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
        assertNotNull(response.getRawResponseRef());
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
}
