package com.tencent.supersonic.headless.server.semantic.modeling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 语义建模草稿异步执行器配置。
 *
 * <p>
 * 职责说明：提供独立、有界的生成线程池，避免模型调用占用通用异步线程。拒绝策略由调用方捕获并 将草稿置为失败。并发说明：线程池容量固定受控，关闭时等待已提交任务完成，任务内部不持有数据库长事务。
 * </p>
 */
@Configuration
public class SemanticModelingExecutorConfig {

    /**
     * 创建语义建模专用执行器。
     *
     * @param properties 阶段 3 运行参数。
     * @return 初始化完成的有界线程池。
     */
    @Bean(name = "semanticModelingExecutor")
    public ThreadPoolTaskExecutor semanticModelingExecutor(SemanticModelingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("semantic-modeling-");
        executor.setCorePoolSize(properties.getExecutorCorePoolSize());
        executor.setMaxPoolSize(properties.getExecutorMaxPoolSize());
        executor.setQueueCapacity(properties.getExecutorQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // AbortPolicy 让提交方感知背压，并把已创建草稿明确转为失败，避免永久 GENERATING。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
