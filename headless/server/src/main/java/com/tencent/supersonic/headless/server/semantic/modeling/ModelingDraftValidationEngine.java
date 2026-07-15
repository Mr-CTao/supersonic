package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.server.semantic.modeling.DraftSemanticValidationService.SampleValidation;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DimensionDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.SensitiveFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TermDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.UncertaintyDraft;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 未发布语义草稿的确定性验证引擎。
 *
 * <p>
 * 职责说明：在阶段 3 Schema/字段校验成功后补充敏感字段漏标、名称冲突、不确定项，并委托隔离草稿 语义服务复用现有 mapper/parser/translator 生成真实
 * selected parse、S2SQL 和物理 SQL。翻译结果只做 LIMIT 与只读 AST 检查，绝不执行 SQL，也不会临时写入正式语义资产。
 * 本组件没有共享可变状态，所有集合均为请求局部变量，因此无需额外线程锁。
 * </p>
 */
@Component
public class ModelingDraftValidationEngine {

    private static final String CATEGORY_FIELD = "FIELD_EXISTENCE";
    private static final String CATEGORY_CONFLICT = "NAME_CONFLICT";
    private static final String CATEGORY_SENSITIVE = "SENSITIVE_FIELD";
    private static final String CATEGORY_UNCERTAINTY = "UNCERTAINTY";
    /**
     * 即使上游误标成 WARNING，也必须由服务端升级为阻塞的高风险业务分类。
     *
     * <p>这里使用稳定分类而不是中文文案判断，避免提示语调整导致门禁漂移。</p>
     */
    private static final Set<String> HIGH_RISK_UNCERTAINTY_CATEGORIES = Set.of(
            "BUSINESS_GRAIN", "BUSINESS_DEFINITION", "SENSITIVE_FIELD", "CROSS_TABLE",
            "CROSS_ASSET", "TARGET_VERSION_DRIFT", "TARGET_ASSET_PERMISSION",
            "METRIC_SEMANTIC_CHANGE", "UNAUTHORIZED_MODIFICATION");
    private static final Set<String> BLOCKING_RETRIEVAL_WORDS = Set.of("数据", "信息", "查询", "列表", "详情",
            "全部", "所有", "data", "info", "query", "list", "detail", "all");
    private static final Set<String> WARNING_RETRIEVAL_WORDS = Set.of("名称", "类型", "状态", "结果", "统计",
            "分析", "name", "type", "status", "result", "count", "id");
    private static final int MIN_RETRIEVAL_TOKEN_CODE_POINTS = 2;

    private final SemanticModelingSensitivityClassifier sensitivityClassifier;
    private final DraftSemanticValidationService draftSemanticValidationService;

    /**
     * 创建阶段 4 验证引擎。
     *
     * @param sensitivityClassifier 与阶段 3 样例脱敏共用的敏感规则。
     * @param draftSemanticValidationService 隔离草稿真实语义解析与翻译服务。
     */
    public ModelingDraftValidationEngine(
            SemanticModelingSensitivityClassifier sensitivityClassifier,
            DraftSemanticValidationService draftSemanticValidationService) {
        this.sensitivityClassifier = sensitivityClassifier;
        this.draftSemanticValidationService = draftSemanticValidationService;
    }

