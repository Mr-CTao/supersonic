package com.tencent.supersonic.headless.server.listener;

import com.tencent.supersonic.common.pojo.DataEvent;
import com.tencent.supersonic.headless.api.pojo.response.RefreshStatus;
import com.tencent.supersonic.headless.server.semantic.diagnostic.ModelHealthService;
import com.tencent.supersonic.headless.server.service.impl.SchemaServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 正式语义资产事件的同步缓存失效协调器。
 *
 * <p>
 * 职责：在保存事务发布 DataEvent 后立即失效相关模型缓存，并在异步词典/Embedding 监听器 启动前写入 PENDING。该监听器不使用 JVM
 * 锁：缓存和健康状态服务均提供线程安全原语；多实例 一致性取决于部署环境是否广播同一 DataEvent。
 */
@Component
@Slf4j
public class SemanticAssetRefreshListener {

    private final SchemaServiceImpl schemaService;
    private final ModelHealthService modelHealthService;

    public SemanticAssetRefreshListener(SchemaServiceImpl schemaService,
            ModelHealthService modelHealthService) {
        this.schemaService = schemaService;
        this.modelHealthService = modelHealthService;
    }

    /**
     * 同步处理资产事件，确保后续请求不会继续命中旧 Schema。
     *
     * @param event 模型、维度或指标变更事件。
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onApplicationEvent(DataEvent event) {
        if (event == null || CollectionUtils.isEmpty(event.getDataItems())) {
            return;
        }
        List<Long> modelIds = ModelHealthService.toModelIds(event.getDataItems());
        if (modelIds.isEmpty()) {
            return;
        }
        modelHealthService.recordSchemaCache(modelIds, RefreshStatus.PENDING);
        modelHealthService.recordDictionary(modelIds, RefreshStatus.PENDING);
        modelHealthService.recordEmbedding(modelIds, RefreshStatus.PENDING);
        try {
            int invalidated = schemaService.invalidateModelSchemas(modelIds);
            modelHealthService.recordSchemaCache(modelIds, RefreshStatus.SUCCEEDED);
            log.info("semantic schema cache invalidated, modelIds={}, entries={}", modelIds,
                    invalidated);
        } catch (RuntimeException exception) {
            modelHealthService.recordSchemaCache(modelIds, RefreshStatus.FAILED);
            log.error("semantic schema cache invalidation failed, modelIds={}", modelIds,
                    exception);
            throw exception;
        }
    }
}
