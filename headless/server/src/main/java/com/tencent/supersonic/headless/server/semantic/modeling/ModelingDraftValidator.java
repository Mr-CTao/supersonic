package com.tencent.supersonic.headless.server.semantic.modeling;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.schema.ValidateResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DerivedFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DimensionDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricFilterDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModificationDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.SensitiveFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermTargetDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.UncertaintyDraft;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * AI 语义建模草稿结构与语义校验器。
 *
 * <p>
 * 职责说明：先使用 fastjson2 JSON Schema 1.0 校验结构，再校验选表、字段、受限指标表达式、对象 key
 * 和术语引用。可安全降级的跨表对象、别名冲突和泛化术语会转换为不确定项；未知表、未知字段和任意 SQL 表达式属于阻塞错误。并发说明：JSON Schema 与 ObjectMapper
 * 均在构造后只读，可安全被多个 Worker 复用。
 * </p>
 */
@Component
public class ModelingDraftValidator {

    private static final String SCHEMA_RESOURCE = "schema/semantic-modeling-draft-v1.json";
    private static final String ROUTED_SCHEMA_RESOURCE =
            "schema/semantic-modeling-draft-v2.json";
    private static final String OBJECT_TYPE_DIMENSION = "DIMENSION";
    private static final String OBJECT_TYPE_METRIC = "METRIC";
    private static final String ISSUE_TERM_TARGET_TYPE_MISMATCH = "TERM_TARGET_TYPE_MISMATCH";
    private static final Pattern DERIVED_FIELD_NAME_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,127}$");
    private static final Pattern DERIVATION_PHYSICAL_FIELD_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    private static final Set<String> ALLOWED_MODIFICATION_FIELDS =
            Set.of("description", "aliases");
    private static final Set<String> GENERIC_TERMS =
            Set.of("数据", "信息", "业务", "指标", "维度", "data", "info", "business", "metric", "dimension");

    private final ObjectMapper objectMapper;
    private final JSONSchema jsonSchema;
    private final JsonNode schemaNode;
    private final JSONSchema routedJsonSchema;
    private final JsonNode routedSchemaNode;

    /**
     * 加载并编译固定 JSON Schema。
     *
     * @param objectMapper 项目 Jackson 实例。
     * @throws IllegalStateException 当内置 Schema 缺失或不可解析时抛出，阻止服务带错误契约启动。
     */
    public ModelingDraftValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SchemaBundle legacy = loadSchemaBundle(SCHEMA_RESOURCE);
        SchemaBundle routed = loadSchemaBundle(ROUTED_SCHEMA_RESOURCE);
        this.schemaNode = legacy.node();
        this.jsonSchema = legacy.schema();
        this.routedSchemaNode = routed.node();
        this.routedJsonSchema = routed.schema();
    }

    /** 加载单个只读 Schema 资源；构造阶段失败会阻止错误契约进入运行态。 */
    private SchemaBundle loadSchemaBundle(String resource) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing semantic modeling draft schema: " + resource);
            }
            String schemaText = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return new SchemaBundle(objectMapper.readTree(schemaText),
                    JSONSchema.parseSchema(schemaText));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to load semantic modeling draft schema: "
                    + resource, exception);
        }
    }

    /**
     * 校验并规范化完整草稿。
     *
     * <p>
     * 调用示例：{@code validator.validateAndNormalize(json, columns, existingNames)}。方法会为合法指标补齐
     * 规范表达式，并把选中其他表的对象移动到 uncertainties；不会写入任何数据库。
     * </p>
     *
     * @param json 待校验 JSON。
     * @param columnsByTable 服务端读取的选表字段。
     * @param existingNames 当前用户具有 ADMIN 权限的既有对象名和别名，小写形式亦可。
     * @return 类型化并已规范化的草稿及 JSON。
     * @throws ModelingDraftException 当结构或语义校验失败时抛出 422 异常。
     */
    public ValidatedDraft validateAndNormalize(String json,
            Map<String, List<DBColumn>> columnsByTable, Set<String> existingNames) {
        if (StringUtils.isBlank(json)) {
            throw invalid(List.of(issue("$", "EMPTY_DRAFT", "结构化草稿不能为空")));
        }

        validateJsonSchema(json);
        ModelingDraftPayload payload = deserialize(json);
        deduplicateUncertainties(payload);
        ModelingDraftPayload validationPayload = payload.validationView();
        List<ModelingValidationIssue> errors = new ArrayList<>();
        Map<String, TableMetadata> metadata = buildMetadata(columnsByTable);
        Set<String> keys = new HashSet<>();
        Map<String, String> objectTypes = new HashMap<>();
        Map<String, CrossTableObjectReference> crossTableObjects = new HashMap<>();
        Set<String> seenNames = normalizeNames(existingNames);
        AtomicInteger uncertaintySequence =
                new AtomicInteger(maxUncertaintySequence(payload.getUncertainties()));

        boolean extendingExisting = ModelingDraftConstants.ACTION_EXTEND_EXISTING
                .equals(payload.getAction());
        for (int modelIndex = 0; modelIndex < validationPayload.getModels().size(); modelIndex++) {
            ModelDraft model = validationPayload.getModels().get(modelIndex);
            String modelPath = extendingExisting
                    ? "$.additions"
                    : "$.models[" + modelIndex + "]";
            registerKey(model.getKey(), modelPath + ".key", keys, errors);
            if (!extendingExisting) {
                // 增量校验视图中的模型名来自已确认目标资产，只是只读定位信息；把它再次与
                // 现有资产名称集合比较会制造虚假的 ALIAS_CONFLICT，并导致每次保存重复告警。
                registerNames(payload, uncertaintySequence, model.getKey(), model.getKey(), null,
                        model.getName(), model.getBizName(), null, seenNames);
            }

            TableMetadata table = metadata.get(normalize(model.getBaseTable()));
            if (table == null) {
                errors.add(issue(modelPath + ".baseTable", "UNKNOWN_TABLE",
                        "baseTable 不在服务端确认的选表范围内"));
                continue;
            }
            // 使用服务端元数据中的真实大小写，避免后续跨数据库比较不一致。
            model.setBaseTable(table.tableName());
            validatePrimaryTimeField(payload, model, table, metadata, modelPath,
                    uncertaintySequence, errors);
            validateDimensions(payload, model, table, metadata, modelPath, keys, objectTypes,
                    crossTableObjects, seenNames, uncertaintySequence, errors);
            validateMetrics(payload, model, table, metadata, modelPath, keys, objectTypes,
                    crossTableObjects, seenNames, uncertaintySequence, errors);
            validateSensitiveFields(payload, model, table, metadata, modelPath, uncertaintySequence,
                    errors);
        }

        validateRoutedSections(payload, seenNames, uncertaintySequence, errors);

        validateTerms(validationPayload, keys, objectTypes, crossTableObjects, seenNames,
                uncertaintySequence, errors);
        validateUncertaintyKeys(payload, keys, errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }

        try {
            String normalizedJson = objectMapper.writeValueAsString(payload);
            // 规范化逻辑可能追加不确定项，因此再次确认输出仍符合固定 Schema。
            validateJsonSchema(normalizedJson);
            return new ValidatedDraft(payload, normalizedJson);
        } catch (JsonProcessingException exception) {
            throw invalid(List.of(issue("$", "SERIALIZE_FAILED", "结构化草稿无法序列化")));
        }
    }

    /**
     * 返回结构化草稿 JSON Schema 的请求级副本。
     *
     * <p>
     * 阶段 4 AI 修订继续复用阶段 3 的同一输出契约，避免修订链路维护第二份 Schema。返回深拷贝是为了 防止调用方意外修改单例校验器持有的只读节点。
     * </p>
     *
     * @return 可安全放入 LLM Gateway 请求的 JSON Schema 副本。
     */
    public JsonNode getJsonSchema() {
        return schemaNode.deepCopy();
    }

    /**
     * 按草稿版本返回 LLM Gateway 使用的只读 JSON Schema 副本。
     *
     * @param schemaVersion 草稿 Schema 版本；2.0 返回路由感知契约，其余按历史 1.0 兼容。
     * @return 不会暴露单例可变状态的 Schema 深拷贝。
     */
    public JsonNode getJsonSchema(String schemaVersion) {
        return ModelingDraftConstants.SCHEMA_VERSION_ROUTED.equals(schemaVersion)
                ? routedSchemaNode.deepCopy()
                : schemaNode.deepCopy();
    }

    /**
     * 先聚合结构问题，再使用 fastjson2 完成固定 JSON Schema 的最终判定。
     *
     * <p>
     * fastjson2 通常只返回第一处 required/additionalProperties 失败；预检递归收集 object、array、 required、properties
     * 和 additionalProperties，使修复轮一次看到完整结构偏差。数量硬限制为 50， 避免不可信输出制造无界错误列表。
     * </p>
     */
    private void validateJsonSchema(String json) {
        try {
            JsonNode candidate = objectMapper.readTree(json);
            boolean routed = ModelingDraftConstants.SCHEMA_VERSION_ROUTED
                    .equals(candidate.path("schemaVersion").asText());
            JsonNode selectedSchemaNode = routed ? routedSchemaNode : schemaNode;
            JSONSchema selectedJsonSchema = routed ? routedJsonSchema : jsonSchema;
            List<ModelingValidationIssue> structuralIssues = new ArrayList<>();
            collectStructuralIssues(candidate, selectedSchemaNode, selectedSchemaNode, "$",
                    structuralIssues);
            if (!structuralIssues.isEmpty()) {
                throw invalid(structuralIssues);
            }
            Object value = JSON.parse(json);
            ValidateResult result = selectedJsonSchema.validate(value);
            if (!result.isSuccess()) {
                // fastjson2 的原始消息可能拼入字段值；前端和 attempt 历史只保存固定脱敏文案。
                throw invalid(
                        List.of(issue("$", "JSON_SCHEMA_INVALID", "结构化草稿不符合 JSON Schema 约束")));
            }
        } catch (ModelingDraftException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw invalid(List.of(issue("$", "JSON_PARSE_FAILED", "模型输出不是合法 JSON")));
        }
    }

    /** 递归收集结构问题；达到上限后立即停止继续遍历。 */
    private void collectStructuralIssues(JsonNode value, JsonNode rawSchema, JsonNode schemaRoot,
            String path, List<ModelingValidationIssue> issues) {
        if (issues.size() >= ModelingDraftConstants.MAX_VALIDATION_ISSUES) {
            return;
        }
        JsonNode schema = resolveSchema(rawSchema, schemaRoot);
        if (!matchesDeclaredType(value, schema.get("type"))) {
            issues.add(issue(path, "JSON_TYPE_MISMATCH", "字段类型不符合 JSON Schema"));
            return;
        }
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isObject()) {
            collectObjectIssues(value, schema, schemaRoot, path, issues);
        } else if (value.isArray() && schema.has("items")) {
            for (int index = 0; index < value.size()
                    && issues.size() < ModelingDraftConstants.MAX_VALIDATION_ISSUES; index++) {
                collectStructuralIssues(value.get(index), schema.get("items"), schemaRoot,
                        path + "[" + index + "]", issues);
            }
        }
    }

    /** 收集对象缺失字段、未知字段以及已声明子字段的递归问题。 */
    private void collectObjectIssues(JsonNode value, JsonNode schema, JsonNode schemaRoot,
            String path, List<ModelingValidationIssue> issues) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode requiredName : required) {
                if (issues.size() >= ModelingDraftConstants.MAX_VALIDATION_ISSUES) {
                    return;
                }
                String field = requiredName.asText();
                if (!value.has(field)) {
                    issues.add(issue(childPath(path, field), "JSON_REQUIRED_MISSING",
                            "缺少 JSON Schema 必填字段"));
                }
            }
        }

        JsonNode properties = schema.get("properties");
        boolean rejectAdditional =
                schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()
                        && !schema.get("additionalProperties").asBoolean();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext() && issues.size() < ModelingDraftConstants.MAX_VALIDATION_ISSUES) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode fieldSchema = properties == null ? null : properties.get(field.getKey());
            if (fieldSchema == null) {
                if (rejectAdditional) {
                    issues.add(issue(childPath(path, field.getKey()), "JSON_UNKNOWN_PROPERTY",
                            "JSON Schema 不允许该字段"));
                }
                continue;
            }
            collectStructuralIssues(field.getValue(), fieldSchema, schemaRoot,
                    childPath(path, field.getKey()), issues);
        }
    }

    /** 解析当前固定 Schema 内的本地 {@code #/...} 引用。 */
    private JsonNode resolveSchema(JsonNode schema, JsonNode schemaRoot) {
        if (schema != null && schema.has("$ref")) {
            String reference = schema.get("$ref").asText();
            if (reference.startsWith("#/")) {
                JsonNode resolved = schemaRoot.at(reference.substring(1));
                if (!resolved.isMissingNode()) {
                    return resolved;
                }
            }
        }
        return schema;
    }

    /** 判断 Jackson 节点是否匹配字符串或联合数组形式的 Schema type。 */
    private boolean matchesDeclaredType(JsonNode value, JsonNode typeNode) {
        if (typeNode == null || typeNode.isMissingNode()) {
            return true;
        }
        if (typeNode.isArray()) {
            for (JsonNode allowed : typeNode) {
                if (matchesType(value, allowed.asText())) {
                    return true;
                }
            }
            return false;
        }
        return matchesType(value, typeNode.asText());
    }

    /** 匹配单个 JSON Schema 基础类型。 */
    private boolean matchesType(JsonNode value, String type) {
        if (value == null) {
            return "null".equals(type);
        }
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    /** 构造不依赖 JSONPath 转义实现的可读字段路径。 */
    private String childPath(String parent, String field) {
        return parent + "." + field;
    }

    /** 将 JSON 绑定为类型化草稿。 */
    private ModelingDraftPayload deserialize(String json) {
        try {
            return objectMapper.readValue(json, ModelingDraftPayload.class);
        } catch (JsonProcessingException exception) {
            throw invalid(List.of(issue("$", "JSON_BIND_FAILED", "模型输出字段类型不正确")));
        }
    }

    /** 构造不区分大小写的表和字段索引。 */
    private Map<String, TableMetadata> buildMetadata(Map<String, List<DBColumn>> columnsByTable) {
        Map<String, TableMetadata> metadata = new LinkedHashMap<>();
        if (columnsByTable == null) {
            return metadata;
        }
        columnsByTable.forEach((table, columns) -> {
            Map<String, ColumnMetadata> fields = new HashMap<>();
            if (columns != null) {
                for (DBColumn column : columns) {
                    if (column != null && StringUtils.isNotBlank(column.getColumnName())) {
                        fields.put(normalize(column.getColumnName()),
                                new ColumnMetadata(column.getColumnName(), column.getDataType()));
                    }
                }
            }
            metadata.put(normalize(table), new TableMetadata(table, fields));
        });
        return metadata;
    }

    /** 校验模型主时间字段。 */
    private void validatePrimaryTimeField(ModelingDraftPayload payload, ModelDraft model,
            TableMetadata table, Map<String, TableMetadata> allTables, String modelPath,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        if (StringUtils.isBlank(model.getPrimaryTimeField())) {
            return;
        }
        FieldResolution resolution = resolveField(model.getPrimaryTimeField(), table, allTables);
        if (resolution.type() == FieldResolutionType.CROSS_TABLE) {
            addCrossTableUncertainty(payload, uncertaintySequence, model.getKey(), null,
                    model.getPrimaryTimeField());
            model.setPrimaryTimeField(null);
        } else if (resolution.type() != FieldResolutionType.LOCAL) {
            errors.add(
                    issue(modelPath + ".primaryTimeField", "UNKNOWN_FIELD", "主时间字段不存在于 baseTable"));
        } else {
            model.setPrimaryTimeField(resolution.field());
        }
    }

    /** 校验维度并将明确的跨表对象移入不确定项。 */
    private void validateDimensions(ModelingDraftPayload payload, ModelDraft model,
            TableMetadata table, Map<String, TableMetadata> allTables, String modelPath,
            Set<String> keys, Map<String, String> objectTypes,
            Map<String, CrossTableObjectReference> crossTableObjects, Set<String> seenNames,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        Iterator<DimensionDraft> iterator = model.getDimensions().iterator();
        int index = 0;
        while (iterator.hasNext()) {
            DimensionDraft dimension = iterator.next();
            String path = modelPath + ".dimensions[" + index + "]";
            if (dimension.getDerivation() != null) {
                validateDerivedDimension(dimension, table, allTables, path, errors);
            } else {
                FieldResolution resolution = resolveField(dimension.getField(), table, allTables);
                if (resolution.type() == FieldResolutionType.CROSS_TABLE) {
                    addCrossTableUncertainty(payload, uncertaintySequence, model.getKey(),
                            dimension.getKey(), dimension.getField());
                    // 对象虽从模型中移除，仍需保留其 key 上下文，供后续术语校验识别“已降级”而非“真正未知”。
                    rememberCrossTableObject(crossTableObjects, model.getKey(), dimension.getKey(),
                            dimension.getField());
                    iterator.remove();
                    index++;
                    continue;
                }
                if (resolution.type() != FieldResolutionType.LOCAL) {
                    errors.add(issue(path + ".field", "UNKNOWN_FIELD", "维度字段不存在于 baseTable"));
                } else {
                    dimension.setField(resolution.field());
                }
            }
            registerKey(dimension.getKey(), path + ".key", keys, errors);
            // 术语引用不仅要命中对象 key，还必须与目标对象的实际类型一致。
            objectTypes.putIfAbsent(normalize(dimension.getKey()), OBJECT_TYPE_DIMENSION);
            registerNames(payload, uncertaintySequence, model.getKey(), dimension.getKey(),
                    dimension.getField(), dimension.getName(), dimension.getBizName(),
                    dimension.getAliases(), seenNames);
            if (looksLikeStatusField(dimension.getField())) {
                addUncertainty(payload, uncertaintySequence, model.getKey(), dimension.getKey(),
                        dimension.getField(), "UNKNOWN_STATUS", "WARNING", "状态字段的枚举值与业务含义需要管理员确认");
            }
            index++;
        }
    }

    /**
     * 校验受限日期差派生字段，拒绝任意表达式和跨表字段。
     *
     * <p>为什么使用结构化操作符：不同数据库的日期函数语法不同，草稿只保存业务含义，阶段 5
     * 再由正式资产 API 选择方言；这里不拼接、更不执行任何 SQL。</p>
     */
    private void validateDerivedDimension(DimensionDraft dimension, TableMetadata table,
            Map<String, TableMetadata> allTables, String path,
            List<ModelingValidationIssue> errors) {
        DerivedFieldDraft derivation = dimension.getDerivation();
        if (!DERIVED_FIELD_NAME_PATTERN.matcher(StringUtils.defaultString(dimension.getField()))
                .matches()) {
            errors.add(issue(path + ".field", "ILLEGAL_DERIVED_FIELD_NAME",
                    "派生字段名必须是安全的英文标识符"));
        }

        FieldResolution start = resolveField(derivation.getStartField(), table, allTables);
        if (start.type() != FieldResolutionType.LOCAL) {
            errors.add(issue(path + ".derivation.startField", "UNKNOWN_DERIVATION_FIELD",
                    "派生字段的起始日期必须来自目标模型 baseTable"));
        } else {
            derivation.setStartField(start.field());
            validateTemporalDerivationField(start, path + ".derivation.startField", errors);
        }

        if ("CURRENT_DATE".equals(derivation.getEndType())) {
            if (StringUtils.isNotBlank(derivation.getEndField())) {
                errors.add(issue(path + ".derivation.endField", "ILLEGAL_DERIVATION_END",
                        "结束类型为 CURRENT_DATE 时不得同时提供结束字段"));
            }
            derivation.setEndField(null);
            return;
        }
        if (!"FIELD".equals(derivation.getEndType())) {
            errors.add(issue(path + ".derivation.endType", "ILLEGAL_DERIVATION_END",
                    "日期差结束值只能是当前日期或同表字段"));
            return;
        }
        FieldResolution end = resolveField(derivation.getEndField(), table, allTables);
        if (end.type() != FieldResolutionType.LOCAL) {
            errors.add(issue(path + ".derivation.endField", "UNKNOWN_DERIVATION_FIELD",
                    "派生字段的结束日期必须来自目标模型 baseTable"));
        } else {
            derivation.setEndField(end.field());
            validateTemporalDerivationField(end, path + ".derivation.endField", errors);
        }
    }

    /**
     * 校验日期差只能引用安全且真实的日期/时间戳物理列。
     *
     * <p>为什么同时校验标识符与类型：隔离 Schema 工厂会把已确认字段组合成受限方言表达式；
     * 若允许任意物理列名或数值/文本列进入表达式，既会产生不可移植 SQL，也会把错误口径延迟到
     * 阶段 5 才暴露。</p>
     */
    private void validateTemporalDerivationField(FieldResolution resolution, String path,
            List<ModelingValidationIssue> errors) {
        if (!DERIVATION_PHYSICAL_FIELD_PATTERN.matcher(resolution.field()).matches()) {
            errors.add(issue(path, "UNSAFE_DERIVATION_FIELD",
                    "日期差物理字段必须是安全的单列标识符"));
            return;
        }
        if (!isDateOrTimestampType(resolution.dataType())) {
            errors.add(issue(path, "DERIVATION_FIELD_NOT_TEMPORAL",
                    "日期差起止字段必须是 DATE、DATETIME 或 TIMESTAMP 类型"));
        }
    }

    /** 判断常见数据库日期与时间戳类型；不把纯 TIME、字符或数值列误当日期基准。 */
    private boolean isDateOrTimestampType(String dataType) {
        String normalizedType = StringUtils.upperCase(StringUtils.trimToEmpty(dataType),
                Locale.ROOT).replaceAll("\\s+", " ");
        return normalizedType.startsWith("DATE") || normalizedType.startsWith("DATETIME")
                || normalizedType.startsWith("TIMESTAMP");
    }

    /** 校验指标聚合、表达式和结构化过滤。 */
    private void validateMetrics(ModelingDraftPayload payload, ModelDraft model,
            TableMetadata table, Map<String, TableMetadata> allTables, String modelPath,
            Set<String> keys, Map<String, String> objectTypes,
            Map<String, CrossTableObjectReference> crossTableObjects, Set<String> seenNames,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        Iterator<MetricDraft> iterator = model.getMetrics().iterator();
        int index = 0;
        while (iterator.hasNext()) {
            MetricDraft metric = iterator.next();
            String path = modelPath + ".metrics[" + index + "]";
            String aggregation = StringUtils.upperCase(metric.getAggregation(), Locale.ROOT);
            metric.setAggregation(aggregation);
            if (!ModelingDraftConstants.ALLOWED_AGGREGATIONS.contains(aggregation)) {
                errors.add(issue(path + ".aggregation", "ILLEGAL_AGGREGATION", "指标聚合函数不受支持"));
            }

            FieldResolution resolution =
                    resolveMetricField(metric.getField(), aggregation, table, allTables);
            if (resolution.type() == FieldResolutionType.CROSS_TABLE) {
                addCrossTableUncertainty(payload, uncertaintySequence, model.getKey(),
                        metric.getKey(), metric.getField());
                // 与维度保持一致：记录被移除对象，避免关联术语被误判为未知引用并阻塞整个草稿。
                rememberCrossTableObject(crossTableObjects, model.getKey(), metric.getKey(),
                        metric.getField());
                iterator.remove();
                index++;
                continue;
            }
            if (resolution.type() != FieldResolutionType.LOCAL) {
                errors.add(issue(path + ".field", "UNKNOWN_FIELD", "指标字段不存在于 baseTable"));
            } else {
                metric.setField(resolution.field());
                validateMetricExpression(metric, path, errors);
            }
            validateFilters(payload, model, metric, table, allTables, path, uncertaintySequence,
                    errors);
            registerKey(metric.getKey(), path + ".key", keys, errors);
            // 使用规范枚举值建立类型索引，避免术语把指标误声明成维度或反向误声明。
            objectTypes.putIfAbsent(normalize(metric.getKey()), OBJECT_TYPE_METRIC);
            registerNames(payload, uncertaintySequence, model.getKey(), metric.getKey(),
                    metric.getField(), metric.getName(), metric.getBizName(), metric.getAliases(),
                    seenNames);
            index++;
        }
    }

    /** 校验敏感字段，只保留当前 baseTable 字段。 */
    private void validateSensitiveFields(ModelingDraftPayload payload, ModelDraft model,
            TableMetadata table, Map<String, TableMetadata> allTables, String modelPath,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        Iterator<SensitiveFieldDraft> iterator = model.getSensitiveFields().iterator();
        int index = 0;
        while (iterator.hasNext()) {
            SensitiveFieldDraft sensitive = iterator.next();
            String path = modelPath + ".sensitiveFields[" + index + "]";
            FieldResolution resolution = resolveField(sensitive.getField(), table, allTables);
            if (resolution.type() == FieldResolutionType.CROSS_TABLE) {
                addCrossTableUncertainty(payload, uncertaintySequence, model.getKey(), null,
                        sensitive.getField());
                iterator.remove();
            } else if (resolution.type() != FieldResolutionType.LOCAL) {
                errors.add(issue(path + ".field", "UNKNOWN_FIELD", "敏感字段不存在于 baseTable"));
            } else {
                sensitive.setField(resolution.field());
            }
            index++;
        }
    }

    /** 校验结构化过滤，跨表过滤转不确定项并移除。 */
    private void validateFilters(ModelingDraftPayload payload, ModelDraft model, MetricDraft metric,
            TableMetadata table, Map<String, TableMetadata> allTables, String metricPath,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        Iterator<MetricFilterDraft> iterator = metric.getFilters().iterator();
        int index = 0;
        while (iterator.hasNext()) {
            MetricFilterDraft filter = iterator.next();
            String path = metricPath + ".filters[" + index + "]";
            filter.setOperator(StringUtils.upperCase(filter.getOperator(), Locale.ROOT));
            if (!ModelingDraftConstants.ALLOWED_FILTER_OPERATORS.contains(filter.getOperator())) {
                errors.add(issue(path + ".operator", "ILLEGAL_FILTER_OPERATOR", "过滤操作符不受支持"));
            }
            validateFilterValues(filter, path, errors);
            FieldResolution resolution = resolveField(filter.getField(), table, allTables);
            if (resolution.type() == FieldResolutionType.CROSS_TABLE) {
                addCrossTableUncertainty(payload, uncertaintySequence, model.getKey(),
                        metric.getKey(), filter.getField());
                iterator.remove();
            } else if (resolution.type() != FieldResolutionType.LOCAL) {
                errors.add(issue(path + ".field", "UNKNOWN_FIELD", "过滤字段不存在于 baseTable"));
            } else {
                filter.setField(resolution.field());
            }
            index++;
        }
    }

    /** 校验不同过滤操作符对应的值数量，避免模糊或不可执行的结构。 */
    private void validateFilterValues(MetricFilterDraft filter, String path,
            List<ModelingValidationIssue> errors) {
        int valueCount = filter.getValues() == null ? 0 : filter.getValues().size();
        String operator = filter.getOperator();
        boolean valid = switch (operator) {
            case "IS_NULL", "IS_NOT_NULL" -> valueCount == 0;
            case "BETWEEN" -> valueCount == 2;
            case "IN", "NOT_IN" -> valueCount > 0;
            case "EQ", "NE", "GT", "GTE", "LT", "LTE" -> valueCount == 1;
            default -> true;
        };
        if (!valid) {
            errors.add(issue(path + ".values", "ILLEGAL_FILTER_VALUES", "过滤值数量与操作符不匹配"));
        }
    }

    /** 校验或补齐受限指标表达式，拒绝任意 SQL。 */
    private void validateMetricExpression(MetricDraft metric, String path,
            List<ModelingValidationIssue> errors) {
        if ("*".equals(metric.getField()) && !"COUNT".equals(metric.getAggregation())) {
            errors.add(issue(path + ".field", "ILLEGAL_METRIC_FIELD", "只有 COUNT 指标允许使用 *"));
            return;
        }
        String canonical = canonicalExpression(metric.getAggregation(), metric.getField());
        if (StringUtils.isBlank(metric.getExpression())) {
            metric.setExpression(canonical);
            return;
        }
        String normalizedInput =
                metric.getExpression().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String normalizedCanonical = canonical.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!normalizedCanonical.equals(normalizedInput)) {
            errors.add(issue(path + ".expression", "ILLEGAL_METRIC_EXPRESSION",
                    "指标表达式必须是所选聚合与单个字段的规范组合"));
        } else {
            metric.setExpression(canonical);
        }
    }

    /** 构造规范指标表达式，不拼接任何用户提供 SQL 片段。 */
    private String canonicalExpression(String aggregation, String field) {
        if ("COUNT_DISTINCT".equals(aggregation)) {
            return "COUNT(DISTINCT " + field + ")";
        }
        return aggregation + "(" + field + ")";
    }

    /**
     * 校验 2.0 路由专属区段，确保增量别名、修改建议和回归问法不会绕过门禁。
     *
     * <p>CREATE_NEW 不得携带任何目标资产修改；EXTEND_EXISTING 仅允许对已确认目标模型自身的
     * description/aliases 做可审计的非删除修改。目标子对象清单当前没有进入确认快照，因此维度、
     * 指标和术语修改一律 fail-closed，避免仅凭 LLM objectKey 产生跨资产修改。</p>
     */
    private void validateRoutedSections(ModelingDraftPayload payload, Set<String> seenNames,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        if (!ModelingDraftConstants.SCHEMA_VERSION_ROUTED.equals(payload.getSchemaVersion())) {
            return;
        }
        boolean extending = ModelingDraftConstants.ACTION_EXTEND_EXISTING
                .equals(payload.getAction());
        if (!extending) {
            if (payload.getModifications() != null && !payload.getModifications().isEmpty()) {
                errors.add(issue("$.modifications", "UNEXPECTED_MODIFICATIONS",
                        "CREATE_NEW 草稿不得修改任何正式资产"));
            }
            if (payload.getRegressionQuestions() != null
                    && !payload.getRegressionQuestions().isEmpty()) {
                errors.add(issue("$.regressionQuestions", "UNEXPECTED_REGRESSION_QUESTIONS",
                        "CREATE_NEW 草稿不得携带目标资产回归问法"));
            }
            return;
        }

        String targetHandle = payload.getTargetAsset() == null ? null
                : payload.getTargetAsset().getCandidateHandle();
        registerIncrementalAliases(payload, targetHandle, seenNames, uncertaintySequence);
        validateModifications(payload, targetHandle, seenNames, uncertaintySequence);
        if (payload.getRegressionQuestions() == null
                || payload.getRegressionQuestions().stream().allMatch(StringUtils::isBlank)) {
            addUncertainty(payload, uncertaintySequence, targetHandle, targetHandle,
                    "regressionQuestions", "REGRESSION_VALIDATION_REQUIRED", "BLOCKING",
                    "增强草稿至少需要一条回归问法进入真实语义解析与只读 SQL 验证");
        }
    }

    /** 将模型级新增别名纳入现有资产重名检查；目标资产只读名称本身不参与新增对象比较。 */
    private void registerIncrementalAliases(ModelingDraftPayload payload, String targetHandle,
            Set<String> seenNames, AtomicInteger uncertaintySequence) {
        if (payload.getAdditions() == null || payload.getAdditions().getAliases() == null) {
            return;
        }
        Set<String> localNames = new HashSet<>();
        for (String alias : payload.getAdditions().getAliases()) {
            addAliasConflict(payload, uncertaintySequence, targetHandle, targetHandle, null,
                    alias, seenNames, localNames);
        }
    }

    /** 对受控 before/after 修改做结构、目标范围和非删除校验。 */
    private void validateModifications(ModelingDraftPayload payload, String targetHandle,
            Set<String> seenNames, AtomicInteger uncertaintySequence) {
        List<ModificationDraft> modifications = payload.getModifications() == null ? List.of()
                : payload.getModifications();
        for (ModificationDraft modification : modifications) {
            if (modification == null) {
                addModificationBlocker(payload, uncertaintySequence, targetHandle, null, null,
                        "修改项不能为空");
                continue;
            }
            String objectKey = modification.getObjectKey();
            String field = modification.getField();
            if (!"MODEL".equals(modification.getObjectType())
                    || !StringUtils.equals(targetHandle, objectKey)) {
                addModificationBlocker(payload, uncertaintySequence, targetHandle, objectKey,
                        field, "当前确认快照只能证明目标模型自身，禁止修改子对象或其他资产");
            }
            if (!ALLOWED_MODIFICATION_FIELDS.contains(field)) {
                addModificationBlocker(payload, uncertaintySequence, targetHandle, objectKey,
                        field, "只允许受控修改 description 或 aliases，禁止修改指标语义和事实粒度");
                continue;
            }
            if (modification.getBeforeValue() == null || modification.getAfterValue() == null) {
                addModificationBlocker(payload, uncertaintySequence, targetHandle, objectKey,
                        field, "修改项必须同时提供非空 beforeValue 和 afterValue");
                continue;
            }
            if (Objects.equals(modification.getBeforeValue(), modification.getAfterValue())) {
                addModificationBlocker(payload, uncertaintySequence, targetHandle, objectKey,
                        field, "beforeValue 与 afterValue 相同，无法形成可审计的增量修改");
                continue;
            }
            if ("description".equals(field)) {
                validateDescriptionModification(payload, uncertaintySequence, targetHandle,
                        modification);
            } else {
                validateAliasModification(payload, uncertaintySequence, targetHandle,
                        modification, seenNames);
            }
        }
    }

    /** 描述修改必须是非空字符串；空 afterValue 等价于删除，第一版明确禁止。 */
    private void validateDescriptionModification(ModelingDraftPayload payload,
            AtomicInteger uncertaintySequence, String targetHandle,
            ModificationDraft modification) {
        if (!(modification.getBeforeValue() instanceof String before)
                || !(modification.getAfterValue() instanceof String after)
                || StringUtils.isBlank(before) || StringUtils.isBlank(after)) {
            addModificationBlocker(payload, uncertaintySequence, targetHandle,
                    modification.getObjectKey(), modification.getField(),
                    "description 修改必须提供非空字符串，禁止通过清空内容删除既有说明");
        }
    }

    /** 别名修改只允许追加安全字符串，不允许删除既有别名。 */
    private void validateAliasModification(ModelingDraftPayload payload,
            AtomicInteger uncertaintySequence, String targetHandle,
            ModificationDraft modification, Set<String> seenNames) {
        List<String> before = stringList(modification.getBeforeValue());
        List<String> after = stringList(modification.getAfterValue());
        if (before == null || after == null || after.isEmpty()) {
            addModificationBlocker(payload, uncertaintySequence, targetHandle,
                    modification.getObjectKey(), modification.getField(),
                    "aliases 修改必须使用非空字符串数组");
            return;
        }
        Set<String> normalizedBefore = before.stream().map(ModelingDraftValidator::normalize)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> normalizedAfter = after.stream().map(ModelingDraftValidator::normalize)
                .collect(java.util.stream.Collectors.toSet());
        if (!normalizedAfter.containsAll(normalizedBefore)) {
            addModificationBlocker(payload, uncertaintySequence, targetHandle,
                    modification.getObjectKey(), modification.getField(),
                    "第一版只允许追加别名，不允许删除正式资产的既有别名");
            return;
        }
        Set<String> localNames = new HashSet<>();
        after.stream().filter(alias -> !normalizedBefore.contains(normalize(alias)))
                .forEach(alias -> addAliasConflict(payload, uncertaintySequence, targetHandle,
                        modification.getObjectKey(), null, alias, seenNames, localNames));
    }

    /** 把 Jackson 绑定的 Object 严格转换为非空字符串列表，类型不匹配时返回 null。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || StringUtils.isBlank(text)) {
                return null;
            }
            result.add(text);
        }
        return result;
    }

    /** 追加范围/结构不安全的修改阻塞项，统一使用稳定业务身份避免重复保存膨胀。 */
    private void addModificationBlocker(ModelingDraftPayload payload,
            AtomicInteger uncertaintySequence, String targetHandle, String objectKey, String field,
            String reason) {
        addUncertainty(payload, uncertaintySequence, targetHandle, objectKey, field,
                "UNAUTHORIZED_MODIFICATION", "BLOCKING", reason);
    }

    /** 校验术语 key、别名以及本地对象引用的存在性和类型一致性。 */
    private void validateTerms(ModelingDraftPayload payload, Set<String> keys,
            Map<String, String> objectTypes,
            Map<String, CrossTableObjectReference> crossTableObjects, Set<String> seenNames,
            AtomicInteger uncertaintySequence, List<ModelingValidationIssue> errors) {
        for (int termIndex = 0; termIndex < payload.getTerms().size(); termIndex++) {
            TermDraft term = payload.getTerms().get(termIndex);
            String path = "$.terms[" + termIndex + "]";
            registerKey(term.getKey(), path + ".key", keys, errors);
            registerNames(payload, uncertaintySequence, null, term.getKey(), null, term.getName(),
                    null, term.getAliases(), seenNames);
            if (GENERIC_TERMS.contains(normalize(term.getName()))) {
                addUncertainty(payload, uncertaintySequence, null, term.getKey(), null,
                        "AMBIGUOUS_TERM", "WARNING", "术语过于宽泛，需要管理员补充业务边界");
            }
            Iterator<TermTargetDraft> targetIterator = term.getTargets().iterator();
            int targetIndex = 0;
            while (targetIterator.hasNext()) {
                TermTargetDraft target = targetIterator.next();
                String targetPath = path + ".targets[" + targetIndex + "]";
                String actualType = objectTypes.get(normalize(target.getObjectKey()));
                if (actualType == null) {
                    CrossTableObjectReference crossTableObject =
                            crossTableObjects.get(normalize(target.getObjectKey()));
                    if (crossTableObject == null) {
                        // 只有既不在有效对象索引、也未被跨表降级的引用才是真正未知引用。
                        errors.add(issue(targetPath + ".objectKey", "UNKNOWN_TERM_REFERENCE",
                                "术语引用的草稿对象不存在"));
                    } else {
                        // 无效 target 不得保留在可编辑草稿中；同时用关联术语的不确定项保留人工确认入口。
                        targetIterator.remove();
                        addUncertainty(payload, uncertaintySequence, crossTableObject.modelKey(),
                                term.getKey(), crossTableObject.field(), "CROSS_TABLE", "BLOCKING",
                                "术语目标依赖已降级的跨表对象，目标关联已移除，需要管理员确认");
                    }
                } else if (!actualType.equals(target.getType())) {
                    // key 命中并不代表引用合法；类型不一致会使前端和后续转换错误解释对象。
                    errors.add(issue(targetPath + ".type", ISSUE_TERM_TARGET_TYPE_MISMATCH,
                            "术语目标类型与引用对象的实际类型不一致，应为 " + actualType));
                }
                targetIndex++;
            }
        }
    }

    /** 记录因单表边界被移除的对象，供术语引用校验做可恢复降级。 */
    private void rememberCrossTableObject(Map<String, CrossTableObjectReference> crossTableObjects,
            String modelKey, String objectKey, String field) {
        if (StringUtils.isBlank(objectKey)) {
            return;
        }
        crossTableObjects.putIfAbsent(normalize(objectKey),
                new CrossTableObjectReference(modelKey, field));
    }

    /** 在追加告警后校验不确定项 key。 */
    private void validateUncertaintyKeys(ModelingDraftPayload payload, Set<String> keys,
            List<ModelingValidationIssue> errors) {
        for (int index = 0; index < payload.getUncertainties().size(); index++) {
            registerKey(payload.getUncertainties().get(index).getKey(),
                    "$.uncertainties[" + index + "].key", keys, errors);
        }
    }

    /** 注册对象 key 并报告重复。 */
    private void registerKey(String key, String path, Set<String> keys,
            List<ModelingValidationIssue> errors) {
        if (!keys.add(normalize(key))) {
            errors.add(issue(path, "DUPLICATE_OBJECT_KEY", "草稿对象 key 必须全局唯一"));
        }
    }

    /** 注册对象名及别名，冲突时追加不确定项而不阻塞保存。 */
    private void registerNames(ModelingDraftPayload payload, AtomicInteger sequence,
            String modelKey, String objectKey, String field, String name, String bizName,
            List<String> aliases, Set<String> seenNames) {
        Set<String> localNames = new HashSet<>();
        addAliasConflict(payload, sequence, modelKey, objectKey, field, name, seenNames,
                localNames);
        addAliasConflict(payload, sequence, modelKey, objectKey, field, bizName, seenNames,
                localNames);
        if (aliases != null) {
            for (String alias : aliases) {
                addAliasConflict(payload, sequence, modelKey, objectKey, field, alias, seenNames,
                        localNames);
            }
        }
    }

    /** 将名称冲突转换为不确定项；同一对象内部重复声明的同名名称只报告一次。 */
    private void addAliasConflict(ModelingDraftPayload payload, AtomicInteger sequence,
            String modelKey, String objectKey, String field, String candidate,
            Set<String> seenNames, Set<String> localNames) {
        if (StringUtils.isBlank(candidate)) {
            return;
        }
        String normalized = normalize(candidate);
        if (!localNames.add(normalized)) {
            return;
        }
        if (!seenNames.add(normalized)) {
            addUncertainty(payload, sequence, modelKey, objectKey, field, "ALIAS_CONFLICT",
                    "BLOCKING", "名称或别名与其他草稿对象或现有资产冲突：" + candidate);
        }
    }

    /** 追加跨表不确定项。 */
    private void addCrossTableUncertainty(ModelingDraftPayload payload, AtomicInteger sequence,
            String modelKey, String objectKey, String field) {
        addUncertainty(payload, sequence, modelKey, objectKey, field, "CROSS_TABLE", "BLOCKING",
                "第一版仅支持单一 baseTable，该对象需要跨表建模，已从自动草稿中移除");
    }

    /** 追加稳定 key 的不确定项；相同派生问题已存在时保持原 key，不在多轮校验中重复膨胀。 */
    private void addUncertainty(ModelingDraftPayload payload, AtomicInteger sequence,
            String modelKey, String objectKey, String field, String category, String severity,
            String reason) {
        boolean hasTarget = StringUtils.isNotBlank(modelKey) || StringUtils.isNotBlank(objectKey)
                || StringUtils.isNotBlank(field);
        UncertaintyIdentity identity = new UncertaintyIdentity(modelKey, objectKey, field,
                category, hasTarget ? null : normalize(reason));
        boolean exists = payload.getUncertainties().stream().map(this::uncertaintyIdentity)
                .anyMatch(identity::equals);
        if (exists) {
            return;
        }
        UncertaintyDraft uncertainty = new UncertaintyDraft();
        uncertainty.setKey(nextUncertaintyKey(payload, sequence));
        uncertainty.setModelKey(modelKey);
        uncertainty.setObjectKey(objectKey);
        uncertainty.setField(field);
        uncertainty.setCategory(category);
        uncertainty.setSeverity(severity);
        uncertainty.setReason(reason);
        payload.getUncertainties().add(uncertainty);
    }

    /**
     * 对输入中已经重复的不确定项按稳定业务身份去重。
     *
     * <p>文案和严重级别属于可变展示属性，不参与身份；重复项保留最早审计 key，并把严重级别
     * 升级到最严格值，避免多轮保存因提示语变化无限追加，也避免去重过程意外降低门禁。</p>
     */
    private void deduplicateUncertainties(ModelingDraftPayload payload) {
        Map<UncertaintyIdentity, UncertaintyDraft> unique = new LinkedHashMap<>();
        for (UncertaintyDraft uncertainty : payload.getUncertainties()) {
            UncertaintyIdentity identity = uncertaintyIdentity(uncertainty);
            UncertaintyDraft existing = unique.putIfAbsent(identity, uncertainty);
            if (existing != null
                    && uncertaintySeverityRank(uncertainty.getSeverity())
                            > uncertaintySeverityRank(existing.getSeverity())) {
                existing.setSeverity(uncertainty.getSeverity());
            }
            if (existing != null && StringUtils.isBlank(existing.getReason())
                    && StringUtils.isNotBlank(uncertainty.getReason())) {
                existing.setReason(uncertainty.getReason());
            }
        }
        payload.setUncertainties(new ArrayList<>(unique.values()));
    }

    /** 将严重级别转换成可比较等级；未知值按最严格处理以保持 fail-closed。 */
    private int uncertaintySeverityRank(String severity) {
        if (ModelingDraftConstants.FINDING_INFO.equalsIgnoreCase(severity)) {
            return 1;
        }
        if (ModelingDraftConstants.FINDING_WARNING.equalsIgnoreCase(severity)) {
            return 2;
        }
        return 3;
    }

    /** 读取既有 uncertainty_N key 的最大序号，避免稀疏历史 key 与新生成 key 冲突。 */
    private int maxUncertaintySequence(List<UncertaintyDraft> uncertainties) {
        int maximum = 0;
        for (UncertaintyDraft uncertainty : uncertainties) {
            String key = uncertainty.getKey();
            if (StringUtils.startsWith(key, "uncertainty_")) {
                try {
                    maximum = Math.max(maximum,
                            Integer.parseInt(key.substring("uncertainty_".length())));
                } catch (NumberFormatException ignored) {
                    // 自定义 key 仍由 Schema 和全局 key 校验处理，不参与自动序号分配。
                }
            }
        }
        return maximum;
    }

    /** 生成当前草稿内唯一的派生不确定项 key。 */
    private String nextUncertaintyKey(ModelingDraftPayload payload, AtomicInteger sequence) {
        Set<String> existingKeys = payload.getUncertainties().stream().map(UncertaintyDraft::getKey)
                .collect(java.util.stream.Collectors.toSet());
        String candidate;
        do {
            candidate = "uncertainty_" + sequence.incrementAndGet();
        } while (existingKeys.contains(candidate));
        return candidate;
    }

    /** 构造忽略审计 key、提示文案和严重级别的稳定业务身份。 */
    private UncertaintyIdentity uncertaintyIdentity(UncertaintyDraft uncertainty) {
        boolean hasTarget = StringUtils.isNotBlank(uncertainty.getModelKey())
                || StringUtils.isNotBlank(uncertainty.getObjectKey())
                || StringUtils.isNotBlank(uncertainty.getField());
        // 没有任何目标坐标的全局问题仍需用规范化原因区分，否则不同全局问题会被误合并。
        String globalDiscriminator = hasTarget ? null : normalize(uncertainty.getReason());
        return new UncertaintyIdentity(uncertainty.getModelKey(), uncertainty.getObjectKey(),
                uncertainty.getField(), uncertainty.getCategory(), globalDiscriminator);
    }

    /** 解析本地、跨表或未知字段。 */
    private FieldResolution resolveField(String rawField, TableMetadata baseTable,
            Map<String, TableMetadata> allTables) {
        if (StringUtils.isBlank(rawField) || rawField.contains(";") || rawField.contains("(")
                || rawField.contains(")") || rawField.contains(" ")) {
            return FieldResolution.unknown();
        }
        String field = rawField;
        String qualifier = null;
        int dotIndex = rawField.lastIndexOf('.');
        if (dotIndex >= 0) {
            qualifier = rawField.substring(0, dotIndex);
            field = rawField.substring(dotIndex + 1);
        }
        String normalizedQualifier = normalize(qualifier);
        String normalizedBaseTable = normalize(baseTable.tableName());
        boolean baseQualified = qualifier == null || normalizedQualifier.equals(normalizedBaseTable)
                || normalizedQualifier.endsWith("." + normalizedBaseTable);
        if (!baseQualified) {
            String tableQualifier = normalizedQualifier.contains(".")
                    ? normalizedQualifier.substring(normalizedQualifier.lastIndexOf('.') + 1)
                    : normalizedQualifier;
            TableMetadata other = allTables.get(tableQualifier);
            if (other != null && other.fields().containsKey(normalize(field))) {
                return FieldResolution.crossTable();
            }
            return FieldResolution.unknown();
        }
        ColumnMetadata actual = baseTable.fields().get(normalize(field));
        return actual == null ? FieldResolution.unknown()
                : FieldResolution.local(actual.fieldName(), actual.dataType());
    }

    /** COUNT(*) 是唯一无需物理字段的合法指标。 */
    private FieldResolution resolveMetricField(String field, String aggregation,
            TableMetadata baseTable, Map<String, TableMetadata> allTables) {
        if ("COUNT".equals(aggregation) && "*".equals(field)) {
            return FieldResolution.local("*", null);
        }
        return resolveField(field, baseTable, allTables);
    }

    /** 判断字段是否很可能需要业务枚举确认。 */
    private boolean looksLikeStatusField(String field) {
        String value = normalize(field);
        return value.equals("status") || value.endsWith("_status") || value.equals("state")
                || value.endsWith("_state") || value.contains("状态");
    }

    /** 规范化已有资产名集合。 */
    private Set<String> normalizeNames(Set<String> names) {
        Set<String> result = new HashSet<>();
        if (names != null) {
            names.stream().filter(StringUtils::isNotBlank).map(ModelingDraftValidator::normalize)
                    .forEach(result::add);
        }
        return result;
    }

    /** 创建 422 校验异常。 */
    private ModelingDraftException invalid(List<ModelingValidationIssue> issues) {
        return new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                ModelingDraftConstants.ERROR_OUTPUT_INVALID, "结构化草稿校验失败", issues);
    }

    /** 创建单个校验问题。 */
    private static ModelingValidationIssue issue(String path, String code, String message) {
        return new ModelingValidationIssue(path, code,
                StringUtils.defaultIfBlank(message, "结构化草稿不符合约束"));
    }

    /** 统一做不区分大小写的 key 比较。 */
    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 已通过校验并规范化的草稿。 */
    public record ValidatedDraft(ModelingDraftPayload payload, String json) {}

    /** 单表元数据索引。 */
    private record TableMetadata(String tableName, Map<String, ColumnMetadata> fields) {}

    /** 仅保存校验需要的物理列名和类型，不保留样例值或其他敏感元数据。 */
    private record ColumnMetadata(String fieldName, String dataType) {}

    /** 字段解析类型。 */
    private enum FieldResolutionType {
        LOCAL, CROSS_TABLE, UNKNOWN
    }

    /** 被单表约束移除的对象最小上下文，不保存对象正文或任何样例值。 */
    private record CrossTableObjectReference(String modelKey, String field) {}

    /** 不确定项的稳定业务身份；展示属性不参与比较，避免同一业务问题在多轮中重复。 */
    private record UncertaintyIdentity(String modelKey, String objectKey, String field,
            String category, String globalDiscriminator) {}

    /** 已编译 Schema 与 Jackson 节点的只读构造期组合。 */
    private record SchemaBundle(JsonNode node, JSONSchema schema) {}

    /** 字段解析结果。 */
    private record FieldResolution(FieldResolutionType type, String field, String dataType) {
        private static FieldResolution local(String field, String dataType) {
            return new FieldResolution(FieldResolutionType.LOCAL, field, dataType);
        }

        private static FieldResolution crossTable() {
            return new FieldResolution(FieldResolutionType.CROSS_TABLE, null, null);
        }

        private static FieldResolution unknown() {
            return new FieldResolution(FieldResolutionType.UNKNOWN, null, null);
        }
    }
}
