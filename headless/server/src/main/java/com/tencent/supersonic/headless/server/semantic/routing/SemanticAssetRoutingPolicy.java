package com.tencent.supersonic.headless.server.semantic.routing;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 语义资产路由策略引擎。
 *
 * <p>
 * 职责：把确定性覆盖证据与已经过 Advisor 白名单校验的语义建议合并，再由服务端裁决最终动作。 完整覆盖等强规则不会被 LLM 覆盖；候选、动作、能力或粒度证据冲突时统一降级为人工澄清。
 * 组件不保存请求状态，也不依赖本机缓存，可被单例服务并发安全复用。
 * </p>
 */
@Component
public class SemanticAssetRoutingPolicy {

    private static final Set<String> ALLOWED_RESULT_OPERATIONS =
            Set.of("ORDER_ASC", "ORDER_DESC", "TOP_N", "PAGINATION");

    /**
     * 裁决最终路由动作。
     *
     * <p>
     * 调用示例：{@code policy.decide(coverage, advice)}。完整覆盖优先复用；其余场景会核对 Advisor 的动作和
     * candidateHandle，并把覆盖、缺失能力与意图中的查询层操作纳入最终快照。
     * </p>
     *
     * @param coverage 确定性覆盖证据。
     * @param advice 已通过白名单校验的可选 LLM 建议。
     * @return 可持久化的服务端裁决。
     */
    public SemanticAssetRoutingDecision decide(SemanticAssetCoverageResult coverage,
            Optional<SemanticAssetRoutingAdvice> advice) {
        SemanticAssetCoverageResult safeCoverage =
                coverage == null ? SemanticAssetCoverageResult.builder().build() : coverage;
        Optional<SemanticAssetRoutingAdvice> safeAdvice =
                advice == null ? Optional.empty() : advice.filter(Objects::nonNull);
        SemanticAssetCoverageResult.CandidateCoverage primary = safeCoverage.primaryCandidate();
        SemanticAssetCoverageResult.CandidateCoverage advisedCandidate =
                findAdvisedCandidate(safeCoverage, safeAdvice);
        SemanticAssetCoverageResult.CandidateCoverage evidenceCandidate =
                selectEvidenceCandidate(primary, advisedCandidate);
        List<SemanticAssetBusinessQuestion> questions = mergeQuestions(
                safeCoverage.getBusinessQuestions(),
                safeAdvice.map(SemanticAssetRoutingAdvice::getBusinessQuestions).orElse(List.of()));
        List<String> operations =
                mergeResultOperations(safeCoverage.getResultOperations(), safeAdvice);
        SemanticAssetDecisionSource source =
                safeAdvice.isPresent() ? SemanticAssetDecisionSource.RULE_AND_LLM
                        : SemanticAssetDecisionSource.RULE_ONLY;

        // 完整覆盖是可复核的强事实；生产链路会跳过 Advisor，这里仍防御性地禁止冲突建议制造缺口。
        if (primary != null && primary.isCompleteCoverage()) {
            return decision(SemanticAssetRouteAction.REUSE_EXISTING, primary, source,
                    "现有资产已完整覆盖业务能力，排序、Top N 和分页由查询层处理", questions,
                    safeCoverage.getResultOperations(), Optional.empty());
        }

        String adviceConflict = adviceConflict(primary, advisedCandidate, safeAdvice);
        if (questions.stream().anyMatch(SemanticAssetBusinessQuestion::isRequired)) {
            return clarification(evidenceCandidate, source,
                    clarificationExplanation(adviceConflict, safeAdvice, "仍有必答业务口径，确认前不能创建或增强资产"),
                    questions, operations, safeAdvice);
        }
        if (safeCoverage.isCloseCandidates()) {
            return clarification(evidenceCandidate, source,
                    clarificationExplanation(adviceConflict, safeAdvice, "多个候选覆盖度接近，需要管理员确认目标资产"),
                    questions, operations, safeAdvice);
        }
        if (primary != null && (!primary.isGrainCompatible() || !primary.isSubjectCompatible())) {
            return clarification(evidenceCandidate, source, clarificationExplanation(adviceConflict,
                    safeAdvice, "候选主题或事实粒度与请求不完全一致，不能仅凭名称自动增强"), questions, operations, safeAdvice);
        }
        if (hasCapabilityConflict(evidenceCandidate, safeAdvice)) {
            return clarification(evidenceCandidate, source, "AI 语义比较与确定性覆盖证据存在能力冲突，需要管理员确认业务口径",
                    questions, operations, safeAdvice);
        }
        if (hasIntentGrainConflict(evidenceCandidate, safeAdvice)) {
            return clarification(evidenceCandidate, source, "AI 识别的业务粒度与候选资产粒度不一致，需要管理员确认",
                    questions, operations, safeAdvice);
        }
        if (hasUnsupportedIntentOperation(safeAdvice)) {
            return clarification(evidenceCandidate, source, "AI 建议包含未支持的查询层操作，需要管理员确认", questions,
                    operations, safeAdvice);
        }

        if (isSafeSmallExtension(primary)) {
            if (StringUtils.isNotBlank(adviceConflict)) {
                return clarification(evidenceCandidate, source, adviceConflict, questions,
                        operations, safeAdvice);
            }
            List<SemanticAssetCapabilityGap> mergedMissing =
                    mergeMissingCapabilities(primary, safeAdvice);
            // Advisor 识别出的额外缺口也必须接受“小范围增量”上限，避免借语义建议扩大修改范围。
            if (mergedMissing
                    .size() > SemanticAssetRoutingConstants.MAX_EXTENSION_MISSING_CAPABILITIES) {
                return clarification(primary, source, "待补充能力已超出安全增强范围，需要管理员确认是否拆分或新建资产", questions,
                        operations, safeAdvice);
            }
            return decision(SemanticAssetRouteAction.EXTEND_EXISTING, primary, source,
                    safeAdvice.filter(item -> StringUtils.isNotBlank(item.getExplanation()))
                            .map(SemanticAssetRoutingAdvice::getExplanation)
                            .orElse("现有资产覆盖主体和粒度，仅缺少少量可控增量能力"),
                    questions, operations, safeAdvice);
        }

        if (primary == null && safeCoverage.isBusinessBoundaryClear()) {
            if (safeAdvice.isPresent() && (safeAdvice.orElseThrow()
                    .getRecommendedAction() != SemanticAssetRouteAction.CREATE_NEW
                    || StringUtils.isNotBlank(safeAdvice.orElseThrow().getCandidateHandle()))) {
                return clarification(null, source, "AI 建议动作与无候选的新建强规则冲突，需要管理员确认", questions,
                        operations, safeAdvice);
            }
            return SemanticAssetRoutingDecision.builder()
                    .action(SemanticAssetRouteAction.CREATE_NEW).decisionSource(source)
                    .explanation(
                            safeAdvice.filter(item -> StringUtils.isNotBlank(item.getExplanation()))
                                    .map(SemanticAssetRoutingAdvice::getExplanation)
                                    .orElse("当前授权范围内没有可复用候选，且业务边界已明确"))
                    .coveredCapabilities(List.of())
                    .missingCapabilities(mergeMissingCapabilities(
                            toGaps(safeCoverage.getRequestedCapabilities()), safeAdvice, null))
                    .resultOperations(operations).businessQuestions(questions)
                    .technicalEvidence(mergeTechnicalEvidence(List.of("授权候选集合为空"), safeAdvice))
                    .build();
        }

        return clarification(evidenceCandidate, source,
                clarificationExplanation(adviceConflict, safeAdvice, "现有证据不足以安全决定新建或增强，请补充业务边界"),
                questions, operations, safeAdvice);
    }

