package com.tencent.supersonic.headless.server.listener;

import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.DataEvent;
import com.tencent.supersonic.common.pojo.DataItem;
import com.tencent.supersonic.common.pojo.enums.EventType;
import com.tencent.supersonic.common.service.EmbeddingService;
import com.tencent.supersonic.headless.api.pojo.response.RefreshStatus;
import com.tencent.supersonic.headless.server.semantic.diagnostic.ModelHealthService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.TextSegmentConvert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 将正式语义资产事件异步同步到 Embedding 索引，并记录模型级刷新状态。
 */
@Component
@Slf4j
public class MetaEmbeddingListener {

    @Autowired
    private EmbeddingConfig embeddingConfig;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ModelHealthService modelHealthService;

    @Value("${s2.embedding.operation.sleep.time:3000}")
    private Integer embeddingOperationSleepTime;

    /**
     * 执行 Embedding 增删改，并将 PENDING 推进为 RUNNING/SUCCEEDED/FAILED。
     *
     * @param event 正式资产变更事件。
     */
    @Async("eventExecutor")
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationEvent(DataEvent event) {
        List<DataItem> dataItems = event.getDataItems();
        if (CollectionUtils.isEmpty(dataItems)) {
            return;
        }
        List<Long> modelIds = ModelHealthService.toModelIds(dataItems);
        List<TextSegment> textSegments = TextSegmentConvert.convertToEmbedding(dataItems);
        if (CollectionUtils.isEmpty(textSegments)) {
            modelHealthService.recordEmbedding(modelIds, RefreshStatus.SUCCEEDED);
            return;
        }
        modelHealthService.recordEmbedding(modelIds, RefreshStatus.RUNNING);
        try {
            sleep();
            updateEmbedding(event, textSegments);
            modelHealthService.recordEmbedding(modelIds, RefreshStatus.SUCCEEDED);
        } catch (RuntimeException exception) {
            modelHealthService.recordEmbedding(modelIds, RefreshStatus.FAILED);
            log.error("meta embedding refresh failed, modelIds={}", modelIds, exception);
            throw exception;
        }
    }

    /** 根据事件类型更新向量索引。 */
    private void updateEmbedding(DataEvent event, List<TextSegment> textSegments) {
        if (event.getEventType().equals(EventType.ADD)) {
            embeddingService.addQuery(embeddingConfig.getMetaCollectionName(), textSegments);
        } else if (event.getEventType().equals(EventType.DELETE)) {
            embeddingService.deleteQuery(embeddingConfig.getMetaCollectionName(), textSegments);
        } else if (event.getEventType().equals(EventType.UPDATE)) {
            embeddingService.deleteQuery(embeddingConfig.getMetaCollectionName(), textSegments);
            embeddingService.addQuery(embeddingConfig.getMetaCollectionName(), textSegments);
        }
    }

    /** 延迟合并短时间内的高频元数据事件。 */
    private void sleep() {
        try {
            Thread.sleep(embeddingOperationSleepTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 刷新线程被中断", e);
        }
    }
}
