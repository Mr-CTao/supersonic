package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.DimensionConstants;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.Dimension;
import com.tencent.supersonic.headless.api.pojo.Field;
import com.tencent.supersonic.headless.api.pojo.FieldParam;
import com.tencent.supersonic.headless.api.pojo.Measure;
import com.tencent.supersonic.headless.api.pojo.MetricDefineByFieldParams;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.QueryConfig;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.SemanticSchema;
import com.tencent.supersonic.headless.api.pojo.enums.DimensionType;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.enums.SchemaType;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DimSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.core.pojo.Ontology;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DerivedFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DimensionDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermTargetDraft;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把单个未发布模型草稿转换为隔离的现有语义层 Schema。
 *
 * <p>
 * 职责说明：构造 RuleSqlParser 消费的 {@link DataSetSchema}，以及 DefaultSemanticTranslator 消费的
 * {@link SemanticSchemaResp} 和 {@link Ontology}。所有 ID 均为请求内 synthetic ID，不落库、不注册到
 * SchemaManager，也不刷新知识索引。数据源对象只复制类型和版本，禁止把 URL、账号或密码带入验证结果。 本工厂没有共享可变状态，synthetic ID 计数器仅在单次 build
 * 调用内使用。
 * </p>
 */
@Component
public class DraftSemanticSchemaFactory {

    private static final long SYNTHETIC_ID_BASE = 8_000_000_000L;

    /**
     * 构造单模型隔离语义 Schema。
     *
     * <p>
     * 调用示例：{@code factory.build(model, terms, columns, database, 0, 20)}。调用方必须先完成阶段 3
     * 字段与表达式校验；本方法只做无副作用的 DTO 转换，不会查询数据库或执行 SQL。
     * </p>
     *
     * @param model 已通过阶段 3 校验的单表模型草稿。
     * @param terms 草稿域级术语。
     * @param columns 当前模型基表的真实字段。
     * @param database 已复核访问权限的数据源；只读取类型和版本。
     * @param modelIndex 草稿内模型序号，用于隔离 synthetic ID。
     * @param previewLimit 样例解析必须携带的最大返回行数。
     * @return parser 与 translator 可直接消费的隔离 Schema 快照。
     * @throws IllegalArgumentException 数据源类型或模型关键字段缺失时抛出。
     */
    public DraftModelSemanticSchema build(ModelDraft model, List<TermDraft> terms,
            List<DBColumn> columns, DatabaseResp database, int modelIndex, int previewLimit) {
        if (model == null || StringUtils.isBlank(model.getKey())
                || StringUtils.isBlank(model.getBaseTable()) || database == null
                || StringUtils.isBlank(database.getType())) {
            throw new IllegalArgumentException("隔离草稿 Schema 缺少模型或数据源信息");
        }
        long dataSetId = SYNTHETIC_ID_BASE + modelIndex * 10_000L;
        long modelId = dataSetId + 1;
        AtomicLong nextId = new AtomicLong(modelId + 1);
        QueryConfig queryConfig = buildQueryConfig(previewLimit);
        DatabaseResp safeDatabase = safeDatabase(database);

        DataSetSchema dataSetSchema = new DataSetSchema();
        dataSetSchema.setDatabaseType(safeDatabase.getType());
        dataSetSchema.setDatabaseVersion(safeDatabase.getVersion());
        dataSetSchema.setQueryConfig(queryConfig);
        dataSetSchema.setDataSet(dataSetElement(model, dataSetId));

        ModelResp modelResp = buildModelResp(model, columns, modelId, safeDatabase.getType());
        List<DimSchemaResp> dimensions = new ArrayList<>();
        List<MetricSchemaResp> metrics = new ArrayList<>();
        Map<String, SchemaElement> targetByKey = new LinkedHashMap<>();

        for (DimensionDraft dimension : safe(model.getDimensions())) {
            long elementId = nextId.getAndIncrement();
            SchemaElement element =
                    dimensionElement(model, dimension, dataSetId, modelId, elementId);
            dataSetSchema.getDimensions().add(element);
            targetByKey.put(normalize(dimension.getKey()), element);
            dimensions.add(dimensionSchema(model, dimension, modelId, elementId,
                    safeDatabase.getType()));
            modelResp.getModelDetail().getDimensions()
                    .add(modelDimension(model, dimension, safeDatabase.getType()));
        }
        Set<String> registeredMeasures = new LinkedHashSet<>();
        for (MetricDraft metric : safe(model.getMetrics())) {
            long elementId = nextId.getAndIncrement();
            SchemaElement element = metricElement(model, metric, dataSetId, modelId, elementId);
            dataSetSchema.getMetrics().add(element);
            targetByKey.put(normalize(metric.getKey()), element);
            metrics.add(metricSchema(model, metric, modelId, elementId));
            if (!"*".equals(metric.getField()) && registeredMeasures.add(metric.getField())) {
                modelResp.getModelDetail().getMeasures().add(modelMeasure(metric.getField()));
            }
        }
        addTerms(dataSetSchema, safe(terms), targetByKey, dataSetId, nextId);

        SemanticSchema semanticSchema = new SemanticSchema(new ArrayList<>(List.of(dataSetSchema)));
        SemanticSchemaResp semanticSchemaResp = buildSemanticSchemaResp(model, dataSetId, modelId,
                queryConfig, safeDatabase, modelResp, dimensions, metrics);
        Ontology ontology =
                buildOntology(model.getKey(), safeDatabase, modelResp, dimensions, metrics);
        return new DraftModelSemanticSchema(semanticSchema, semanticSchemaResp, ontology, dataSetId,
                model.getKey());
    }

