package com.tencent.supersonic.common.pojo;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.tencent.supersonic.common.llm.LlmConstants;
import dev.langchain4j.provider.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话模型动态表单参数定义。
 *
 * <p>
 * 职责说明：集中声明供应商候选项及其 Base URL、模型名、API 版本、温度等联动默认值。KIMI 复用 OpenAI-compatible 基础模型工厂，但使用 K3 官方要求的
 * Moonshot 地址、模型名和固定温度。
 * </p>
 *
 * <p>
 * 并发说明：所有配置对象在类初始化后只读使用，不保存用户请求态；返回的参数列表是新集合，因此无需额外锁。
 * </p>
 */
public class ChatModelParameters {
    private static final String MODULE_NAME = "对话模型配置";
    private static final String KIMI_DEFAULT_BASE_URL = "https://api.moonshot.cn/v1";
    private static final String KIMI_DEFAULT_MODEL_NAME = "kimi-k3";
    private static final String KIMI_DEFAULT_TEMPERATURE = "1.0";

    public static final Parameter CHAT_MODEL_PROVIDER =
            new Parameter("provider", ModelProvider.DEMO_CHAT_MODEL.getProvider(), "接口协议", "",
                    "list", MODULE_NAME, getCandidateProviders());

    public static final Parameter CHAT_MODEL_BASE_URL =
            new Parameter("baseUrl", ModelProvider.DEMO_CHAT_MODEL.getBaseUrl(), "BaseUrl", "",
                    "string", MODULE_NAME, null, getBaseUrlDependency());

    public static final Parameter CHAT_MODEL_NAME =
            new Parameter("modelName", ModelProvider.DEMO_CHAT_MODEL.getModelName(), "ModelName",
                    "", "string", MODULE_NAME, null, getModelNameDependency());

    public static final Parameter CHAT_MODEL_API_KEY = new Parameter("apiKey", "", "ApiKey", "",
            "password", MODULE_NAME, null, getApiKeyDependency());

    public static final Parameter CHAT_MODEL_API_VERSION = new Parameter("apiVersion", "2024-02-01",
            "ApiVersion", "", "string", MODULE_NAME, null, getApiVersionDependency());

    public static final Parameter CHAT_MODEL_TEMPERATURE = new Parameter("temperature", "0.0",
            "Temperature", "", "slider", MODULE_NAME, null, getTemperatureDependency());

    public static final Parameter CHAT_MODEL_TIMEOUT =
            new Parameter("timeOut", "60", "超时时间(秒)", "", "number", MODULE_NAME);

    /**
     * 返回大模型管理页面需要渲染的参数定义。
     *
     * @return 按供应商、地址、密钥、模型名、版本、温度和超时排序的新参数列表。
     */
    public static List<Parameter> getParameters() {
        return Lists.newArrayList(CHAT_MODEL_PROVIDER, CHAT_MODEL_BASE_URL, CHAT_MODEL_API_KEY,
                CHAT_MODEL_NAME, CHAT_MODEL_API_VERSION, CHAT_MODEL_TEMPERATURE,
                CHAT_MODEL_TIMEOUT);
    }

    /** 返回当前系统支持创建连接的供应商列表。 */
    private static List<String> getCandidateProviders() {
        return Lists.newArrayList(OpenAiModelFactory.PROVIDER, LlmConstants.PROVIDER_KIMI,
                OllamaModelFactory.PROVIDER, DifyModelFactory.PROVIDER);
    }

