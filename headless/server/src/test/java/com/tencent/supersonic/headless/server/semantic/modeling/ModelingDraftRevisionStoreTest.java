package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingRevisionAttemptDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingRevisionAttemptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 AI 草稿修订跨实例租约单元测试。
 *
 * <p>
 * 职责说明：覆盖 Provider 前认领、同键重放和拒绝、不同键活动冲突、过期恢复、成功版本事务、失败释放 以及手工操作门禁。测试使用 Mapper mock 验证状态机和锁顺序，不调用真实
 * Provider，也不写正式语义资产。
 * </p>
 */
class ModelingDraftRevisionStoreTest {

    private SemanticModelingDraftMapper draftMapper;
    private SemanticModelingDraftVersionMapper versionMapper;
    private SemanticModelingRevisionAttemptMapper attemptMapper;
    private ModelingDraftRevisionStore store;
    private User user;

    /** 初始化 Mapper mock 和一分钟测试租约。 */
    @BeforeEach
    void setUp() {
        draftMapper = mock(SemanticModelingDraftMapper.class);
        versionMapper = mock(SemanticModelingDraftVersionMapper.class);
        attemptMapper = mock(SemanticModelingRevisionAttemptMapper.class);
        store = new ModelingDraftRevisionStore(draftMapper, versionMapper, attemptMapper, 60_000L);
        user = User.get(1L, "admin");
        when(attemptMapper.selectCurrentTimestamp()).thenAnswer(invocation -> new Date());
    }

