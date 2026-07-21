package dev.langchain4j.provider;

import com.tencent.supersonic.common.llm.LlmConstants;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.EmbeddingModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 协议模型工厂。
 *
 * <p>
 * 职责说明：根据系统中配置的 {@link ChatModelConfig} 和 {@link EmbeddingModelConfig} 创建 OpenAI 兼容的普通对话、流式对话和
 * Embedding 模型。该工厂同时承担第三方 OpenAI-compatible 服务的轻量适配职责，例如 Kimi 的标准兼容协议和小米 MiMo 的自定义鉴权请求头。
 *
 * <p>
 * 并发说明：本类由 Spring 以单例方式管理，但不持有可变共享状态；每次创建模型时都只使用方法内局部变量组装 builder，因此无需额外的 {@code synchronized} 保护。
 */
@Service
public class OpenAiModelFactory implements ModelFactory, InitializingBean {

    public static final String PROVIDER = "OPEN_AI";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL_NAME = "gpt-4o-mini";
    public static final String DEFAULT_EMBEDDING_MODEL_NAME = "text-embedding-ada-002";
    public static final String DEFAULT_API_VERSION = "2024-02-01";
    private static final String XIAOMI_MIMO_BASE_URL_KEYWORD = "xiaomimimo.com";
    private static final String XIAOMI_MIMO_API_KEY_HEADER = "api-key";

    /**
     * 创建 OpenAI 兼容的普通对话模型。
     *
     * <p>
     * 调用示例：{@code openAiModelFactory.createChatModel(chatModelConfig)}。
     *
     * @param modelConfig 大模型连接配置，包含 BaseUrl、模型名称、密钥、超时和日志开关等信息。
     * @return 可直接用于非流式问答的大语言模型实例。
     * @throws IllegalStateException 当运行期 builder 暴露了 apiVersion 方法但反射设置失败时抛出。
     */
    @Override
    public ChatLanguageModel createChatModel(ChatModelConfig modelConfig) {
        String apiKey = modelConfig.keyDecrypt();
        OpenAiChatModel.OpenAiChatModelBuilder openAiChatModelBuilder = OpenAiChatModel.builder()
                .baseUrl(modelConfig.getBaseUrl()).modelName(modelConfig.getModelName())
                .apiKey(apiKey).temperature(modelConfig.getTemperature())
                .topP(modelConfig.getTopP()).maxRetries(modelConfig.getMaxRetries())
                .timeout(Duration.ofSeconds(modelConfig.getTimeOut()))
                .logRequests(modelConfig.getLogRequests())
                .logResponses(modelConfig.getLogResponses())
                .customHeaders(buildOpenAiCompatibleHeaders(modelConfig, apiKey));
        applyApiVersionIfSupported(openAiChatModelBuilder, modelConfig.getApiVersion());
        if (modelConfig.getJsonFormat() != null && modelConfig.getJsonFormat()) {
            openAiChatModelBuilder.strictJsonSchema(true)
                    .responseFormat(modelConfig.getJsonFormatType());
        }
        return openAiChatModelBuilder.build();
    }

    /**
     * 创建 OpenAI 兼容的流式对话模型。
     *
     * @param modelConfig 大模型连接配置，包含 BaseUrl、模型名称、密钥、超时和日志开关等信息。
     * @return 可直接用于流式问答的大语言模型实例。
     * @throws IllegalStateException 当运行期 builder 暴露了 apiVersion 方法但反射设置失败时抛出。
     */
    @Override
    public OpenAiStreamingChatModel createChatStreamingModel(ChatModelConfig modelConfig) {
        String apiKey = modelConfig.keyDecrypt();
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder openAiStreamingChatModelBuilder =
                OpenAiStreamingChatModel.builder().baseUrl(modelConfig.getBaseUrl())
                        .modelName(modelConfig.getModelName()).apiKey(apiKey)
                        .temperature(modelConfig.getTemperature()).topP(modelConfig.getTopP())
                        .timeout(Duration.ofSeconds(modelConfig.getTimeOut()))
                        .logRequests(modelConfig.getLogRequests())
                        .logResponses(modelConfig.getLogResponses())
                        .customHeaders(buildOpenAiCompatibleHeaders(modelConfig, apiKey));
        applyApiVersionIfSupported(openAiStreamingChatModelBuilder, modelConfig.getApiVersion());
        return openAiStreamingChatModelBuilder.build();
    }

