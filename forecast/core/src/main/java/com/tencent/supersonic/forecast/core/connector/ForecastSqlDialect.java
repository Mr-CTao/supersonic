package com.tencent.supersonic.forecast.core.connector;

import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;

/**
 * 预测 Connector 需要的最小 SQL 方言集合。
 *
 * <p>
 * 方言只处理服务端白名单标识符和固定表达式，不接受用户 SQL。
 * </p>
 */
public interface ForecastSqlDialect {

    /**
     * 返回支持的数据源类型。
     *
     * @return SuperSonic 数据源类型。
     */
    String databaseType();

    /**
     * 安全引用已经通过白名单校验的标识符。
     *
     * @param identifier 标识符。
     * @return 方言引用后的标识符。
     */
    String quote(String identifier);

    /**
     * 构造物理表或视图的限定名称。
     *
     * @param table 关系定义。
     * @return 限定名称。
     */
    String qualify(RelationTable table);

    /**
     * 将稳定记录键转换为可比较文本。
     *
     * @param expression 受控列表达式。
     * @return 文本转换表达式。
     */
    String castText(String expression);

    /**
     * 返回两个可空更新时间中的较晚值。
     *
     * @param left 左时间表达式。
     * @param right 右时间表达式。
     * @return 可空安全的最大时间表达式。
     */
    String greatestTimestamp(String left, String right);
}
