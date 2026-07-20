package com.tencent.supersonic.forecast.core.mapping;

import com.tencent.supersonic.forecast.api.enums.ForecastQuantityTransform;
import com.tencent.supersonic.forecast.api.enums.ForecastRelationMode;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ColumnRef;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ValueMapping;

/**
 * Forecast 映射测试夹具，集中维护一份最小合法的单表增量配置。
 */
final class ForecastMappingTestFixture {

    private ForecastMappingTestFixture() {}

    /** 创建包含复合水位所需字段的合法配置。 */
    static ForecastMappingConfig validIncrementalConfig() {
        ForecastMappingConfig config = new ForecastMappingConfig();
        config.setRelationMode(ForecastRelationMode.SINGLE);
        config.setSourceTimeZone("Asia/Shanghai");
        config.setSyncMode(ForecastSyncMode.INCREMENTAL);

        RelationTable table = new RelationTable();
        table.setSchema("wms");
        table.setTable("inventory_task");
        table.setAlias("s");
        config.getSource().setSingle(table);

        config.getFields().setSourceRecordId(column("id"));
        config.getFields().setQuantity(column("quantity"));
        config.getFields().setOccurredAt(column("occurred_at"));
        config.getFields().setSourceUpdatedAt(column("updated_at"));
        config.getFields().setWarehouseCode(column("warehouse_code"));
        config.getFields().setDirection(column("direction"));
        return config;
    }

    /** 创建固定关系别名下的受控列映射。 */
    static ValueMapping column(String columnName) {
        ColumnRef column = new ColumnRef();
        column.setTableAlias("s");
        column.setColumn(columnName);
        ValueMapping mapping = new ValueMapping();
        mapping.setSourceType(ForecastValueSource.COLUMN);
        mapping.setColumn(column);
        mapping.setTransform(ForecastQuantityTransform.NONE);
        return mapping;
    }
}
