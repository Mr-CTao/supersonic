package com.tencent.supersonic.headless.server.semantic.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticAssetRoutingDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义资产路由应用服务并发与绑定回归测试。
 *
 * <p>验证已消费路由只能由原草稿重放，以及轮询能触发过期租约的数据库 CAS 回收。</p>
 */
class SemanticAssetRoutingServiceTest {

    private SemanticAssetRoutingStore store;
    private SemanticAssetRoutingPermissionService permissionService;
    private SemanticAssetReuseValidationService reuseValidationService;
    private ThreadPoolTaskExecutor executor;
    private ObjectMapper objectMapper;
    private SemanticAssetRoutingService service;

    /** 初始化隔离的应用服务。 */
    @BeforeEach
    void setUp() {
        store = mock(SemanticAssetRoutingStore.class);
        permissionService = mock(SemanticAssetRoutingPermissionService.class);
        reuseValidationService = mock(SemanticAssetReuseValidationService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        objectMapper = new ObjectMapper();
        service = new SemanticAssetRoutingService(store,
                mock(SemanticAssetCandidateRetriever.class),
                mock(SemanticAssetCoverageAnalyzer.class),
                mock(SemanticAssetRoutingAdvisor.class), mock(SemanticAssetRoutingPolicy.class),
                permissionService, reuseValidationService, executor, objectMapper);
    }

    /** 已消费确认路由只能由绑定的同一草稿重放。 */
    @Test
    void shouldAllowOnlyBoundDraftToReplayConsumedRoute() throws Exception {
        SemanticAssetCandidate candidate = SemanticAssetCandidate.builder()
                .candidateHandle("candidate_1").assetType("MODEL").assetId(7L)
                .assetVersion(99L).name("库存汇总").baseTables(List.of("stock_summary"))
                .build();
        SemanticAssetRoutingDO route = succeededRoute();
        route.setConsumedByDraftId(41L);
        route.setCandidateSnapshot(objectMapper.writeValueAsString(List.of(candidate)));
        route.setConfirmedCandidateId(7L);
        route.setConfirmedCandidateVersion(99L);
        when(store.findById(9L)).thenReturn(route);
        when(store.currentDatabaseTime()).thenReturn(new Date(1_700_000_000_000L));

        ConfirmedSemanticAssetRoute confirmed =
                service.requireBoundRoute(9L, 41L, User.getDefaultUser());

        assertEquals(7L, confirmed.getTargetAssetId());
        assertThrows(SemanticAssetRoutingException.class,
                () -> service.requireBoundRoute(9L, 42L, User.getDefaultUser()));
        verify(permissionService).requireCurrentCandidateVersion(any(), any());
    }

    /** GET 轮询发现过期分析租约时应重新提交，由 claim CAS 决定唯一 Worker。 */
    @Test
    void shouldScheduleRecoveryWhenAnalysisLeaseExpired() {
        SemanticAssetRoutingDO route = new SemanticAssetRoutingDO();
        route.setId(9L);
        route.setStatus(SemanticAssetRouteStatus.ANALYZING.name());
        route.setLeaseExpiresAt(new Date(1_699_999_999_000L));
        route.setCandidateSnapshot("[]");
        when(store.findById(9L)).thenReturn(route);
        when(store.currentDatabaseTime()).thenReturn(new Date(1_700_000_000_000L));

        service.get(9L, User.getDefaultUser());

        verify(executor).execute(any(Runnable.class));
    }

    /** 超大业务答案必须在权限、指纹和持久化前被应用服务拒绝。 */
    @Test
    void shouldRejectOversizedBusinessAnswersBeforeCreatingRoute() {
        Map<String, Object> answers = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) {
            answers.put("answer_" + index, "a".repeat(1_800));
        }
        SemanticAssetRouteAnalyzeReq request = new SemanticAssetRouteAnalyzeReq();
        request.setBusinessAnswers(answers);

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> service.create(request, "analyze-key", User.getDefaultUser()));

