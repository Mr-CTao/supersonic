package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AI 语义建模草稿启动恢复器。
 *
 * <p>
 * 职责说明：应用启动完成后将超时的 GENERATING 草稿原子转为 GENERATION_FAILED，避免进程重启留下 永久轮询状态。并发说明：数据库条件更新允许多个实例同时执行，只有仍处于
 * GENERATING 的行会被修改。
 * </p>
 */
@Slf4j
@Component
public class ModelingDraftRecoveryRunner {

    private final ModelingDraftService modelingDraftService;

    /**
     * 创建启动恢复器。
     *
     * @param modelingDraftService 草稿应用服务。
     */
    public ModelingDraftRecoveryRunner(ModelingDraftService modelingDraftService) {
        this.modelingDraftService = modelingDraftService;
    }

    /**
     * 应用就绪后恢复超时草稿。
     *
     * @param event Spring 应用就绪事件。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recover(ApplicationReadyEvent event) {
        try {
            int recovered = modelingDraftService.recoverStaleGenerations();
            if (recovered > 0) {
                log.warn("recovered stale semantic modeling drafts: count={}", recovered);
            }
        } catch (RuntimeException exception) {
            // 启动恢复失败不阻断主服务；列表/详情仍会再次触发懒恢复。
            log.error("failed to recover stale semantic modeling drafts", exception);
        }
    }
}
