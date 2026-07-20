package com.tencent.supersonic.forecast.server.worker;

import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.service.ForecastControlStore;
import com.tencent.supersonic.forecast.server.service.ForecastJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 按每个 Profile 时区和 Cron 生成幂等系统任务。
 *
 * <p>
 * 协调器每分钟只扫描小型 Profile 控制表，不访问源库。即使多个 Worker 同时命中同一调度槽， 元数据库 {@code createdBy + Idempotency-Key}
 * 唯一约束也只会生成一个任务。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "forecast.worker", name = "enabled", havingValue = "true")
public class ForecastScheduleCoordinator {

    private final ForecastControlStore controlStore;
    private final ForecastJobService jobService;
    private final ForecastDecisionSchemaValidator schemaValidator;

    /**
     * 创建调度协调器。
     *
     * @param controlStore 控制面服务。
     * @param jobService 任务服务。
     * @param schemaValidator 决策库启动门禁。
     */
    public ForecastScheduleCoordinator(ForecastControlStore controlStore,
            ForecastJobService jobService, ForecastDecisionSchemaValidator schemaValidator) {
        this.controlStore = controlStore;
        this.jobService = jobService;
        this.schemaValidator = schemaValidator;
    }

    /** 每分钟评估 Profile 的同步、预测和周对账 Cron。 */
    @Scheduled(cron = "0 * * * * *")
    public void schedule() {
        if (!schemaValidator.isReady()) {
            return;
        }
        for (ForecastProfileDO profile : controlStore.enabledProfiles()) {
            try {
                ZonedDateTime now = ZonedDateTime.now(ZoneId.of(profile.getTimeZone()))
                        .truncatedTo(ChronoUnit.MINUTES);
                enqueueIfDue(profile, now, profile.getSyncCron(), ForecastJobType.INCREMENTAL_SYNC,
                        "incremental");
                enqueueIfDue(profile, now, profile.getForecastCron(), ForecastJobType.FORECAST,
                        "forecast");
                enqueueIfDue(profile, now, profile.getReconcileCron(), ForecastJobType.RECONCILE,
                        "reconcile");
            } catch (RuntimeException exception) {
                log.warn("Forecast Profile 调度配置无效 profileId={} type={}", profile.getId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    /** 判断当前分钟是否为 Cron 槽位并幂等入队。 */
    private void enqueueIfDue(ForecastProfileDO profile, ZonedDateTime now, String cron,
            ForecastJobType type, String slot) {
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime next = expression.next(now.minusMinutes(1));
        if (next == null || next.isAfter(now)) {
            return;
        }
        ForecastJobReq request = new ForecastJobReq();
        request.setProfileId(profile.getId());
        request.setType(type);
        request.setHistoryDays(profile.getHistoryDays());
        jobService.createScheduled(request, now.toLocalDate(), slot);
    }
}