    /**
     * 执行未发布草稿的阶段 4 确定性检查。
     *
     * <p>
     * 调用示例：{@code engine.validate(validated.payload(), columns, 1L, user, 20)}。调用方必须先用
     * {@link ModelingDraftValidator#validateAndNormalize(String, Map, Set)} 完成 Schema、真实字段和指标表达式
     * 校验；本方法不调用 LLM，也不执行生成的 SQL。
     * </p>
     *
     * @param payload 已通过阶段 3 基础校验的草稿。
     * @param columnsByTable 服务端重新读取的真实字段。
     * @param dataSourceId 草稿数据源 ID。
     * @param user 当前管理员，用于隔离语义验证再次复核数据源权限。
     * @param sqlPreviewLimit 真实翻译 SQL 的 LIMIT，上限由请求 DTO 保证。
     * @return 可持久化为验证报告的结构化结果。
     */
    public ModelingDraftValidationOutcome validate(ModelingDraftPayload payload,
            Map<String, List<DBColumn>> columnsByTable, Long dataSourceId, User user,
            int sqlPreviewLimit) {
        // 增强草稿仅把 additions 映射为请求内校验视图；正式目标资产从不复制进草稿或校验结果。
        ModelingDraftPayload validationPayload = payload.validationView();
        List<ModelingValidationFinding> blocking = new ArrayList<>();
        List<ModelingValidationFinding> warnings = new ArrayList<>();
        List<ModelingPlannedObject> plannedObjects = collectPlannedObjects(validationPayload);
        Map<String, List<DBColumn>> normalizedColumns = normalizeColumns(columnsByTable);

        ModelingValidationCheckResult fieldResult = passed(CATEGORY_FIELD, "表、主时间字段、维度、指标和过滤字段均存在",
                countFieldReferences(validationPayload), "SERVER_METADATA");
        ModelingValidationCheckResult conflictResult =
                inspectConflicts(payload, blocking, warnings);
        ModelingValidationCheckResult pollutionResult =
                inspectRetrievalPollution(payload, blocking, warnings);
        ModelingValidationCheckResult sensitiveResult =
                inspectSensitiveFields(validationPayload, normalizedColumns, blocking);
        SampleValidation samples = draftSemanticValidationService.validate(validationPayload,
                columnsByTable, dataSourceId, user, sqlPreviewLimit);
        blocking.addAll(samples.blockingItems());
        warnings.addAll(samples.warningItems());
        ModelingValidationCheckResult uncertaintyResult =
                inspectUncertainties(payload, blocking, warnings);
        List<ModelingValidationCheckResult> requiredChecks = List.of(
                passed(ModelingValidationGate.CHECK_JSON_SCHEMA,
                        "草稿 JSON Schema " + StringUtils.defaultIfBlank(payload.getSchemaVersion(), "1.0")
                                + " 校验通过",
                        1,
                        "JSON_SCHEMA"),
                passed(ModelingValidationGate.CHECK_TABLE_FIELD_EXISTENCE, "表、主时间字段、维度和过滤字段均存在",
                        countFieldReferences(validationPayload), "SERVER_METADATA"),
                passed(ModelingValidationGate.CHECK_METRIC_EXPRESSION_FIELD,
                        "指标表达式字段引用均已通过阶段 3 服务端校验",
                        countMetrics(validationPayload), "SERVER_METADATA"),
                sensitiveResult, conflictResult, pollutionResult, samples.sampleQuestionResult(),
                samples.sqlGenerationResult(), samples.sqlReadOnlyResult(),
                samples.performanceResult());

        return ModelingDraftValidationOutcome.builder().plannedObjects(plannedObjects)
                .requiredCheckResults(requiredChecks).fieldExistenceResult(fieldResult)
                .conflictResult(conflictResult).sensitiveFieldResult(sensitiveResult)
                .sampleQuestionResults(samples.results()).sqlSafetyResult(samples.sqlResult())
                .performanceRiskResult(samples.performanceResult())
                .uncertaintyResult(uncertaintyResult).blockingItems(List.copyOf(blocking))
                .warningItems(List.copyOf(warnings)).build();
    }

    /** 把草稿对象转换为报告中的新增对象摘要。 */
    private List<ModelingPlannedObject> collectPlannedObjects(ModelingDraftPayload payload) {
        List<ModelingPlannedObject> objects = new ArrayList<>();
        for (ModelDraft model : safe(payload.getModels())) {
            objects.add(planned("MODEL", model.getKey(),
                    displayName(model.getBizName(), model.getName()), model.getKey()));
            for (DimensionDraft dimension : safe(model.getDimensions())) {
                objects.add(planned("DIMENSION", dimension.getKey(),
                        displayName(dimension.getBizName(), dimension.getName()), model.getKey()));
            }
            for (MetricDraft metric : safe(model.getMetrics())) {
                objects.add(planned("METRIC", metric.getKey(),
                        displayName(metric.getBizName(), metric.getName()), model.getKey()));
            }
        }
        for (TermDraft term : safe(payload.getTerms())) {
            objects.add(planned("TERM", term.getKey(), term.getName(), null));
        }
        return List.copyOf(objects);
    }