    /** 判断是否满足同主题同粒度的小范围增强边界。 */
    private boolean isSafeSmallExtension(SemanticAssetCoverageResult.CandidateCoverage candidate) {
        return candidate != null && candidate.isSubjectCompatible() && candidate.isGrainCompatible()
                && !candidate.isCompleteCoverage()
                && candidate.getScore() >= SemanticAssetRoutingConstants.MIN_EXTENSION_SCORE
                && !safe(candidate.getMissingCapabilities()).isEmpty()
                && candidate.getMissingCapabilities()
                        .size() <= SemanticAssetRoutingConstants.MAX_EXTENSION_MISSING_CAPABILITIES;
    }

    /** 找到 Advisor handle 对应的确定性覆盖条目；异常缺失按冲突处理而非回退主候选。 */
    private SemanticAssetCoverageResult.CandidateCoverage findAdvisedCandidate(
            SemanticAssetCoverageResult coverage, Optional<SemanticAssetRoutingAdvice> advice) {
        String handle = advice.map(SemanticAssetRoutingAdvice::getCandidateHandle)
                .filter(StringUtils::isNotBlank).orElse(null);
        if (handle == null) {
            return null;
        }
        return safe(coverage.getCandidateCoverages()).stream()
                .filter(item -> item != null && item.getCandidate() != null)
                .filter(item -> StringUtils.equals(item.getCandidate().getCandidateHandle(),
                        handle))
                .findFirst().orElse(null);
    }