    /**
     * 创建 OpenAI 兼容的 Embedding 模型。
     *
     * @param embeddingModel Embedding 连接配置，包含 BaseUrl、模型名称、密钥和日志开关等信息。
     * @return 可直接用于向量化文本的 Embedding 模型实例。
     */
    @Override
    public EmbeddingModel createEmbeddingModel(EmbeddingModelConfig embeddingModel) {
        return OpenAiEmbeddingModel.builder().baseUrl(embeddingModel.getBaseUrl())
                .apiKey(embeddingModel.getApiKey()).modelName(embeddingModel.getModelName())
                .maxRetries(embeddingModel.getMaxRetries())
                .logRequests(embeddingModel.getLogRequests())
                .logResponses(embeddingModel.getLogResponses()).build();
    }

    /**
     * 将本工厂注册到模型供应商注册表。
     *
     * <p>
     * Kimi 的基础 ChatLanguageModel 调用与 OpenAI 协议兼容，因此复用本工厂；K3 特有的思考内容和严格参数约束由 Conversation Gateway 中的
     * KimiProviderAdapter 处理，避免在通用工厂中混入厂商分支。
     * </p>
     */
    @Override
    public void afterPropertiesSet() {
        ModelProvider.add(PROVIDER, this);
        ModelProvider.add(LlmConstants.PROVIDER_KIMI, this);
    }

    /**
     * 在运行期 builder 支持 apiVersion 时设置版本号。
     *
     * <p>
     * 设计意图：Supersonic 当前源码里内置了一份扩展过的 OpenAiChatModel，该 builder 暴露 apiVersion；但发布包中同时存在
     * langchain4j-open-ai 原版 jar，原版 builder 没有 apiVersion。直接链式调用会在类加载顺序变化时触发
     * NoSuchMethodError，因此这里改为反射探测，兼容两种运行期类。
     *
     * @param builder OpenAI 或 OpenAI-compatible 模型 builder。
     * @param apiVersion 用户在页面上配置的 API 版本；为空时不设置。
     * @throws IllegalStateException 当方法存在但调用失败时抛出，避免静默创建出错误配置的模型。
     */
    private void applyApiVersionIfSupported(Object builder, String apiVersion) {
        if (apiVersion == null || apiVersion.isBlank()) {
            return;
        }
        try {
            Method apiVersionMethod = builder.getClass().getMethod("apiVersion", String.class);
            apiVersionMethod.invoke(builder, apiVersion);
        } catch (NoSuchMethodException ignored) {
            // 原版 langchain4j OpenAI builder 不支持 apiVersion；多数 OpenAI-compatible 平台也不需要该字段。
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to apply OpenAI API version.", exception);
        }
    }

    /**
     * 为 OpenAI-compatible 平台构造自定义请求头。
     *
     * <p>
     * 小米 MiMo 的 curl 文档要求携带 {@code api-key} 请求头；OpenAI SDK 兼容模式仍会发送 Authorization Bearer。这里额外补充
     * {@code api-key}，以同时兼容 SDK 风格和网关风格的鉴权校验。方法只在内存中使用解密后的密钥，不写日志、不返回给前端，避免泄露敏感信息。
     *
     * @param modelConfig 大模型连接配置。
     * @param apiKey 已解密的 API Key。
     * @return 第三方平台需要的自定义请求头；没有额外请求头时返回 {@code null}。
     */
    private Map<String, String> buildOpenAiCompatibleHeaders(ChatModelConfig modelConfig,
            String apiKey) {
        Map<String, String> customHeaders = new HashMap<>();
        String baseUrl = modelConfig.getBaseUrl();
        if (baseUrl != null && apiKey != null && !apiKey.isBlank()
                && baseUrl.toLowerCase(Locale.ROOT).contains(XIAOMI_MIMO_BASE_URL_KEYWORD)) {
            customHeaders.put(XIAOMI_MIMO_API_KEY_HEADER, apiKey);
        }
        return customHeaders.isEmpty() ? null : customHeaders;
    }
}