    /** 构造单个计划新增对象。 */
    private ModelingPlannedObject planned(String type, String key, String name, String modelKey) {
        return ModelingPlannedObject.builder().type(type).key(key).name(name).modelKey(modelKey)
                .build();
    }

    /** 汇总字段引用数量，用于报告展示覆盖范围。 */
    private int countFieldReferences(ModelingDraftPayload payload) {
        int count = 0;
        for (ModelDraft model : safe(payload.getModels())) {
            count += StringUtils.isBlank(model.getPrimaryTimeField()) ? 0 : 1;
            count += safe(model.getDimensions()).size();
            count += safe(model.getMetrics()).size();
            count += safe(model.getSensitiveFields()).size();
            count += safe(model.getMetrics()).stream().map(MetricDraft::getFilters)
                    .filter(Objects::nonNull).mapToInt(Collection::size).sum();
        }
        return count;
    }

    /** 统计指标数量，证明指标表达式引用检查覆盖范围。 */
    private int countMetrics(ModelingDraftPayload payload) {
        return safe(payload.getModels()).stream().map(ModelDraft::getMetrics)
                .filter(Objects::nonNull).mapToInt(Collection::size).sum();
    }

    /**
     * 将名称冲突不确定项转换为唯一一条门禁 finding。
     *
     * <p>Validator 负责发现并按稳定业务身份去重，Engine 只负责把严重级别映射到最终报告；
     * {@link #inspectUncertainties(ModelingDraftPayload, List, List)} 会跳过该分类，避免同一冲突同时
     * 生成 NAME_OR_ALIAS_CONFLICT 与 UNRESOLVED_UNCERTAINTY 两条 finding。</p>
     */
    private ModelingValidationCheckResult inspectConflicts(ModelingDraftPayload payload,
            List<ModelingValidationFinding> blocking,
            List<ModelingValidationFinding> warnings) {
        List<UncertaintyDraft> conflicts = safe(payload.getUncertainties()).stream()
                .filter(item -> "ALIAS_CONFLICT".equals(item.getCategory())).toList();
        int blockingCount = 0;
        for (UncertaintyDraft conflict : conflicts) {
            boolean isBlocking = ModelingDraftConstants.FINDING_BLOCKING
                    .equalsIgnoreCase(conflict.getSeverity());
            List<ModelingValidationFinding> target = isBlocking ? blocking : warnings;
            target.add(finding(CATEGORY_CONFLICT, "NAME_OR_ALIAS_CONFLICT",
                    isBlocking ? ModelingDraftConstants.FINDING_BLOCKING
                            : ModelingDraftConstants.FINDING_WARNING,
                    "$.uncertainties", sensitivityClassifier.sanitizeText(conflict.getReason()),
                    conflict.getModelKey()));
            if (isBlocking) {
                blockingCount++;
            }
        }
        String status = blockingCount > 0 ? ModelingDraftConstants.VALIDATION_FAILED
                : conflicts.isEmpty() ? ModelingDraftConstants.VALIDATION_PASSED
                        : ModelingDraftConstants.VALIDATION_WARNING;
        String summary = blockingCount > 0 ? "发现与现有资产或其他草稿对象的名称冲突，必须修订"
                : conflicts.isEmpty() ? "未发现名称或别名冲突" : "发现名称或别名冲突，需要管理员确认";
        return checkResult(CATEGORY_CONFLICT, status, summary, conflicts.size(),
                conflicts.size() - blockingCount, blockingCount, "AUTHORIZED_ASSET_NAMES");
    }