    /** 构建同时约束明细和聚合查询的预览 LIMIT。 */
    private QueryConfig buildQueryConfig(int previewLimit) {
        QueryConfig queryConfig = new QueryConfig();
        queryConfig.getDetailTypeDefaultConfig().setLimit(previewLimit);
        queryConfig.getAggregateTypeDefaultConfig().setLimit(previewLimit);
        // 未发布草稿验证不应自动附加“最近 7 天”等发布态默认时间范围。
        queryConfig.getAggregateTypeDefaultConfig().setTimeDefaultConfig(null);
        queryConfig.getDetailTypeDefaultConfig().setTimeDefaultConfig(null);
        return queryConfig;
    }

    /** 仅复制翻译器需要的非敏感数据源方言信息。 */
    private DatabaseResp safeDatabase(DatabaseResp database) {
        return DatabaseResp.builder().id(database.getId()).type(database.getType())
                .version(database.getVersion()).build();
    }

    /** 构造隔离数据集元素。 */
    private SchemaElement dataSetElement(ModelDraft model, long dataSetId) {
        return SchemaElement.builder().dataSetId(dataSetId).dataSetName(model.getKey())
                .id(dataSetId).name(model.getKey())
                .bizName(display(model.getBizName(), model.getName(), model.getKey()))
                .description(model.getDescription()).type(SchemaElementType.DATASET)
                .alias(copy(model.getValidationAliases())).build();
    }

    /** 构造 parser 使用的维度元素。 */
    private SchemaElement dimensionElement(ModelDraft model, DimensionDraft dimension,
            long dataSetId, long modelId, long elementId) {
        Map<String, Object> extInfo = new HashMap<>();
        extInfo.put(DraftSemanticSchemaMapper.EXT_DRAFT_OBJECT_KEY, dimension.getKey());
        extInfo.put(DimensionConstants.DIMENSION_TYPE, dimensionType(model, dimension));
        return SchemaElement.builder().dataSetId(dataSetId).dataSetName(model.getKey())
                .model(modelId).id(elementId)
                .name(semanticName(dimension.getName(), dimension.getKey()))
                .bizName(display(dimension.getBizName(), dimension.getName(), dimension.getKey()))
                .description(dimension.getDescription()).type(SchemaElementType.DIMENSION)
                .alias(copy(dimension.getAliases())).extInfo(extInfo).useCnt(0L).build();
    }

