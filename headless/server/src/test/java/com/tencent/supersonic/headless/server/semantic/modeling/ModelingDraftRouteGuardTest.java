package com.tencent.supersonic.headless.server.semantic.modeling;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.AdditionsDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.RouteSummaryDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TargetAssetDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.TargetDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 已确认语义资产路由快照防篡改回归测试。
 *
 * <p>覆盖首次 LLM 生成、人工保存和历史草稿兼容三条路径，确保路由 ID、动作、
 * 目标版本和业务口径不能被模型或后续编辑改写。</p>
 */
class ModelingDraftRouteGuardTest {

    private ObjectMapper objectMapper;
    private ModelingDraftRouteGuard guard;

    /** 初始化无共享状态的守卫和 JSON 映射器。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        guard = new ModelingDraftRouteGuard(objectMapper);
    }

    /** 完全匹配服务端已确认快照的增量草稿应通过。 */
    @Test
    void shouldAcceptGeneratedDraftWithExactConfirmedRoute() {
        ModelingDraftPayload payload = payload();
        ModelingDraftGenerateReq request = request(payload);

        assertDoesNotThrow(() -> guard.validateGenerated(payload, request));
    }

    /** LLM 即使只改写目标基线版本，也必须被拒绝而不能继续生成。 */
    @Test
    void shouldRejectGeneratedDraftWhenTargetVersionDrifts() {
        ModelingDraftPayload payload = payload();
        ModelingDraftGenerateReq request = request(payload);
        payload.getTargetAsset().setBaseVersion(8L);

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> guard.validateGenerated(payload, request));

        assertEquals(ModelingDraftConstants.ERROR_OUTPUT_INVALID, exception.getErrorCode());
    }

    /** 已保存路由草稿的业务目标变更必须要求重新分析。 */
    @Test
    void shouldRejectMutationWhenConfirmedBusinessGoalChanges() throws Exception {
        ModelingDraftPayload baseline = payload();
        SemanticModelingDraftDO draft = routedDraft();
        String baselineJson = objectMapper.writeValueAsString(baseline);
        ModelingDraftPayload candidate =
                objectMapper.readValue(baselineJson, ModelingDraftPayload.class);
        candidate.setBusinessGoal("已改写的业务目标");

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> guard.validateMutation(draft, baselineJson, candidate));

        assertEquals(ModelingDraftConstants.ERROR_CONFLICT, exception.getErrorCode());
    }

    /** 路由草稿不得由 LLM 或人工注入未确认的正式主题域 ID。 */
    @Test
    void shouldRejectMutationWhenTargetDomainIsInjected() throws Exception {
        ModelingDraftPayload baseline = payload();
        SemanticModelingDraftDO draft = routedDraft();
        String baselineJson = objectMapper.writeValueAsString(baseline);
        ModelingDraftPayload candidate =
                objectMapper.readValue(baselineJson, ModelingDraftPayload.class);
        TargetDomain targetDomain = new TargetDomain();
        targetDomain.setDomainId(99L);
        candidate.setTargetDomain(targetDomain);

        assertThrows(ModelingDraftException.class,
                () -> guard.validateMutation(draft, baselineJson, candidate));
    }

    /** 没有路由快照的历史 1.0 草稿仍保持原编辑兼容性。 */
    @Test
    void shouldAllowHistoricalDraftWithoutRouteSnapshot() {
        SemanticModelingDraftDO historical = new SemanticModelingDraftDO();

        assertDoesNotThrow(() -> guard.validateMutation(historical, "{}", new ModelingDraftPayload()));
    }

    /** 没有路由快照的历史生成请求继续由 1.0 Schema 校验，不应被 2.0 路由守卫误拦截。 */
    @Test
    void shouldAllowHistoricalGeneratedDraftWithoutRouteSnapshot() {
        ModelingDraftGenerateReq historicalRequest = new ModelingDraftGenerateReq();
        ModelingDraftPayload historicalPayload = new ModelingDraftPayload();
        historicalPayload.setSchemaVersion(ModelingDraftConstants.SCHEMA_VERSION);

        assertDoesNotThrow(() -> guard.validateGenerated(historicalPayload, historicalRequest));
    }

    /** 构造一份最小合法增量路由草稿。 */
    private ModelingDraftPayload payload() {
        RouteSummaryDraft summary = new RouteSummaryDraft();
        summary.setRouteAnalysisId(41L);
        summary.setDecisionSource("RULE_AND_LLM");
        summary.setExplanation("已有模型覆盖主体，仅缺少派生能力");
        summary.setCoveredCapabilities(List.of("物料编码"));
        summary.setMissingCapabilities(List.of("呆滞时长"));
        summary.setQueryOperations(List.of("ORDER_DESC", "TOP_N"));
        summary.setBusinessAnswers(Map.of("grain", "MATERIAL_AND_BATCH"));

        TargetAssetDraft target = new TargetAssetDraft();
        target.setCandidateHandle("candidate_1");
        target.setAssetType("MODEL");
        target.setName("库存汇总");
        target.setBaseVersion(7L);
        target.setBaseTable("stock_summary");

        ModelingDraftPayload payload = new ModelingDraftPayload();
        payload.setSchemaVersion(ModelingDraftConstants.SCHEMA_VERSION_ROUTED);
        payload.setAction(ModelingDraftConstants.ACTION_EXTEND_EXISTING);
        payload.setRouteSummary(summary);
        payload.setTargetAsset(target);
        payload.setBusinessGoal("分析库存呆滞时长");
        payload.setAdditions(new AdditionsDraft());
        payload.setUncertainties(List.of());
        payload.setModifications(List.of());
        payload.setRegressionQuestions(List.of());
        return payload;
    }

    /** 将不可信的客户端请求补齐为服务端确认过的路由上下文。 */
    private ModelingDraftGenerateReq request(ModelingDraftPayload payload) {
        ModelingDraftGenerateReq request = new ModelingDraftGenerateReq();
        request.setRouteAnalysisId(41L);
        request.setRouteAction(ModelingDraftConstants.ACTION_EXTEND_EXISTING);
        request.setBusinessGoal(payload.getBusinessGoal());
        // 模拟真实请求快照：服务端路由上下文与随后的 LLM 输出必须是独立对象。
        RouteSummaryDraft routeSummary = objectMapper.convertValue(payload.getRouteSummary(),
                RouteSummaryDraft.class);
        TargetAssetDraft targetAsset = objectMapper.convertValue(payload.getTargetAsset(),
                TargetAssetDraft.class);
        request.setRouteContext(Map.of("routeSummary", routeSummary, "targetAsset", targetAsset));
        return request;
    }

    /** 构造已绑定路由的草稿主记录。 */
    private SemanticModelingDraftDO routedDraft() {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setRouteAnalysisId(41L);
        draft.setRouteAction(ModelingDraftConstants.ACTION_EXTEND_EXISTING);
        return draft;
    }
}