    /**
     * 使用最小确定性规则检查会进入召回词典的名称与别名。
     *
     * <p>
     * 规则只判断空/纯标点、单字符、明确停用词以及重复通用别名；正常业务短语不按长度一刀切。 finding 仅携带对象类型、草稿 key 和路径，不回显原始名称或别名。
     * </p>
     */
    private ModelingValidationCheckResult inspectRetrievalPollution(ModelingDraftPayload payload,
            List<ModelingValidationFinding> blocking, List<ModelingValidationFinding> warnings) {
        List<RetrievalNameCandidate> candidates = retrievalCandidates(payload);
        int blockingBefore = blocking.size();
        int warningBefore = warnings.size();
        for (RetrievalNameCandidate candidate : candidates) {
            inspectRetrievalCandidate(candidate, blocking, warnings);
        }
        inspectRepeatedCommonAliases(candidates, blocking);

        // 同一候选可能同时命中“高频词”和“重复别名”，计数按候选封顶，避免报告出现失败数大于检查数。
        int failed = Math.min(candidates.size(), blocking.size() - blockingBefore);
        int warningCount = Math.min(candidates.size(), warnings.size() - warningBefore);
        String status = failed > 0 ? ModelingDraftConstants.VALIDATION_FAILED
                : warningCount > 0 ? ModelingDraftConstants.VALIDATION_WARNING
                        : ModelingDraftConstants.VALIDATION_PASSED;
        String summary = failed > 0 ? "发现会明确污染召回词典的名称或别名"
                : warningCount > 0 ? "发现需要管理员确认的通用名称或别名" : "未发现高频词污染风险";
        return checkResult(ModelingValidationGate.CHECK_RETRIEVAL_POLLUTION, status, summary,
                candidates.size(), Math.max(0, candidates.size() - failed), failed,
                "STATIC_RETRIEVAL_NAMING_RULES");
    }

    /** 收集模型、维度、指标、术语及其别名，不收集描述和样例文本。 */
    private List<RetrievalNameCandidate> retrievalCandidates(ModelingDraftPayload payload) {
        List<RetrievalNameCandidate> candidates = new ArrayList<>();
        ModelingDraftPayload validationPayload = payload.validationView();
        for (ModelDraft model : safe(validationPayload.getModels())) {
            addNameCandidate(candidates, "MODEL", model.getKey(), model.getKey(),
                    "$.models[" + model.getKey() + "].name", model.getName(), false);
            addOptionalNameCandidate(candidates, "MODEL", model.getKey(), model.getKey(),
                    "$.models[" + model.getKey() + "].bizName", model.getBizName(), false);
            int modelAliasIndex = 0;
            for (String alias : safe(model.getValidationAliases())) {
                addNameCandidate(candidates, "MODEL", model.getKey(), model.getKey(),
                        "$.additions.aliases[" + modelAliasIndex++ + "]", alias, true);
            }
            for (DimensionDraft dimension : safe(model.getDimensions())) {
                collectElementCandidates(candidates, "DIMENSION", dimension.getKey(),
                        model.getKey(), dimension.getName(), dimension.getBizName(),
                        dimension.getAliases());
            }
            for (MetricDraft metric : safe(model.getMetrics())) {
                collectElementCandidates(candidates, "METRIC", metric.getKey(), model.getKey(),
                        metric.getName(), metric.getBizName(), metric.getAliases());
            }
        }
        for (TermDraft term : safe(validationPayload.getTerms())) {
            addNameCandidate(candidates, "TERM", term.getKey(), null,
                    "$.terms[" + term.getKey() + "].name", term.getName(), false);
            int aliasIndex = 0;
            for (String alias : safe(term.getAliases())) {
                addNameCandidate(candidates, "TERM", term.getKey(), null,
                        "$.terms[" + term.getKey() + "].aliases[" + aliasIndex++ + "]", alias,
                        true);
            }
        }
        return candidates;
    }

    /** 收集维度或指标的 name、bizName 与 aliases。 */
    private void collectElementCandidates(List<RetrievalNameCandidate> candidates,
            String objectType, String objectKey, String modelKey, String name, String bizName,
            List<String> aliases) {
        String basePath = "$.models[" + modelKey + "]." + objectType.toLowerCase(Locale.ROOT) + "s["
                + objectKey + "]";
        addNameCandidate(candidates, objectType, objectKey, modelKey, basePath + ".name", name,
                false);
        addOptionalNameCandidate(candidates, objectType, objectKey, modelKey, basePath + ".bizName",
                bizName, false);
        int aliasIndex = 0;
        for (String alias : safe(aliases)) {
            addNameCandidate(candidates, objectType, objectKey, modelKey,
                    basePath + ".aliases[" + aliasIndex++ + "]", alias, true);
        }
    }

