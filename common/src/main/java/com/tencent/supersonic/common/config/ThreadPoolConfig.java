package com.tencent.supersonic.common.config;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 平台通用线程池配置。
 *
 * <p>职责说明：集中定义事件、通用、映射、问答以及语义缺口采集等后台执行器。并发说明：所有 Bean 都是
 * {@link ThreadPoolExecutor}，由调用方决定提交策略；其中语义缺口采集线程池使用有界队列和拒绝异常，确保
 * Chat BI 主链路不会在队列满时退化为调用线程执行。</p>
 */
@Component
public class ThreadPoolConfig {

    /**
     * 创建事件线程池。
     *
     * @return 事件异步执行器。
     */
    @Bean("eventExecutor")
    public ThreadPoolExecutor getTaskEventExecutor() {
        return new ThreadPoolExecutor(4, 8, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-event-pool-").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 创建通用线程池。
     *
     * @return 通用异步执行器。
     */
    @Bean("commonExecutor")
    public ThreadPoolExecutor getCommonExecutor() {
        return new ThreadPoolExecutor(8, 16, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-common-pool-").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 创建语义映射线程池。
     *
     * @return schema map 异步执行器。
     */
    @Bean("mapExecutor")
    public ThreadPoolExecutor getMapExecutor() {
        return new ThreadPoolExecutor(8, 16, 60 * 3, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("supersonic-map-pool-").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 创建问答线程池。
     *
     * @return Chat BI 异步执行器。
     */
    @Bean("chatExecutor")
    public ThreadPoolExecutor getChatExecutor() {
        return new ThreadPoolExecutor(8, 16, 60 * 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactoryBuilder().setNameFormat("supersonic-chat-pool-").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 创建语义缺口采集专用线程池。
     *
     * @return 语义缺口采集执行器。
     *
     * <p>设计取舍：阶段 2 不引入 outbox 或消息队列，因此用小型有界线程池隔离采集写库压力。拒绝策略使用
     * {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}，让调用方显式记录 warn 并丢弃采集事件；
     * 不使用 caller-runs，避免 DB 慢写或短时峰值把问答/反馈接口拖慢。</p>
     */
    @Bean("semanticGapCaptureExecutor")
    public ThreadPoolExecutor getSemanticGapCaptureExecutor() {
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                new ThreadFactoryBuilder().setNameFormat("supersonic-semantic-gap-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
