package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受限 LLM 路由建议解析测试。
 *
 * <p>
 * 职责：验证候选 handle 白名单、正式资产 ID 与 SQL 禁止字段，防止 Provider 输出直接成为 可执行路由事实。Provider
 * 使用测试桩同步返回固定文本，不存在真实网络或共享状态。
 * </p>
 */
class SemanticAssetRoutingAdvisorTest {

    /** 未知 candidateHandle 必须拒绝，不能回退到新建。 */
    @Test
    void shouldRejectUnknownCandidateHandle() {
        SemanticAssetRoutingAdvisor advisor = advisorReturning("""
                {"recommendedAction":"EXTEND_EXISTING","candidateHandle":"candidate_99",
                 "coveredCapabilities":[],"missingCapabilities":[],"businessQuestions":[],
                 "explanation":"增强未知候选"}
                """);

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> advisor.advise("库存分析", List.of(candidate("candidate_1")), emptyCoverage()));

        assertEquals("INVALID_ADVISOR_OUTPUT", exception.getErrorCode());
    }

    /** Provider 返回正式资产 ID 或 SQL 字段时必须拒绝。 */
    @Test
    void shouldRejectFormalAssetIdAndSql() {
        SemanticAssetRoutingAdvisor advisor = advisorReturning("""
                {"recommendedAction":"EXTEND_EXISTING","candidateHandle":"candidate_1",
                 "assetId":101,"sql":"select * from secret_table",
                 "coveredCapabilities":[],"missingCapabilities":[],"businessQuestions":[],
                 "explanation":"越权输出"}
                """);

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> advisor.advise("库存分析", List.of(candidate("candidate_1")), emptyCoverage()));

        assertEquals("INVALID_ADVISOR_OUTPUT", exception.getErrorCode());
    }

    /** SQL 或正式 ID 即使藏在合法 explanation 字段中也必须拒绝。 */
    @Test
    void shouldRejectSqlAndFormalIdHiddenInText() {
        SemanticAssetRoutingAdvisor advisor = advisorReturning("""
                {"recommendedAction":"EXTEND_EXISTING","candidateHandle":"candidate_1",
                 "coveredCapabilities":[],"missingCapabilities":[],"businessQuestions":[],
                 "explanation":"执行 SELECT value FROM secret；model_id=42"}
                """);

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> advisor.advise("库存分析", List.of(candidate("candidate_1")), emptyCoverage()));

        assertEquals("INVALID_ADVISOR_OUTPUT", exception.getErrorCode());
    }

    /** CREATE_NEW 不得附带候选 handle，避免把旧资产身份夹带进新建动作。 */
    @Test
    void shouldRejectCandidateHandleForCreateNew() {
        SemanticAssetRoutingAdvisor advisor = advisorReturning("""
                {"recommendedAction":"CREATE_NEW","candidateHandle":"candidate_1",
                 "coveredCapabilities":[],"missingCapabilities":[],"businessQuestions":[],
                 "explanation":"建议新建"}
                """);

        assertThrows(SemanticAssetRoutingException.class,
                () -> advisor.advise("库存分析", List.of(candidate("candidate_1")), emptyCoverage()));
    }

    /** 合法建议只能引用服务端分配的候选 handle。 */
    @Test
    void shouldAcceptWhitelistedCandidateHandle() {
        SemanticAssetRoutingAdvisor advisor = advisorReturning("""
                {"recommendedAction":"EXTEND_EXISTING","candidateHandle":"candidate_1",
                 "coveredCapabilities":["物料"],
                 "missingCapabilities":[{"type":"DERIVED_FIELD","name":"呆滞时长","reason":"缺失"}],
                 "businessQuestions":[],"explanation":"仅缺少派生能力"}
                """);

        Optional<SemanticAssetRoutingAdvice> advice =
                advisor.advise("库存分析", List.of(candidate("candidate_1")), emptyCoverage());

        assertEquals("candidate_1", advice.orElseThrow().getCandidateHandle());
    }

    /** 完整复用、无候选且边界清楚、未决问题均应跳过慢速 LLM。 */
    @Test
    void shouldSkipSemanticComparisonWhenRulesAreSufficient() {
        SemanticAssetRoutingAdvisor advisor = advisorReturning("{}");
        SemanticAssetCoverageResult complete = coverage(true, true, List.of());
        SemanticAssetCoverageResult create =
                SemanticAssetCoverageResult.builder().candidateCoverages(List.of())
                        .businessBoundaryClear(true).businessQuestions(List.of()).build();
        SemanticAssetBusinessQuestion question = SemanticAssetBusinessQuestion.builder()
                .key("grain").question("请选择粒度").required(true).build();
        SemanticAssetCoverageResult unresolved = coverage(false, true, List.of(question));

        assertFalse(advisor.requiresSemanticComparison(complete));
        assertFalse(advisor.requiresSemanticComparison(create));
        assertFalse(advisor.requiresSemanticComparison(unresolved));
        assertTrue(advisor.requiresSemanticComparison(coverage(false, true, List.of())));
    }

    /** 首次结构非法时只能在同一会话修复一次，并返回会话 ID。 */
    @Test
    void shouldRepairAtMostOnceAndReturnConversationId() {
        AtomicInteger repairCalls = new AtomicInteger();
        SemanticAssetRoutingAdviceProvider provider = new SemanticAssetRoutingAdviceProvider() {
            @Override
            public SemanticAssetRoutingProviderResult advise(
                    SemanticAssetRoutingAdvisorRequest request,
                    SemanticAssetRoutingAdvisorContext context) {
                return new SemanticAssetRoutingProviderResult("not-json", 77L);
            }

            @Override
            public SemanticAssetRoutingProviderResult repair(String invalidOutput,
                    SemanticAssetRoutingAdvisorRequest request,
                    SemanticAssetRoutingAdvisorContext context, Long conversationId) {
                repairCalls.incrementAndGet();
                return new SemanticAssetRoutingProviderResult("""
                        {"recommendedAction":"EXTEND_EXISTING",
                         "candidateHandle":"candidate_1","coveredCapabilities":[],
                         "missingCapabilities":[],"businessQuestions":[],
                         "explanation":"修复完成"}
                        """, conversationId);
            }
        };
        SemanticAssetRoutingAdvisor advisor =
                new SemanticAssetRoutingAdvisor(new ObjectMapper(), Optional.of(provider));

        SemanticAssetRoutingAdvisorResult result = advisor.advise(9L, 1, 17, null, "库存分析",
                List.of(candidate("candidate_1")), emptyCoverage());

        assertEquals(1, repairCalls.get());
        assertEquals(77L, result.llmConversationId());
        assertEquals("candidate_1", result.advice().orElseThrow().getCandidateHandle());
    }

    /** 业务目标和候选摘要中的敏感值必须在进入 Provider 前使用共享规则脱敏。 */
    @Test
    void shouldSanitizeSensitiveContextBeforeProviderCall() {
        AtomicReference<SemanticAssetRoutingAdvisorRequest> captured = new AtomicReference<>();
        SemanticAssetRoutingAdviceProvider provider = (request, context) -> {
            captured.set(request);
            return new SemanticAssetRoutingProviderResult("""
                    {"recommendedAction":"EXTEND_EXISTING",
                     "candidateHandle":"candidate_1","coveredCapabilities":[],
                     "missingCapabilities":[],"businessQuestions":[],
                     "explanation":"安全建议"}
                    """, 77L);
        };
        SemanticAssetCandidate candidate = candidate("candidate_1");
        candidate.setDescription("负责人 owner@example.com");
        SemanticAssetRoutingAdvisor advisor =
                new SemanticAssetRoutingAdvisor(new ObjectMapper(), Optional.of(provider));

        advisor.advise(9L, 1, 17, null, "联系人 13800138000", List.of(candidate), emptyCoverage());

        assertEquals("[MASKED]", captured.get().getBusinessGoal());
        assertEquals("[MASKED]", captured.get().getCandidates().get(0).getDescription());
    }

    /** 创建返回固定原文的 Advisor。 */
    private SemanticAssetRoutingAdvisor advisorReturning(String json) {
        SemanticAssetRoutingAdviceProvider provider =
                (request, context) -> new SemanticAssetRoutingProviderResult(json, null);
        return new SemanticAssetRoutingAdvisor(new ObjectMapper(), Optional.of(provider));
    }

    /** 创建可控制规则跳过条件的覆盖证据。 */
    private SemanticAssetCoverageResult coverage(boolean complete, boolean boundaryClear,
            List<SemanticAssetBusinessQuestion> questions) {
        SemanticAssetCoverageResult.CandidateCoverage candidateCoverage =
                SemanticAssetCoverageResult.CandidateCoverage.builder()
                        .candidate(candidate("candidate_1")).completeCoverage(complete)
                        .subjectCompatible(true).grainCompatible(true).score(90)
                        .missingCapabilities(complete ? List.of() : List.of("呆滞时长")).build();
        return SemanticAssetCoverageResult.builder().candidateCoverages(List.of(candidateCoverage))
                .businessBoundaryClear(boundaryClear).businessQuestions(questions).build();
    }

    /** 创建最小白名单候选。 */
    private SemanticAssetCandidate candidate(String handle) {
        return SemanticAssetCandidate.builder().candidateHandle(handle).assetType("MODEL")
                .assetId(1L).assetVersion(1L).name("stock").bizName("库存").build();
    }

    /** 创建 Advisor 所需的空覆盖上下文。 */
    private SemanticAssetCoverageResult emptyCoverage() {
        return SemanticAssetCoverageResult.builder().candidateCoverages(List.of())
                .requestedCapabilities(List.of()).resultOperations(List.of())
                .businessQuestions(List.of()).businessBoundaryClear(true).closeCandidates(false)
                .build();
    }
}
