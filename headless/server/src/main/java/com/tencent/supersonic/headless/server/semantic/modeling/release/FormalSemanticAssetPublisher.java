package com.tencent.supersonic.headless.server.semantic.modeling.release;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.SensitiveLevelEnum;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.Field;
import com.tencent.supersonic.headless.api.pojo.FieldParam;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.MetricDefineByFieldParams;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.enums.DimensionType;
import com.tencent.supersonic.headless.api.pojo.enums.MetricDefineType;
import com.tencent.supersonic.headless.api.pojo.request.DimensionReq;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.request.TermReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DimensionDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricFilterDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.SensitiveFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermTargetDraft;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TermService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 草稿对象到现有正式语义管理服务的受控适配器。
 *
 * <p>
 * 职责说明：把已验证的单表草稿转换为现有 {@link ModelService}、{@link DimensionService}、 {@link MetricService} 和
 * {@link TermService} 请求，并通过同一组服务执行逆序删除。该类绝不访问 正式元数据 Mapper。并发说明：适配器无共享可变状态；发布幂等由发布步骤唯一键和对象 ext 中的
 * 服务端归属标记共同保证。
 * </p>
 */
@Component
public class FormalSemanticAssetPublisher {

    private static final String EXT_RELEASE_ID = "aiSemanticReleaseId";
    private static final String EXT_DRAFT_ID = "aiSemanticDraftId";
    private static final String EXT_OBJECT_KEY = "aiSemanticObjectKey";
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DatabaseService databaseService;
    private final ModelService modelService;
    private final DimensionService dimensionService;
    private final MetricService metricService;
    private final TermService termService;

    /**
     * 创建正式语义资产适配器。
     *
     * @param databaseService 数据源元数据服务。
     * @param modelService 现有模型管理服务。
     * @param dimensionService 现有维度管理服务。
     * @param metricService 现有指标管理服务。
     * @param termService 现有术语管理服务。
     */
    public FormalSemanticAssetPublisher(DatabaseService databaseService, ModelService modelService,
            DimensionService dimensionService, MetricService metricService,
            TermService termService) {
        this.databaseService = databaseService;
        this.modelService = modelService;
        this.dimensionService = dimensionService;
        this.metricService = metricService;
        this.termService = termService;
    }

    /**
     * 通过现有模型 API 创建单表模型。
     *
     * <p>
     * 调用示例：{@code publisher.createModel(releaseId, draft, model, user)}。物理字段始终从
     * 服务端数据源元数据重新读取，不信任草稿上传的字段类型。
     * </p>
     *
     * @param releaseId 发布 ID。
     * @param draft 草稿主记录。
     * @param model 已验证模型草稿。
     * @param user 发布管理员。
     * @return 正式模型响应。
     * @throws Exception 数据源元数据读取或现有模型 API 拒绝创建时抛出。
     */
    public ModelResp createModel(Long releaseId, SemanticModelingDraftDO draft, ModelDraft model,
            User user) throws Exception {
        TableReference table = resolveTable(draft, model.getBaseTable());
        DatabaseResp database = databaseService.getDatabase(draft.getDataSourceId(), user);
        List<DBColumn> columns = databaseService.getColumns(draft.getDataSourceId(),
                table.catalog(), table.database(), table.table());
        ModelDetail detail = new ModelDetail();
        detail.setQueryType("table_query");
        detail.setDbType(database == null ? null : database.getType());
        detail.setTableQuery(table.qualifiedName());
        detail.setFields(safe(columns).stream().map(column -> Field.builder()
                .fieldName(column.getColumnName()).dataType(column.getDataType()).build())
                .toList());

        ModelReq request = new ModelReq();
        request.setName(model.getName());
        request.setBizName(model.getBizName());
        request.setDescription(model.getDescription());
        request.setDatabaseId(draft.getDataSourceId());
        request.setDomainId(requireDomainId(draft));
        request.setIsOpen(0);
        request.setAdmins(List.of(user.getName()));
        request.setModelDetail(detail);
        request.setExt(ownership(releaseId, draft.getId(), model.getKey()));
        return modelService.createModel(request, user);
    }

