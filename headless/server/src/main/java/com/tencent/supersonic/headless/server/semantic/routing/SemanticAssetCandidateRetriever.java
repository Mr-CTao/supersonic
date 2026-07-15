package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.ModelDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ModelDOMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语义资产确定性候选召回器。
 *
 * <p>
 * 职责：优先使用 Gap 命中模型/指标/维度，再结合同数据源、选表和名称证据召回模型。模型先按 VIEWER ACL 过滤，ADMIN
 * 仅标记是否可增强；模型详情、维度、指标均批量读取，禁止候选循环 N+1。 组件无共享可变缓存，可并发复用。
 * </p>
 */
@Component
public class SemanticAssetCandidateRetriever {

    private static final int TRACE_MODEL_PRIORITY = 1_000;
    private static final int TRACE_ELEMENT_PRIORITY = 800;
    private static final int SELECTED_TABLE_PRIORITY = 300;
    private static final int NAME_RELEVANCE_PRIORITY = 100;
    private static final int MAX_TRACE_IDS = 100;
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\d+");

    private final SemanticGapMapper gapMapper;
    private final ModelService modelService;
    private final DimensionService dimensionService;
    private final MetricService metricService;
    private final ModelDOMapper modelDOMapper;
    private final SemanticAssetVersionService versionService;
    private final ObjectMapper objectMapper;

