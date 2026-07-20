package com.tencent.supersonic.forecast.core.mapping;

import com.tencent.supersonic.forecast.api.enums.ForecastRelationMode;
import com.tencent.supersonic.forecast.api.enums.ForecastSyncMode;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.CanonicalFields;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ColumnRef;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.RelationTable;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ValueMapping;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在任何 SQL 生成前执行映射白名单和能力校验。
 *
 * <p>
 * Validator 无共享状态，所有方法仅依赖入参，线程安全。
 * </p>
 */
public class ForecastMappingValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]{0,127}");

    /**
     * 校验映射结构并返回阻断错误与能力警告。
     *
     * @param config 待校验配置。
     * @return 校验报告。
     */
    public ValidationReport validate(ForecastMappingConfig config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (config == null) {
            errors.add("映射配置不能为空");
            return new ValidationReport(errors, warnings);
        }
        validateTimeZone(config, errors);
        Set<String> aliases = validateRelations(config, errors);
        validateFields(config.getFields(), aliases, config.getSyncMode(), errors, warnings);
        if (config.getSyncMode() == ForecastSyncMode.SNAPSHOT_LOOKBACK) {
            warnings.add("当前数据流使用最近窗口重扫，无法提供严格的全历史增量保证");
        }
        return new ValidationReport(List.copyOf(errors), List.copyOf(warnings));
    }

    /** 校验源时区。 */
    private void validateTimeZone(ForecastMappingConfig config, List<String> errors) {
        try {
            ZoneId.of(config.getSourceTimeZone());
        } catch (DateTimeException | NullPointerException exception) {
            errors.add("sourceTimeZone 不是有效时区");
        }
    }

    /** 校验单表或一次主从关系并返回允许的别名集合。 */
    private Set<String> validateRelations(ForecastMappingConfig config, List<String> errors) {
        Set<String> aliases = new HashSet<>();
        if (config.getSource() == null || config.getRelationMode() == null) {
            errors.add("关系来源和 relationMode 不能为空");
            return aliases;
        }
        if (config.getRelationMode() == ForecastRelationMode.SINGLE) {
            validateTable(config.getSource().getSingle(), "single", aliases, errors);
            if (config.getSource().getHeader() != null || config.getSource().getDetail() != null
                    || config.getSource().getJoin() != null) {
                errors.add("SINGLE 模式不能配置主从关联");
            }
            return aliases;
        }
        validateTable(config.getSource().getHeader(), "header", aliases, errors);
        validateTable(config.getSource().getDetail(), "detail", aliases, errors);
        if (config.getSource().getJoin() == null || config.getSource().getJoin().getLeft() == null
                || config.getSource().getJoin().getRight() == null) {
            errors.add("HEADER_DETAIL 模式必须配置一次等值关联");
            return aliases;
        }
        String joinType = config.getSource().getJoin().getType();
        if (!("INNER".equalsIgnoreCase(joinType) || "LEFT".equalsIgnoreCase(joinType))) {
            errors.add("join type 只允许 INNER 或 LEFT");
        }
        validateColumn(config.getSource().getJoin().getLeft(), "join.left", aliases, errors);
        validateColumn(config.getSource().getJoin().getRight(), "join.right", aliases, errors);
        return aliases;
    }

    /** 校验物理关系名称和唯一别名。 */
    private void validateTable(RelationTable table, String path, Set<String> aliases,
            List<String> errors) {
        if (table == null) {
            errors.add(path + " 关系不能为空");
            return;
        }
        validateIdentifier(table.getTable(), path + ".table", errors);
        validateOptionalIdentifier(table.getCatalog(), path + ".catalog", errors);
        validateOptionalIdentifier(table.getSchema(), path + ".schema", errors);
        validateIdentifier(table.getAlias(), path + ".alias", errors);
        if (table.getAlias() != null && !aliases.add(table.getAlias())) {
            errors.add("关系别名必须唯一: " + table.getAlias());
        }
    }

    /** 校验所有标准字段及来源类型。 */
    private void validateFields(CanonicalFields fields, Set<String> aliases, ForecastSyncMode mode,
            List<String> errors, List<String> warnings) {
        if (fields == null) {
            errors.add("标准字段集合不能为空");
            return;
        }
        validateRequired(fields.getSourceRecordId(), "sourceRecordId", aliases, errors);
        validateRequired(fields.getQuantity(), "quantity", aliases, errors);
        validateRequired(fields.getOccurredAt(), "occurredAt", aliases, errors);
        validateRequired(fields.getWarehouseCode(), "warehouseCode", aliases, errors);
        validateRequired(fields.getDirection(), "direction", aliases, errors);
        validateOptional(fields.getTaskId(), "taskId", aliases, errors);
        validateOptional(fields.getStatus(), "status", aliases, errors);
        validateOptional(fields.getDeleted(), "deleted", aliases, errors);
        validateOptional(fields.getSourceUpdatedAt(), "sourceUpdatedAt", aliases, errors);
        if (mode == ForecastSyncMode.INCREMENTAL && fields.getSourceUpdatedAt() == null) {
            errors.add("INCREMENTAL 模式必须配置 sourceUpdatedAt");
        }
        if (fields.getSourceRecordId() != null
                && fields.getSourceRecordId().getSourceType() != ForecastValueSource.COLUMN) {
            errors.add("sourceRecordId 必须来自稳定列");
        }
        if (fields.getOccurredAt() != null
                && fields.getOccurredAt().getSourceType() != ForecastValueSource.COLUMN) {
            errors.add("occurredAt 必须来自业务时间列");
        }
        if (fields.getSourceUpdatedAt() != null
                && fields.getSourceUpdatedAt().getSourceType() != ForecastValueSource.COLUMN) {
            errors.add("sourceUpdatedAt 必须来自更新时间列");
        }
        if (fields.getTaskId() == null) {
            warnings.add("未配置 taskId，将只生成数量预测");
        }
        if (fields.getStatus() == null) {
            warnings.add("未配置状态字段，所有未删除记录将按 COMPLETED 处理");
        }
        if (fields.getDeleted() == null) {
            warnings.add("未配置删除标记，增量同步无法识别源库硬删除");
        }
    }

    /** 校验必填字段映射。 */
    private void validateRequired(ValueMapping mapping, String path, Set<String> aliases,
            List<String> errors) {
        if (mapping == null) {
            errors.add(path + " 映射不能为空");
            return;
        }
        validateOptional(mapping, path, aliases, errors);
    }

    /** 校验列/常量二选一及可选的第二更新时间列。 */
    private void validateOptional(ValueMapping mapping, String path, Set<String> aliases,
            List<String> errors) {
        if (mapping == null) {
            return;
        }
        if (mapping.getSourceType() == ForecastValueSource.COLUMN) {
            validateColumn(mapping.getColumn(), path + ".column", aliases, errors);
            if (mapping.getSecondaryColumn() != null) {
                if (!"sourceUpdatedAt".equals(path)) {
                    errors.add(path + " 不允许配置 secondaryColumn");
                }
                validateColumn(mapping.getSecondaryColumn(), path + ".secondaryColumn", aliases,
                        errors);
            }
        } else if (mapping.getSourceType() == ForecastValueSource.CONSTANT) {
            if (mapping.getConstant() == null || mapping.getConstant().isBlank()) {
                errors.add(path + " 的常量不能为空");
            }
            if (mapping.getColumn() != null || mapping.getSecondaryColumn() != null) {
                errors.add(path + " 的常量映射不能同时引用列");
            }
        } else {
            errors.add(path + " 的 sourceType 不受支持");
        }
    }

    /** 校验列别名和列名均来自白名单。 */
    private void validateColumn(ColumnRef column, String path, Set<String> aliases,
            List<String> errors) {
        if (column == null) {
            errors.add(path + " 不能为空");
            return;
        }
        if (!aliases.contains(column.getTableAlias())) {
            errors.add(path + " 引用了未知关系别名: " + column.getTableAlias());
        }
        validateIdentifier(column.getColumn(), path + ".column", errors);
    }

    /** 校验必填数据库标识符。 */
    private void validateIdentifier(String value, String path, List<String> errors) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            errors.add(path + " 包含非法数据库标识符");
        }
    }

    /** 校验可空数据库标识符。 */
    private void validateOptionalIdentifier(String value, String path, List<String> errors) {
        if (value != null && !value.isBlank()) {
            validateIdentifier(value, path, errors);
        }
    }

    /**
     * 结构校验报告。
     *
     * @param errors 阻断错误。
     * @param warnings 非阻断警告。
     */
    public record ValidationReport(List<String> errors, List<String> warnings) {

        /**
         * 判断配置是否可继续执行数据库校验。
         *
         * @return 没有阻断错误时返回 true。
         */
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
