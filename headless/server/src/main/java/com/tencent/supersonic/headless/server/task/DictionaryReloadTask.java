package com.tencent.supersonic.headless.server.task;

import com.tencent.supersonic.headless.server.service.impl.DictWordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 语义字典启动与定时刷新任务。
 *
 * <p>
 * 职责说明：启动时加载词典，定时任务以容错模式刷新；阶段 5 发布编排使用严格刷新方法感知 失败并记录审计。并发说明：底层 {@link DictWordService}
 * 负责词典快照切换，本类不缓存共享状态。
 * </p>
 */
@Slf4j
@Component
@Order(2)
public class DictionaryReloadTask implements CommandLineRunner {

    @Autowired
    private DictWordService dictWordService;

    /**
     * 应用启动时初始化语义字典。
     *
     * @param args 启动参数，本任务不读取该参数。
     */
    @Override
    public void run(String... args) {
        updateKnowledgeDimValue();
    }

    /** 启动阶段以容错方式加载字典；失败记录完整堆栈但不阻断应用启动。 */
    public void updateKnowledgeDimValue() {
        try {
            log.debug("ApplicationStartedInit start");
            dictWordService.loadDictWord();
            log.debug("ApplicationStartedInit end");
        } catch (Exception e) {
            log.error("ApplicationStartedInit error", e);
        }
    }

    /** 定时容错刷新词典；失败只记录日志，避免调度线程退出。 */
    @Scheduled(cron = "${reload.knowledge.corn:0 0/1 * * * ?}")
    public void reloadKnowledge() {
        log.debug("reloadKnowledge start");
        try {
            reloadKnowledgeOrThrow();
        } catch (Exception e) {
            log.error("reloadKnowledge error", e);
        }
        log.debug("reloadKnowledge end");
    }

    /**
     * 严格刷新语义词典并向调用方传播失败。
     *
     * <p>
     * 调用示例：{@code dictionaryReloadTask.reloadKnowledgeOrThrow()}。发布编排必须使用本方法，
     * 不能使用吞错的定时入口，否则无法区分对象创建成功与知识未生效。
     * </p>
     *
     * @throws RuntimeException 底层词典读取或快照替换失败时原样传播。
     */
    public void reloadKnowledgeOrThrow() {
        dictWordService.reloadDictWord();
    }
}
