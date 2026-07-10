package com.tencent.supersonic.headless.server.semantic.modeling;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.schema.ValidateResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.persistence.dataobject.LlmModelCapabilityDO;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TermService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 语义建模生成上下文构建器。
 *
 * <p>
 * 职责说明：创建前复核数据源 ACL、JSON 模型能力、Gap 归属和真实表字段；生成时按需读取最多三行 样例并在服务端脱敏，再组装受字符上限保护的
 * Prompt。既有主题域上下文仅包含当前用户对其具有 ADMIN 权限的模型及批量读取的维度、指标；术语则限定为主题域管理员可见的全量术语，或与已授权维度、指标有关联的术语。
 * 并发说明：本组件无请求级共享状态，固定 JsonNode 只读复用。
 * </p>
 */
@Component
public class ModelingDraftContextBuilder {

    private static final String SCHEMA_RESOURCE = "schema/semantic-modeling-draft-v1.json";
    private static final String EXAMPLE_RESOURCE = "schema/semantic-modeling-draft-v1-example.json";
    private static final String MASKED = "[MASKED]";
    private static final int SAMPLE_VALUE_MAX_LENGTH = 256;
    private static final int EXISTING_ASSET_TOP_K_PER_TYPE = 20;
    private static final Pattern SENSITIVE_COLUMN = Pattern
            .compile("(?i).*(password|passwd|pwd|secret|token|credential|api[_-]?key|authorization|"
                    + "cookie|session|phone|mobile|email|"
                    + "id[_-]?card|ssn|address|customer[_-]?name|user[_-]?name|real[_-]?name|"
                    + "full[_-]?name|first[_-]?name|last[_-]?name|姓名|手机号|电话|邮箱|身份证|" + "地址|密钥).*");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(?:1[3-9]\\d{9}|[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9X]|[^@\\s]+@[^@\\s]+\\.[^@\\s]+|"
                    + "bearer\\s+[A-Za-z0-9._~+/=-]+|(?:sk|rk)-[A-Za-z0-9_-]{8,}|"
                    + "(?:eyJ[A-Za-z0-9_-]+\\.){2}[A-Za-z0-9_-]+|"
                    + "(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|"
                    + "secret|password|passwd|pwd)\\s*[:=]\\s*[\"']?[A-Za-z0-9._~+/=-]{6,})");

    private final ObjectMapper objectMapper;
    private final DatabaseService databaseService;
    private final SemanticGapMapper gapMapper;
    private final LlmConversationGatewayService gatewayService;
    private final DomainService domainService;
    private final ModelService modelService;
    private final DimensionService dimensionService;
    private final MetricService metricService;
    private final TermService termService;
    private final SemanticModelingProperties properties;
    private final JsonNode schemaNode;
    private final JsonNode exampleNode;
    private final String outputContract;

    /**
     * 创建上下文构建器并加载固定输出 Schema。
     *
     * @param objectMapper 项目 JSON 映射器。
     * @param databaseService 数据源服务。
     * @param gapMapper 缺口 Mapper。
     * @param gatewayService 阶段 1 LLM Gateway。
     * @param domainService 主题域只读服务。
     * @param modelService 模型只读服务。
     * @param dimensionService 维度只读服务。
     * @param metricService 指标只读服务。
     * @param termService 术语只读服务。
     * @param properties 阶段 3 安全限制。
     */
    public ModelingDraftContextBuilder(ObjectMapper objectMapper, DatabaseService databaseService,
            SemanticGapMapper gapMapper, LlmConversationGatewayService gatewayService,
            DomainService domainService, ModelService modelService,
            DimensionService dimensionService, MetricService metricService, TermService termService,
            SemanticModelingProperties properties) {
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.gapMapper = gapMapper;
        this.gatewayService = gatewayService;
        this.domainService = domainService;
        this.modelService = modelService;
        this.dimensionService = dimensionService;
        this.metricService = metricService;
        this.termService = termService;
        this.properties = properties;
        this.schemaNode = loadSchema(objectMapper);
        this.exampleNode = loadJsonResource(objectMapper, EXAMPLE_RESOURCE);
        validateExample(schemaNode, exampleNode);
        this.outputContract = buildOutputContract(exampleNode);
    }

