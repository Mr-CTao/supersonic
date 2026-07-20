package com.tencent.supersonic.forecast.server.worker;

import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import com.tencent.supersonic.forecast.server.service.ForecastControlStore;
import com.tencent.supersonic.forecast.server.util.ForecastErrorSanitizer;
import com.tencent.supersonic.forecast.server.worker.ForecastJobExecutor.ForecastJobCancelledException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立 Forecast Worker 的队列轮询、固定线程池、心跳和租约恢复运行时。
 *
 * <p>
 * 全局并发由有界线程池控制；跨实例并发由元数据库任务 CAS 与资源租约控制。运行上下文存放在 {@link ConcurrentHashMap}，心跳线程只写原子 leaseLost
 * 标记，不直接中断 JDBC 调用。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "forecast.worker", name = "enabled", havingValue = "true")
public class ForecastWorkerRuntime {

    private static final String WORKER_VERSION = "forecast-mvp-1";

    private final ForecastControlStore controlStore;
    private final ForecastJobExecutor jobExecutor;
    private final ForecastProperties properties;
    private final ForecastDecisionSchemaValidator schemaValidator;
    private final Map<Long, RunningJob> runningJobs = new ConcurrentHashMap<>();
    private final AtomicInteger activeJobs = new AtomicInteger();
    private final Instant startedAt = Instant.now();
    private final String workerId = createWorkerId();
    private ThreadPoolExecutor executor;

    /**
     * 创建 Worker 运行时。
     *
     * @param controlStore 元数据库控制面。
     * @param jobExecutor 任务执行器。
     * @param properties Worker 配置。
     * @param schemaValidator 决策库启动门禁。
     */
    public ForecastWorkerRuntime(ForecastControlStore controlStore, ForecastJobExecutor jobExecutor,
            ForecastProperties properties, ForecastDecisionSchemaValidator schemaValidator) {
        this.controlStore = controlStore;
        this.jobExecutor = jobExecutor;
        this.properties = properties;
        this.schemaValidator = schemaValidator;
    }

    /** 初始化固定大小、有界队列线程池。 */
    @PostConstruct
    public void initialize() {
        int concurrency = properties.getWorker().getConcurrency();
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), runnable -> {
                    Thread thread = new Thread(runnable, "forecast-job-" + UUID.randomUUID());
                    thread.setDaemon(false);
                    thread.setUncaughtExceptionHandler((ignored, exception) -> log.error(
                            "Forecast Worker 未捕获异常 type={}", exception.getClass().getName()));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        controlStore.heartbeatWorker(workerId, WORKER_VERSION, 0, startedAt);
        log.info("Forecast Worker 已启动 workerId={} concurrency={}", workerId, concurrency);
    }

    /**
     * 轮询并 CAS 认领任务；有界线程池没有容量时不读取更多任务。
     */
    @Scheduled(fixedDelayString = "#{${forecast.worker.poll-seconds:5} * 1000}")
    public void poll() {
        if (!schemaValidator.isReady()) {
            return;
        }
        int available = properties.getWorker().getConcurrency() - activeJobs.get();
        if (available <= 0) {
            return;
        }
        for (ForecastJobDO candidate : controlStore.queued(available)) {
            if (!controlStore.claim(candidate, workerId)) {
                continue;
            }
            List<String> resources;
            try {
                resources = jobExecutor.resourceKeys(candidate);
            } catch (RuntimeException exception) {
                controlStore.fail(candidate.getId(), workerId,
                        ForecastErrorSanitizer.code(exception),
                        ForecastErrorSanitizer.message(exception));
                continue;
            }
            if (!controlStore.acquireResources(resources, candidate.getId(), workerId)) {
                controlStore.yieldClaim(candidate.getId(), workerId);
                continue;
            }
            RunningJob running = new RunningJob(candidate, resources, new AtomicBoolean(false));
            runningJobs.put(candidate.getId(), running);
            activeJobs.incrementAndGet();
            try {
                executor.execute(() -> run(running));
            } catch (RejectedExecutionException exception) {
                runningJobs.remove(candidate.getId());
                activeJobs.decrementAndGet();
                controlStore.releaseResources(resources, candidate.getId(), workerId);
                controlStore.yieldClaim(candidate.getId(), workerId);
            }
        }
    }

    /**
     * 刷新 Worker/任务/资源租约，并恢复已过期任务。
     */
    @Scheduled(fixedDelayString = "#{${forecast.worker.heartbeat-seconds:15} * 1000}")
    public void heartbeat() {
        controlStore.heartbeatWorker(workerId, WORKER_VERSION, activeJobs.get(), startedAt);
        for (RunningJob running : runningJobs.values()) {
            boolean jobLease = controlStore.heartbeat(running.job().getId(), workerId);
            boolean resourceLease = controlStore.renewResources(running.resources(),
                    running.job().getId(), workerId);
            if (!jobLease || !resourceLease) {
                running.leaseLost().set(true);
            }
        }
        controlStore.recoverExpired();
    }

    /** 单任务执行与状态收口。 */
    private void run(RunningJob running) {
        ForecastJobDO job = running.job();
        try {
            jobExecutor.execute(job, workerId,
                    () -> running.leaseLost().get() || controlStore.isCancelling(job.getId()));
            if (controlStore.isCancelling(job.getId())) {
                controlStore.confirmCancelled(job.getId(), workerId);
            } else if (running.leaseLost().get()) {
                controlStore.fail(job.getId(), workerId, "LEASE_LOST", "任务资源租约已丢失，已停止执行");
            } else {
                controlStore.complete(job.getId(), workerId);
            }
        } catch (ForecastJobCancelledException exception) {
            if (controlStore.isCancelling(job.getId())) {
                controlStore.confirmCancelled(job.getId(), workerId);
            } else {
                controlStore.fail(job.getId(), workerId, "LEASE_LOST", "任务租约已丢失，已在安全页边界停止");
            }
        } catch (Throwable exception) {
            String code = ForecastErrorSanitizer.code(exception);
            String message = ForecastErrorSanitizer.message(exception);
            controlStore.fail(job.getId(), workerId, code, message);
            // 不输出原始 SQLException message，避免 JDBC URL 或凭据属性进入日志。
            log.error("Forecast 任务失败 jobId={} code={} exceptionType={}", job.getId(), code,
                    exception.getClass().getName());
        } finally {
            controlStore.releaseResources(running.resources(), job.getId(), workerId);
            runningJobs.remove(job.getId());
            activeJobs.decrementAndGet();
        }
    }

    /** 关闭前停止接收新任务并等待短时间完成页边界。 */
    @PreDestroy
    public void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                runningJobs.values().forEach(job -> job.leaseLost().set(true));
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** 创建不含用户名和完整主机信息的 Worker ID。 */
    private static String createWorkerId() {
        String runtime = ManagementFactory.getRuntimeMXBean().getName();
        String hostHash;
        try {
            hostHash = Integer.toHexString(InetAddress.getLocalHost().getHostName().hashCode());
        } catch (Exception ignored) {
            hostHash = "unknown";
        }
        return hostHash + "-" + Integer.toHexString(runtime.hashCode()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 一个运行任务的不可变上下文，leaseLost 使用原子标记跨调度线程通信。 */
    private record RunningJob(ForecastJobDO job, List<String> resources, AtomicBoolean leaseLost) {}
}