    /**
     * 创建候选召回器。
     *
     * @param gapMapper 缺口 Mapper。
     * @param modelService 模型 ACL 服务。
     * @param dimensionService 维度批量服务。
     * @param metricService 指标批量服务。
     * @param modelDOMapper 模型详情批量 Mapper。
     * @param versionService 模型及子对象稳定版本计算服务。
     * @param objectMapper JSON 映射器。
     */
    public SemanticAssetCandidateRetriever(SemanticGapMapper gapMapper, ModelService modelService,
            DimensionService dimensionService, MetricService metricService,
            ModelDOMapper modelDOMapper, SemanticAssetVersionService versionService,
            ObjectMapper objectMapper) {
        this.gapMapper = gapMapper;
        this.modelService = modelService;
        this.dimensionService = dimensionService;
        this.metricService = metricService;
        this.modelDOMapper = modelDOMapper;
        this.versionService = versionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量召回当前用户可见候选。
     *
     * <p>
     * 调用示例：{@code retrieve(request, user)}。返回值最多包含固定数量候选，handle 按最终确定性顺序 分配；未授权资产不会进入中间摘要或 Advisor
     * 请求。
     * </p>
     *
     * @param request 已规范化分析请求。
     * @param user 当前用户。
     * @return 有界候选快照。
     */
    public List<SemanticAssetCandidate> retrieve(SemanticAssetRouteAnalyzeReq request, User user) {
        SemanticGapDO gap = loadGap(request);
        Set<Long> tracedModels = parseIds(gap == null ? null : gap.getMatchedModelIds());
        Set<Long> tracedDimensions = parseIds(gap == null ? null : gap.getMatchedDimensionIds());
        Set<Long> tracedMetrics = parseIds(gap == null ? null : gap.getMatchedMetricIds());
        Set<Long> tracedElementModels =
                loadTracedElementModels(tracedDimensions, tracedMetrics);
        List<ModelResp> authorizedModels = safe(
                modelService.getModelListWithAuth(user, request.getDomainId(), AuthType.VIEWER))
                        .stream().filter(Objects::nonNull).filter(model -> Objects
                                .equals(request.getDataSourceId(), model.getDatabaseId()))
                        .toList();
        if (authorizedModels.isEmpty()) {
            return List.of();
        }
        // ModelResp 已携带轻量模型详情，因此可在截断前同时考虑追踪子对象和选表证据；这样既保持
        // 有界重型查询，也不会因模型 ID 较大而把真实命中或同表高覆盖候选提前排除。
        List<ModelResp> visibleModels = authorizedModels.stream()
                .sorted(Comparator
                        .comparingInt((ModelResp model) -> preliminaryPriority(model, request,
                                tracedModels, tracedElementModels))
                        .reversed().thenComparing(ModelResp::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(SemanticAssetRoutingConstants.MAX_CANDIDATE_RECALL_POOL).toList();
        Set<Long> visibleIds = visibleModels.stream().map(ModelResp::getId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> manageableIds =
                safe(modelService.getModelListWithAuth(user, request.getDomainId(), AuthType.ADMIN))
                        .stream().map(ModelResp::getId).filter(visibleIds::contains)
                        .collect(Collectors.toSet());

        // 三次批量读取覆盖全部候选事实，避免按候选逐个查询维度、指标或版本。
        List<Long> modelIds = visibleIds.stream().sorted().toList();
        MetaFilter filter = new MetaFilter(modelIds);
        List<DimensionResp> dimensions = safe(dimensionService.getDimensions(filter));
        List<MetricResp> metrics = safe(metricService.getMetrics(filter));
        Map<Long, ModelDO> modelDetails = safe(modelDOMapper.selectBatchIds(modelIds)).stream()
                .filter(Objects::nonNull).collect(Collectors.toMap(ModelDO::getId, item -> item,
                        (left, right) -> left, LinkedHashMap::new));

        Map<Long, List<DimensionResp>> dimensionsByModel = dimensions.stream()
                .filter(item -> item != null && visibleIds.contains(item.getModelId()))
                .collect(Collectors.groupingBy(DimensionResp::getModelId));
        Map<Long, List<MetricResp>> metricsByModel = metrics.stream()
                .filter(item -> item != null && visibleIds.contains(item.getModelId()))
                .collect(Collectors.groupingBy(MetricResp::getModelId));

        List<RankedCandidate> ranked = new ArrayList<>();
        for (ModelResp model : visibleModels) {
            ModelDO modelDO = modelDetails.get(model.getId());
            ModelDetail detail = parseModelDetail(modelDO);
            List<DimensionResp> modelDimensions =
                    dimensionsByModel.getOrDefault(model.getId(), List.of());
            List<MetricResp> modelMetrics = metricsByModel.getOrDefault(model.getId(), List.of());
            List<String> evidence = new ArrayList<>();
            int priority = 0;
            if (tracedModels.contains(model.getId())) {
                priority += TRACE_MODEL_PRIORITY;
                evidence.add("原问答链路命中该模型");
            }
            if (modelDimensions.stream().map(DimensionResp::getId)
                    .anyMatch(tracedDimensions::contains)
                    || modelMetrics.stream().map(MetricResp::getId)
                            .anyMatch(tracedMetrics::contains)) {
                priority += TRACE_ELEMENT_PRIORITY;
                evidence.add("原问答链路命中该模型下的维度或指标");
            }
            List<String> baseTables = extractBaseTables(detail);
            if (hasSelectedTable(baseTables, request.getSelectedTables())) {
                priority += SELECTED_TABLE_PRIORITY;
                evidence.add("候选模型与当前选表一致");
            }
            if (hasNameRelevance(model, request.getBusinessGoal())) {
                priority += NAME_RELEVANCE_PRIORITY;
                evidence.add("候选名称与业务目标相关");
            }
            if (evidence.isEmpty()) {
                evidence.add("同主题域且同数据源的可见模型");
            }
            SemanticAssetCandidate candidate = SemanticAssetCandidate.builder()
                    .assetType(SemanticAssetRoutingConstants.ASSET_TYPE_MODEL)
                    .assetId(model.getId())
                    .assetVersion(versionService.versionOf(modelDO, modelDimensions, modelMetrics))
                    .name(model.getName())
                    .bizName(model.getBizName()).description(model.getDescription())
                    .domainId(model.getDomainId()).dataSourceId(model.getDatabaseId())
                    .baseTables(baseTables).grain(extractGrain(detail))
                    .dimensionCapabilities(dimensionCapabilities(modelDimensions))
                    .metricCapabilities(metricCapabilities(modelMetrics))
                    .timeCapabilities(timeCapabilities(modelDimensions))
                    .manageable(manageableIds.contains(model.getId()))
                    .evidenceSources(List.copyOf(evidence)).tracePriority(priority).build();
            ranked.add(new RankedCandidate(candidate, priority));
        }

        ranked.sort(Comparator.comparingInt(RankedCandidate::priority).reversed()
                .thenComparing(item -> item.candidate().getAssetId()));
        List<SemanticAssetCandidate> result = new ArrayList<>();
        for (int index = 0; index < Math.min(SemanticAssetRoutingConstants.MAX_CANDIDATES,
                ranked.size()); index++) {
            SemanticAssetCandidate candidate = ranked.get(index).candidate();
            candidate.setCandidateHandle("candidate_" + (index + 1));
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    /**
     * 按结构化维度/指标 ID 批量反查所属模型，确保追踪事实进入候选池。
     *
     * <p>两个查询都使用 ID 集合一次批量读取，并限制在已解析的最多 100 个追踪 ID；空集合不会
     * 调用服务，避免 MetaFilter 空条件退化为全表查询。</p>
     */
    private Set<Long> loadTracedElementModels(Set<Long> dimensionIds, Set<Long> metricIds) {
        Set<Long> modelIds = new LinkedHashSet<>();
        if (!dimensionIds.isEmpty()) {
            MetaFilter dimensionFilter = MetaFilter.builder()
                    .ids(dimensionIds.stream().sorted().toList()).build();
            safe(dimensionService.getDimensions(dimensionFilter)).stream()
                    .filter(Objects::nonNull).map(DimensionResp::getModelId)
                    .filter(Objects::nonNull).forEach(modelIds::add);
        }
        if (!metricIds.isEmpty()) {
            MetaFilter metricFilter = MetaFilter.builder()
                    .ids(metricIds.stream().sorted().toList()).build();
            safe(metricService.getMetrics(metricFilter)).stream().filter(Objects::nonNull)
                    .map(MetricResp::getModelId).filter(Objects::nonNull)
                    .forEach(modelIds::add);
        }
        return Set.copyOf(modelIds);
    }

    /** 计算重型字段批量加载前的候选优先级，严格保持追踪、选表、名称的可信顺序。 */
    private int preliminaryPriority(ModelResp model, SemanticAssetRouteAnalyzeReq request,
            Set<Long> tracedModels, Set<Long> tracedElementModels) {
        int priority = 0;
        if (tracedModels.contains(model.getId())) {
            priority += TRACE_MODEL_PRIORITY;
        }
        if (tracedElementModels.contains(model.getId())) {
            priority += TRACE_ELEMENT_PRIORITY;
        }
        if (hasSelectedTable(extractBaseTables(model.getModelDetail()),
                request.getSelectedTables())) {
            priority += SELECTED_TABLE_PRIORITY;
        }
        if (hasNameRelevance(model, request.getBusinessGoal())) {
            priority += NAME_RELEVANCE_PRIORITY;
        }
        return priority;
    }

    /** 加载 Gap 来源并保持数据源来源无额外查询。 */
    private SemanticGapDO loadGap(SemanticAssetRouteAnalyzeReq request) {
        if (!SemanticAssetRoutingConstants.SOURCE_SEMANTIC_GAP.equals(request.getSourceType())) {
            return null;
        }
        return gapMapper.selectById(request.getSourceId());
    }

    /** 解析逗号或 JSON 形式的结构化追踪 ID，并限制数量。 */
    private Set<Long> parseIds(String raw) {
        if (StringUtils.isBlank(raw)) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        Matcher matcher = NUMERIC_ID_PATTERN.matcher(raw);
        while (matcher.find() && ids.size() < MAX_TRACE_IDS) {
            try {
                ids.add(Long.parseLong(matcher.group()));
            } catch (NumberFormatException ignored) {
                // 超长非法数字不是可信追踪事实，安全忽略且不回显原始内容。
            }
        }
        return Set.copyOf(ids);
    }

    /** 解析模型详情；损坏详情安全退化为空，不尝试解释 SQL。 */
    private ModelDetail parseModelDetail(ModelDO model) {
        if (model == null || StringUtils.isBlank(model.getModelDetail())) {
            return new ModelDetail();
        }
        try {
            return objectMapper.readValue(model.getModelDetail(), ModelDetail.class);
        } catch (JsonProcessingException exception) {
            return new ModelDetail();
        }
    }

    /** 从结构化模型详情读取单表名称，不读取 sqlQuery。 */
    private List<String> extractBaseTables(ModelDetail detail) {
        if (detail == null || StringUtils.isBlank(detail.getTableQuery())) {
            return List.of();
        }
        String table = detail.getTableQuery().trim();
        // tableQuery 不是简单标识符时可能承载表达式；为避免把 SQL 进入候选快照，直接丢弃。
        return table.matches("[A-Za-z0-9_.$`\"]{1,255}") ? List.of(table) : List.of();
    }

    /** 从模型标识符提取事实粒度。 */
    private List<String> extractGrain(ModelDetail detail) {
        if (detail == null) {
            return List.of();
        }
        return safe(detail.getIdentifiers()).stream().filter(Objects::nonNull)
                .map(identifier -> StringUtils.defaultIfBlank(identifier.getName(),
                        identifier.getBizName()))
                .filter(StringUtils::isNotBlank).distinct().toList();
    }

    /** 汇总维度名称、业务名和别名。 */
    private List<String> dimensionCapabilities(List<DimensionResp> dimensions) {
        Set<String> values = new LinkedHashSet<>();
        for (DimensionResp item : safe(dimensions)) {
            addNames(values, item.getName(), item.getBizName(), item.getAlias());
        }
        return List.copyOf(values);
    }

    /** 汇总指标名称、业务名和别名。 */
    private List<String> metricCapabilities(List<MetricResp> metrics) {
        Set<String> values = new LinkedHashSet<>();
        for (MetricResp item : safe(metrics)) {
            addNames(values, item.getName(), item.getBizName(), item.getAlias());
        }
        return List.copyOf(values);
    }

    /** 单独汇总时间维度，供覆盖分析解释时间能力。 */
    private List<String> timeCapabilities(List<DimensionResp> dimensions) {
        Set<String> values = new LinkedHashSet<>();
        for (DimensionResp item : safe(dimensions)) {
            if (item != null && item.isTimeDimension()) {
                addNames(values, item.getName(), item.getBizName(), item.getAlias());
            }
        }
        return List.copyOf(values);
    }

    /** 把名称、业务名和逗号别名加入去重集合。 */
    private void addNames(Set<String> target, String name, String bizName, String aliases) {
        if (StringUtils.isNotBlank(name)) {
            target.add(name);
        }
        if (StringUtils.isNotBlank(bizName)) {
            target.add(bizName);
        }
        if (StringUtils.isNotBlank(aliases)) {
            Pattern.compile(",|，").splitAsStream(aliases).map(String::trim)
                    .filter(StringUtils::isNotBlank).forEach(target::add);
        }
    }

    /** 判断候选简单表名是否命中当前选表。 */
    private boolean hasSelectedTable(List<String> baseTables, List<String> selectedTables) {
        Set<String> selected =
                safe(selectedTables).stream().map(this::normalize).collect(Collectors.toSet());
        return safe(baseTables).stream().map(this::normalize).anyMatch(selected::contains);
    }

    /** 使用至少两个连续字符判断模型名称业务相关性。 */
    private boolean hasNameRelevance(ModelResp model, String goal) {
        String normalizedGoal = normalize(goal);
        for (String value : new String[] {model.getName(), model.getBizName(), model.getAlias()}) {
            String candidate = normalize(value);
            for (int index = 0; index + 2 <= candidate.length(); index++) {
                if (normalizedGoal.contains(candidate.substring(index, index + 2))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 规范化比较文本。 */
    private String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9_]", "");
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 候选与确定性召回优先级。 */
    private record RankedCandidate(SemanticAssetCandidate candidate, int priority) {}
}
