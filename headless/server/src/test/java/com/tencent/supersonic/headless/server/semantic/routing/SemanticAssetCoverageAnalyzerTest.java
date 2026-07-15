package com.tencent.supersonic.headless.server.semantic.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义资产确定性覆盖分析测试。
 *
 * <p>
 * 职责：验证查询层 Top N/排序不会被误建模，并覆盖完整复用、派生能力缺失及粒度不兼容等 关键路由证据。测试仅使用内存候选，不访问数据库或 LLM，因而不存在共享状态与并发风险。
 * </p>
 */
class SemanticAssetCoverageAnalyzerTest {

    private final SemanticAssetCoverageAnalyzer analyzer = new SemanticAssetCoverageAnalyzer();

    /** 完整覆盖时应只留下查询层操作，不产生新语义能力。 */
    @Test
    void shouldRecognizeCompleteCoverageAndResultOperations() {
        SemanticAssetCandidate candidate = stockCandidate("candidate_1", List.of("物料"));

        SemanticAssetCoverageResult result = analyzer.analyze("按物料编码查询库存数量，按库存数量降序取前10条",
                List.of("tb_wms_stock"), Map.of(), List.of(candidate));

        SemanticAssetCoverageResult.CandidateCoverage primary = result.primaryCandidate();
        assertTrue(primary.isCompleteCoverage());
        assertTrue(primary.getMissingCapabilities().isEmpty());
        assertTrue(result.getResultOperations().contains("ORDER_DESC"));
        assertTrue(result.getResultOperations().contains("TOP_N"));
    }

    /** 同主题同粒度但缺少派生字段时应保留该字段作为唯一增量。 */
    @Test
    void shouldIdentifySmallDerivedCapabilityGap() {
        SemanticAssetCandidate candidate = stockCandidate("candidate_1", List.of("物料", "批次"));

        SemanticAssetCoverageResult result = analyzer.analyze("按物料和批次分析呆滞时长并按呆滞时长降序",
                List.of("tb_wms_stock"), Map.of("grain", "物料和批次"), List.of(candidate));

        SemanticAssetCoverageResult.CandidateCoverage primary = result.primaryCandidate();
        assertTrue(primary.isGrainCompatible());
        assertEquals(List.of("呆滞时长"), primary.getMissingCapabilities());
        assertTrue(result.getResultOperations().contains("ORDER_DESC"));
    }

    /** 呆滞时长回归应只缺派生能力，并把 Top N 与四类业务口径分层表达。 */
    @Test
    void shouldRouteSluggishDurationScenarioWithoutCreatingQueryLayerObjects() {
        SemanticAssetCandidate candidate =
                stockCandidate("candidate_1", List.of("物料", "批次"));

        SemanticAssetCoverageResult result = analyzer.analyze(
                "统计一下仓库中的呆滞时长前十的物料信息",
                List.of("tb_wms_stock"), Map.of(), List.of(candidate));

        assertEquals(List.of("呆滞时长"),
                result.primaryCandidate().getMissingCapabilities());
        assertTrue(result.getResultOperations().contains("TOP_N"));
        assertTrue(result.getResultOperations().contains("ORDER_DESC"));
        List<String> questionKeys = result.getBusinessQuestions().stream()
                .map(SemanticAssetBusinessQuestion::getKey).toList();
        assertTrue(questionKeys.containsAll(List.of("grain", "duration_date_basis",
                "positive_stock_only", "invalid_date_policy")));
    }

    /** 未提取到业务能力时不能因命中模型或选表就误判完整覆盖。 */
    @Test
    void shouldNotTreatEmptyCapabilitySetAsCompleteCoverage() {
        SemanticAssetCandidate candidate = stockCandidate("candidate_1", List.of("物料"));

        SemanticAssetCoverageResult result = analyzer.analyze("查询一下信息",
                List.of("tb_wms_stock"), Map.of(), List.of(candidate));

        assertFalse(result.primaryCandidate().isCompleteCoverage());
        assertFalse(result.isBusinessBoundaryClear());
    }

    /** 名称接近但请求粒度与候选粒度无交集时不能标为可安全增强。 */
    @Test
    void shouldRejectAutomaticExtensionForDifferentGrain() {
        SemanticAssetCandidate candidate = stockCandidate("candidate_1", List.of("物料", "批次"));

        SemanticAssetCoverageResult result = analyzer.analyze("按仓库汇总库存数量", List.of("tb_wms_stock"),
                Map.of("grain", "仓库"), List.of(candidate));

        assertFalse(result.primaryCandidate().isGrainCompatible());
    }

    /** 构造可复用的库存模型候选。 */
    private SemanticAssetCandidate stockCandidate(String handle, List<String> grain) {
        return SemanticAssetCandidate.builder().candidateHandle(handle).assetType("MODEL")
                .assetId(101L).assetVersion(7L).name("wms_stock_summary").bizName("WMS物料库存汇总")
                .description("物料批次库存汇总").dataSourceId(3L).domainId(5L)
                .baseTables(List.of("tb_wms_stock")).grain(grain)
                .dimensionCapabilities(List.of("物料编码", "物料名称", "批次"))
                .metricCapabilities(List.of("库存数量")).timeCapabilities(List.of()).manageable(true)
                .evidenceSources(List.of("TRACE_MODEL")).tracePriority(100).build();
    }
}
