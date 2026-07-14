package com.tencent.supersonic.headless.server.task;

import javax.annotation.PreDestroy;

import com.tencent.supersonic.common.config.EmbeddingConfig;
import com.tencent.supersonic.common.pojo.DataItem;
import com.tencent.supersonic.common.service.EmbeddingService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import dev.langchain4j.inmemory.spring.InMemoryEmbeddingStoreFactory;
import dev.langchain4j.store.embedding.EmbeddingStoreFactory;
import dev.langchain4j.store.embedding.EmbeddingStoreFactoryProvider;
import dev.langchain4j.store.embedding.TextSegmentConvert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语义元数据向量刷新与内存索引持久化任务。
 *
 * <p>
 * 职责说明：定时刷新维度/指标 embedding，并为阶段 5 提供可感知失败的严格入口。并发说明： 共享向量存储由 {@link EmbeddingService}
 * 实现负责线程安全，本类不保留请求级可变状态。
 * </p>
 */
@Component
@Slf4j
@Order(2)
public class MetaEmbeddingTask implements CommandLineRunner {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EmbeddingConfig embeddingConfig;

    @Autowired
    private MetricService metricService;

    @Autowired
    private DimensionService dimensionService;

    /** 应用退出前持久化内存向量存储；非内存实现无需处理。 */
    @PreDestroy
    public void onShutdown() {
        embeddingStorePersistFile();
    }

    private void embeddingStorePersistFile() {
        EmbeddingStoreFactory embeddingStoreFactory = EmbeddingStoreFactoryProvider.getFactory();
        if (embeddingStoreFactory instanceof InMemoryEmbeddingStoreFactory inMemoryFactory) {
            long startTime = System.currentTimeMillis();
            inMemoryFactory.persistFile();
            long duration = System.currentTimeMillis() - startTime;
            log.info("Embedding file has been regularly persisted in {} milliseconds", duration);
        }
    }

    /** 定时持久化内存向量存储，防止进程退出造成索引丢失。 */
    @Scheduled(cron = "${s2.inMemoryEmbeddingStore.persist.cron:0 0 * * * ?}")
    public void executePersistFileTask() {
        embeddingStorePersistFile();
    }

    /** 定时容错刷新元数据 embedding；失败只记录日志。 */
    @Scheduled(cron = "${s2.reload.meta.embedding.corn:0 0 */2 * * ?}")
    public void reloadMetaEmbedding() {
        long startTime = System.currentTimeMillis();
        try {
            reloadMetaEmbeddingOrThrow();
        } catch (Exception e) {
            log.error("Failed to reload meta embedding.", e);
        }
        long duration = System.currentTimeMillis() - startTime;
        log.info("Embedding has been regularly reloaded  in {} milliseconds", duration);
    }

    /**
     * 严格重建维度与指标 embedding，并向调用方传播失败。
     *
     * <p>
     * 调用示例：{@code metaEmbeddingTask.reloadMetaEmbeddingOrThrow()}。发布与回滚编排使用该入口
     * 将刷新结果独立写入发布记录；任一数据事件读取或向量写入失败都视为本步骤失败。
     * </p>
     *
     * @throws RuntimeException 数据事件构造或向量存储写入失败时原样传播。
     */
    public void reloadMetaEmbeddingOrThrow() {
        List<DataItem> metricDataItems = metricService.getDataEvent().getDataItems();
        embeddingService.addQuery(embeddingConfig.getMetaCollectionName(),
                TextSegmentConvert.convertToEmbedding(metricDataItems));

        List<DataItem> dimensionDataItems = dimensionService.getAllDataEvents().getDataItems();
        embeddingService.addQuery(embeddingConfig.getMetaCollectionName(),
                TextSegmentConvert.convertToEmbedding(dimensionDataItems));
    }

    /**
     * 应用启动时触发一次容错 embedding 刷新。
     *
     * @param args 启动参数，本任务不读取该参数。
     */
    @Override
    public void run(String... args) throws Exception {
        try {
            reloadMetaEmbedding();
        } catch (Exception e) {
            log.error("initMetaEmbedding error", e);
        }
    }
}
