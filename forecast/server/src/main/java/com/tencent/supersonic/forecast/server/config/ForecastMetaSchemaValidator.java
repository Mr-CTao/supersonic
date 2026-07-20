package com.tencent.supersonic.forecast.server.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Forecast 元数据库 Schema 的只读启动门禁。
 *
 * <p>
 * 迁移必须由受控发布流程显式执行。本组件只读取专用版本表，不创建或修改任何对象，避免 Standalone 与 Worker 并发启动时争抢 DDL 锁。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ForecastMetaSchemaValidator {

    private static final String COMPONENT = "forecast_meta";
    private static final int EXPECTED_VERSION = 2;
    private static final String VERSION_SQL =
            "SELECT version FROM s2_forecast_schema_version WHERE component=?";

    private final DataSource dataSource;
    private final ForecastProperties properties;

    /**
     * 创建元数据库版本门禁。
     *
     * @param dataSource SuperSonic 元数据库连接池。
     * @param properties Forecast 集中配置。
     */
    public ForecastMetaSchemaValidator(DataSource dataSource, ForecastProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /**
     * 在应用完成初始化后校验 Forecast 元数据库版本。
     *
     * @throws IllegalStateException 版本表缺失、版本不匹配或元数据库不可访问时阻止应用就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!properties.getMeta().isValidateSchemaOnStartup()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(VERSION_SQL)) {
            statement.setString(1, COMPONENT);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != EXPECTED_VERSION) {
                    throw new IllegalStateException("Forecast 元数据库 Schema 版本不匹配，" + "请先执行对应正向迁移脚本");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Forecast 元数据库 Schema 校验失败，" + "请先执行对应正向迁移脚本",
                    exception);
        }
    }
}
