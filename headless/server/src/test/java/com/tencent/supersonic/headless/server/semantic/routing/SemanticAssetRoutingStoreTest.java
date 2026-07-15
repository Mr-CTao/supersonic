package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticAssetRoutingDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticAssetRoutingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义资产路由持久化幂等测试。
 *
 * <p>
 * 职责：验证同键同指纹重放与同键不同指纹冲突。Mapper 使用 Mockito 隔离，测试不会连接数据库； Store 本身不保存进程内共享状态，并发一致性由最终 Mapper
 * 唯一键和条件更新保证。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SemanticAssetRoutingStoreTest {

    @Mock
    private SemanticAssetRoutingMapper mapper;

    /** 相同幂等键和相同指纹应重放同一路由。 */
    @Test
    void shouldReplaySameIdempotencyKeyAndFingerprint() {
        SemanticAssetRoutingDO existing = existing("fingerprint-a");
        when(mapper.selectByIdempotencyKey("admin", "key-1")).thenReturn(existing);
        SemanticAssetRoutingStore store = new SemanticAssetRoutingStore(mapper, new ObjectMapper());

        Optional<SemanticAssetRoutingDO> replay =
                store.findIdempotentRoute("admin", "key-1", "fingerprint-a");

        assertEquals(9L, replay.orElseThrow().getId());
    }

    /** 相同幂等键但请求指纹不同必须返回冲突。 */
    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentFingerprint() {
        when(mapper.selectByIdempotencyKey("admin", "key-1")).thenReturn(existing("fingerprint-a"));
        SemanticAssetRoutingStore store = new SemanticAssetRoutingStore(mapper, new ObjectMapper());

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> store.findIdempotentRoute("admin", "key-1", "fingerprint-b"));

        assertEquals("IDEMPOTENCY_CONFLICT", exception.getErrorCode());
    }

    /** 迟到的失败回写必须携带认领时锁版本，避免覆盖已回收的新租约。 */
    @Test
    void shouldFailOnlyTheClaimedLeaseVersion() {
        Date databaseNow = new Date(1_700_000_000_000L);
        SemanticAssetRoutingDO route = existing("fingerprint-a");
        route.setLockVersion(7);
        when(mapper.selectDatabaseTime()).thenReturn(databaseNow);
        SemanticAssetRoutingStore store = new SemanticAssetRoutingStore(mapper, new ObjectMapper());

        store.failAnalysis(route, "PROVIDER_FAILED", "分析失败", "admin");

        verify(mapper).failAnalysis(9L, 7, "PROVIDER_FAILED", "分析失败", databaseNow, null, "admin");
    }

    /** Provider 已创建会话后失败时仍应保存会话审计引用。 */
    @Test
    void shouldPersistConversationIdWhenProviderFails() {
        Date databaseNow = new Date(1_700_000_000_000L);
        SemanticAssetRoutingDO route = existing("fingerprint-a");
        route.setLockVersion(7);
        when(mapper.selectDatabaseTime()).thenReturn(databaseNow);
        SemanticAssetRoutingStore store = new SemanticAssetRoutingStore(mapper, new ObjectMapper());

        store.failAnalysis(route, "ROUTING_ADVISOR_PROVIDER_FAILED", "AI 语义比较失败", 88L, "admin");

        verify(mapper).failAnalysis(9L, 7, "ROUTING_ADVISOR_PROVIDER_FAILED", "AI 语义比较失败",
                databaseNow, 88L, "admin");
    }

    /** LLM 会话 ID 必须和最终规则、建议快照在同一完成更新中持久化。 */
    @Test
    void shouldPersistLlmConversationIdWithCompletedAnalysis() {
        Date databaseNow = new Date(1_700_000_000_000L);
        SemanticAssetRoutingDO route = existing("fingerprint-a");
        route.setLockVersion(7);
        when(mapper.selectDatabaseTime()).thenReturn(databaseNow);
        when(mapper.completeAnalysis(anyLong(), anyInt(), anyString(), anyString(),
                nullable(String.class), anyString(), nullable(String.class), nullable(Long.class),
                nullable(Long.class), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq(88L), any(Date.class), any(Date.class), anyString())).thenReturn(1);
        when(mapper.selectById(9L)).thenReturn(route);
        SemanticAssetRoutingStore store = new SemanticAssetRoutingStore(mapper, new ObjectMapper());
        SemanticAssetCoverageResult coverage = SemanticAssetCoverageResult.builder()
                .candidateCoverages(List.of()).businessBoundaryClear(true).build();
        SemanticAssetRoutingDecision decision =
                SemanticAssetRoutingDecision.builder().action(SemanticAssetRouteAction.CREATE_NEW)
                        .decisionSource(SemanticAssetDecisionSource.RULE_AND_LLM).build();

        store.completeAnalysis(route, List.of(), coverage, Optional.empty(), decision, 88L,
                "admin");

        verify(mapper).completeAnalysis(anyLong(), anyInt(), anyString(), anyString(),
                nullable(String.class), anyString(), nullable(String.class), nullable(Long.class),
                nullable(Long.class), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq(88L), any(Date.class), any(Date.class), eq("admin"));
    }

    /** 两个实例并发确认同一幂等请求时，乐观锁败者应重放胜者结果。 */
    @Test
    void shouldReplayConcurrentConfirmationWithSameFingerprint() {
        Date databaseNow = new Date(1_700_000_000_000L);
        SemanticAssetRoutingDO route = existing("fingerprint-a");
        route.setAnalysisVersion(1);
        route.setLockVersion(3);
        SemanticAssetRoutingDO concurrent = existing("fingerprint-a");
        concurrent.setConfirmationIdempotencyKey("confirm-key");
        concurrent.setConfirmationRequestFingerprint("confirm-fingerprint");
        when(mapper.selectDatabaseTime()).thenReturn(databaseNow);
        when(mapper.selectById(9L)).thenReturn(concurrent);
        SemanticAssetRoutingStore store = new SemanticAssetRoutingStore(mapper, new ObjectMapper());
        SemanticAssetRouteConfirmReq request = new SemanticAssetRouteConfirmReq();
        request.setAnalysisVersion(1);
        request.setAction(SemanticAssetRouteAction.CREATE_NEW);

        SemanticAssetRoutingStore.ConfirmationResult replay = store.confirm(route, request, null,
                "confirm-key", "confirm-fingerprint", User.getDefaultUser());

        assertSame(concurrent, replay.route());
        assertEquals(true, replay.replay());
    }

    /** 创建既有持久化快照。 */
    private SemanticAssetRoutingDO existing(String fingerprint) {
        SemanticAssetRoutingDO route = new SemanticAssetRoutingDO();
        route.setId(9L);
        route.setRequestFingerprint(fingerprint);
        return route;
    }
}