    /**
     * 查询同一发布已创建但步骤结果未知的模型。
     *
     * @param releaseId 发布 ID。
     * @param draft 草稿主记录。
     * @param model 模型草稿。
     * @return 归属标记完全匹配的模型；未命中返回 null。
     */
    public ModelResp findOwnedModel(Long releaseId, SemanticModelingDraftDO draft,
            ModelDraft model) {
        MetaFilter filter = MetaFilter.builder().domainId(draft.getDomainId())
                .bizName(model.getBizName()).build();
        return safe(modelService.getModelList(filter)).stream()
                .filter(item -> StringUtils.equals(item.getBizName(), model.getBizName()))
                .filter(item -> ownedBy(item.getExt(), releaseId, draft.getId(), model.getKey()))
                .findFirst().orElse(null);
    }

    /**
     * 通过现有维度 API 创建维度。
     *
     * @param releaseId 发布 ID。
     * @param draft 草稿主记录。
     * @param model 所属模型草稿。
     * @param dimension 维度草稿。
     * @param modelId 正式模型 ID。
     * @param user 发布管理员。
     * @return 正式维度响应。
     * @throws Exception 现有维度 API 拒绝创建时抛出。
     */
    public DimensionResp createDimension(Long releaseId, SemanticModelingDraftDO draft,
            ModelDraft model, DimensionDraft dimension, Long modelId, User user) throws Exception {
        DimensionReq request = new DimensionReq();
        request.setName(dimension.getName());
        request.setBizName(dimension.getBizName());
        request.setDescription(dimension.getDescription());
        request.setModelId(modelId);
        request.setExpr(dimension.getField());
        request.setType(dimensionType(model, dimension).name());
        request.setSemanticType(semanticType(dimension.getSemanticType()));
        request.setAlias(String.join(",", safe(dimension.getAliases())));
        request.setSensitiveLevel(sensitiveLevel(model, dimension.getField()));
        request.setExt(ownership(releaseId, draft.getId(), dimension.getKey()));
        return dimensionService.createDimension(request, user);
    }

    /**
     * 查询同一发布已创建但步骤结果未知的维度。
     *
     * @param releaseId 发布 ID。
     * @param draftId 草稿 ID。
     * @param dimension 维度草稿。
     * @param modelId 正式模型 ID。
     * @return 归属标记完全匹配的维度；未命中返回 null。
     */
    public DimensionResp findOwnedDimension(Long releaseId, Long draftId, DimensionDraft dimension,
            Long modelId) {
        DimensionResp existing = dimensionService.getDimension(dimension.getBizName(), modelId);
        return existing != null
                && ownedBy(existing.getExt(), releaseId, draftId, dimension.getKey()) ? existing
                        : null;
    }

    /**
     * 通过现有指标 API 创建字段型聚合指标。
     *
     * @param releaseId 发布 ID。
     * @param draft 草稿主记录。
     * @param model 所属模型草稿。
     * @param metric 指标草稿。
     * @param modelId 正式模型 ID。
     * @param user 发布管理员。
     * @return 正式指标响应。
     * @throws Exception 结构化过滤条件非法或现有指标 API 拒绝创建时抛出。
     */
    public MetricResp createMetric(Long releaseId, SemanticModelingDraftDO draft, ModelDraft model,
            MetricDraft metric, Long modelId, User user) throws Exception {
        MetricDefineByFieldParams definition = new MetricDefineByFieldParams();
        definition.setExpr(metricExpression(metric));
        definition.setFilterSql(buildFilterSql(metric.getFilters()));
        if (!"*".equals(metric.getField())) {
            definition.setFields(List.of(new FieldParam(metric.getField())));
        }

        MetricReq request = new MetricReq();
        request.setName(metric.getName());
        request.setBizName(metric.getBizName());
        request.setDescription(metric.getDescription());
        request.setModelId(modelId);
        request.setAlias(String.join(",", safe(metric.getAliases())));
        request.setMetricDefineType(MetricDefineType.FIELD);
        request.setMetricDefineByFieldParams(definition);
        request.setSensitiveLevel(sensitiveLevel(model, metric.getField()));
        request.setExt(ownership(releaseId, draft.getId(), metric.getKey()));
        return metricService.createMetric(request, user);
    }