    /** 构造 parser 使用的指标元素。 */
    private SchemaElement metricElement(ModelDraft model, MetricDraft metric, long dataSetId,
            long modelId, long elementId) {
        Map<String, Object> extInfo = new HashMap<>();
        extInfo.put(DraftSemanticSchemaMapper.EXT_DRAFT_OBJECT_KEY, metric.getKey());
        return SchemaElement.builder().dataSetId(dataSetId).dataSetName(model.getKey())
                .model(modelId).id(elementId).name(semanticName(metric.getName(), metric.getKey()))
                .bizName(display(metric.getBizName(), metric.getName(), metric.getKey()))
                .description(metric.getDescription()).type(SchemaElementType.METRIC)
                .alias(copy(metric.getAliases())).defaultAgg(metric.getAggregation())
                .extInfo(extInfo).useCnt(0L).build();
    }

    /** 构造 translator 使用的维度定义。 */
    private DimSchemaResp dimensionSchema(ModelDraft model, DimensionDraft dimension, long modelId,
            long elementId, String databaseType) {
        DimSchemaResp response = new DimSchemaResp();
        response.setId(elementId);
        response.setName(semanticName(dimension.getName(), dimension.getKey()));
        response.setBizName(
                display(dimension.getBizName(), dimension.getName(), dimension.getKey()));
        response.setDescription(dimension.getDescription());
        response.setModelId(modelId);
        response.setModelName(model.getKey());
        response.setModelBizName(model.getKey());
        response.setType(dimensionType(model, dimension));
        response.setExpr(dimensionExpression(dimension, databaseType));
        response.setAlias(String.join(",", copy(dimension.getAliases())));
        response.setSemanticType(semanticType(dimension.getSemanticType()));
        response.getFields().addAll(dimensionPhysicalFields(dimension));
        return response;
    }

    /** 构造 translator 使用的字段型指标定义。 */
    private MetricSchemaResp metricSchema(ModelDraft model, MetricDraft metric, long modelId,
            long elementId) {
        MetricSchemaResp response = new MetricSchemaResp();
        response.setId(elementId);
        response.setName(semanticName(metric.getName(), metric.getKey()));
        response.setBizName(display(metric.getBizName(), metric.getName(), metric.getKey()));
        response.setDescription(metric.getDescription());
        response.setModelId(modelId);
        response.setModelName(model.getKey());
        response.setModelBizName(model.getKey());
        response.setAlias(String.join(",", copy(metric.getAliases())));
        response.setDefaultAgg(metric.getAggregation());
        response.setMetricDefineType(MetricDefineType.FIELD);
        MetricDefineByFieldParams definition = new MetricDefineByFieldParams();
        definition.setExpr(metricExpression(metric));
        if (!"*".equals(metric.getField())) {
            definition.getFields().add(new FieldParam(metric.getField()));
            response.getFields().add(metric.getField());
        }
        response.setMetricDefinition(MetricDefineType.FIELD, definition);
        return response;
    }

    /** 构造物理单表模型；字段来自本次服务端元数据快照。 */
    private ModelResp buildModelResp(ModelDraft model, List<DBColumn> columns, long modelId,
            String databaseType) {
        ModelResp response = new ModelResp();
        response.setId(modelId);
        response.setName(model.getKey());
        response.setBizName(model.getKey());
        response.setDescription(model.getDescription());
        ModelDetail detail = new ModelDetail();
        detail.setDbType(databaseType);
        detail.setTableQuery(model.getBaseTable());
        for (DBColumn column : safe(columns)) {
            detail.getFields().add(Field.builder().fieldName(column.getColumnName())
                    .dataType(column.getDataType()).build());
        }
        response.setModelDetail(detail);
        return response;
    }

    /** 构造物理模型维度。 */
    private Dimension modelDimension(ModelDraft model, DimensionDraft dimension,
            String databaseType) {
        Dimension response = new Dimension();
        response.setName(dimension.getField());
        response.setBizName(dimension.getField());
        response.setExpr(dimensionExpression(dimension, databaseType));
        response.setType(dimensionType(model, dimension));
        return response;
    }