    /** 冲突场景优先展示 Advisor 指向的合理候选，同时保留其他候选供管理员比较。 */
    private SemanticAssetCoverageResult.CandidateCoverage selectEvidenceCandidate(
            SemanticAssetCoverageResult.CandidateCoverage primary,
            SemanticAssetCoverageResult.CandidateCoverage advisedCandidate) {
        return isReasonableCandidate(advisedCandidate) ? advisedCandidate : primary;
    }

    /** 判断候选是否至少满足可讨论的主题、粒度和基础得分边界。 */
    private boolean isReasonableCandidate(SemanticAssetCoverageResult.CandidateCoverage candidate) {
        return candidate != null && candidate.isSubjectCompatible() && candidate.isGrainCompatible()
                && candidate.getScore() >= SemanticAssetRoutingConstants.MIN_EXTENSION_SCORE;
    }

    /** 返回 Advisor 与确定性候选或动作冲突的安全说明；无冲突时返回 null。 */
    private String adviceConflict(SemanticAssetCoverageResult.CandidateCoverage primary,
            SemanticAssetCoverageResult.CandidateCoverage advisedCandidate,
            Optional<SemanticAssetRoutingAdvice> advice) {
        if (advice.isEmpty()) {
            return null;
        }
        SemanticAssetRoutingAdvice value = advice.orElseThrow();
        String handle = value.getCandidateHandle();
        if (StringUtils.isNotBlank(handle) && advisedCandidate == null) {
            return "AI 建议候选不在当前确定性覆盖快照中，需要重新分析";
        }
        if (StringUtils.isNotBlank(handle) && primary != null
                && !StringUtils.equals(handle, primary.getCandidate().getCandidateHandle())) {
            return isReasonableCandidate(advisedCandidate) ? "规则主候选与 AI 语义候选不一致，需要管理员确认目标资产"
                    : "AI 建议候选未满足服务端主题、粒度或覆盖边界，需要管理员确认";
        }
        if (isSafeSmallExtension(primary)
                && value.getRecommendedAction() != SemanticAssetRouteAction.EXTEND_EXISTING) {
            return "AI 建议动作与服务端安全增强规则冲突，需要管理员确认";
        }
        return null;
    }

    /** 优先解释候选冲突，其次解释动作冲突，最后使用规则澄清原因。 */
    private String clarificationExplanation(String candidateConflict,
            Optional<SemanticAssetRoutingAdvice> advice, String ruleExplanation) {
        if (StringUtils.isNotBlank(candidateConflict)) {
            return candidateConflict;
        }
        if (advice.isPresent() && advice.orElseThrow()
                .getRecommendedAction() != SemanticAssetRouteAction.NEEDS_CLARIFICATION) {
            return "AI 建议动作与服务端风险规则冲突，需要管理员确认";
        }
        return advice.filter(item -> StringUtils.isNotBlank(item.getExplanation()))
                .map(SemanticAssetRoutingAdvice::getExplanation).orElse(ruleExplanation);
    }