    /**
     * 查询同一发布已创建但步骤结果未知的指标。
     *
     * @param releaseId 发布 ID。
     * @param draftId 草稿 ID。
     * @param metric 指标草稿。
     * @param modelId 正式模型 ID。
     * @return 归属标记完全匹配的指标；未命中返回 null。
     */
    public MetricResp findOwnedMetric(Long releaseId, Long draftId, MetricDraft metric,
            Long modelId) {
        MetricResp existing = metricService.getMetric(modelId, metric.getBizName());
        return existing != null && ownedBy(existing.getExt(), releaseId, draftId, metric.getKey())
                ? existing
                : null;
    }

    /**
     * 通过现有术语 API 创建域级术语。
     *
     * @param draft 草稿主记录。
     * @param term 术语草稿。
     * @param objectIds 草稿对象 key 到正式 ID 的映射。
     * @param user 发布管理员。
     * @return 正式术语响应。
     */
    public TermResp createTerm(SemanticModelingDraftDO draft, TermDraft term,
            Map<String, Long> objectIds, User user) {
        TermReq request = new TermReq();
        request.setDomainId(requireDomainId(draft));
        request.setName(term.getName());
        request.setDescription(term.getDescription());
        request.setAlias(new ArrayList<>(safe(term.getAliases())));
        request.setRelatedMetrics(targetIds(term, objectIds, SemanticReleaseConstants.TYPE_METRIC));
        request.setRelateDimensions(
                targetIds(term, objectIds, SemanticReleaseConstants.TYPE_DIMENSION));
        return termService.saveOrUpdate(request, user);
    }

    /**
     * 识别当前发布调用成功但步骤结果未知的术语。
     *
     * <p>
     * 现有术语对象没有 ext 扩展字段，因此仅在名称、创建人和创建时间窗口全部匹配时恢复； 任一条件不满足都返回 null，避免认领历史同名术语。
     * </p>
     *
     * @param draft 草稿主记录。
     * @param term 术语草稿。
     * @param user 发布管理员。
     * @param releaseCreatedAt 发布记录创建时间。
     * @return 可确认属于本次发布的术语，否则返回 null。
     */
    public TermResp findOwnedTerm(SemanticModelingDraftDO draft, TermDraft term, User user,
            Date releaseCreatedAt) {
        return safe(termService.getTerms(requireDomainId(draft), term.getName())).stream()
                .filter(item -> StringUtils.equals(item.getName(), term.getName()))
                .filter(item -> StringUtils.equalsIgnoreCase(item.getCreatedBy(), user.getName()))
                .filter(item -> item.getCreatedAt() != null && releaseCreatedAt != null
                        && !item.getCreatedAt().before(releaseCreatedAt))
                .findFirst().orElse(null);
    }

    /**
     * 通过现有语义管理删除 API 逆序删除一个 AI 新增对象。
     *
     * @param type 对象类型。
     * @param targetId 发布步骤记录的正式对象 ID。
     * @param user 回滚管理员。
     * @throws Exception 删除 API 拒绝操作时抛出。
     */
    public void delete(String type, Long targetId, User user) throws Exception {
        switch (type) {
            case SemanticReleaseConstants.TYPE_TERM -> termService.delete(targetId);
            case SemanticReleaseConstants.TYPE_METRIC -> metricService.deleteMetric(targetId, user);
            case SemanticReleaseConstants.TYPE_DIMENSION -> dimensionService
                    .deleteDimension(targetId, user);
            case SemanticReleaseConstants.TYPE_MODEL -> modelService.deleteModel(targetId, user);
            default -> throw new IllegalArgumentException("不支持的回滚对象类型");
        }
    }