    /**
     * 在草稿落库前完成权限和真实元数据预检。
     *
     * <p>
     * 调用示例：{@code snapshot = contextBuilder.preflight(request, user)}。客户端上传的表名只用于选择；
     * 返回快照中的表名和字段完全来自数据源元数据。
     * </p>
     *
     * @param request 创建请求，会被规范化为服务端真实表名。
     * @param user 当前用户。
     * @return 可安全提交给异步 Worker 的只读上下文快照。
     * @throws ModelingDraftException 数据源越权、未知表/字段上限或模型能力不满足时抛出。
     */
    public PreflightSnapshot preflight(ModelingDraftGenerateReq request, User user) {
        normalizeAndValidateSource(request);
        verifyDataSourceAccess(request.getDataSourceId(), user);
        LlmModelCapabilityDO capability = verifyJsonCapability(request.getChatModelId(), user);
        SemanticGapDO gap = loadAndValidateGap(request);

        try {
            List<String> availableTables = databaseService.getTables(request.getDataSourceId(),
                    request.getCatalogName(), request.getDatabaseName());
            Map<String, String> canonicalTables =
                    availableTables.stream().filter(StringUtils::isNotBlank)
                            .collect(Collectors.toMap(ModelingDraftContextBuilder::normalize,
                                    Function.identity(), (left, right) -> left,
                                    LinkedHashMap::new));
            List<String> selectedTables =
                    canonicalizeSelectedTables(request.getSelectedTables(), canonicalTables);
            request.setSelectedTables(selectedTables);

            ModelBuildReq modelBuildReq = new ModelBuildReq();
            modelBuildReq.setDatabaseId(request.getDataSourceId());
            modelBuildReq.setCatalog(request.getCatalogName());
            modelBuildReq.setDb(request.getDatabaseName());
            modelBuildReq.setTables(selectedTables);
            Map<String, List<DBColumn>> columns = databaseService.getDbColumns(modelBuildReq);
            int totalColumns = columns.values().stream().filter(Objects::nonNull)
                    .mapToInt(Collection::size).sum();
            int maxColumns = Math.min(300, properties.getMaxColumns());
            if (totalColumns > maxColumns) {
                throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE,
                        "选表字段总数超过 " + maxColumns + " 列，请缩小范围");
            }

            ExistingSemanticContext existing = loadExistingContext(request.getDomainId(), user);
            Map<String, Object> rankedSummary = rankExistingSummary(existing.summary(),
                    request.getBusinessGoal(), selectedTables);
            return new PreflightSnapshot(copyRequest(request), Map.copyOf(columns),
                    existing.names(), rankedSummary, buildGapContext(gap),
                    capability.getMaxContextTokens());
        } catch (ModelingDraftException exception) {
            throw exception;
        } catch (SQLException | RuntimeException exception) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST,
                    "无法读取选表元数据，请确认 catalog、database 和表选择");
        }
    }

    /**
     * 为异步模型调用构建最终 Prompt。
     *
     * @param snapshot 创建事务前完成的可信元数据快照。
     * @param user 原创建用户，用于采样时再次复核数据源 ACL。
     * @return Gateway 请求所需的 Prompt、JSON Schema 和校验上下文。
     * @throws ModelingDraftException 上下文在降级后仍超限时抛出。
     */
    public GenerationContext build(PreflightSnapshot snapshot, User user) {
        ModelingDraftGenerateReq request = snapshot.request();
        Map<String, List<Map<String, Object>>> samples =
                request.getIncludeSampleData() ? loadMaskedSamples(request, user) : Map.of();
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(snapshot, samples, true, true);
        int contextCharacterBudget = resolveContextCharacterBudget(snapshot.maxContextTokens());

        if (exceedsContextBudget(systemPrompt, userPrompt, contextCharacterBudget)
                && !samples.isEmpty()) {
            // 样例是最低优先级上下文，超限时先退回 schema-only。
            userPrompt = buildUserPrompt(snapshot, Map.of(), true, true);
        }
        if (exceedsContextBudget(systemPrompt, userPrompt, contextCharacterBudget)) {
            // 相似问法历史优先级低于当前问题、Schema 和既有资产冲突上下文。
            userPrompt = buildUserPrompt(snapshot, Map.of(), true, false);
        }
        if (exceedsContextBudget(systemPrompt, userPrompt, contextCharacterBudget)) {
            // 既有资产只用于避免冲突，再超限时移除，不影响真实选表 Schema。
            userPrompt = buildUserPrompt(snapshot, Map.of(), false, false);
        }
        if (exceedsContextBudget(systemPrompt, userPrompt, contextCharacterBudget)) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, "建模上下文超过安全上限，请减少选表或缩短业务目标");
        }
        return new GenerationContext(systemPrompt, userPrompt, outputContract,
                schemaNode.deepCopy(), snapshot.columnsByTable(), snapshot.existingNames());
    }

    /**
     * 人工保存前重新读取表字段与既有资产名称。
     *
     * @param dataSourceId 数据源 ID。
     * @param catalogName catalog。
     * @param databaseName database/schema。
     * @param selectedTables 草稿创建时保存的选表。
     * @param domainId 可选主题域 ID。
     * @param user 当前保存用户。
     * @return 最新字段映射和既有对象名称。
     * @throws ModelingDraftException 数据源越权、表已删除或字段总数超限时抛出。
     */
    public ValidationContext reloadValidationContext(Long dataSourceId, String catalogName,
            String databaseName, List<String> selectedTables, Long domainId, User user) {
        verifyDataSourceAccess(dataSourceId, user);
        try {
            List<String> availableTables =
                    databaseService.getTables(dataSourceId, catalogName, databaseName);
            Map<String, String> canonicalTables =
                    availableTables.stream().filter(StringUtils::isNotBlank)
                            .collect(Collectors.toMap(ModelingDraftContextBuilder::normalize,
                                    Function.identity(), (left, right) -> left,
                                    LinkedHashMap::new));
            List<String> canonical = canonicalizeSelectedTables(selectedTables, canonicalTables);
            ModelBuildReq modelBuildReq = new ModelBuildReq();
            modelBuildReq.setDatabaseId(dataSourceId);
            modelBuildReq.setCatalog(catalogName);
            modelBuildReq.setDb(databaseName);
            modelBuildReq.setTables(canonical);
            Map<String, List<DBColumn>> columns = databaseService.getDbColumns(modelBuildReq);
            int totalColumns = columns.values().stream().filter(Objects::nonNull)
                    .mapToInt(Collection::size).sum();
            if (totalColumns > Math.min(300, properties.getMaxColumns())) {
                throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, "当前选表字段总数已超过草稿安全上限");
            }
            ExistingSemanticContext existing = loadExistingContext(domainId, user);
            return new ValidationContext(Map.copyOf(columns), existing.names());
        } catch (ModelingDraftException exception) {
            throw exception;
        } catch (SQLException | RuntimeException exception) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "无法重新读取草稿选表元数据，请刷新数据源后重试");
        }
    }

    /** 规范化来源类型并执行条件必填校验。 */
    private void normalizeAndValidateSource(ModelingDraftGenerateReq request) {
        String sourceType =
                StringUtils.upperCase(StringUtils.trim(request.getSourceType()), Locale.ROOT);
        request.setSourceType(sourceType);
        if (!Set.of(ModelingDraftConstants.SOURCE_SEMANTIC_GAP,
                ModelingDraftConstants.SOURCE_DATA_SOURCE).contains(sourceType)) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "不支持的草稿来源类型");
        }
        if (ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(sourceType)
                && request.getSourceId() == null) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "Gap 来源必须提供 sourceId");
        }
        if (ModelingDraftConstants.SOURCE_DATA_SOURCE.equals(sourceType)) {
            request.setSourceId(null);
        }
        int maxTables = Math.min(10, properties.getMaxTables());
        if (request.getSelectedTables() == null || request.getSelectedTables().isEmpty()
                || request.getSelectedTables().size() > maxTables) {
            throw new ModelingDraftException(HttpStatus.BAD_REQUEST,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "必须选择 1 至 " + maxTables + " 张表");
        }
    }

    /** 复核数据源 ACL，并统一转换越权响应。 */
    private void verifyDataSourceAccess(Long databaseId, User user) {
        try {
            if (databaseService.getDatabase(databaseId, user) == null) {
                throw new IllegalArgumentException("Database not found");
            }
        } catch (RuntimeException exception) {
            throw new ModelingDraftException(HttpStatus.FORBIDDEN,
                    ModelingDraftConstants.ERROR_ACCESS_DENIED, "无权访问所选数据源");
        }
    }

    /** 确保所选模型对当前用户可见、启用且支持 JSON mode。 */
    private LlmModelCapabilityDO verifyJsonCapability(Integer chatModelId, User user) {
        try {
            LlmModelCapabilityDO supported = gatewayService.listCapabilities(user).stream()
                    .filter(capability -> Objects.equals(capability.getChatModelId(), chatModelId))
                    .filter(this::isJsonCapable).findFirst().orElse(null);
            if (supported != null) {
                return supported;
            }
        } catch (RuntimeException ignored) {
            // 对前端只暴露统一能力错误，不泄露模型配置或 Provider 细节。
        }
        throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                ModelingDraftConstants.ERROR_INVALID_REQUEST, "请选择当前用户可用且支持 JSON 输出的 LLM");
    }

    /** 判断 Gateway 能力是否满足结构化草稿要求。 */
    private boolean isJsonCapable(LlmModelCapabilityDO capability) {
        return Boolean.TRUE.equals(capability.getEnabled())
                && Boolean.TRUE.equals(capability.getSupportJsonMode());
    }

    /** 读取并核对 Gap 上下文，缺失的数据源和主题域允许由管理员补选。 */
    private SemanticGapDO loadAndValidateGap(ModelingDraftGenerateReq request) {
        if (!ModelingDraftConstants.SOURCE_SEMANTIC_GAP.equals(request.getSourceType())) {
            return null;
        }
        SemanticGapDO gap = gapMapper.selectById(request.getSourceId());
        if (gap == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "语义缺口不存在");
        }
        if (gap.getDataSourceId() != null
                && !Objects.equals(gap.getDataSourceId(), request.getDataSourceId())) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "补选数据源与缺口记录的数据源不一致");
        }
        if (gap.getDomainId() != null && request.getDomainId() != null
                && !Objects.equals(gap.getDomainId(), request.getDomainId())) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_INVALID_REQUEST, "补选主题域与缺口记录的主题域不一致");
        }
        if (request.getDomainId() == null) {
            request.setDomainId(gap.getDomainId());
        }
        return gap;
    }

    /** 将前端选表映射为服务端元数据的真实名称，并去重保持顺序。 */
    private List<String> canonicalizeSelectedTables(List<String> requested,
            Map<String, String> available) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String table : requested) {
            String canonical = available.get(normalize(table));
            if (canonical == null) {
                throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ModelingDraftConstants.ERROR_INVALID_REQUEST,
                        "选中的表不在服务端读取到的元数据中：" + StringUtils.abbreviate(table, 64));
            }
            result.add(canonical);
        }
        return List.copyOf(result);
    }

    /**
     * 批量读取当前用户可安全提供给 LLM 的既有语义上下文。
     *
     * <p>
     * 模型、维度和指标仅来自当前用户具有 ADMIN 权限的模型。主题域管理员可使用本域全部术语。仅有模型管理权时，术语必须与已授权维度或指标 ID 有交集。
     * 所有对象均按集合批量查询，不在术语循环中访问数据库。
     * </p>
     */
    private ExistingSemanticContext loadExistingContext(Long domainId, User user) {
        if (domainId == null) {
            return ExistingSemanticContext.empty();
        }

        List<DomainResp> administrativeDomains = Objects
                .requireNonNullElse(domainService.getDomainListWithAdminAuth(user), List.of());
        DomainResp domain = administrativeDomains.stream()
                .filter(item -> Objects.equals(item.getId(), domainId)).findFirst().orElse(null);
        // getDomainListWithAdminAuth 也会包含仅因模型管理权而可见的主题域，因此必须以
        // hasEditPermission 区分真正的主题域管理员，不能把列表命中直接当作域级授权。
        boolean hasDomainAdminPermission = domain != null && domain.isHasEditPermission();

        List<ModelResp> models = Objects.requireNonNullElse(
                modelService.getModelListWithAuth(user, domainId, AuthType.ADMIN), List.of());
        List<Long> modelIds =
                models.stream().map(ModelResp::getId).filter(Objects::nonNull).toList();
        List<DimensionResp> dimensions = List.of();
        List<MetricResp> metrics = List.of();
        if (!modelIds.isEmpty()) {
            // 一个 MetaFilter 批量读取全部授权模型的对象，避免按模型或术语逐项查询。
            MetaFilter filter = new MetaFilter(modelIds);
            dimensions =
                    Objects.requireNonNullElse(dimensionService.getDimensions(filter), List.of());
            metrics = Objects.requireNonNullElse(metricService.getMetrics(filter), List.of());
        }

        Map<Long, List<TermResp>> termSets =
                Objects.requireNonNullElse(termService.getTermSets(Set.of(domainId)), Map.of());
        List<TermResp> termsForDomain = termSets.get(domainId);
        List<TermResp> domainTerms = termsForDomain == null ? List.of()
                : termsForDomain.stream().filter(Objects::nonNull).toList();
        List<TermResp> terms =
                filterAuthorizedTerms(domainTerms, dimensions, metrics, hasDomainAdminPermission);

        Set<String> names = new HashSet<>();
        List<Map<String, Object>> modelSummary = models.stream()
                .map(model -> summary(model.getId(), model.getName(), model.getBizName(), null))
                .toList();
        List<Map<String, Object>> dimensionSummary =
                dimensions.stream().map(dimension -> summary(dimension.getId(), dimension.getName(),
                        dimension.getBizName(), dimension.getAlias())).toList();
        List<Map<String, Object>> metricSummary =
                metrics.stream().map(metric -> summary(metric.getId(), metric.getName(),
                        metric.getBizName(), metric.getAlias())).toList();
        List<Map<String, Object>> termSummary = terms.stream()
                .map(term -> summary(term.getId(), term.getName(), null,
                        term.getAlias() == null ? null : String.join(",", term.getAlias())))
                .toList();
        collectNames(names, modelSummary, dimensionSummary, metricSummary, termSummary);

        Map<String, Object> summary = new LinkedHashMap<>();
        if (domain != null) {
            summary.put("targetDomain",
                    summary(domain.getId(), domain.getName(), domain.getBizName(), null));
        }
        summary.put("models", modelSummary);
        summary.put("dimensions", dimensionSummary);
        summary.put("metrics", metricSummary);
        summary.put("terms", termSummary);
        return new ExistingSemanticContext(Set.copyOf(names), Map.copyOf(summary));
    }

    /**
     * 按业务目标和选表相关性为每类既有资产保留确定性 Top-K。
     *
     * <p>
     * 该裁剪只影响进入 LLM 的摘要；{@code existingNames} 仍保留全部已授权名称用于本地冲突校验。 Java 的稳定排序保证同分对象维持数据库批量读取顺序，便于复现
     * Prompt。
     * </p>
     */
    private Map<String, Object> rankExistingSummary(Map<String, Object> summary,
            String businessGoal, List<String> selectedTables) {
        if (summary.isEmpty()) {
            return Map.of();
        }
        String relevanceContext = normalize(
                StringUtils.defaultString(businessGoal) + " " + String.join(" ", selectedTables));
        Map<String, Object> rankedSummary = new LinkedHashMap<>();
        if (summary.containsKey("targetDomain")) {
            rankedSummary.put("targetDomain", summary.get("targetDomain"));
        }
        for (String group : List.of("models", "dimensions", "metrics", "terms")) {
            Object rawGroup = summary.get(group);
            if (!(rawGroup instanceof List<?> rawItems)) {
                continue;
            }
            List<Map<String, Object>> items =
                    rawItems.stream().filter(Map.class::isInstance).map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) item;
                        return cast;
                    }).collect(Collectors.toCollection(ArrayList::new));
            items.sort((left, right) -> Integer.compare(relevanceScore(right, relevanceContext),
                    relevanceScore(left, relevanceContext)));
            rankedSummary.put(group, List.copyOf(
                    items.subList(0, Math.min(EXISTING_ASSET_TOP_K_PER_TYPE, items.size()))));
        }
        return Collections.unmodifiableMap(rankedSummary);
    }

    /** 对名称、业务名和别名做直接命中及双字符片段命中评分。 */
    private int relevanceScore(Map<String, Object> item, String relevanceContext) {
        int score = 0;
        for (String field : List.of("name", "bizName", "aliases")) {
            Object raw = item.get(field);
            if (raw == null || StringUtils.isBlank(raw.toString())) {
                continue;
            }
            String candidate = normalize(raw.toString());
            if (relevanceContext.contains(candidate)) {
                score += 1_000 + candidate.length();
            }
            for (int index = 0; index + 2 <= candidate.length(); index++) {
                if (relevanceContext.contains(candidate.substring(index, index + 2))) {
                    score += 10;
                }
            }
        }
        return score;
    }

    /** 根据模型上下文窗口为首次生成和一次修复预留输出空间。 */
    private int resolveContextCharacterBudget(Integer maxContextTokens) {
        int configuredLimit = Math.max(1, properties.getMaxContextCharacters());
        if (maxContextTokens == null || maxContextTokens <= 0) {
            return configuredLimit;
        }
        long reservedOutputTokens = Math.max(1, properties.getMaxOutputTokens()) * 2L;
        long modelBudget = maxContextTokens.longValue() - reservedOutputTokens;
        if (modelBudget <= 0) {
            throw new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, "所选模型上下文窗口不足以预留生成和修复输出空间");
        }
        return (int) Math.min(configuredLimit, modelBudget);
    }

    /** 把 Gateway 临时注入的完整 Schema 也计入保守字符预算。 */
    private boolean exceedsContextBudget(String systemPrompt, String userPrompt, int budget) {
        long usedCharacters =
                (long) systemPrompt.length() + userPrompt.length() + schemaNode.toString().length();
        return usedCharacters > budget;
    }

    /**
     * 按主题域或模型对象管理权限筛选可进入 LLM 上下文的术语。
     *
     * @param domainTerms 已一次性读取的主题域术语。
     * @param dimensions 当前用户 ADMIN 模型下的维度。
     * @param metrics 当前用户 ADMIN 模型下的指标。
     * @param hasDomainAdminPermission 是否具有目标主题域的编辑管理权。
     * @return 主题域管理员的全量术语，或与已授权维度、指标有交集的术语。
     */
    private List<TermResp> filterAuthorizedTerms(List<TermResp> domainTerms,
            List<DimensionResp> dimensions, List<MetricResp> metrics,
            boolean hasDomainAdminPermission) {
        if (hasDomainAdminPermission) {
            return domainTerms;
        }
        Set<Long> dimensionIds = dimensions.stream().map(DimensionResp::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> metricIds = metrics.stream().map(MetricResp::getId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 空关联术语和仅关联其他模型对象的术语都不能进入 Prompt；任一授权关联命中即可保留。
        return domainTerms.stream()
                .filter(term -> hasIntersection(term.getRelateDimensions(), dimensionIds)
                        || hasIntersection(term.getRelatedMetrics(), metricIds))
                .toList();
    }

    /** 判断术语关联 ID 与授权对象 ID 是否至少有一项交集。 */
    private boolean hasIntersection(Collection<Long> relatedIds, Set<Long> authorizedIds) {
        return relatedIds != null && !relatedIds.isEmpty() && !authorizedIds.isEmpty()
                && relatedIds.stream().filter(Objects::nonNull).anyMatch(authorizedIds::contains);
    }

    /** 构造不含表达式和正式写入字段的既有对象摘要。 */
    private Map<String, Object> summary(Long id, String name, String bizName, String aliases) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        value.put("bizName", bizName);
        value.put("aliases", aliases);
        return value;
    }

    /** 汇总既有对象名和逗号分隔别名，用于冲突不确定项。 */
    @SafeVarargs
    private final void collectNames(Set<String> target, List<Map<String, Object>>... groups) {
        for (List<Map<String, Object>> group : groups) {
            for (Map<String, Object> item : group) {
                addName(target, item.get("name"));
                addName(target, item.get("bizName"));
                Object aliases = item.get("aliases");
                if (aliases != null) {
                    for (String alias : aliases.toString().split(",")) {
                        addName(target, alias);
                    }
                }
            }
        }
    }

    /** 规范化后加入名称集合。 */
    private void addName(Set<String> target, Object value) {
        if (value != null && StringUtils.isNotBlank(value.toString())) {
            target.add(normalize(value.toString()));
        }
    }

    /** 构造缺口只读上下文；不含完整 SQL 和用户身份。 */
    private Map<String, Object> buildGapContext(SemanticGapDO gap) {
        if (gap == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("gapId", gap.getId());
        context.put("question", gap.getQuestion());
        context.put("failureType", gap.getFailureType());
        context.put("failureReason", gap.getFailureReason());
        context.put("matchedModelIds", gap.getMatchedModelIds());
        context.put("matchedMetricIds", gap.getMatchedMetricIds());
        context.put("matchedDimensionIds", gap.getMatchedDimensionIds());
        context.put("recentQuestions", gap.getRecentQuestions());
        // Gap 的可选字段允许为 null，不能使用拒绝 null 的 Map.copyOf。
        return Collections.unmodifiableMap(context);
    }

    /** 按表读取有限样例；任何单表失败都仅回退该表的 schema-only。 */
    private Map<String, List<Map<String, Object>>> loadMaskedSamples(
            ModelingDraftGenerateReq request, User user) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : request.getSelectedTables()) {
            try {
                List<Map<String, Object>> rows =
                        databaseService.sampleRows(request.getDataSourceId(),
                                request.getCatalogName(), request.getDatabaseName(), table,
                                Math.min(3, properties.getSampleRowsPerTable()),
                                properties.getSampleTimeoutSeconds(), user);
                result.put(table, maskRows(rows));
            } catch (SQLException | RuntimeException ignored) {
                // 安全降级：不记录 SQL、样例值或驱动异常，避免敏感内容进入日志。
            }
        }
        return result;
    }

    /** 对样例行做列名和内容双重脱敏，并限制字符串长度。 */
    private List<Map<String, Object>> maskRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> safeRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (SENSITIVE_COLUMN.matcher(StringUtils.defaultString(entry.getKey())).matches()
                        || value != null && SENSITIVE_VALUE.matcher(value.toString()).find()) {
                    // 只要单元格内部含有一段敏感值就整格遮盖，避免“联系人: 邮箱”类混合文本绕过整串匹配。
                    safeRow.put(entry.getKey(), MASKED);
                } else if (value == null || value instanceof Number || value instanceof Boolean) {
                    safeRow.put(entry.getKey(), value);
                } else {
                    // 驱动特有对象一律转换为短字符串，避免序列化其内部连接或大对象状态。
                    safeRow.put(entry.getKey(),
                            StringUtils.abbreviate(value.toString(), SAMPLE_VALUE_MAX_LENGTH));
                }
            }
            masked.add(safeRow);
        }
        return List.copyOf(masked);
    }

    /** 构造固定系统约束，不包含数据或用户输入。 */
    private String buildSystemPrompt() {
        return "你是 SuperSonic AI 语义建模草稿助手。只输出符合给定 JSON Schema 1.0 的 JSON，"
                + "不要输出 Markdown。每个模型必须且只能使用一个 selectedTables 中的 baseTable。"
                + "维度必须引用该表单个字段；指标只能使用 SUM、COUNT、COUNT_DISTINCT、AVG、MAX、MIN "
                + "和结构化 filters，禁止任意 SQL、子查询、JOIN、窗口函数或跨表表达式。"
                + "术语只能通过草稿本地 objectKey 关联维度或指标。无法确定、别名冲突、状态枚举、"
                + "跨表需求必须写入 uncertainties，不得编造表、字段、正式资产 ID 或样例值。"
                + "识别可能的敏感字段并给出 maskingStrategy；生成可由业务人员理解的示例问法。";
    }

    /** 按优先级组装用户 Prompt。 */
    private String buildUserPrompt(PreflightSnapshot snapshot,
            Map<String, List<Map<String, Object>>> samples, boolean includeExisting,
            boolean includeGapHistory) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        ModelingDraftGenerateReq request = snapshot.request();
        prompt.put("businessGoal", request.getBusinessGoal());
        prompt.put("targetDomainId", request.getDomainId());
        prompt.put("sourceType", request.getSourceType());
        Map<String, Object> gapContext = new LinkedHashMap<>(snapshot.gapContext());
        if (!includeGapHistory) {
            gapContext.remove("recentQuestions");
        }
        prompt.put("gapContext", gapContext);
        prompt.put("dataSource",
                Map.of("id", request.getDataSourceId(), "catalog",
                        StringUtils.defaultString(request.getCatalogName()), "database",
                        StringUtils.defaultString(request.getDatabaseName())));
        prompt.put("selectedTableSchemas", snapshot.columnsByTable());
        if (includeExisting && !snapshot.existingSummary().isEmpty()) {
            prompt.put("existingSemanticAssets", snapshot.existingSummary());
        }
        if (!samples.isEmpty()) {
            prompt.put("maskedSamples", samples);
            prompt.put("sampleNotice", "样例已经服务端脱敏，仅用于理解字段，禁止复制为固定枚举或输出值");
        }
        try {
            // 输出契约始终位于长上下文末尾，降低模型在大量字段元数据后遗忘字段名的概率。
            return "请基于以下可信上下文生成隔离的语义建模草稿：\n" + objectMapper.writeValueAsString(prompt) + "\n\n"
                    + outputContract;
        } catch (JsonProcessingException exception) {
            throw new ModelingDraftException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ModelingDraftConstants.ERROR_PROVIDER, "无法构建建模上下文");
        }
    }

    /** 复制规范化请求，避免 Controller 线程离开后继续依赖可变对象。 */
    private ModelingDraftGenerateReq copyRequest(ModelingDraftGenerateReq source) {
        ModelingDraftGenerateReq copy = new ModelingDraftGenerateReq();
        copy.setSourceType(source.getSourceType());
        copy.setSourceId(source.getSourceId());
        copy.setTitle(source.getTitle());
        copy.setBusinessGoal(source.getBusinessGoal());
        copy.setDomainId(source.getDomainId());
        copy.setDataSourceId(source.getDataSourceId());
        copy.setCatalogName(source.getCatalogName());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setSelectedTables(List.copyOf(source.getSelectedTables()));
        copy.setChatModelId(source.getChatModelId());
        copy.setIncludeSampleData(Boolean.TRUE.equals(source.getIncludeSampleData()));
        return copy;
    }

    /** 从 classpath 加载并解析固定 JSON Schema。 */
    private JsonNode loadSchema(ObjectMapper mapper) {
        return loadJsonResource(mapper, SCHEMA_RESOURCE);
    }

    /** 从 classpath 加载固定 JSON 资源。 */
    private JsonNode loadJsonResource(ObjectMapper mapper, String resource) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing semantic modeling resource: " + resource);
            }
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return mapper.readTree(json);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load semantic modeling resource: " + resource, exception);
        }
    }

    /** 启动时使用与 Worker 相同的 Schema 验证示例，阻止文档契约漂移。 */
    private void validateExample(JsonNode schema, JsonNode example) {
        try {
            ValidateResult result = JSONSchema.parseSchema(schema.toString())
                    .validate(JSON.parse(example.toString()));
            if (!result.isSuccess()) {
                throw new IllegalStateException(
                        "Semantic modeling example violates schema: " + result.getMessage());
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to validate semantic modeling example",
                    exception);
        }
    }

    /** 构造供首次生成和修复轮共同使用的紧凑、无歧义输出契约。 */
    private String buildOutputContract(JsonNode example) {
        return "输出契约（字段名和层级必须严格一致）：\n"
                + "1. 顶层只能包含 schemaVersion、businessGoal、targetDomain、models、terms、uncertainties；"
                + "schemaVersion 固定为 1.0。\n"
                + "2. models 每项使用 key/name/bizName/description/baseTable/primaryTimeField/"
                + "dimensions/metrics/sensitiveFields/sampleQuestions；示例问法只能放 sampleQuestions。\n"
                + "3. 维度和指标都使用 key 与 field；指标另用 aggregation/expression/aliases/filters。"
                + "禁止使用 model、sourceField、exampleQuestions、orderBy、limit 或 model.filters。\n"
                + "4. terms 位于顶层；术语 targets 只能用 type 和 objectKey，objectKey 引用维度或指标 key。\n"
                + "5. 无法单表确定的内容写入 uncertainties，不得新增 Schema 未声明字段。\n"
                + "最小合法结构示例（selected_table 必须替换为真实选表）：\n" + example.toString();
    }

    /** 统一不区分大小写的元数据比较。 */
    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 创建事务前完成的可信快照。 */
    public record PreflightSnapshot(ModelingDraftGenerateReq request,
            Map<String, List<DBColumn>> columnsByTable, Set<String> existingNames,
            Map<String, Object> existingSummary, Map<String, Object> gapContext,
            Integer maxContextTokens) {
        /** 兼容单元测试和进程内调用方未指定模型窗口时使用配置字符上限。 */
        public PreflightSnapshot(ModelingDraftGenerateReq request,
                Map<String, List<DBColumn>> columnsByTable, Set<String> existingNames,
                Map<String, Object> existingSummary, Map<String, Object> gapContext) {
            this(request, columnsByTable, existingNames, existingSummary, gapContext, null);
        }
    }

    /** Gateway 调用上下文。 */
    public record GenerationContext(String systemPrompt, String userPrompt, String outputContract,
            JsonNode jsonSchema, Map<String, List<DBColumn>> columnsByTable,
            Set<String> existingNames) {}

    /** 人工保存时使用的最新校验上下文。 */
    public record ValidationContext(Map<String, List<DBColumn>> columnsByTable,
            Set<String> existingNames) {}

    /** 既有语义资产的脱敏摘要和冲突名称集合。 */
    private record ExistingSemanticContext(Set<String> names, Map<String, Object> summary) {
        private static ExistingSemanticContext empty() {
            return new ExistingSemanticContext(Set.of(), Map.of());
        }
    }
}