    /** 构造统一的人工澄清决策。 */
    private SemanticAssetRoutingDecision clarification(
            SemanticAssetCoverageResult.CandidateCoverage candidate,
            SemanticAssetDecisionSource source, String explanation,
            List<SemanticAssetBusinessQuestion> questions, List<String> operations,
            Optional<SemanticAssetRoutingAdvice> advice) {
        return decision(SemanticAssetRouteAction.NEEDS_CLARIFICATION, candidate, source,
                explanation, questions, operations, advice);
    }

    /** 构造带规则和 Advisor 补充证据的统一决策。 */
    private SemanticAssetRoutingDecision decision(SemanticAssetRouteAction action,
            SemanticAssetCoverageResult.CandidateCoverage candidate,
            SemanticAssetDecisionSource source, String explanation,
            List<SemanticAssetBusinessQuestion> questions, List<String> operations,
            Optional<SemanticAssetRoutingAdvice> advice) {
        return SemanticAssetRoutingDecision.builder().action(action)
                .candidateHandle(candidate == null || candidate.getCandidate() == null ? null
                        : candidate.getCandidate().getCandidateHandle())
                .decisionSource(source).explanation(explanation)
                .coveredCapabilities(mergeCoveredCapabilities(candidate, advice))
                .missingCapabilities(mergeMissingCapabilities(candidate, advice))
                .resultOperations(safe(operations)).businessQuestions(questions)
                .technicalEvidence(mergeTechnicalEvidence(
                        candidate == null ? List.of() : safe(candidate.getTechnicalEvidence()),
                        advice))
                .build();
    }