    /** 解析并校验主题域；正式语义对象不允许发布到空主题域。 */
    private Long requireDomainId(SemanticModelingDraftDO draft) {
        if (draft.getDomainId() == null) {
            throw new IllegalArgumentException("发布草稿缺少目标主题域");
        }
        return draft.getDomainId();
    }

    /** 从服务端草稿元信息与模型基表构造元数据读取位置。 */
    private TableReference resolveTable(SemanticModelingDraftDO draft, String baseTable) {
        String[] parts = StringUtils.split(StringUtils.trimToEmpty(baseTable), '.');
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("模型基表不能为空");
        }
        String catalog = draft.getCatalogName();
        String database = draft.getDatabaseName();
        String table = parts[parts.length - 1];
        if (parts.length == 2) {
            database = parts[0];
        } else if (parts.length >= 3) {
            catalog = parts[parts.length - 3];
            database = parts[parts.length - 2];
        }
        return new TableReference(catalog, database, table);
    }

    /** 推导现有维度类型；主时间字段优先作为分区时间维度。 */
    private DimensionType dimensionType(ModelDraft model, DimensionDraft dimension) {
        if (StringUtils.equalsIgnoreCase(model.getPrimaryTimeField(), dimension.getField())) {
            return DimensionType.partition_time;
        }
        return "DATE".equalsIgnoreCase(dimension.getSemanticType()) ? DimensionType.time
                : DimensionType.categorical;
    }

    /** 将草稿语义类型映射到现有 CATEGORY/DATE 等字符串。 */
    private String semanticType(String value) {
        return StringUtils.isBlank(value) || "TEXT".equalsIgnoreCase(value) ? "CATEGORY"
                : value.toUpperCase(Locale.ROOT);
    }

    /** 按字段读取发布前已人工确认的最高敏感级别。 */
    private int sensitiveLevel(ModelDraft model, String field) {
        return safe(model.getSensitiveFields()).stream()
                .filter(item -> StringUtils.equalsIgnoreCase(item.getField(), field))
                .map(SensitiveFieldDraft::getLevel).map(this::sensitiveLevel)
                .max(Integer::compareTo).orElse(SensitiveLevelEnum.LOW.getCode());
    }

    /** 将草稿 LOW/MEDIUM/HIGH 映射到现有敏感级别编码。 */
    private int sensitiveLevel(String value) {
        if ("HIGH".equalsIgnoreCase(value)) {
            return SensitiveLevelEnum.HIGH.getCode();
        }
        if ("MEDIUM".equalsIgnoreCase(value) || "MID".equalsIgnoreCase(value)) {
            return SensitiveLevelEnum.MID.getCode();
        }
        return SensitiveLevelEnum.LOW.getCode();
    }

    /** 生成阶段 3 已限制为单字段聚合的规范表达式。 */
    private String metricExpression(MetricDraft metric) {
        if (StringUtils.isNotBlank(metric.getExpression())) {
            return metric.getExpression();
        }
        if ("COUNT_DISTINCT".equals(metric.getAggregation())) {
            return "COUNT(DISTINCT " + metric.getField() + ")";
        }
        return metric.getAggregation() + "(" + metric.getField() + ")";
    }

    /**
     * 从结构化过滤条件构造语义指标 filterSql。
     *
     * <p>
     * 字段名必须满足保守标识符白名单，运算符通过固定映射生成，值统一按 SQL 字符串字面量 转义；因此不会把任意 SQL 片段从草稿透传到正式元数据。
     * </p>
     */
    private String buildFilterSql(List<MetricFilterDraft> filters) {
        return safe(filters).stream().map(this::filterExpression)
                .collect(Collectors.joining(" AND "));
    }

    /** 将单个结构化过滤条件转换为白名单 SQL 片段。 */
    private String filterExpression(MetricFilterDraft filter) {
        String field = StringUtils.trimToEmpty(filter.getField());
        if (!SAFE_FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("指标过滤字段不合法");
        }
        String operator = StringUtils.upperCase(filter.getOperator(), Locale.ROOT);
        List<String> values = safe(filter.getValues());
        return switch (operator) {
            case "IS_NULL" -> field + " IS NULL";
            case "IS_NOT_NULL" -> field + " IS NOT NULL";
            case "EQ", "NE", "GT", "GTE", "LT", "LTE" -> field + " " + scalarOperator(operator)
                    + " " + literal(singleValue(values));
            case "IN", "NOT_IN" -> field + ("IN".equals(operator) ? " IN (" : " NOT IN (")
                    + requireValues(values).stream().map(this::literal)
                            .collect(Collectors.joining(", "))
                    + ")";
            case "BETWEEN" -> field + " BETWEEN " + literal(valueAt(values, 0)) + " AND "
                    + literal(valueAt(values, 1));
            default -> throw new IllegalArgumentException("指标过滤运算符不受支持");
        };
    }

    /** 映射固定标量比较运算符。 */
    private String scalarOperator(String operator) {
        return Map.of("EQ", "=", "NE", "<>", "GT", ">", "GTE", ">=", "LT", "<", "LTE", "<=")
                .get(operator);
    }

    /** 读取恰好一个值。 */
    private String singleValue(List<String> values) {
        if (values.size() != 1) {
            throw new IllegalArgumentException("指标过滤条件需要且只能包含一个值");
        }
        return values.get(0);
    }

    /** 校验 IN 条件至少包含一个值。 */
    private List<String> requireValues(List<String> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("IN 指标过滤条件至少需要一个值");
        }
        return values;
    }

    /** 读取固定位置值并提供统一边界错误。 */
    private String valueAt(List<String> values, int index) {
        if (values.size() != 2) {
            throw new IllegalArgumentException("BETWEEN 指标过滤条件必须包含两个值");
        }
        return values.get(index);
    }

    /** 生成经过单引号转义的 SQL 字符串字面量。 */
    private String literal(String value) {
        return "'" + StringUtils.defaultString(value).replace("'", "''") + "'";
    }

    /** 按目标类型解析术语引用，缺失引用会在发布前验证阶段阻塞。 */
    private List<Long> targetIds(TermDraft term, Map<String, Long> objectIds, String type) {
        return safe(term.getTargets()).stream()
                .filter(target -> type.equalsIgnoreCase(target.getType()))
                .map(TermTargetDraft::getObjectKey).map(FormalSemanticAssetPublisher::normalize)
                .map(objectIds::get).filter(Objects::nonNull).distinct().toList();
    }

    /** 构造服务端发布归属标记，用于故障后安全识别已创建对象。 */
    private Map<String, Object> ownership(Long releaseId, Long draftId, String objectKey) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put(EXT_RELEASE_ID, releaseId);
        ext.put(EXT_DRAFT_ID, draftId);
        ext.put(EXT_OBJECT_KEY, objectKey);
        return ext;
    }

    /** 验证既有对象是否确由当前发布创建，避免把并发创建的同名对象误认领。 */
    private boolean ownedBy(Map<String, Object> ext, Long releaseId, Long draftId,
            String objectKey) {
        if (ext == null) {
            return false;
        }
        return Objects.equals(String.valueOf(releaseId), String.valueOf(ext.get(EXT_RELEASE_ID)))
                && Objects.equals(String.valueOf(draftId), String.valueOf(ext.get(EXT_DRAFT_ID)))
                && Objects.equals(normalize(objectKey),
                        normalize(String.valueOf(ext.get(EXT_OBJECT_KEY))));
    }

    /** 对可空集合安全退化为空集合。 */
    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 规范草稿对象 key，供术语关联和归属判断使用。 */
    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 物理表定位信息；qualifiedName 只由服务端元数据与已验证表名组成。 */
    private record TableReference(String catalog, String database, String table) {
        private String qualifiedName() {
            return Stream.of(catalog, database, table).filter(StringUtils::isNotBlank)
                    .collect(Collectors.joining("."));
        }
    }
}