    /** 可选业务名为 null 时不进入检查，显式空字符串仍按污染处理。 */
    private void addOptionalNameCandidate(List<RetrievalNameCandidate> candidates,
            String objectType, String objectKey, String modelKey, String path, String value,
            boolean alias) {
        if (value != null) {
            addNameCandidate(candidates, objectType, objectKey, modelKey, path, value, alias);
        }
    }

    /** 添加一条名称候选。 */
    private void addNameCandidate(List<RetrievalNameCandidate> candidates, String objectType,
            String objectKey, String modelKey, String path, String value, boolean alias) {
        candidates.add(new RetrievalNameCandidate(objectType, objectKey, modelKey, path, value,
                alias, retrievalToken(value)));
    }

    /** 检查单个候选，不在消息中回显候选原文。 */
    private void inspectRetrievalCandidate(RetrievalNameCandidate candidate,
            List<ModelingValidationFinding> blocking, List<ModelingValidationFinding> warnings) {
        String token = candidate.normalizedToken();
        if (StringUtils.isBlank(token)) {
            blocking.add(pollutionFinding(candidate, "RETRIEVAL_NAME_EMPTY_OR_PUNCTUATION",
                    ModelingDraftConstants.FINDING_BLOCKING, "名称或别名为空或仅含标点，禁止进入召回词典"));
            return;
        }
        int codePoints = token.codePointCount(0, token.length());
        if (codePoints < MIN_RETRIEVAL_TOKEN_CODE_POINTS
                || BLOCKING_RETRIEVAL_WORDS.contains(token)) {
            blocking.add(pollutionFinding(candidate, "RETRIEVAL_HIGH_FREQUENCY_POLLUTION",
                    ModelingDraftConstants.FINDING_BLOCKING, "名称或别名过于通用，会明确污染召回词典"));
        } else if (WARNING_RETRIEVAL_WORDS.contains(token)) {
            warnings.add(pollutionFinding(candidate, "RETRIEVAL_GENERIC_NAME_RISK",
                    ModelingDraftConstants.FINDING_WARNING, "名称或别名较为通用，需要管理员确认召回边界"));
        }
    }

    /** 重复使用通用别名会放大召回歧义，因此升级为明确阻塞。 */
    private void inspectRepeatedCommonAliases(List<RetrievalNameCandidate> candidates,
            List<ModelingValidationFinding> blocking) {
        Map<String, List<RetrievalNameCandidate>> aliasesByToken =
                candidates.stream().filter(RetrievalNameCandidate::alias)
                        .filter(item -> BLOCKING_RETRIEVAL_WORDS.contains(item.normalizedToken())
                                || WARNING_RETRIEVAL_WORDS.contains(item.normalizedToken()))
                        .collect(Collectors.groupingBy(RetrievalNameCandidate::normalizedToken,
                                LinkedHashMap::new, Collectors.toList()));
        aliasesByToken.values().stream().filter(items -> items.size() > 1).forEach(items -> {
            for (RetrievalNameCandidate candidate : items) {
                blocking.add(pollutionFinding(candidate, "RETRIEVAL_COMMON_ALIAS_DUPLICATED",
                        ModelingDraftConstants.FINDING_BLOCKING, "同一通用别名被多个对象重复使用，会造成不可控召回歧义"));
            }
        });
    }

    /** 创建只包含对象定位信息的污染风险 finding。 */
    private ModelingValidationFinding pollutionFinding(RetrievalNameCandidate candidate,
            String code, String severity, String message) {
        return ModelingValidationFinding.builder()
                .category(ModelingValidationGate.CHECK_RETRIEVAL_POLLUTION).code(code)
                .severity(severity).path(candidate.path()).message(message)
                .modelKey(candidate.modelKey()).objectType(candidate.objectType())
                .objectKey(candidate.objectKey()).build();
    }