        assertEquals("BUSINESS_ANSWERS_TOO_LARGE", exception.getErrorCode());
        verifyNoInteractions(store, permissionService);
    }

    /** 确认复用必须先完成数据库 CAS，再触发现有知识刷新。 */
    @Test
    void shouldRefreshExistingKnowledgeOnlyAfterConfirmationCasWins() throws Exception {
        SemanticAssetCandidate candidate = reuseCandidate();
        SemanticAssetRoutingDO route = unconfirmedReuseRoute(candidate);
        SemanticAssetRoutingDO confirmed = confirmedReuseRoute(route);
        when(store.findById(9L)).thenReturn(route);
        when(store.currentDatabaseTime()).thenReturn(new Date(1_700_000_000_000L));
        when(store.confirm(eq(route), any(), eq(candidate), anyString(), anyString(), any()))
                .thenReturn(new SemanticAssetRoutingStore.ConfirmationResult(confirmed, false));

        SemanticAssetRouteResp response =
                service.confirm(9L, reuseRequest(), "reuse-confirm-key", User.getDefaultUser());

        assertEquals(SemanticAssetRouteAction.REUSE_EXISTING, response.getConfirmedAction());
        InOrder order = inOrder(reuseValidationService, store);
        order.verify(reuseValidationService).validateCandidate(candidate, User.getDefaultUser());
        order.verify(store).confirm(eq(route), any(), eq(candidate), anyString(), anyString(), any());
        order.verify(reuseValidationService).refreshConfirmed(9L, candidate,
                User.getDefaultUser());
    }

    /** 相同确认的幂等重放必须再次刷新，以恢复 CAS 成功后事件发布失败的场景。 */
    @Test
    void shouldRefreshExistingKnowledgeForIdempotentConfirmationReplay() throws Exception {
        SemanticAssetCandidate candidate = reuseCandidate();
        SemanticAssetRoutingDO route = unconfirmedReuseRoute(candidate);
        SemanticAssetRoutingDO confirmed = confirmedReuseRoute(route);
        when(store.findById(9L)).thenReturn(route);
        when(store.currentDatabaseTime()).thenReturn(new Date(1_700_000_000_000L));
        when(store.confirm(eq(route), any(), eq(candidate), anyString(), anyString(), any()))
                .thenReturn(new SemanticAssetRoutingStore.ConfirmationResult(confirmed, true));

        service.confirm(9L, reuseRequest(), "reuse-confirm-key", User.getDefaultUser());

        verify(reuseValidationService).refreshConfirmed(9L, candidate, User.getDefaultUser());
    }

    /** 首次刷新发布失败后，使用同一幂等键重试必须能够再次发布并恢复。 */
    @Test
    void shouldRecoverReuseRefreshFailureWithSameIdempotencyKey() throws Exception {
        SemanticAssetCandidate candidate = reuseCandidate();
        SemanticAssetRoutingDO route = unconfirmedReuseRoute(candidate);
        SemanticAssetRoutingDO confirmed = confirmedReuseRoute(route);
        when(store.findById(9L)).thenReturn(route, confirmed);
        when(store.currentDatabaseTime()).thenReturn(new Date(1_700_000_000_000L));
        when(store.confirm(eq(route), any(), eq(candidate), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    confirmed.setConfirmationIdempotencyKey(invocation.getArgument(3));
                    confirmed.setConfirmationRequestFingerprint(invocation.getArgument(4));
                    return new SemanticAssetRoutingStore.ConfirmationResult(confirmed, false);
                });
        doThrow(new IllegalStateException("event publish failed")).doNothing()
                .when(reuseValidationService)
                .refreshConfirmed(9L, candidate, User.getDefaultUser());

        assertThrows(IllegalStateException.class,
                () -> service.confirm(9L, reuseRequest(), "reuse-confirm-key",
                        User.getDefaultUser()));
        SemanticAssetRouteResp replay =
                service.confirm(9L, reuseRequest(), "reuse-confirm-key", User.getDefaultUser());

        assertEquals(SemanticAssetRouteAction.REUSE_EXISTING, replay.getConfirmedAction());
        verify(reuseValidationService, times(2)).refreshConfirmed(9L, candidate,
                User.getDefaultUser());
        verify(store).confirm(eq(route), any(), eq(candidate), anyString(), anyString(), any());
    }

    /** 确认 CAS 冲突时不得产生知识刷新副作用。 */
    @Test
    void shouldNotRefreshExistingKnowledgeWhenConfirmationCasConflicts() throws Exception {
        SemanticAssetCandidate candidate = reuseCandidate();
        SemanticAssetRoutingDO route = unconfirmedReuseRoute(candidate);
        when(store.findById(9L)).thenReturn(route);
        when(store.currentDatabaseTime()).thenReturn(new Date(1_700_000_000_000L));
        when(store.confirm(eq(route), any(), eq(candidate), anyString(), anyString(), any()))
                .thenThrow(new SemanticAssetRoutingException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "ROUTE_CONFIRMATION_CONFLICT", "路由版本已变化"));

        assertThrows(SemanticAssetRoutingException.class,
                () -> service.confirm(9L, reuseRequest(), "reuse-confirm-key",
                        User.getDefaultUser()));

        verify(reuseValidationService, never()).refreshConfirmed(any(), any(), any());
    }

    /** 构造可复用的候选资产快照。 */
    private SemanticAssetCandidate reuseCandidate() {
        return SemanticAssetCandidate.builder().candidateHandle("candidate_1").assetType("MODEL")
                .assetId(7L).assetVersion(99L).name("库存汇总").domainId(5L).build();
    }

    /** 构造尚未确认的复用路由。 */
    private SemanticAssetRoutingDO unconfirmedReuseRoute(SemanticAssetCandidate candidate)
            throws Exception {
        SemanticAssetRoutingDO route = succeededRoute();
        route.setConfirmedAction(null);
        route.setRecommendedAction(SemanticAssetRouteAction.REUSE_EXISTING.name());
        route.setRecommendedCandidateId(candidate.getAssetId());
        route.setRecommendedCandidateVersion(candidate.getAssetVersion());
        route.setAnalysisVersion(1);
        route.setBusinessQuestions("[]");
        route.setCandidateSnapshot(objectMapper.writeValueAsString(List.of(candidate)));
        return route;
    }

    /** 构造数据库 CAS 成功后的已确认复用路由。 */
    private SemanticAssetRoutingDO confirmedReuseRoute(SemanticAssetRoutingDO source) {
        SemanticAssetRoutingDO confirmed = succeededRoute();
        confirmed.setConfirmedAction(SemanticAssetRouteAction.REUSE_EXISTING.name());
        confirmed.setConfirmedCandidateId(7L);
        confirmed.setConfirmedCandidateVersion(99L);
        confirmed.setRecommendedAction(SemanticAssetRouteAction.REUSE_EXISTING.name());
        confirmed.setRecommendedCandidateId(7L);
        confirmed.setCandidateSnapshot(source.getCandidateSnapshot());
        confirmed.setAnalysisVersion(1);
        return confirmed;
    }

    /** 构造接受推荐的复用确认请求。 */
    private SemanticAssetRouteConfirmReq reuseRequest() {
        SemanticAssetRouteConfirmReq request = new SemanticAssetRouteConfirmReq();
        request.setAnalysisVersion(1);
        request.setAction(SemanticAssetRouteAction.REUSE_EXISTING);
        request.setCandidateHandle("candidate_1");
        return request;
    }

    /** 构造已确认且尚未过期的增强路由。 */
    private SemanticAssetRoutingDO succeededRoute() {
        SemanticAssetRoutingDO route = new SemanticAssetRoutingDO();
        route.setId(9L);
        route.setStatus(SemanticAssetRouteStatus.SUCCEEDED.name());
        route.setConfirmedAction(SemanticAssetRouteAction.EXTEND_EXISTING.name());
        route.setExpiresAt(new Date(1_700_086_400_000L));
        route.setSelectedTables("[\"stock_summary\"]");
        route.setRuleEvidence("{}");
        route.setCoveredCapabilities("[]");
        route.setMissingCapabilities("[]");
        route.setResultOperations("[]");
        route.setBusinessAnswers("{}");
        return route;
    }
}
