package com.tencent.supersonic.forecast.core.mapping;

import com.tencent.supersonic.forecast.api.enums.ForecastCanonicalStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.api.enums.ForecastQuantityTransform;
import com.tencent.supersonic.forecast.api.enums.ForecastValueSource;
import com.tencent.supersonic.forecast.api.model.ForecastCanonicalEvent;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig;
import com.tencent.supersonic.forecast.api.model.ForecastMappingConfig.ValueMapping;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 将 Connector 投影的一行数据转换为不可变标准事件。
 *
 * <p>
 * 转换器无共享状态；所有类型和字典错误都包含字段名但不包含源记录值，避免日志泄漏业务数据。
 * </p>
 */
public class ForecastRowTransformer {

    /** 老 WMS 常见的斜杠日期时间格式；仅作为 ISO/JDBC 格式之后的兼容兜底。 */
    private static final DateTimeFormatter LEGACY_SLASH_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** 老 WMS 常见的斜杠纯日期格式；纯日期统一按源时区当天零点解释。 */
    private static final DateTimeFormatter LEGACY_SLASH_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 转换一行受控投影。
     *
     * @param row 使用 ForecastColumnAliases 的行数据。
     * @param config 已通过结构校验的映射。
     * @return 标准事件。
     * @throws IllegalArgumentException 字段为空、类型或枚举映射非法。
     */
    public ForecastCanonicalEvent transform(Map<String, Object> row, ForecastMappingConfig config) {
        ZoneId zoneId = ZoneId.of(config.getSourceTimeZone());
        Object recordRaw = value(row, config.getFields().getSourceRecordId(),
                ForecastColumnAliases.SOURCE_RECORD_ID);
        Object quantityRaw =
                value(row, config.getFields().getQuantity(), ForecastColumnAliases.QUANTITY);
        Object occurredRaw =
                value(row, config.getFields().getOccurredAt(), ForecastColumnAliases.OCCURRED_AT);
        Object warehouseRaw = value(row, config.getFields().getWarehouseCode(),
                ForecastColumnAliases.WAREHOUSE_CODE);
        Object directionRaw =
                value(row, config.getFields().getDirection(), ForecastColumnAliases.DIRECTION);
        Object updatedRaw = config.getFields().getSourceUpdatedAt() == null ? occurredRaw
                : value(row, config.getFields().getSourceUpdatedAt(),
                        ForecastColumnAliases.SOURCE_UPDATED_AT);

        String sourceStatus =
                string(value(row, config.getFields().getStatus(), ForecastColumnAliases.STATUS),
                        "status", false);
        String canonicalStatus = map(config.getFields().getStatus(), sourceStatus);
        return ForecastCanonicalEvent.builder()
                .sourceRecordId(string(recordRaw, "sourceRecordId", true))
                .taskId(string(
                        value(row, config.getFields().getTaskId(), ForecastColumnAliases.TASK_ID),
                        "taskId", false))
                .quantity(quantity(quantityRaw, config.getFields().getQuantity()))
                .occurredAt(instant(occurredRaw, zoneId, "occurredAt"))
                .sourceUpdatedAt(instant(updatedRaw, zoneId, "sourceUpdatedAt"))
                .warehouseCode(string(warehouseRaw, "warehouseCode", true))
                .direction(enumValue(ForecastDirection.class,
                        map(config.getFields().getDirection(), directionRaw), "direction"))
                .sourceStatus(sourceStatus)
                .canonicalStatus(canonicalStatus == null ? ForecastCanonicalStatus.COMPLETED
                        : enumValue(ForecastCanonicalStatus.class, canonicalStatus, "status"))
                .deleted(booleanValue(
                        value(row, config.getFields().getDeleted(), ForecastColumnAliases.DELETED)))
                .build();
    }

    /** 根据列/常量配置取得原始值。 */
    private Object value(Map<String, Object> row, ValueMapping mapping, String alias) {
        if (mapping == null) {
            return null;
        }
        return mapping.getSourceType() == ForecastValueSource.CONSTANT ? mapping.getConstant()
                : row.get(alias);
    }

    /** 应用精确字典映射，未命中时保留源值。 */
    private String map(ValueMapping mapping, Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        if (mapping == null || mapping.getValueMap() == null) {
            return value;
        }
        return mapping.getValueMap().getOrDefault(value, value);
    }

