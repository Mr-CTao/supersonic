package com.tencent.supersonic.common.llm;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Provider Adapter 注册表。
 *
 * <p>
 * 职责说明：从 Spring 容器中收集所有 {@link LlmProviderAdapter}，按 provider/baseUrl/modelName 选择第一个匹配的适配器。
 * 并发说明：Adapter 列表由 Spring 初始化后只读使用，选择过程无共享可变状态，不需要额外锁。
 * </p>
 */
@Service
public class LlmProviderAdapterRegistry {

    private final List<LlmProviderAdapter> adapters;

    /**
     * 创建 Adapter 注册表。
     *
     * @param adapters Spring 注入的所有 Provider Adapter。
     */
    public LlmProviderAdapterRegistry(List<LlmProviderAdapter> adapters) {
        this.adapters = adapters;
    }

    /**
     * 根据供应商配置查找 Adapter。
     *
     * @param providerType 供应商类型。
     * @param baseUrl 模型 base URL。
     * @param modelName 模型名。
     * @return 匹配的 Adapter。
     * @throws IllegalStateException 当没有可用 Adapter 时抛出。
     */
    public LlmProviderAdapter getAdapter(String providerType, String baseUrl, String modelName) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(providerType, baseUrl, modelName)).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported LLM provider adapter: " + providerType));
    }
}