    /** 返回供应商到默认 Base URL 的联动配置。 */
    private static List<Parameter.Dependency> getBaseUrlDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(), getCandidateProviders(),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_BASE_URL,
                        LlmConstants.PROVIDER_KIMI, KIMI_DEFAULT_BASE_URL,
                        OllamaModelFactory.PROVIDER, OllamaModelFactory.DEFAULT_BASE_URL,
                        DifyModelFactory.PROVIDER, DifyModelFactory.DEFAULT_BASE_URL));
    }

    /** 返回需要显示 API Key 控件的供应商及其默认值。 */
    private static List<Parameter.Dependency> getApiKeyDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER, LlmConstants.PROVIDER_KIMI,
                        DifyModelFactory.PROVIDER),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER,
                        ModelProvider.DEMO_CHAT_MODEL.getApiKey(), LlmConstants.PROVIDER_KIMI, "",
                        DifyModelFactory.PROVIDER, ModelProvider.DEMO_CHAT_MODEL.getApiKey()));
    }

    /** 返回 OpenAI-compatible 供应商的 API 版本联动配置；Kimi 默认留空。 */
    private static List<Parameter.Dependency> getApiVersionDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER, LlmConstants.PROVIDER_KIMI),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_API_VERSION,
                        LlmConstants.PROVIDER_KIMI, ""));
    }

    /** 返回供应商到默认模型名的联动配置。 */
    private static List<Parameter.Dependency> getModelNameDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(), getCandidateProviders(),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_MODEL_NAME,
                        LlmConstants.PROVIDER_KIMI, KIMI_DEFAULT_MODEL_NAME,
                        OllamaModelFactory.PROVIDER, OllamaModelFactory.DEFAULT_MODEL_NAME,
                        DifyModelFactory.PROVIDER, DifyModelFactory.DEFAULT_MODEL_NAME));
    }

    /**
     * 返回供应商切换时的温度默认值。
     *
     * <p>
     * Kimi K3 的 temperature 固定为 1.0；页面切换到 KIMI 时必须覆盖项目原有的 0.0 默认值，否则连接测试会被 Kimi 参数校验拒绝。
     * </p>
     */
    private static List<Parameter.Dependency> getTemperatureDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(), getCandidateProviders(),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, "0.0", LlmConstants.PROVIDER_KIMI,
                        KIMI_DEFAULT_TEMPERATURE, OllamaModelFactory.PROVIDER, "0.0",
                        DifyModelFactory.PROVIDER, "0.0"));
    }

    /** 返回历史 Endpoint 参数的 OpenAI 联动配置。 */
    private static List<Parameter.Dependency> getEndpointDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER), ImmutableMap
                        .of(OpenAiModelFactory.PROVIDER, OpenAiModelFactory.DEFAULT_MODEL_NAME));
    }

    /** 返回历史联网搜索开关的 OpenAI 联动配置。 */
    private static List<Parameter.Dependency> getEnableSearchDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER),
                ImmutableMap.of(OpenAiModelFactory.PROVIDER, "false"));
    }

    /** 返回历史 Secret Key 参数的 OpenAI 联动配置。 */
    private static List<Parameter.Dependency> getSecretKeyDependency() {
        return getDependency(CHAT_MODEL_PROVIDER.getName(),
                Lists.newArrayList(OpenAiModelFactory.PROVIDER), ImmutableMap.of(
                        OpenAiModelFactory.PROVIDER, ModelProvider.DEMO_CHAT_MODEL.getApiKey()));
    }

    /**
     * 构造一个供应商字段驱动的动态表单依赖。
     *
     * @param dependencyParameterName 触发联动的字段名。
     * @param includesValue 需要展示目标控件的字段值。
     * @param setDefaultValue 各字段值对应的默认值。
     * @return 仅包含当前联动规则的新列表。
     */
    private static List<Parameter.Dependency> getDependency(String dependencyParameterName,
            List<String> includesValue, Map<String, String> setDefaultValue) {

        Parameter.Dependency.Show show = new Parameter.Dependency.Show();
        show.setIncludesValue(includesValue);

        Parameter.Dependency dependency = new Parameter.Dependency();
        dependency.setName(dependencyParameterName);
        dependency.setShow(show);
        dependency.setSetDefaultValue(setDefaultValue);
        List<Parameter.Dependency> dependencies = new ArrayList<>();
        dependencies.add(dependency);
        return dependencies;
    }
}
