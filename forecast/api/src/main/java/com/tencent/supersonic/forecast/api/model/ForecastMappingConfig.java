package com.tencent.supersonic.forecast.api.model;

import com.tencent.supersonic.forecast.api.enums.ForecastQuantityTransform;
import com.tencent.supersonic.forecast.api.enums.ForecastRelationMode;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据流到标准出入库事件的白名单映射配置。
 *
 * <p>
 * 职责：描述单表或一次主从关联、标准字段来源和有限安全变换。该对象不包含任意 SQL，所有标识符 必须由 Connector 再次校验和引用，避免配置层绕过只读边界。
 * </p>
 */
@Data
public class ForecastMappingConfig {

    @NotNull
    private ForecastRelationMode relationMode = ForecastRelationMode.SINGLE;

    @Valid
    @NotNull
    private RelationSource source = new RelationSource();

    @Valid
    @NotNull
    private CanonicalFields fields = new CanonicalFields();

    @NotBlank
    private String sourceTimeZone = "UTC";

    @NotNull
    private ForecastSyncMode syncMode = ForecastSyncMode.INCREMENTAL;

    @Min(1)
    @Max(90)
    private int lookbackDays = 7;

    /**
     * 关系来源定义。
     */
    @Data
    public static class RelationSource {
        @Valid
        private RelationTable single;
        @Valid
        private RelationTable header;
        @Valid
        private RelationTable detail;
        @Valid
        private JoinDefinition join;
    }

    /**
     * 一个可发现的物理表或视图。
     */
    @Data
    public static class RelationTable {
        private String catalog;
        private String schema;
        @NotBlank
        private String table;
        @NotBlank
        private String alias;
    }

    /**
     * 主表与明细表唯一允许的等值关联。
     */
    @Data
    public static class JoinDefinition {
        @NotNull
        private ColumnRef left;
        @NotNull
        private ColumnRef right;
        private String type = "INNER";
    }

    /**
     * 受控列引用。
     */
    @Data
    public static class ColumnRef {
        @NotBlank
        private String tableAlias;
        @NotBlank
        private String column;
    }

    /**
     * 单个标准字段的列或常量映射。
     */
    @Data
    public static class ValueMapping {
        @NotNull
        private ForecastValueSource sourceType = ForecastValueSource.COLUMN;
        @Valid
        private ColumnRef column;
        @Valid
        private ColumnRef secondaryColumn;
        private String constant;
        private Map<String, String> valueMap = new LinkedHashMap<>();
        @NotNull
        private ForecastQuantityTransform transform = ForecastQuantityTransform.NONE;
    }

    /**
     * 标准事件字段集合。
     */
    @Data
    public static class CanonicalFields {
        @Valid
        @NotNull
        private ValueMapping sourceRecordId;
        @Valid
        private ValueMapping taskId;
        @Valid
        @NotNull
        private ValueMapping quantity;
        @Valid
        @NotNull
        private ValueMapping occurredAt;
        @Valid
        private ValueMapping sourceUpdatedAt;
        @Valid
        @NotNull
        private ValueMapping warehouseCode;
        @Valid
        @NotNull
        private ValueMapping direction;
        @Valid
        private ValueMapping status;
        @Valid
        private ValueMapping deleted;
    }
}