    /**
     * 把结构化 DATE_DIFF 翻译成受限方言表达式。
     *
     * <p>字段、操作符、单位和结束类型都已由 {@link ModelingDraftValidator} 依据服务端元数据白名单
     * 校验；本方法只在固定模板中选择方言，不接收任意 SQL。普通维度仍直接引用已校验物理列。</p>
     */
    private String dimensionExpression(DimensionDraft dimension, String databaseType) {
        DerivedFieldDraft derivation = dimension.getDerivation();
        if (derivation == null) {
            return dimension.getField();
        }
        String unit = derivation.getUnit().toUpperCase(Locale.ROOT);
        String start = derivation.getStartField();
        String end = "CURRENT_DATE".equals(derivation.getEndType()) ? "CURRENT_TIMESTAMP"
                : derivation.getEndField();
        String dialect = StringUtils.upperCase(StringUtils.trimToEmpty(databaseType),
                Locale.ROOT);
        return switch (dialect) {
            case "POSTGRESQL" -> postgresDateDiff(unit, start, end);
            case "CLICKHOUSE" -> "dateDiff('" + unit.toLowerCase(Locale.ROOT) + "', " + start
                    + ", " + end + ")";
            case "PRESTO", "TRINO", "DUCKDB" -> "date_diff('"
                    + unit.toLowerCase(Locale.ROOT) + "', " + start + ", " + end + ")";
            default -> "TIMESTAMPDIFF(" + unit + ", " + start + ", " + end + ")";
        };
    }

    /** PostgreSQL 通过固定秒数换算 DAY/HOUR，避免注入任意 interval 或单位表达式。 */
    private String postgresDateDiff(String unit, String start, String end) {
        int secondsPerUnit = "HOUR".equals(unit) ? 3_600 : 86_400;
        return "FLOOR(EXTRACT(EPOCH FROM (" + end + " - " + start + ")) / "
                + secondsPerUnit + ")";
    }

    /** 返回派生表达式真实依赖的物理列，禁止把派生输出名伪装成表字段。 */
    private List<String> dimensionPhysicalFields(DimensionDraft dimension) {
        DerivedFieldDraft derivation = dimension.getDerivation();
        if (derivation == null) {
            return List.of(dimension.getField());
        }
        List<String> fields = new ArrayList<>(List.of(derivation.getStartField()));
        if ("FIELD".equals(derivation.getEndType())) {
            fields.add(derivation.getEndField());
        }
        return fields;
    }

    /** 构造物理模型度量字段，聚合由 MetricSchemaResp 表达式负责。 */
    private Measure modelMeasure(String field) {
        Measure response = new Measure();
        response.setName(field);
        response.setBizName(field);
        response.setExpr(field);
        return response;
    }

    /** 把本模型相关术语及其本地目标写入 DataSetSchema。 */
    private void addTerms(DataSetSchema dataSetSchema, List<TermDraft> terms,
            Map<String, SchemaElement> targetByKey, long dataSetId, AtomicLong nextId) {
        for (TermDraft term : terms) {
            List<Long> targetIds = safe(term.getTargets()).stream()
                    .map(TermTargetDraft::getObjectKey).map(DraftSemanticSchemaFactory::normalize)
                    .map(targetByKey::get).filter(java.util.Objects::nonNull)
                    .map(SchemaElement::getId).distinct().toList();
            if (targetIds.isEmpty()) {
                continue;
            }
            Map<String, Object> extInfo = new HashMap<>();
            extInfo.put(DraftSemanticSchemaMapper.EXT_DRAFT_OBJECT_KEY, term.getKey());
            extInfo.put(DraftSemanticSchemaMapper.EXT_TERM_TARGET_IDS, targetIds);
            SchemaElement element = SchemaElement.builder().dataSetId(dataSetId)
                    .dataSetName(dataSetSchema.getDataSet().getName()).id(nextId.getAndIncrement())
                    .name(semanticName(term.getName(), term.getKey())).bizName(term.getName())
                    .description(term.getDescription()).type(SchemaElementType.TERM)
                    .alias(copy(term.getAliases())).extInfo(extInfo).useCnt(0L).build();
            dataSetSchema.getTerms().add(element);
        }
    }

