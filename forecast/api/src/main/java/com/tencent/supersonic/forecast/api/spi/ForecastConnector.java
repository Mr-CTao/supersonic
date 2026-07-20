package com.tencent.supersonic.forecast.api.spi;

import com.tencent.supersonic.forecast.api.model.ForecastCursor;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingValidation;
import com.tencent.supersonic.forecast.api.model.ForecastMetadata;
import com.tencent.supersonic.forecast.api.model.ForecastPage;
import com.tencent.supersonic.forecast.api.model.ForecastReadContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * 客户源数据库接入扩展点。
 *
 * <p>
 * 实现类只负责数据库方言、元数据和有界读取，不保存密码、不持久化水位，也不处理任务并发。
 * </p>
 */
public interface ForecastConnector {

    /**
     * 判断 Connector 是否支持指定 SuperSonic 数据源类型。
     *
     * @param databaseType 例如 mysql、postgresql、sqlserver。
     * @return 支持时返回 true。
     */
    boolean supports(String databaseType);

    /**
     * 发现可用于映射的库表字段。
     *
     * @param connection 已由调用方设置为只读的连接。
     * @param catalog 可选 catalog。
     * @param schema 可选 schema/database。
     * @param tablePattern 可选表名模式。
     * @return 元数据快照。
     * @throws SQLException 元数据访问失败。
     */
    ForecastMetadata discover(Connection connection, String catalog, String schema,
            String tablePattern) throws SQLException;

    /**
     * 校验映射并执行最多 sampleLimit 行的受控预览。
     *
     * @param connection 只读连接。
     * @param config 映射配置。
     * @param sampleLimit 最大样例数，服务端必须限制在 1 到 100。
     * @return 校验结果。
     * @throws SQLException 查询或类型读取失败。
     */
    ForecastMappingValidation validate(Connection connection, ForecastMappingConfig config,
            int sampleLimit) throws SQLException;

    /**
     * 捕获任务开始时固定的复合上界，防止长时间扫描期间新记录导致漂移。
     *
     * @param connection 只读连接。
     * @param config 映射配置。
     * @param historyStart 业务时间起点。
     * @param historyEnd 业务时间终点。
     * @return 固定上界；无数据时返回空水位。
     * @throws SQLException 查询失败。
     */
    ForecastCursor captureUpperBound(Connection connection, ForecastMappingConfig config,
            Instant historyStart, Instant historyEnd) throws SQLException;

    /**
     * 使用 keyset 复合水位读取一页标准事件。
     *
     * @param connection 只读连接。
     * @param config 已验证映射。
     * @param context 有界读取上下文。
     * @return 当前页标准事件和下一水位。
     * @throws SQLException 查询或类型转换失败。
     */
    ForecastPage readPage(Connection connection, ForecastMappingConfig config,
            ForecastReadContext context) throws SQLException;
}
