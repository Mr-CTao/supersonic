package com.tencent.supersonic.forecast.server.worker;

import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.database.ForecastDatabaseRegistry;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository;
import com.tencent.supersonic.forecast.server.service.ForecastControlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker 启动后的决策库 Schema 版本门禁。
 *
 * <p>
 * 生产默认只读校验，不在应用启动时执行 DDL。多个 Profile 复用同一决策库时只校验一次。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "forecast.worker", name = "enabled", havingValue = "true")
public class ForecastDecisionSchemaValidator {

    private final ForecastControlStore controlStore;
    private final ForecastDatabaseRegistry databaseRegistry;
    private final ForecastDecisionRepository decisionRepository;
    private final ForecastProperties properties;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * 创建 Schema 门禁。
     *
     * @param controlStore 控制面服务。
     * @param databaseRegistry 决策库连接注册表。
     * @param decisionRepository 决策库访问层。
     * @param properties Forecast 配置。
     */
    public ForecastDecisionSchemaValidator(ForecastControlStore controlStore,
            ForecastDatabaseRegistry databaseRegistry,
            ForecastDecisionRepository decisionRepository, ForecastProperties properties) {
        this.controlStore = controlStore;
        this.databaseRegistry = databaseRegistry;
        this.decisionRepository = decisionRepository;
        this.properties = properties;
    }

    /**
     * 校验全部启用 Profile 的决策库版本。
     *
     * @throws IllegalStateException 任一决策库未迁移时阻止 Worker 接单。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!properties.getDecision().isValidateSchemaOnStartup()) {
            ready.set(true);
            return;
        }
        Set<Long> databaseIds = new LinkedHashSet<>();
        controlStore.enabledProfiles().stream().map(ForecastProfileDO::getDecisionDatabaseId)
                .forEach(databaseIds::add);
        for (Long databaseId : databaseIds) {
            try (Connection connection = databaseRegistry.openDecisionReadOnly(databaseId)) {
                decisionRepository.validateSchema(connection);
                connection.rollback();
            } catch (SQLException | RuntimeException exception) {
                throw new IllegalStateException("Forecast 决策库 Schema 校验失败，Worker 已停止接单", exception);
            }
        }
        // ApplicationReady 事件和 @Scheduled 可能由不同线程推进，原子标记提供可见性保证。
        ready.set(true);
    }

    /**
     * 判断启动 Schema 门禁是否已通过。
     *
     * @return 只有校验成功或显式关闭校验时返回 true。
     */
    public boolean isReady() {
        return ready.get();
    }
}