    /** 构造 translator 的完整语义响应 DTO。 */
    private SemanticSchemaResp buildSemanticSchemaResp(ModelDraft model, long dataSetId,
            long modelId, QueryConfig queryConfig, DatabaseResp database, ModelResp modelResp,
            List<DimSchemaResp> dimensions, List<MetricSchemaResp> metrics) {
        DataSetResp dataSet = new DataSetResp();
        dataSet.setId(dataSetId);
        dataSet.setName(model.getKey());
        dataSet.setBizName(display(model.getBizName(), model.getName(), model.getKey()));
        dataSet.setDescription(model.getDescription());
        dataSet.setQueryConfig(queryConfig);

        SemanticSchemaResp response = new SemanticSchemaResp();
        response.setDataSetId(dataSetId);
        response.setModelIds(List.of(modelId));
        response.setSchemaType(SchemaType.DATASET);
        response.setDataSetResp(dataSet);
        response.setDatabaseResp(database);
        response.setModelResps(new ArrayList<>(List.of(modelResp)));
        response.setDimensions(new ArrayList<>(dimensions));
        response.setMetrics(new ArrayList<>(metrics));
        response.setModelRelas(new ArrayList<>());
        return response;
    }

    /** 构造 translator 的隔离 Ontology，不注册到正式 SchemaManager。 */
    private Ontology buildOntology(String modelKey, DatabaseResp database, ModelResp model,
            List<DimSchemaResp> dimensions, List<MetricSchemaResp> metrics) {
        Ontology ontology = new Ontology();
        ontology.setDatabase(database);
        ontology.setModelMap(new LinkedHashMap<>(Map.of(modelKey, model)));
        ontology.setDimensionMap(
                new LinkedHashMap<>(Map.of(modelKey, new ArrayList<>(dimensions))));
        ontology.setMetricMap(new LinkedHashMap<>(Map.of(modelKey, new ArrayList<>(metrics))));
        ontology.setJoinRelations(new ArrayList<>());
        return ontology;
    }

    /** 将草稿聚合枚举转换为既有字段型指标表达式。 */
    private String metricExpression(MetricDraft metric) {
        if (StringUtils.isNotBlank(metric.getExpression())) {
            return metric.getExpression();
        }
        if ("COUNT_DISTINCT".equals(metric.getAggregation())) {
            return "COUNT(DISTINCT " + metric.getField() + ")";
        }
        return metric.getAggregation() + "(" + metric.getField() + ")";
    }

    /** 推导现有语义层维度类型。 */
    private DimensionType dimensionType(ModelDraft model, DimensionDraft dimension) {
        if (StringUtils.equalsIgnoreCase(model.getPrimaryTimeField(), dimension.getField())) {
            return DimensionType.partition_time;
        }
        if ("DATE".equalsIgnoreCase(dimension.getSemanticType())) {
            return DimensionType.time;
        }
        return DimensionType.categorical;
    }

    /** 推导现有语义层 SemanticType；TEXT 兼容为 CATEGORY。 */
    private String semanticType(String value) {
        if (StringUtils.isBlank(value) || "TEXT".equalsIgnoreCase(value)) {
            return "CATEGORY";
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /** 语义对象内部名称优先使用草稿 name。 */
    private static String semanticName(String name, String key) {
        return StringUtils.defaultIfBlank(name, key);
    }

    /** 依次选择首个非空展示名称。 */
    private static String display(String first, String second, String third) {
        return StringUtils.firstNonBlank(first, second, third);
    }

    /** 对可空列表安全退化为空列表。 */
    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 复制别名，防止 parser 修改草稿原集合。 */
    private static List<String> copy(List<String> values) {
        return new ArrayList<>(safe(values));
    }

    /** 规范草稿本地 key。 */
    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 单模型 parser/translator 隔离快照。 */
    public record DraftModelSemanticSchema(SemanticSchema semanticSchema,
            SemanticSchemaResp semanticSchemaResp, Ontology ontology, long dataSetId,
            String dataSetName) {}
}
