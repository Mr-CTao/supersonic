package com.tencent.supersonic.forecast.core.mapping;

/**
 * Connector 与标准行转换器之间固定的投影别名。
 */
public final class ForecastColumnAliases {
    public static final String SOURCE_RECORD_ID = "s2_source_record_id";
    public static final String TASK_ID = "s2_task_id";
    public static final String QUANTITY = "s2_quantity";
    public static final String OCCURRED_AT = "s2_occurred_at";
    public static final String SOURCE_UPDATED_AT = "s2_source_updated_at";
    public static final String WAREHOUSE_CODE = "s2_warehouse_code";
    public static final String DIRECTION = "s2_direction";
    public static final String STATUS = "s2_status";
    public static final String DELETED = "s2_deleted";

    private ForecastColumnAliases() {}
}