    /** 转换必填或可选字符串。 */
    private String string(Object raw, String field, boolean required) {
        String value = raw == null ? null : raw.toString().trim();
        if (required && (value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value == null || value.isBlank() ? null : value;
    }

    /** 转换并执行受控数量变换。 */
    private BigDecimal quantity(Object raw, ValueMapping mapping) {
        if (raw == null) {
            throw new IllegalArgumentException("quantity 不能为空");
        }
        try {
            BigDecimal value = raw instanceof BigDecimal decimal ? decimal
                    : new BigDecimal(raw.toString().trim());
            ForecastQuantityTransform transform = mapping.getTransform();
            if (transform == ForecastQuantityTransform.ABS) {
                value = value.abs();
            } else if (transform == ForecastQuantityTransform.NEGATE) {
                value = value.negate();
            }
            if (value.signum() < 0) {
                throw new IllegalArgumentException("quantity 归一化后不能为负数");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("quantity 不是有效数值", exception);
        }
    }

    /**
     * 将常见 JDBC 时间类型统一为 Instant。
     *
     * <p>
     * 部分老系统会把 DATE/DATETIME 定义成字符列，或者由 JDBC 驱动返回 {@link java.sql.Date}。 因此这里在 ISO 格式之外显式兼容 JDBC
     * escape 格式、纯日期和斜杠格式。所有失败消息只包含字段名， 不回显客户库中的原始值。
     * </p>
     *
     * @param raw JDBC 返回的原始时间值。
     * @param zoneId 客户源库时区，用于解释不带时区的日期时间。
     * @param field 标准字段名，仅用于脱敏错误提示。
     * @return 统一的 UTC 时间点。
     * @throws IllegalArgumentException 原值为空或不属于白名单时间格式。
     */
    private Instant instant(Object raw, ZoneId zoneId, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().atZone(zoneId).toInstant();
        }
        if (raw instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(zoneId).toInstant();
        }
        if (raw instanceof LocalDate localDate) {
            return localDate.atStartOfDay(zoneId).toInstant();
        }
        // java.sql.Date 覆盖了 toInstant() 并直接抛异常，必须先转成 LocalDate，不能落入 Date 分支。
        if (raw instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().atStartOfDay(zoneId).toInstant();
        }
        if (raw instanceof Date date) {
            return date.toInstant();
        }
        return parseTextualInstant(raw.toString().trim(), zoneId, field);
    }

    /**
     * 解析受控的字符时间格式。
     *
     * @param raw 已去除首尾空白的时间文本。
     * @param zoneId 客户源库时区。
     * @param field 标准字段名。
     * @return 统一的 UTC 时间点。
     * @throws IllegalArgumentException 文本为空或格式不在白名单内。
     */
    private Instant parseTextualInstant(String raw, ZoneId zoneId, String field) {
        if (raw.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }

        // 优先解析带时区的 ISO 文本，避免错误套用 Profile 时区。
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试不带时区的受控格式。
        }
        try {
            return LocalDateTime.parse(raw).atZone(zoneId).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试 JDBC escape 时间格式。
        }
        try {
            return Timestamp.valueOf(raw).toLocalDateTime().atZone(zoneId).toInstant();
        } catch (IllegalArgumentException ignored) {
            // 继续尝试纯日期和老系统斜杠格式。
        }
        try {
            return LocalDate.parse(raw).atStartOfDay(zoneId).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试斜杠日期时间。
        }
        try {
            return LocalDateTime.parse(raw, LEGACY_SLASH_DATE_TIME).atZone(zoneId).toInstant();
        } catch (DateTimeParseException ignored) {
            // 最后尝试斜杠纯日期。
        }
        try {
            return LocalDate.parse(raw, LEGACY_SLASH_DATE).atStartOfDay(zoneId).toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " 不是受支持的时间格式", exception);
        }
    }

    /** 解析常见布尔和软删除编码。 */
    private boolean booleanValue(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        String value = raw.toString().trim().toLowerCase(Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "y".equals(value) || "yes".equals(value)
                || "deleted".equals(value);
    }

    /** 解析标准枚举并给出不含源值的错误。 */
    private <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 映射不能为空");
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " 未映射到标准枚举", exception);
        }
    }
}