    /** 合并规则覆盖能力、Advisor 覆盖能力，以及可由候选快照再次确认的意图能力。 */
    private List<String> mergeCoveredCapabilities(
            SemanticAssetCoverageResult.CandidateCoverage candidate,
            Optional<SemanticAssetRoutingAdvice> advice) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        addNamedValues(merged,
                candidate == null ? List.of() : safe(candidate.getCoveredCapabilities()));
        advice.ifPresent(item -> addNamedValues(merged, safe(item.getCoveredCapabilities())));
        Set<String> candidateCapabilities = candidateCapabilities(candidate);
        for (String capability : intentCapabilities(advice)) {
            String key = normalize(capability);
            if (candidateCapabilities.contains(key)) {
                merged.putIfAbsent(key, capability);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 合并规则缺口、Advisor 结构化缺口，并把未分类的意图能力按不足证据处理。 */
    private List<SemanticAssetCapabilityGap> mergeMissingCapabilities(
            SemanticAssetCoverageResult.CandidateCoverage candidate,
            Optional<SemanticAssetRoutingAdvice> advice) {
        List<SemanticAssetCapabilityGap> rules =
                candidate == null ? List.of() : toGaps(candidate.getMissingCapabilities());
        return mergeMissingCapabilities(rules, advice, candidate);
    }

    /** 按稳定能力名合并缺口；Advisor 的结构化类型和原因优先于规则通用说明。 */
    private List<SemanticAssetCapabilityGap> mergeMissingCapabilities(
            List<SemanticAssetCapabilityGap> rules, Optional<SemanticAssetRoutingAdvice> advice,
            SemanticAssetCoverageResult.CandidateCoverage candidate) {
        LinkedHashMap<String, SemanticAssetCapabilityGap> merged = new LinkedHashMap<>();
        for (SemanticAssetCapabilityGap gap : safe(rules)) {
            if (gap != null && StringUtils.isNotBlank(gap.getName())) {
                merged.putIfAbsent(normalize(gap.getName()), gap);
            }
        }
        advice.map(SemanticAssetRoutingAdvice::getMissingCapabilities).stream()
                .flatMap(List::stream).filter(Objects::nonNull)
                .filter(gap -> StringUtils.isNotBlank(gap.getName()))
                .forEach(gap -> merged.put(normalize(gap.getName()), gap));

        Set<String> covered = new LinkedHashSet<>();
        mergeCoveredCapabilities(candidate, advice).stream().map(this::normalize)
                .forEach(covered::add);
        for (String capability : intentCapabilities(advice)) {
            String key = normalize(capability);
            if (!covered.contains(key) && !merged.containsKey(key)) {
                merged.put(key, SemanticAssetCapabilityGap.builder().type("INTENT_CAPABILITY")
                        .name(capability).reason("AI 语义意图识别到该能力，但候选覆盖证据不足").build());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 检查 Advisor 对规则已覆盖和缺失能力是否给出相反结论。 */
    private boolean hasCapabilityConflict(SemanticAssetCoverageResult.CandidateCoverage candidate,
            Optional<SemanticAssetRoutingAdvice> advice) {
        if (candidate == null || advice.isEmpty()) {
            return false;
        }
        Set<String> ruleCovered = normalized(safe(candidate.getCoveredCapabilities()));
        Set<String> ruleMissing = normalized(safe(candidate.getMissingCapabilities()));
        Set<String> advisedCovered =
                normalized(safe(advice.orElseThrow().getCoveredCapabilities()));
        Set<String> advisedMissing = advice.orElseThrow().getMissingCapabilities() == null
                ? Set.of()
                : advice.orElseThrow().getMissingCapabilities().stream().filter(Objects::nonNull)
                        .map(SemanticAssetCapabilityGap::getName).filter(StringUtils::isNotBlank)
                        .map(this::normalize).collect(java.util.stream.Collectors.toSet());
        return advisedCovered.stream().anyMatch(ruleMissing::contains)
                || advisedMissing.stream().anyMatch(ruleCovered::contains);
    }

    /** 对比已知候选粒度与 Advisor 意图粒度；完全不相交才视为高风险冲突。 */
    private boolean hasIntentGrainConflict(SemanticAssetCoverageResult.CandidateCoverage candidate,
            Optional<SemanticAssetRoutingAdvice> advice) {
        if (candidate == null || candidate.getCandidate() == null || advice.isEmpty()
                || advice.orElseThrow().getIntent() == null) {
            return false;
        }
        List<String> candidateGrain = safe(candidate.getCandidate().getGrain()).stream()
                .filter(StringUtils::isNotBlank).toList();
        List<String> intentGrain = safe(advice.orElseThrow().getIntent().getGrain()).stream()
                .filter(StringUtils::isNotBlank).toList();
        if (candidateGrain.isEmpty() || intentGrain.isEmpty()) {
            return false;
        }
        return intentGrain.stream().noneMatch(
                intent -> candidateGrain.stream().anyMatch(known -> comparableName(intent, known)));
    }

    /** 未知查询层操作不能进入可执行语义，也不能被静默丢弃。 */
    private boolean hasUnsupportedIntentOperation(Optional<SemanticAssetRoutingAdvice> advice) {
        return advice.map(SemanticAssetRoutingAdvice::getIntent)
                .map(SemanticAssetRoutingAdvice.Intent::getResultOperations).stream()
                .flatMap(List::stream).filter(StringUtils::isNotBlank)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> !ALLOWED_RESULT_OPERATIONS.contains(value));
    }

    /** 合并规则与 Advisor 意图中的受支持查询层操作。 */
    private List<String> mergeResultOperations(List<String> ruleOperations,
            Optional<SemanticAssetRoutingAdvice> advice) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(safe(ruleOperations));
        advice.map(SemanticAssetRoutingAdvice::getIntent)
                .map(SemanticAssetRoutingAdvice.Intent::getResultOperations).stream()
                .flatMap(List::stream).filter(StringUtils::isNotBlank)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(ALLOWED_RESULT_OPERATIONS::contains).forEach(merged::add);
        return new ArrayList<>(merged);
    }

    /** 返回 Advisor 意图中的维度和指标能力，保持模型输出顺序。 */
    private List<String> intentCapabilities(Optional<SemanticAssetRoutingAdvice> advice) {
        if (advice.isEmpty() || advice.orElseThrow().getIntent() == null) {
            return List.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        addNamedValues(values, safe(advice.orElseThrow().getIntent().getDimensions()));
        addNamedValues(values, safe(advice.orElseThrow().getIntent().getMeasures()));
        return new ArrayList<>(values.values());
    }

    /** 收集候选快照中可再次核对的维度、指标和时间能力。 */
    private Set<String> candidateCapabilities(
            SemanticAssetCoverageResult.CandidateCoverage candidate) {
        if (candidate == null || candidate.getCandidate() == null) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        safe(candidate.getCandidate().getDimensionCapabilities()).stream().map(this::normalize)
                .forEach(values::add);
        safe(candidate.getCandidate().getMetricCapabilities()).stream().map(this::normalize)
                .forEach(values::add);
        safe(candidate.getCandidate().getTimeCapabilities()).stream().map(this::normalize)
                .forEach(values::add);
        return values;
    }

    /** 把 Advisor 的安全主题和粒度摘要加入可审计技术证据。 */
    private List<String> mergeTechnicalEvidence(List<String> rules,
            Optional<SemanticAssetRoutingAdvice> advice) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(safe(rules));
        advice.map(SemanticAssetRoutingAdvice::getIntent).ifPresent(intent -> {
            if (StringUtils.isNotBlank(intent.getSubject())) {
                merged.add("AI 识别业务主题：" + intent.getSubject());
            }
            if (!safe(intent.getGrain()).isEmpty()) {
                merged.add("AI 识别事实粒度：" + String.join("、", intent.getGrain()));
            }
        });
        return new ArrayList<>(merged);
    }

    /** 把字符串缺口转换为结构化能力说明。 */
    private List<SemanticAssetCapabilityGap> toGaps(List<String> missing) {
        return safe(missing)
                .stream().filter(StringUtils::isNotBlank).map(name -> SemanticAssetCapabilityGap
                        .builder().type("CAPABILITY").name(name).reason("候选资产未覆盖该业务能力").build())
                .toList();
    }

    /** 按稳定 key 合并规则和 LLM 问题，规则问题优先。 */
    private List<SemanticAssetBusinessQuestion> mergeQuestions(
            List<SemanticAssetBusinessQuestion> rules,
            List<SemanticAssetBusinessQuestion> llmQuestions) {
        Map<String, SemanticAssetBusinessQuestion> merged = new LinkedHashMap<>();
        for (SemanticAssetBusinessQuestion question : safe(rules)) {
            if (question != null && StringUtils.isNotBlank(question.getKey())) {
                merged.putIfAbsent(question.getKey(), question);
            }
        }
        for (SemanticAssetBusinessQuestion question : safe(llmQuestions)) {
            if (question != null && StringUtils.isNotBlank(question.getKey())) {
                merged.putIfAbsent(question.getKey(), question);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 按规范化名称去重追加文本，同时保留首个展示值。 */
    private void addNamedValues(Map<String, String> target, List<String> values) {
        safe(values).stream().filter(StringUtils::isNotBlank)
                .forEach(value -> target.putIfAbsent(normalize(value), value));
    }

    /** 将文本列表转换为规范化名称集合。 */
    private Set<String> normalized(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        safe(values).stream().filter(StringUtils::isNotBlank).map(this::normalize)
                .forEach(normalized::add);
        return normalized;
    }

    /** 对业务名称做仅用于比较的轻量规范化，不修改持久化展示值。 */
    private String normalize(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-]+", "");
    }

    /** 判断两个粒度名称是否具备包含关系，以兼容“物料”和“物料编码”等表达。 */
    private boolean comparableName(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        return StringUtils.isNotBlank(normalizedLeft) && StringUtils.isNotBlank(normalizedRight)
                && (normalizedLeft.contains(normalizedRight)
                        || normalizedRight.contains(normalizedLeft));
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
