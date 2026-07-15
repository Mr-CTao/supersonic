package com.tencent.supersonic.headless.server.semantic.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义资产路由策略测试。
 *
 * <p>
 * 职责：固定服务端强规则优先级，确保 LLM 建议不能覆盖完整复用证据、粒度风险、候选接近或 必答业务问题。测试数据均为不可变值，不涉及共享资源或线程安全问题。
 * </p>
 */
class SemanticAssetRoutingPolicyTest {

    private final SemanticAssetRoutingPolicy policy = new SemanticAssetRoutingPolicy();

    /** 完整覆盖必须优先复用，即使 LLM 建议新建。 */
    @Test
    void shouldPreferReuseOverConflictingLlmCreateAdvice() {
        SemanticAssetCoverageResult coverage = coverage(
                candidateCoverage("candidate_1", 130, true, true, List.of()), false, List.of());
        SemanticAssetRoutingAdvice advice = SemanticAssetRoutingAdvice.builder()
                .recommendedAction(SemanticAssetRouteAction.CREATE_NEW).explanation("建议新建").build();

        SemanticAssetRoutingDecision decision = policy.decide(coverage, Optional.of(advice));

        assertEquals(SemanticAssetRouteAction.REUSE_EXISTING, decision.getAction());
        assertEquals(SemanticAssetDecisionSource.RULE_AND_LLM, decision.getDecisionSource());
    }

    /** 同主题同粒度且只有少量缺失能力时应推荐增量增强。 */
    @Test
    void shouldExtendForSmallCompatibleGap() {
        SemanticAssetCoverageResult coverage =
                coverage(candidateCoverage("candidate_1", 105, false, true, List.of("呆滞时长")), false,
                        List.of());

        SemanticAssetRoutingDecision decision = policy.decide(coverage, Optional.empty());

        assertEquals(SemanticAssetRouteAction.EXTEND_EXISTING, decision.getAction());
        assertEquals("candidate_1", decision.getCandidateHandle());
    }

    /** 候选粒度不兼容时不能仅因名称相似自动增强。 */
    @Test
    void shouldClarifyForIncompatibleGrain() {
        SemanticAssetCoverageResult coverage =
                coverage(candidateCoverage("candidate_1", 110, false, false, List.of("仓库库存")),
                        false, List.of());

        assertEquals(SemanticAssetRouteAction.NEEDS_CLARIFICATION,
                policy.decide(coverage, Optional.empty()).getAction());
    }

    /** 没有候选且业务边界清楚时才允许推荐新建。 */
    @Test
    void shouldCreateOnlyWhenNoCandidateAndBoundaryIsClear() {
        SemanticAssetCoverageResult coverage = SemanticAssetCoverageResult.builder()
                .candidateCoverages(List.of()).requestedCapabilities(List.of("订单履约率"))
                .resultOperations(List.of()).businessQuestions(List.of())
                .businessBoundaryClear(true).closeCandidates(false).build();

        assertEquals(SemanticAssetRouteAction.CREATE_NEW,
                policy.decide(coverage, Optional.empty()).getAction());
    }

    /** 第一、第二候选接近时必须人工澄清。 */
    @Test
    void shouldClarifyWhenTopCandidatesAreClose() {
        SemanticAssetCoverageResult coverage =
                SemanticAssetCoverageResult.builder()
                        .candidateCoverages(List.of(
                                candidateCoverage("candidate_1", 100, false, true, List.of("呆滞时长")),
                                candidateCoverage("candidate_2", 96, false, true, List.of("呆滞时长"))))
                        .requestedCapabilities(List.of("呆滞时长")).resultOperations(List.of())
                        .businessQuestions(List.of()).businessBoundaryClear(true)
                        .closeCandidates(true).build();

        assertEquals(SemanticAssetRouteAction.NEEDS_CLARIFICATION,
                policy.decide(coverage, Optional.empty()).getAction());
    }

    /** 存在必答业务问题时必须优先澄清。 */
    @Test
    void shouldClarifyWhenRequiredBusinessQuestionIsUnresolved() {
        SemanticAssetBusinessQuestion question =
                SemanticAssetBusinessQuestion.builder().key("date_basis").question("按最早还是最近日期计算？")
                        .required(true).answerType("SINGLE_SELECT").build();
        SemanticAssetCoverageResult coverage =
                coverage(candidateCoverage("candidate_1", 105, false, true, List.of("呆滞时长")), false,
                        List.of(question));

        assertEquals(SemanticAssetRouteAction.NEEDS_CLARIFICATION,
                policy.decide(coverage, Optional.empty()).getAction());
    }