    /** 去除空白和标点，只保留小写字母、数字及 Unicode 文字用于确定性比较。 */
    private String retrievalToken(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder token = new StringBuilder();
        value.trim().toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit)
                .forEach(token::appendCodePoint);
        return token.toString();
    }

    /** 检测高置信敏感物理列是否漏标或显式关闭脱敏。 */
    private ModelingValidationCheckResult inspectSensitiveFields(ModelingDraftPayload payload,
            Map<String, List<DBColumn>> columnsByTable, List<ModelingValidationFinding> blocking) {
        int checked = 0;
        int failed = 0;
        for (ModelDraft model : safe(payload.getModels())) {
            Map<String, SensitiveFieldDraft> declared = safe(model.getSensitiveFields()).stream()
                    .collect(Collectors.toMap(item -> normalize(item.getField()),
                            Function.identity(), (left, right) -> left, LinkedHashMap::new));
            Set<String> checkedFields = new HashSet<>();
            Set<String> failedFields = new HashSet<>();

            // 显式声明为 HIGH/CRITICAL 已经是管理员或 AI 给出的强证据，不能再依赖字段名正则才检查脱敏策略。
            for (SensitiveFieldDraft declaration : safe(model.getSensitiveFields())) {
                String field = normalize(declaration.getField());
                if (!isHighSensitivity(declaration.getLevel())) {
                    continue;
                }
                checkedFields.add(field);
                if ("NONE".equalsIgnoreCase(declaration.getMaskingStrategy())
                        && failedFields.add(field)) {
                    blocking.add(finding(CATEGORY_SENSITIVE, "SENSITIVE_FIELD_UNMASKED",
                            ModelingDraftConstants.FINDING_BLOCKING,
                            "$.models[" + model.getKey() + "].sensitiveFields",
                            "HIGH/CRITICAL 敏感字段不能使用 NONE 脱敏策略", model.getKey()));
                }
            }
            for (DBColumn column : columnsByTable.getOrDefault(normalize(model.getBaseTable()),
                    List.of())) {
                if (!sensitivityClassifier.isSensitiveColumn(column.getColumnName(),
                        column.getComment())) {
                    continue;
                }
                String field = normalize(column.getColumnName());
                checkedFields.add(field);
                SensitiveFieldDraft declaration = declared.get(field);
                if (declaration == null) {
                    if (failedFields.add(field)) {
                        blocking.add(finding(CATEGORY_SENSITIVE, "SENSITIVE_FIELD_UNDECLARED",
                                ModelingDraftConstants.FINDING_BLOCKING,
                                "$.models[" + model.getKey() + "].sensitiveFields",
                                "检测到高置信敏感字段尚未标注", model.getKey()));
                    }
                } else if ("NONE".equalsIgnoreCase(declaration.getMaskingStrategy())
                        && failedFields.add(field)) {
                    blocking.add(finding(CATEGORY_SENSITIVE, "SENSITIVE_FIELD_UNMASKED",
                            ModelingDraftConstants.FINDING_BLOCKING,
                            "$.models[" + model.getKey() + "].sensitiveFields",
                            "高置信敏感字段不能使用 NONE 脱敏策略", model.getKey()));
                }
            }
            checked += checkedFields.size();
            failed += failedFields.size();
        }
        String status = failed == 0 ? "PASSED" : "FAILED";
        String summary = failed == 0 ? "敏感字段均已声明有效脱敏策略" : "存在敏感字段漏标或未配置脱敏";
        return checkResult(CATEGORY_SENSITIVE, status, summary, checked, checked - failed, failed,
                "SHARED_SENSITIVITY_RULES");
    }

    /** 判断显式敏感级别是否必须配置不可逆或遮盖型策略。 */
    private boolean isHighSensitivity(String level) {
        return "HIGH".equalsIgnoreCase(level) || "CRITICAL".equalsIgnoreCase(level);
    }

    /**
     * 按统一严重级别策略处理尚未消除的不确定项。
     *
     * <p>BLOCKING 阻止提交，WARNING 只进入警告集合，INFO 仅保留在草稿正文中供页面解释。
     * 粒度、敏感字段、跨表/跨资产、目标版本漂移和越权修改属于高风险分类，即使上游误标为
     * WARNING 也会在这里升级为阻塞，确保报告统计和 submit gate 使用同一服务端结果。</p>
     */
    private ModelingValidationCheckResult inspectUncertainties(ModelingDraftPayload payload,
            List<ModelingValidationFinding> blocking,
            List<ModelingValidationFinding> warnings) {
        List<UncertaintyDraft> uncertainties = safe(payload.getUncertainties());
        int blockingCount = 0;
        int warningCount = 0;
        for (UncertaintyDraft uncertainty : uncertainties) {
            if ("ALIAS_CONFLICT".equalsIgnoreCase(uncertainty.getCategory())) {
                // 名称冲突已由 inspectConflicts 生成带专用 code 的唯一 finding。
                continue;
            }
            String severity = StringUtils.upperCase(
                    StringUtils.defaultIfBlank(uncertainty.getSeverity(),
                            ModelingDraftConstants.FINDING_BLOCKING),
                    Locale.ROOT);
            String category = StringUtils.upperCase(
                    StringUtils.defaultString(uncertainty.getCategory()), Locale.ROOT);
            boolean highRisk = HIGH_RISK_UNCERTAINTY_CATEGORIES.contains(category);
            String message = sensitivityClassifier.sanitizeText(
                    StringUtils.defaultIfBlank(uncertainty.getReason(), "存在尚未处理的不确定项"));
            if (ModelingDraftConstants.FINDING_BLOCKING.equals(severity) || highRisk
                    || (!ModelingDraftConstants.FINDING_WARNING.equals(severity)
                            && !ModelingDraftConstants.FINDING_INFO.equals(severity))) {
                blocking.add(finding(CATEGORY_UNCERTAINTY, "UNRESOLVED_UNCERTAINTY",
                        ModelingDraftConstants.FINDING_BLOCKING, "$.uncertainties", message,
                        uncertainty.getModelKey()));
                blockingCount++;
            } else if (ModelingDraftConstants.FINDING_WARNING.equals(severity)) {
                warnings.add(finding(CATEGORY_UNCERTAINTY, "UNRESOLVED_UNCERTAINTY",
                        ModelingDraftConstants.FINDING_WARNING, "$.uncertainties", message,
                        uncertainty.getModelKey()));
                warningCount++;
            }
        }
        String status = blockingCount > 0 ? ModelingDraftConstants.VALIDATION_FAILED
                : warningCount > 0 ? ModelingDraftConstants.VALIDATION_WARNING
                        : ModelingDraftConstants.VALIDATION_PASSED;
        String summary = blockingCount > 0 ? "存在阻塞级业务问题，提交审批前必须修订"
                : warningCount > 0 ? "存在需要管理员关注的警告，但不阻止提交" : "不存在阻塞级不确定项";
        return checkResult(CATEGORY_UNCERTAINTY, status, summary, uncertainties.size(),
                uncertainties.size() - blockingCount, blockingCount, "DRAFT_UNCERTAINTIES");
    }

    /** 创建字段级验证发现。 */
    private ModelingValidationFinding finding(String category, String code, String severity,
            String path, String message, String modelKey) {
        return ModelingValidationFinding.builder().category(category).code(code).severity(severity)
                .path(path).message(message).modelKey(modelKey).build();
    }

    /** 创建通过状态检查摘要。 */
    private ModelingValidationCheckResult passed(String category, String summary, int checked,
            String mode) {
        return checkResult(category, "PASSED", summary, checked, checked, 0, mode);
    }

    /** 创建通用检查摘要。 */
    private ModelingValidationCheckResult checkResult(String category, String status,
            String summary, int checked, int passed, int failed, String mode) {
        return ModelingValidationCheckResult.builder().category(category).status(status)
                .summary(summary).checkedCount(checked).passedCount(passed).failedCount(failed)
                .mode(mode).build();
    }

    /** 把表名映射规范为大小写无关索引。 */
    private Map<String, List<DBColumn>> normalizeColumns(
            Map<String, List<DBColumn>> columnsByTable) {
        Map<String, List<DBColumn>> normalized = new HashMap<>();
        if (columnsByTable != null) {
            columnsByTable.forEach((table, columns) -> normalized.put(normalize(table),
                    columns == null ? List.of() : List.copyOf(columns)));
        }
        return normalized;
    }

    /** 名称展示优先业务名。 */
    private String displayName(String bizName, String name) {
        return StringUtils.defaultIfBlank(bizName, name);
    }

    /** 对可空列表安全退化为空列表。 */
    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 规范对象 key 与物理名称。 */
    private static String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /** 一条可能进入召回词典的名称候选。 */
    private record RetrievalNameCandidate(String objectType, String objectKey, String modelKey,
            String path, String rawValue, boolean alias, String normalizedToken) {}

}