    /** 新键在无活动修订时应持久化 RUNNING 租约，且只有该结果允许调用 Provider。 */
    @Test
    void shouldClaimNewRevisionBeforeProviderCall() {
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.insert(any(SemanticModelingRevisionAttemptDO.class)))
                .thenAnswer(invocation -> {
                    SemanticModelingRevisionAttemptDO inserted = invocation.getArgument(0);
                    inserted.setId(10L);
                    return 1;
                });

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Rev-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.CLAIMED);
        assertThat(result.shouldInvokeProvider()).isTrue();
        assertThat(result.attempt().getStatus())
                .isEqualTo(ModelingDraftRevisionStore.STATUS_RUNNING);
        assertThat(result.attempt().getActiveMarker()).isEqualTo(1);
        assertThat(result.attempt().getLeaseExpiresAt())
                .isAfter(result.attempt().getLeaseStartedAt());
    }

    /** 相同键成功终态必须直接重放，不能再次插入 attempt 或调用 Provider。 */
    @Test
    void shouldReplaySucceededSameKey() {
        SemanticModelingRevisionAttemptDO succeeded = attempt(
                ModelingDraftRevisionStore.STATUS_SUCCEEDED, "Rev-Key", "fingerprint-1", future());
        succeeded.setActiveMarker(null);
        succeeded.setResultVersionId(30L);
        succeeded.setResultVersionNo(3);
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(3, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectByDraftIdAndIdempotencyKey(1L, "Rev-Key")).thenReturn(succeeded);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Rev-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.REPLAY_SUCCEEDED);
        assertThat(result.shouldInvokeProvider()).isFalse();
        verify(attemptMapper, never()).insert(any(SemanticModelingRevisionAttemptDO.class));
        verify(versionMapper, never()).selectByDraftIdAndRequestIdempotencyKey(anyLong(),
                anyString());
    }

    /** restore 或手工保存占用共享版本幂等键时，修订必须在 Provider 前稳定冲突。 */
    @Test
    void shouldRejectVersionIdempotencyKeyOwnedByAnotherOperation() {
        SemanticModelingDraftVersionDO restored = new SemanticModelingDraftVersionDO();
        restored.setDraftId(1L);
        restored.setVersionNo(3);
        restored.setChangeSource(ModelingDraftConstants.VERSION_RESTORED);
        restored.setRequestIdempotencyKey("Shared-Key");
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(3, ModelingDraftConstants.STATUS_DRAFT));
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "Shared-Key"))
                .thenReturn(restored);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 3, "Shared-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.IDEMPOTENCY_CONFLICT);
        assertThat(result.shouldInvokeProvider()).isFalse();
        assertThat(result.attempt()).isNull();
        verify(attemptMapper, never()).selectCurrentTimestamp();
        verify(attemptMapper, never()).selectActiveByDraftId(anyLong());
        verify(attemptMapper, never()).insert(any(SemanticModelingRevisionAttemptDO.class));
    }

    /** 不同版本幂等键互不影响，未占用的新键仍可正常认领修订租约。 */
    @Test
    void shouldAllowRevisionWhenOnlyDifferentVersionKeyExists() {
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "Revision-Key"))
                .thenReturn(null);
        when(attemptMapper.insert(any(SemanticModelingRevisionAttemptDO.class))).thenReturn(1);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Revision-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.CLAIMED);
        verify(attemptMapper).insert(any(SemanticModelingRevisionAttemptDO.class));
    }

    /** 相同键过期时必须先持久化 SYSTEM_FAILED，再要求调用方更换新键。 */
    @Test
    void shouldExpireSameKeyAndRequireNewKey() {
        SemanticModelingRevisionAttemptDO expired = attempt(
                ModelingDraftRevisionStore.STATUS_RUNNING, "Rev-Key", "fingerprint-1", past());
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectByDraftIdAndIdempotencyKey(1L, "Rev-Key")).thenReturn(expired);
        when(attemptMapper.expire(eq(10L), anyString(), eq(user.getName()), any(Date.class)))
                .thenReturn(1);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Rev-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.SAME_KEY_EXPIRED);
        assertThat(result.shouldInvokeProvider()).isFalse();
        assertThat(expired.getStatus()).isEqualTo(ModelingDraftRevisionStore.STATUS_SYSTEM_FAILED);
        assertThat(expired.getActiveMarker()).isNull();
        verify(attemptMapper, never()).insert(any(SemanticModelingRevisionAttemptDO.class));
    }

    /** 相同键 FAILED 或 SYSTEM_FAILED 终态必须稳定拒绝，不允许重新调用 Provider。 */
    @Test
    void shouldRejectFailedSameKeyWithoutProviderCall() {
        SemanticModelingRevisionAttemptDO failed = attempt(ModelingDraftRevisionStore.STATUS_FAILED,
                "Rev-Key", "fingerprint-1", past());
        failed.setActiveMarker(null);
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectByDraftIdAndIdempotencyKey(1L, "Rev-Key")).thenReturn(failed);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Rev-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.SAME_KEY_TERMINAL);
        assertThat(result.shouldInvokeProvider()).isFalse();
        verify(attemptMapper, never()).insert(any(SemanticModelingRevisionAttemptDO.class));
    }

    /** 不同键遇到未过期活动租约时必须冲突，防止两个会话并发修改同一草稿。 */
    @Test
    void shouldRejectDifferentKeyWhileAnotherLeaseIsActive() {
        SemanticModelingRevisionAttemptDO active = attempt(
                ModelingDraftRevisionStore.STATUS_RUNNING, "Other-Key", "fingerprint-0", future());
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectActiveByDraftId(1L)).thenReturn(active);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Rev-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.OTHER_ACTIVE_REVISION);
        assertThat(result.shouldInvokeProvider()).isFalse();
        verify(attemptMapper, never()).insert(any(SemanticModelingRevisionAttemptDO.class));
    }

    /** 新键可以在旧租约过期并原子释放后获得新的活动租约。 */
    @Test
    void shouldExpireOldLeaseThenClaimDifferentKey() {
        SemanticModelingRevisionAttemptDO expired = attempt(
                ModelingDraftRevisionStore.STATUS_RUNNING, "Other-Key", "fingerprint-0", past());
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectActiveByDraftId(1L)).thenReturn(expired);
        when(attemptMapper.expire(eq(10L), anyString(), eq(user.getName()), any(Date.class)))
                .thenReturn(1);
        when(attemptMapper.insert(any(SemanticModelingRevisionAttemptDO.class))).thenReturn(1);

        ModelingDraftRevisionStore.ClaimResult result =
                store.claim(1L, 2, "Rev-Key", "fingerprint-1", user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.ClaimDisposition.CLAIMED);
        assertThat(expired.getStatus()).isEqualTo(ModelingDraftRevisionStore.STATUS_SYSTEM_FAILED);
        verify(attemptMapper).insert(any(SemanticModelingRevisionAttemptDO.class));
    }

    /** 成功完成必须按 draft→attempt 锁顺序，并在同一服务事务中写版本和结束 attempt。 */
    @Test
    void shouldCompleteVersionAndAttemptWithConsistentLockOrder() {
        SemanticModelingRevisionAttemptDO running = attempt(
                ModelingDraftRevisionStore.STATUS_RUNNING, "Rev-Key", "fingerprint-1", future());
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftDO saved = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        when(attemptMapper.selectById(10L)).thenReturn(running);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(attemptMapper.selectByIdForUpdate(10L)).thenReturn(running);
        when(draftMapper.updateDraftWithAiVersion(eq(1L), eq(2), eq("{\"models\":[]}"), eq(3),
                eq(50L), eq(user.getName()), any(Date.class))).thenReturn(1);
        when(versionMapper.insert(any(SemanticModelingDraftVersionDO.class)))
                .thenAnswer(invocation -> {
                    SemanticModelingDraftVersionDO version = invocation.getArgument(0);
                    version.setId(30L);
                    return 1;
                });
        when(attemptMapper.markSucceeded(eq(10L), eq(30L), eq(3), eq(50L), eq(user.getName()),
                any(Date.class))).thenReturn(1);
        when(draftMapper.selectById(1L)).thenReturn(saved);

        ModelingDraftRevisionStore.CompletionResult result =
                store.completeSuccess(10L, "{\"models\":[]}", "新增 1 项", 50L, user);

        assertThat(result.disposition())
                .isEqualTo(ModelingDraftRevisionStore.CompletionDisposition.SUCCEEDED);
        assertThat(result.version().getId()).isEqualTo(30L);
        assertThat(result.attempt().getStatus())
                .isEqualTo(ModelingDraftRevisionStore.STATUS_SUCCEEDED);
        assertThat(result.attempt().getActiveMarker()).isNull();
        InOrder lockOrder = inOrder(attemptMapper, draftMapper);
        lockOrder.verify(attemptMapper).selectById(10L);
        lockOrder.verify(draftMapper).selectByIdForUpdate(1L);
        lockOrder.verify(attemptMapper).selectByIdForUpdate(10L);
    }

    /** Provider 失败必须结束 RUNNING attempt 并释放活动标记。 */
    @Test
    void shouldReleaseLeaseOnProviderFailure() {
        SemanticModelingRevisionAttemptDO running = attempt(
                ModelingDraftRevisionStore.STATUS_RUNNING, "Rev-Key", "fingerprint-1", future());
        when(attemptMapper.selectById(10L)).thenReturn(running);
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectByIdForUpdate(10L)).thenReturn(running);
        when(attemptMapper.markFailed(eq(10L), eq("PROVIDER_TIMEOUT"), eq(user.getName()),
                any(Date.class))).thenReturn(1);

        SemanticModelingRevisionAttemptDO completed =
                store.completeFailure(10L, "PROVIDER_TIMEOUT", user);

        assertThat(completed.getStatus()).isEqualTo(ModelingDraftRevisionStore.STATUS_FAILED);
        assertThat(completed.getActiveMarker()).isNull();
        assertThat(completed.getErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
    }

    /** 手工操作仅阻止未过期租约；过期租约应原子结束后放行。 */
    @Test
    void shouldBlockLiveLeaseButReleaseExpiredLeaseForManualOperations() {
        SemanticModelingRevisionAttemptDO live = attempt(ModelingDraftRevisionStore.STATUS_RUNNING,
                "Live-Key", "fingerprint-1", future());
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(2, ModelingDraftConstants.STATUS_DRAFT));
        when(attemptMapper.selectActiveByDraftId(1L)).thenReturn(live);

        assertThatThrownBy(() -> store.assertNoActiveRevision(1L, user)).isInstanceOfSatisfying(
                ModelingDraftException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ModelingDraftConstants.ERROR_REVISION_RUNNING));

        SemanticModelingRevisionAttemptDO expired = attempt(
                ModelingDraftRevisionStore.STATUS_RUNNING, "Expired-Key", "fingerprint-2", past());
        when(attemptMapper.selectActiveByDraftId(1L)).thenReturn(expired);
        when(attemptMapper.expire(eq(10L), anyString(), eq(user.getName()), any(Date.class)))
                .thenReturn(1);

        assertThatCode(() -> store.assertNoActiveRevision(1L, user)).doesNotThrowAnyException();
        assertThat(expired.getStatus()).isEqualTo(ModelingDraftRevisionStore.STATUS_SYSTEM_FAILED);
        assertThat(expired.getActiveMarker()).isNull();
    }

    /** 报告快照必须使用数据库时钟判断活动租约，避免应用节点时钟漂移。 */
    @Test
    void shouldUseDatabaseClockForActiveRevisionSnapshot() {
        Date databaseNow = new Date(1_000_000L);
        SemanticModelingRevisionAttemptDO active =
                attempt(ModelingDraftRevisionStore.STATUS_RUNNING, "Live-Key", "fingerprint-1",
                        new Date(databaseNow.getTime() + 1_000L));
        when(attemptMapper.selectActiveByDraftId(1L)).thenReturn(active);
        when(attemptMapper.selectCurrentTimestamp()).thenReturn(databaseNow);

        assertThat(store.hasActiveRevision(1L)).isTrue();

        verify(attemptMapper).selectCurrentTimestamp();
    }

    /** 构造草稿主记录。 */
    private SemanticModelingDraftDO draft(int versionNo, String status) {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(1L);
        draft.setCurrentVersionNo(versionNo);
        draft.setStatus(status);
        return draft;
    }

    /** 构造指定租约终点的修订尝试。 */
    private SemanticModelingRevisionAttemptDO attempt(String status, String key, String fingerprint,
            Date leaseExpiresAt) {
        SemanticModelingRevisionAttemptDO attempt = new SemanticModelingRevisionAttemptDO();
        attempt.setId(10L);
        attempt.setDraftId(1L);
        attempt.setBaseVersionNo(2);
        attempt.setIdempotencyKey(key);
        attempt.setRequestFingerprint(fingerprint);
        attempt.setStatus(status);
        attempt.setActiveMarker(
                ModelingDraftRevisionStore.STATUS_RUNNING.equals(status) ? 1 : null);
        attempt.setLeaseStartedAt(new Date(System.currentTimeMillis() - 1_000L));
        attempt.setLeaseExpiresAt(leaseExpiresAt);
        attempt.setCreatedBy(user.getName());
        attempt.setUpdatedBy(user.getName());
        return attempt;
    }

    /** 返回稳定的未来租约时间。 */
    private Date future() {
        return new Date(System.currentTimeMillis() + 60_000L);
    }

    /** 返回稳定的过去租约时间。 */
    private Date past() {
        return new Date(System.currentTimeMillis() - 60_000L);
    }
}