    /** Advisor 指向另一个合理候选时不得静默沿用规则主候选。 */
    @Test
    void shouldClarifyWhenAdvisorSelectsAnotherReasonableCandidate() {
        SemanticAssetCoverageResult coverage =
                SemanticAssetCoverageResult.builder()
                        .candidateCoverages(List.of(
                                candidateCoverage("candidate_1", 110, false, true, List.of("呆滞时长")),
                                candidateCoverage("candidate_2", 90, false, true, List.of("周转天数"))))
                        .requestedCapabilities(List.of("呆滞时长")).resultOperations(List.of())
                        .businessQuestions(List.of()).businessBoundaryClear(true)
                        .closeCandidates(false).build();
        SemanticAssetRoutingAdvice advice = SemanticAssetRoutingAdvice.builder()
                .recommendedAction(SemanticAssetRouteAction.EXTEND_EXISTING)
                .candidateHandle("candidate_2").explanation("第二候选语义更接近").build();

        SemanticAssetRoutingDecision decision = policy.decide(coverage, Optional.of(advice));

        assertEquals(SemanticAssetRouteAction.NEEDS_CLARIFICATION, decision.getAction());
        assertEquals("candidate_2", decision.getCandidateHandle());
        assertTrue(decision.getExplanation().contains("候选不一致"));
    }

    /** 一致的 Advisor 建议应补充覆盖、缺失能力和意图中的查询层操作。 */
    @Test
    void shouldMergeAdvisorEvidenceForConsistentExtension() {
        SemanticAssetCoverageResult coverage =
                coverage(candidateCoverage("candidate_1", 105, false, true, List.of("呆滞时长")), false,
                        List.of());
        SemanticAssetRoutingAdvice advice = SemanticAssetRoutingAdvice.builder()
                .recommendedAction(SemanticAssetRouteAction.EXTEND_EXISTING)
                .candidateHandle("candidate_1").coveredCapabilities(List.of("批次"))
                .missingCapabilities(List.of(SemanticAssetCapabilityGap.builder()
                        .type("DERIVED_FIELD").name("呆滞基准日期").reason("候选缺少日期基准").build()))
                .intent(SemanticAssetRoutingAdvice.Intent.builder().dimensions(List.of("物料"))
                        .measures(List.of("呆滞时长")).resultOperations(List.of("TOP_N")).build())
                .explanation("同主题同粒度，仅需补充派生能力").build();

        SemanticAssetRoutingDecision decision = policy.decide(coverage, Optional.of(advice));

        assertEquals(SemanticAssetRouteAction.EXTEND_EXISTING, decision.getAction());
        assertTrue(decision.getCoveredCapabilities().contains("批次"));
        assertTrue(decision.getMissingCapabilities().stream()
                .anyMatch(gap -> "呆滞时长".equals(gap.getName())));
        assertTrue(decision.getMissingCapabilities().stream()
                .anyMatch(gap -> "呆滞基准日期".equals(gap.getName())));
        assertTrue(decision.getResultOperations().contains("TOP_N"));
    }

    /** Advisor 动作与安全增强强规则冲突时必须进入人工确认。 */
    @Test
    void shouldClarifyWhenAdvisorActionConflictsWithExtensionRule() {
        SemanticAssetCoverageResult coverage =
                coverage(candidateCoverage("candidate_1", 105, false, true, List.of("呆滞时长")), false,
                        List.of());
        SemanticAssetRoutingAdvice advice = SemanticAssetRoutingAdvice.builder()
                .recommendedAction(SemanticAssetRouteAction.CREATE_NEW).explanation("建议新建").build();

        SemanticAssetRoutingDecision decision = policy.decide(coverage, Optional.of(advice));

        assertEquals(SemanticAssetRouteAction.NEEDS_CLARIFICATION, decision.getAction());
        assertTrue(decision.getExplanation().contains("动作"));
    }

    /** 创建单候选覆盖结果。 */
    private SemanticAssetCoverageResult coverage(
            SemanticAssetCoverageResult.CandidateCoverage candidate, boolean close,
            List<SemanticAssetBusinessQuestion> questions) {
        return SemanticAssetCoverageResult.builder().candidateCoverages(List.of(candidate))
                .requestedCapabilities(candidate.getMissingCapabilities())
                .resultOperations(List.of()).businessQuestions(questions)
                .businessBoundaryClear(true).closeCandidates(close).build();
    }

    /** 创建可直接驱动策略的候选覆盖证据。 */
    private SemanticAssetCoverageResult.CandidateCoverage candidateCoverage(String handle,
            int score, boolean complete, boolean grainCompatible, List<String> missing) {
        SemanticAssetCandidate candidate = SemanticAssetCandidate.builder().candidateHandle(handle)
                .assetType("MODEL").assetId(1L).assetVersion(1L).name("stock").bizName("库存")
                .manageable(true).build();
        return SemanticAssetCoverageResult.CandidateCoverage.builder().candidate(candidate)
                .score(score).completeCoverage(complete).grainCompatible(grainCompatible)
                .subjectCompatible(true).coveredCapabilities(List.of("物料", "库存数量"))
                .missingCapabilities(missing).build();
    }
}
