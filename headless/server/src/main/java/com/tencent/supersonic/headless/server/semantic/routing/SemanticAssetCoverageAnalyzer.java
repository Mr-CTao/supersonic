package com.tencent.supersonic.headless.server.semantic.routing;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义资产确定性覆盖分析器。
 *
 * <p>
 * 职责：从业务目标中分离查询层操作、请求能力和粒度，再用可解释加权规则比较已授权候选。分析器 不调用 LLM、不读取数据库、不信任模型自报置信度；组件无共享可变状态，可并发复用。
 * </p>
 */
@Component
public class SemanticAssetCoverageAnalyzer {

    private static final int SCORE_TRACE_UNIT = 1;
    private static final int SCORE_SELECTED_TABLE = 25;
    private static final int SCORE_SUBJECT = 25;
    private static final int SCORE_GRAIN = 20;
    private static final int SCORE_GRAIN_MISMATCH = 50;
    private static final int SCORE_COVERED_CAPABILITY = 8;
    private static final int SCORE_MISSING_CAPABILITY = 8;
    private static final Pattern TOP_N_PATTERN =
            Pattern.compile("(?:前|top\\s*|最多\\s*)([一二三四五六七八九十百\\d]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_GRAIN_PATTERN =
            Pattern.compile("按(.{1,30}?)(?:统计|汇总|分析|查看|查询)");
    private static final Set<String> GENERIC_TERMS =
            Set.of("数据", "信息", "明细", "列表", "结果", "业务", "系统", "仓库", "情况");

    /**
     * 分析请求与候选覆盖度。
     *
     * <p>
     * 调用示例：{@code analyze(goal, tables, answers, candidates)}。查询层 Top N、排序和分页只进入
     * resultOperations，不会成为缺失语义能力。
     * </p>
     *
     * @param businessGoal 业务目标。
     * @param selectedTables 服务端确认的选表。
     * @param businessAnswers 已确认业务答案。
     * @param candidates 已通过 ACL 的候选快照。
     * @return 排序后的确定性覆盖结果。
     */
    public SemanticAssetCoverageResult analyze(String businessGoal, List<String> selectedTables,
            Map<String, Object> businessAnswers, List<SemanticAssetCandidate> candidates) {
        List<String> resultOperations = detectResultOperations(businessGoal);
        List<String> requestedCapabilities = extractRequestedCapabilities(businessGoal);
        List<String> requestedGrain = extractRequestedGrain(businessGoal, businessAnswers);
        List<SemanticAssetCoverageResult.CandidateCoverage> coverages = Objects
                .requireNonNullElse(candidates, List.<SemanticAssetCandidate>of()).stream()
                .filter(Objects::nonNull)
                .map(candidate -> analyzeCandidate(candidate, businessGoal, selectedTables,
                        requestedCapabilities, requestedGrain))
                .sorted(Comparator
                        .comparingInt(SemanticAssetCoverageResult.CandidateCoverage::getScore)
                        .reversed().thenComparing(item -> item.getCandidate().getCandidateHandle()))
                .toList();
        boolean closeCandidates = coverages.size() > 1 && coverages.get(0).getScore() - coverages
                .get(1).getScore() <= SemanticAssetRoutingConstants.CLOSE_CANDIDATE_SCORE_GAP;
        List<SemanticAssetBusinessQuestion> questions =
                buildBusinessQuestions(businessGoal, requestedCapabilities, requestedGrain,
                        businessAnswers, selectedTables);
        boolean boundaryClear = !requestedCapabilities.isEmpty()
                && questions.stream().noneMatch(SemanticAssetBusinessQuestion::isRequired);
        return SemanticAssetCoverageResult.builder().candidateCoverages(coverages)
                .requestedCapabilities(requestedCapabilities).resultOperations(resultOperations)
                .businessQuestions(questions).businessBoundaryClear(boundaryClear)
                .closeCandidates(closeCandidates).build();
    }

    /** 分析单个候选并生成可解释分数。 */
    private SemanticAssetCoverageResult.CandidateCoverage analyzeCandidate(
            SemanticAssetCandidate candidate, String businessGoal, List<String> selectedTables,
            List<String> requestedCapabilities, List<String> requestedGrain) {
        List<String> allCapabilities = new ArrayList<>();
        allCapabilities.addAll(safe(candidate.getDimensionCapabilities()));
        allCapabilities.addAll(safe(candidate.getMetricCapabilities()));
        allCapabilities.addAll(safe(candidate.getTimeCapabilities()));
        List<String> covered = requestedCapabilities.stream()
                .filter(term -> matchesAny(term, allCapabilities, candidate)).distinct().toList();
        List<String> missing = requestedCapabilities.stream()
                .filter(term -> !covered.contains(term)).distinct().toList();
        boolean subjectCompatible = isSubjectCompatible(candidate, businessGoal, selectedTables);
        boolean grainCompatible = isGrainCompatible(requestedGrain, candidate.getGrain());
        int score = candidate.getTracePriority() * SCORE_TRACE_UNIT;
        score += hasSelectedTable(candidate, selectedTables) ? SCORE_SELECTED_TABLE : 0;
        score += subjectCompatible ? SCORE_SUBJECT : 0;
        score += grainCompatible ? SCORE_GRAIN : -SCORE_GRAIN_MISMATCH;
        score += covered.size() * SCORE_COVERED_CAPABILITY;
        score -= missing.size() * SCORE_MISSING_CAPABILITY;
        // 空能力集合不能作为“完整覆盖”的正向证据，否则仅凭同表或相似名称就会误自动复用。
        boolean complete = !requestedCapabilities.isEmpty() && subjectCompatible
                && grainCompatible && missing.isEmpty();
        List<String> evidence = new ArrayList<>(safe(candidate.getEvidenceSources()));
        if (hasSelectedTable(candidate, selectedTables)) {
            evidence.add("候选模型与当前选表一致");
        }
        if (!requestedGrain.isEmpty()) {
            evidence.add(grainCompatible ? "候选粒度覆盖请求粒度" : "候选粒度与请求粒度不一致");
        }
        return SemanticAssetCoverageResult.CandidateCoverage.builder().candidate(candidate)
                .score(score).completeCoverage(complete).subjectCompatible(subjectCompatible)
                .grainCompatible(grainCompatible).coveredCapabilities(covered)
                .missingCapabilities(missing).technicalEvidence(List.copyOf(evidence)).build();
    }

    /** 识别排序、Top N 和分页等查询层操作。 */
    private List<String> detectResultOperations(String goal) {
        String value = StringUtils.defaultString(goal).toLowerCase(Locale.ROOT);
        Set<String> operations = new LinkedHashSet<>();
        if (TOP_N_PATTERN.matcher(value).find()) {
            operations.add("TOP_N");
        }
        if (value.contains("降序") || value.contains("从高到低") || value.contains("最高")
                || operations.contains("TOP_N")) {
            operations.add("ORDER_DESC");
        } else if (value.contains("升序") || value.contains("从低到高") || value.contains("最低")) {
            operations.add("ORDER_ASC");
        }
        if (value.contains("分页") || value.contains("每页")) {
            operations.add("PAGINATION");
        }
        return List.copyOf(operations);
    }

    /** 从目标中提取轻量、可解释的能力短语。 */
    private List<String> extractRequestedCapabilities(String goal) {
        String value = StringUtils.defaultString(goal).toLowerCase(Locale.ROOT);
        value = TOP_N_PATTERN.matcher(value).replaceAll(" ");
        value = value.replaceAll("统计一下|查询一下|看一下|统计|查询|分析|查看|汇总|排序|降序|升序|分页|每页|取|按|并", " ");
        value = value.replaceAll("中的|以及|或者|的|中|和|与|及", " ");
        value = value.replaceAll("[，。；、,;:/\\\\()（）?？\\s]+", " ");
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.split(" ")) {
            String trimmed = token.trim();
            if (trimmed.length() < 2 || GENERIC_TERMS.contains(trimmed)) {
                continue;
            }
            for (String suffix : List.of("信息", "明细", "列表", "结果", "情况")) {
                if (trimmed.endsWith(suffix) && trimmed.length() > suffix.length()) {
                    trimmed = trimmed.substring(0, trimmed.length() - suffix.length());
                }
            }
            if (trimmed.length() >= 2 && !GENERIC_TERMS.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    /** 从结构化答案或“按...汇总/分析”句式提取请求粒度。 */
    private List<String> extractRequestedGrain(String goal, Map<String, Object> answers) {
        Object answer = Objects.requireNonNullElse(answers, Map.<String, Object>of()).get("grain");
        String raw = answer == null ? null : answer.toString();
        if ("MATERIAL".equals(raw)) {
            raw = "物料";
        } else if ("MATERIAL_AND_BATCH".equals(raw)) {
            raw = "物料和批次";
        } else if ("PRIMARY_ENTITY".equals(raw)) {
            raw = "主业务对象";
        } else if ("PRIMARY_ENTITY_AND_DETAIL".equals(raw)) {
            raw = "主业务对象和明细";
        }
        if (StringUtils.isBlank(raw)) {
            Matcher matcher = EXPLICIT_GRAIN_PATTERN.matcher(StringUtils.defaultString(goal));
            raw = matcher.find() ? matcher.group(1) : null;
        }
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        return Pattern.compile("和|与|及|、|,|，").splitAsStream(raw).map(String::trim)
                .filter(StringUtils::isNotBlank).distinct().toList();
    }

    /** 为通用派生时长能力生成日期基准问题，不绑定具体 WMS 字段名。 */
    private List<SemanticAssetBusinessQuestion> buildBusinessQuestions(String businessGoal,
            List<String> capabilities, List<String> grain, Map<String, Object> answers,
            List<String> selectedTables) {
        Map<String, Object> safeAnswers = Objects.requireNonNullElse(answers, Map.of());
        List<SemanticAssetBusinessQuestion> questions = new ArrayList<>();
        boolean durationRequested = capabilities.stream().anyMatch(item -> item.contains("时长"));
        if (durationRequested && !safeAnswers.containsKey("duration_date_basis")) {
            questions.add(SemanticAssetBusinessQuestion.builder().key("duration_date_basis")
                    .question("该时长应按哪个业务日期作为计算基准？").required(true).answerType("SINGLE_SELECT")
                    .affectsRecommendation(true)
                    .options(List.of(option("EARLIEST_BUSINESS_DATE", "最早业务日期"),
                            option("LATEST_BUSINESS_DATE", "最近业务日期")))
                    .build());
        }
        if (durationRequested && !safeAnswers.containsKey("invalid_date_policy")) {
            questions.add(SemanticAssetBusinessQuestion.builder().key("invalid_date_policy")
                    .question("空日期或非法日期应如何处理？").required(true).answerType("SINGLE_SELECT")
                    .affectsRecommendation(false)
                    .options(List.of(option("EXCLUDE", "排除"), option("KEEP_NULL", "保留为空")))
                    .build());
        }
        if (durationRequested && grain.isEmpty() && !safeAnswers.containsKey("grain")) {
            boolean materialContext = StringUtils.containsAnyIgnoreCase(
                    StringUtils.defaultString(businessGoal), "物料", "批次", "material", "batch");
            List<SemanticAssetBusinessQuestion.QuestionOption> grainOptions = materialContext
                    ? List.of(option("MATERIAL", "按物料"),
                            option("MATERIAL_AND_BATCH", "按物料 + 批次"))
                    : List.of(option("PRIMARY_ENTITY", "按主业务对象"),
                            option("PRIMARY_ENTITY_AND_DETAIL", "按主业务对象 + 明细"));
            questions.add(SemanticAssetBusinessQuestion.builder().key("grain")
                    .question(materialContext ? "该能力应按物料还是物料与批次粒度计算？"
                            : "该能力应按主业务对象还是对象与明细粒度计算？")
                    .required(true)
                    .answerType("SINGLE_SELECT").affectsRecommendation(true)
                    .options(grainOptions).build());
        }
        String inventoryEvidence = StringUtils.defaultString(businessGoal) + " "
                + safe(selectedTables).stream().filter(StringUtils::isNotBlank)
                        .collect(java.util.stream.Collectors.joining(" "));
        boolean inventoryContext = StringUtils.containsAnyIgnoreCase(inventoryEvidence, "库存",
                "存量", "stock", "inventory");
        if (durationRequested && inventoryContext
                && !safeAnswers.containsKey("positive_stock_only")) {
            questions.add(SemanticAssetBusinessQuestion.builder().key("positive_stock_only")
                    .question("是否只统计库存数量大于零的记录？").required(true)
                    .answerType("BOOLEAN").affectsRecommendation(false).build());
        }
        return List.copyOf(questions);
    }

    /** 创建稳定业务问题选项。 */
    private SemanticAssetBusinessQuestion.QuestionOption option(String key, String label) {
        return SemanticAssetBusinessQuestion.QuestionOption.builder().key(key).label(label).build();
    }

    /** 判断请求能力是否已由候选名称或对象能力覆盖。 */
    private boolean matchesAny(String requested, List<String> capabilities,
            SemanticAssetCandidate candidate) {
        String normalized = normalize(requested);
        return capabilities.stream().filter(StringUtils::isNotBlank).map(this::normalize)
                .anyMatch(value -> value.contains(normalized) || normalized.contains(value))
                || java.util.stream.Stream.of(candidate.getName(), candidate.getBizName())
                        .filter(StringUtils::isNotBlank).map(this::normalize)
                        .anyMatch(value -> value.contains(normalized));
    }

    /** 判断主题是否由追踪、名称或选表证据支持。 */
    private boolean isSubjectCompatible(SemanticAssetCandidate candidate, String goal,
            List<String> selectedTables) {
        String normalizedGoal = normalize(goal);
        boolean named = java.util.stream.Stream
                .of(candidate.getName(), candidate.getBizName(), candidate.getDescription())
                .filter(StringUtils::isNotBlank).map(this::normalize)
                .anyMatch(name -> name.length() >= 2 && (normalizedGoal.contains(name)
                        || containsChineseFragment(normalizedGoal, name)));
        return candidate.getTracePriority() > 0 || named
                || hasSelectedTable(candidate, selectedTables);
    }

    /** 使用至少两个连续字符判断中文主题片段，避免单字符高频词污染。 */
    private boolean containsChineseFragment(String goal, String candidate) {
        for (int index = 0; index + 2 <= candidate.length(); index++) {
            if (goal.contains(candidate.substring(index, index + 2))) {
                return true;
            }
        }
        return false;
    }

    /** 判断请求粒度是否为空或被候选粒度覆盖。 */
    private boolean isGrainCompatible(List<String> requested, List<String> candidateGrain) {
        if (requested == null || requested.isEmpty()) {
            return true;
        }
        List<String> actual = safe(candidateGrain).stream().map(this::normalize).toList();
        return requested.stream().map(this::normalize).allMatch(item -> actual.stream()
                .anyMatch(value -> value.contains(item) || item.contains(value)));
    }

    /** 判断候选是否使用当前选表。 */
    private boolean hasSelectedTable(SemanticAssetCandidate candidate, List<String> selected) {
        Set<String> requested = safe(selected).stream().map(this::normalize)
                .collect(java.util.stream.Collectors.toSet());
        return safe(candidate.getBaseTables()).stream().map(this::normalize)
                .anyMatch(requested::contains);
    }

    /** 规范化可比较文本。 */
    private String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9_]", "");
    }

    /** 把可空列表转换为只读空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
