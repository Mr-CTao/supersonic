package com.tencent.supersonic.headless.server.semantic.modeling;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingValidationReportDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingValidationReportMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 短事务存储门禁单元测试。
 *
 * <p>
 * 职责说明：验证提交只接受当前版本最新且无阻塞项的报告，并验证活动报告会阻止重复验证。测试使用 Mockito，不连接数据库、不执行正式语义写入。
 * </p>
 */
class ModelingDraftStage4StoreTest {

    private SemanticModelingDraftMapper draftMapper;
    private SemanticModelingValidationReportMapper reportMapper;
    private SemanticModelingDraftVersionMapper versionMapper;
    private ModelingDraftRevisionStore revisionStore;
    private ModelingDraftStage4Store store;
    private User user;

    /** 初始化 LambdaUpdateWrapper 所需的 MyBatis 表字段缓存。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                SemanticModelingValidationReportDO.class);
    }

    /** 初始化 Mapper mock 和无状态存储服务。 */
    @BeforeEach
    void setUp() {
        draftMapper = mock(SemanticModelingDraftMapper.class);
        reportMapper = mock(SemanticModelingValidationReportMapper.class);
        versionMapper = mock(SemanticModelingDraftVersionMapper.class);
        revisionStore = mock(ModelingDraftRevisionStore.class);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(new Date());
        store = new ModelingDraftStage4Store(draftMapper, reportMapper, versionMapper,
                revisionStore, new SemanticModelingProperties(), new ObjectMapper());
        user = User.get(1L, "admin");
    }

    /** 当前版本最新 WARNING 报告在无阻塞项时允许提交待审批。 */
    @Test
    void shouldSubmitLatestWarningReportWithoutBlockingItems() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingValidationReportDO report =
                report(8L, 2, ModelingDraftConstants.VALIDATION_WARNING, 0);
        SemanticModelingDraftDO submitted =
                draft(2, ModelingDraftConstants.STATUS_PENDING_APPROVAL);
        submitted.setSubmittedValidationReportId(8L);
        submitted.setSubmittedBy(user.getName());
        submitted.setSubmittedAt(new Date());
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectById(8L)).thenReturn(report);
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(draftMapper.submitForApproval(eq(1L), eq(2), eq(8L), eq("submit-key"),
                eq(user.getName()), any(Date.class))).thenReturn(1);
        when(draftMapper.selectById(1L)).thenReturn(submitted);

        ModelingDraftStage4Store.SubmissionResult result =
                store.submit(1L, 2, 8L, "submit-key", user);

        assertThat(result.replay()).isFalse();
        assertThat(result.draft().getStatus())
                .isEqualTo(ModelingDraftConstants.STATUS_PENDING_APPROVAL);
        assertThat(result.draft().getSubmittedValidationReportId()).isEqualTo(8L);
        InOrder lockOrder = inOrder(draftMapper, revisionStore);
        lockOrder.verify(draftMapper).selectByIdForUpdate(1L);
        lockOrder.verify(revisionStore).assertNoActiveRevision(1L, user);
    }

    /** 完成报告时必须把十项必需检查与最终状态原子写入，供 submit 再校验。 */
    @Test
    void shouldPersistRequiredChecksWhenCompletingValidation() {
        SemanticModelingValidationReportDO completed =
                report(8L, 2, ModelingDraftConstants.VALIDATION_PASSED, 0);
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.selectById(8L)).thenReturn(completed);

        store.completeValidation(completed);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SemanticModelingValidationReportDO>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reportMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet().toLowerCase())
                .contains("required_check_results");
        assertThat(updateCaptor.getValue().getParamNameValuePairs())
                .containsValue(completed.getRequiredCheckResults());
    }

    /** 即使旧报告通过，只要不是当前版本最新报告也必须拒绝提交。 */
    @Test
    void shouldRejectNonLatestValidationReport() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingValidationReportDO requested =
                report(8L, 2, ModelingDraftConstants.VALIDATION_PASSED, 0);
        SemanticModelingValidationReportDO latest =
                report(9L, 2, ModelingDraftConstants.VALIDATION_FAILED, 1);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectById(8L)).thenReturn(requested);
        when(reportMapper.selectOne(any())).thenReturn(latest);

        assertThatThrownBy(() -> store.submit(1L, 2, 8L, "submit-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_SUBMISSION_CONFLICT));
        verify(draftMapper, never()).submitForApproval(any(), any(), any(), any(), any(), any());
    }

    /** 草稿版本推进后，旧版本报告即使完整通过也不能用于新版本提交。 */
    @Test
    void shouldRejectPreviousVersionReportAfterDraftAdvances() {
        SemanticModelingDraftDO draft = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingValidationReportDO previousVersion =
                report(8L, 2, ModelingDraftConstants.VALIDATION_PASSED, 0);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectById(8L)).thenReturn(previousVersion);

        assertThatThrownBy(() -> store.submit(1L, 3, 8L, "submit-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_SUBMISSION_CONFLICT));
        verify(reportMapper, never()).selectOne(any());
        verify(draftMapper, never()).submitForApproval(any(), any(), any(), any(), any(), any());
    }

    /** 新一轮验证仍在运行时，旧的通过报告也不能跨过“最新报告”提交门禁。 */
    @Test
    void shouldRejectPassedReportWhenNewerValidationIsRunning() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingValidationReportDO requested =
                report(8L, 2, ModelingDraftConstants.VALIDATION_PASSED, 0);
        SemanticModelingValidationReportDO running =
                report(9L, 2, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        running.setFinishedAt(null);
        running.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectById(8L)).thenReturn(requested);
        when(reportMapper.selectOne(any())).thenReturn(running);

        assertThatThrownBy(() -> store.submit(1L, 2, 8L, "submit-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_SUBMISSION_CONFLICT));
        verify(draftMapper, never()).submitForApproval(any(), any(), any(), any(), any(), any());
    }

    /** 状态字符串为 PASSED 但缺少必需检查集合时仍必须 fail-closed。 */
    @Test
    void shouldRejectPassedReportWithIncompleteRequiredChecks() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingValidationReportDO report =
                report(8L, 2, ModelingDraftConstants.VALIDATION_PASSED, 0);
        report.setRequiredCheckResults("[]");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectById(8L)).thenReturn(report);

        assertThatThrownBy(() -> store.submit(1L, 2, 8L, "submit-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_SUBMISSION_CONFLICT));

        verify(draftMapper, never()).submitForApproval(any(), any(), any(), any(), any(), any());
    }

    /** 未过期 RUNNING 报告必须阻止第二个验证任务。 */
    @Test
    void shouldRejectSecondRunningValidation() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(20L);
        version.setDraftId(1L);
        version.setVersionNo(2);
        SemanticModelingValidationReportDO active = new SemanticModelingValidationReportDO();
        active.setId(7L);
        active.setDraftId(1L);
        active.setStatus(ModelingDraftConstants.VALIDATION_RUNNING);
        active.setActiveMarker(1);
        active.setCreatedAt(new Date());
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectOne(any())).thenReturn(active);

        assertThatThrownBy(() -> store.startValidation(1L, version, "{}", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_VALIDATION_RUNNING));
        verify(reportMapper, never()).insert(any(SemanticModelingValidationReportDO.class));
        verify(revisionStore).assertNoActiveRevision(1L, user);
    }

    /** 新验证必须只使用一次数据库时间，并把同一时间写入 RUNNING 报告。 */
    @Test
    void shouldCreateValidationWithDatabaseTimestamp() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(20L);
        version.setDraftId(1L);
        version.setVersionNo(2);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);

        store.startValidation(1L, version, "{}", user);

        ArgumentCaptor<SemanticModelingValidationReportDO> reportCaptor =
                ArgumentCaptor.forClass(SemanticModelingValidationReportDO.class);
        verify(reportMapper).insert(reportCaptor.capture());
        verify(reportMapper).selectCurrentTimestamp();
        assertThat(reportCaptor.getValue().getCreatedAt()).isSameAs(databaseNow);
    }

    /** JVM 当前时间与数据库时钟差异不能把租约内报告误判为过期。 */
    @Test
    void shouldUseDatabaseClockWhenRejectingFreshValidationLease() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(20L);
        version.setDraftId(1L);
        version.setVersionNo(2);
        SemanticModelingValidationReportDO active =
                report(7L, 2, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 1_000L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);

        assertThatThrownBy(() -> store.startValidation(1L, version, "{}", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_VALIDATION_RUNNING));

        verify(reportMapper, never()).update(isNull(), any());
        verify(reportMapper, never()).insert(any(SemanticModelingValidationReportDO.class));
    }

    /** 过期租约必须以同一个数据库时间结束旧报告并创建新报告。 */
    @Test
    void shouldExpireValidationAndCreateReplacementWithSameDatabaseTimestamp() {
        // 数据库时间故意晚于测试 JVM 数十年，证明“JVM 偏慢”不会延迟过期恢复。
        Date databaseNow = new Date(4_102_444_800_000L);
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(20L);
        version.setDraftId(1L);
        version.setVersionNo(2);
        SemanticModelingValidationReportDO active =
                report(7L, 2, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 600_001L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(reportMapper.update(isNull(), any())).thenReturn(1);

        store.startValidation(1L, version, "{}", user);

        ArgumentCaptor<SemanticModelingValidationReportDO> reportCaptor =
                ArgumentCaptor.forClass(SemanticModelingValidationReportDO.class);
        verify(reportMapper).update(isNull(), any());
        verify(reportMapper).insert(reportCaptor.capture());
        verify(reportMapper).selectCurrentTimestamp();
        assertThat(reportCaptor.getValue().getCreatedAt()).isSameAs(databaseNow);
    }

    /** 数据库租约时间为空或读取异常时不得结束旧报告，也不得创建新报告。 */
    @Test
    void shouldFailClosedWhenValidationDatabaseTimeIsUnavailable() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(20L);
        version.setDraftId(1L);
        version.setVersionNo(2);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(null);

        assertThatThrownBy(() -> store.startValidation(1L, version, "{}", user))
                .isInstanceOf(RuntimeException.class);

        when(reportMapper.selectCurrentTimestamp()).thenThrow(new RuntimeException("database"));
        assertThatThrownBy(() -> store.startValidation(1L, version, "{}", user))
                .isInstanceOf(RuntimeException.class);
        verify(reportMapper, never()).update(isNull(), any());
        verify(reportMapper, never()).insert(any(SemanticModelingValidationReportDO.class));
    }

    /** 活动 AI 修订必须在验证报告创建前阻止验证，避免基线并行变化。 */
    @Test
    void shouldRejectValidationWhileAiRevisionIsRunning() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        SemanticModelingDraftVersionDO version = new SemanticModelingDraftVersionDO();
        version.setId(20L);
        version.setDraftId(1L);
        version.setVersionNo(2);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        doThrow(new ModelingDraftException(org.springframework.http.HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_REVISION_RUNNING, "AI 正在修订")).when(revisionStore)
                        .assertNoActiveRevision(1L, user);

        assertThatThrownBy(() -> store.startValidation(1L, version, "{}", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_REVISION_RUNNING));

        verify(reportMapper, never()).insert(any(SemanticModelingValidationReportDO.class));
    }

    /** 活动 AI 修订必须在报告门禁读取前阻止提交审批。 */
    @Test
    void shouldRejectSubmissionWhileAiRevisionIsRunning() {
        SemanticModelingDraftDO draft = draft(2, ModelingDraftConstants.STATUS_DRAFT);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(draft);
        doThrow(new ModelingDraftException(org.springframework.http.HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_REVISION_RUNNING, "AI 正在修订")).when(revisionStore)
                        .assertNoActiveRevision(1L, user);

        assertThatThrownBy(() -> store.submit(1L, 2, 8L, "submit-key", user))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_REVISION_RUNNING));

        verify(reportMapper, never()).selectById(any());
        verify(draftMapper, never()).submitForApproval(any(), any(), any(), any(), any(), any());
    }

    /** 恢复历史快照必须追加连续新版本，且不修改目标历史版本。 */
    @Test
    void shouldAppendHistoricalSnapshotAsNewVersion() {
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        locked.setLlmConversationId(9L);
        SemanticModelingDraftVersionDO target = new SemanticModelingDraftVersionDO();
        target.setId(10L);
        target.setDraftId(1L);
        target.setVersionNo(1);
        target.setDraftJson("{\"businessGoal\":\"snapshot\"}");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(target);
        when(draftMapper.updateDraftForRestore(eq(1L), eq(3), eq(8), eq(target.getDraftJson()),
                eq(4), eq(user.getName()), any(Date.class))).thenReturn(1);

        ModelingDraftStage4Store.RestoreResult result =
                store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user);

        ArgumentCaptor<SemanticModelingDraftVersionDO> versionCaptor =
                ArgumentCaptor.forClass(SemanticModelingDraftVersionDO.class);
        verify(versionMapper).insert(versionCaptor.capture());
        SemanticModelingDraftVersionDO restored = versionCaptor.getValue();
        assertThat(restored.getVersionNo()).isEqualTo(4);
        assertThat(restored.getDraftJson()).isEqualTo(target.getDraftJson());
        assertThat(restored.getChangeSource()).isEqualTo(ModelingDraftConstants.VERSION_RESTORED);
        assertThat(restored.getResultLockVersion()).isEqualTo(9);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.lockVersion()).isEqualTo(9);
        // 目标对象只作为只读快照输入，测试对象仍保持原版本号和 JSON。
        assertThat(target.getVersionNo()).isEqualTo(1);
        assertThat(target.getDraftJson()).isEqualTo("{\"businessGoal\":\"snapshot\"}");
    }

    /** 相同幂等键和指纹只重放首次恢复结果，不再推进版本。 */
    @Test
    void shouldReplayRestoreWithoutCreatingAnotherVersion() {
        SemanticModelingDraftDO locked = draft(7, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(15);
        SemanticModelingDraftVersionDO replay = new SemanticModelingDraftVersionDO();
        replay.setDraftId(1L);
        replay.setVersionNo(4);
        replay.setChangeSource(ModelingDraftConstants.VERSION_RESTORED);
        replay.setRequestFingerprint("restore-fingerprint");
        replay.setResultLockVersion(9);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(replay);
        doThrow(new ModelingDraftException(org.springframework.http.HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_REVISION_RUNNING, "AI 正在修订")).when(revisionStore)
                        .assertNoActiveRevision(1L, user);

        ModelingDraftStage4Store.RestoreResult result =
                store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user);

        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.version()).isSameAs(replay);
        assertThat(result.lockVersion()).isEqualTo(9);
        verify(revisionStore, never()).assertNoActiveRevision(any(), any());
        verify(reportMapper, never()).selectOne(any());
        verify(draftMapper, never()).updateDraftForRestore(any(), any(), any(), any(), any(), any(),
                any());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** 首次成功后立即同键重试必须重放同一版本和结果锁，且只插入一次版本。 */
    @Test
    void shouldReplayImmediateRestoreRetryWithFirstResult() {
        SemanticModelingDraftDO initial = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        initial.setLockVersion(8);
        SemanticModelingDraftDO afterRestore = draft(4, ModelingDraftConstants.STATUS_DRAFT);
        afterRestore.setLockVersion(9);
        SemanticModelingDraftVersionDO target = new SemanticModelingDraftVersionDO();
        target.setDraftId(1L);
        target.setVersionNo(1);
        target.setDraftJson("{\"businessGoal\":\"snapshot\"}");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(initial, afterRestore);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(target);
        when(draftMapper.updateDraftForRestore(eq(1L), eq(3), eq(8), eq(target.getDraftJson()),
                eq(4), eq(user.getName()), any(Date.class))).thenReturn(1);

        ModelingDraftStage4Store.RestoreResult first =
                store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(first.version());

        ModelingDraftStage4Store.RestoreResult replay =
                store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.version()).isSameAs(first.version());
        assertThat(replay.lockVersion()).isEqualTo(first.lockVersion()).isEqualTo(9);
        verify(versionMapper, times(1)).insert(any(SemanticModelingDraftVersionDO.class));
        verify(revisionStore, times(1)).assertNoActiveRevision(1L, user);
    }

    /** 迁移前缺少结果锁的历史 restore 行不能与当前锁版本拼接，必须 fail-closed。 */
    @Test
    void shouldFailClosedForHistoricalRestoreWithoutResultLockVersion() {
        SemanticModelingDraftDO locked = draft(8, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(20);
        SemanticModelingDraftVersionDO replay = new SemanticModelingDraftVersionDO();
        replay.setDraftId(1L);
        replay.setVersionNo(4);
        replay.setChangeSource(ModelingDraftConstants.VERSION_RESTORED);
        replay.setRequestFingerprint("restore-fingerprint");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(replay);

        assertThatThrownBy(
                () -> store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user))
                        .isInstanceOf(RuntimeException.class)
                        .isNotInstanceOf(ModelingDraftException.class);

        verify(revisionStore, never()).assertNoActiveRevision(any(), any());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** 相同幂等键绑定不同目标或基线时必须拒绝，不能错误重放旧恢复结果。 */
    @Test
    void shouldRejectRestoreIdempotencyKeyReuseForDifferentRequest() {
        SemanticModelingDraftDO locked = draft(4, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(9);
        SemanticModelingDraftVersionDO replay = new SemanticModelingDraftVersionDO();
        replay.setDraftId(1L);
        replay.setVersionNo(4);
        replay.setChangeSource(ModelingDraftConstants.VERSION_RESTORED);
        replay.setRequestFingerprint("first-fingerprint");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(replay);

        assertThatThrownBy(() -> store.restoreVersion(1L, 2, 4, 9, "restore-key",
                "different-fingerprint", user)).isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT));

        verify(draftMapper, never()).updateDraftForRestore(any(), any(), any(), any(), any(), any(),
                any());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** AI 修订等其他操作占用同一版本幂等键时，恢复必须对称返回幂等冲突。 */
    @Test
    void shouldRejectRestoreKeyOwnedByAnotherVersionOperation() {
        SemanticModelingDraftDO locked = draft(4, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(9);
        SemanticModelingDraftVersionDO replay = new SemanticModelingDraftVersionDO();
        replay.setDraftId(1L);
        replay.setVersionNo(4);
        replay.setChangeSource(ModelingDraftConstants.VERSION_AI_REVISED);
        replay.setRequestFingerprint("restore-fingerprint");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(versionMapper.selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key"))
                .thenReturn(replay);

        assertThatThrownBy(
                () -> store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user))
                        .isInstanceOfSatisfying(ModelingDraftException.class,
                                exception -> assertThat(exception.getErrorCode()).isEqualTo(
                                        ModelingDraftConstants.ERROR_IDEMPOTENCY_CONFLICT));

        verify(revisionStore, never()).assertNoActiveRevision(any(), any());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** 非 DRAFT 或过期基线都必须在读取历史快照前拒绝恢复。 */
    @Test
    void shouldRejectRestoreWhenDraftIsReadOnlyOrBaselineIsStale() {
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_PENDING_APPROVAL);
        locked.setLockVersion(8);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);

        assertThatThrownBy(
                () -> store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user))
                        .isInstanceOfSatisfying(ModelingDraftException.class,
                                exception -> assertThat(exception.getErrorCode())
                                        .isEqualTo(ModelingDraftConstants.ERROR_CONFLICT));

        locked.setStatus(ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(9);
        assertThatThrownBy(() -> store.restoreVersion(1L, 1, 3, 8, "restore-key-2",
                "restore-fingerprint-2", user)).isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_CONFLICT));

        verify(versionMapper, never()).selectOne(any());
        verify(draftMapper, never()).updateDraftForRestore(any(), any(), any(), any(), any(), any(),
                any());
    }

    /** 活动验证必须阻止恢复，避免同一版本报告与新草稿版本失配。 */
    @Test
    void shouldRejectRestoreWhileValidationIsRunning() {
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setActiveMarker(1);
        active.setFinishedAt(null);
        Date databaseNow = new Date(1_800_000L);
        active.setCreatedAt(new Date(databaseNow.getTime() - 600_000L));
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);

        assertThatThrownBy(
                () -> store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user))
                        .isInstanceOfSatisfying(ModelingDraftException.class,
                                exception -> assertThat(exception.getErrorCode()).isEqualTo(
                                        ModelingDraftConstants.ERROR_VALIDATION_RUNNING));

        verify(versionMapper, never()).selectOne(any());
        verify(draftMapper, never()).updateDraftForRestore(any(), any(), any(), any(), any(), any(),
                any());
    }

    /** 活动 AI revision 必须按统一锁顺序在历史版本读取前阻止恢复。 */
    @Test
    void shouldRejectRestoreWhileAiRevisionIsRunning() {
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        doThrow(new ModelingDraftException(org.springframework.http.HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_REVISION_RUNNING, "AI 正在修订")).when(revisionStore)
                        .assertNoActiveRevision(1L, user);

        assertThatThrownBy(
                () -> store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user))
                        .isInstanceOfSatisfying(ModelingDraftException.class,
                                exception -> assertThat(exception.getErrorCode())
                                        .isEqualTo(ModelingDraftConstants.ERROR_REVISION_RUNNING));

        verify(versionMapper).selectByDraftIdAndRequestIdempotencyKey(1L, "restore-key");
        verify(versionMapper, never()).selectOne(any());
    }

    /** 过期 RUNNING 报告必须原子结束后允许恢复继续创建新版本。 */
    @Test
    void shouldRestoreAfterExpiringStaleValidation() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 600_001L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        SemanticModelingDraftVersionDO target = new SemanticModelingDraftVersionDO();
        target.setDraftId(1L);
        target.setVersionNo(1);
        target.setDraftJson("{\"businessGoal\":\"snapshot\"}");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(reportMapper.update(isNull(), any())).thenReturn(1);
        when(versionMapper.selectOne(any())).thenReturn(target);
        when(draftMapper.updateDraftForRestore(eq(1L), eq(3), eq(8), eq(target.getDraftJson()),
                eq(4), eq(user.getName()), any(Date.class))).thenReturn(1);

        ModelingDraftStage4Store.RestoreResult result =
                store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user);

        assertThat(result.idempotentReplay()).isFalse();
        verify(reportMapper).update(isNull(), any());
        verify(versionMapper).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** 正常完成先获胜并释放 active marker 时，恢复不得覆盖终态且可安全继续。 */
    @Test
    void shouldContinueRestoreWhenValidationCompletionWinsRecoveryRace() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 600_001L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        SemanticModelingDraftVersionDO target = new SemanticModelingDraftVersionDO();
        target.setDraftId(1L);
        target.setVersionNo(1);
        target.setDraftJson("{\"businessGoal\":\"snapshot\"}");
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(reportMapper.selectOne(any())).thenReturn(active,
                (SemanticModelingValidationReportDO) null);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(reportMapper.update(isNull(), any())).thenReturn(0);
        when(versionMapper.selectOne(any())).thenReturn(target);
        when(draftMapper.updateDraftForRestore(eq(1L), eq(3), eq(8), eq(target.getDraftJson()),
                eq(4), eq(user.getName()), any(Date.class))).thenReturn(1);

        store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user);

        verify(reportMapper).update(isNull(), any());
        verify(versionMapper).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** 活动验证存在但数据库时间不可用时必须保持 fail-closed，不执行恢复写入。 */
    @Test
    void shouldFailClosedRestoreWhenValidationDatabaseTimeIsUnavailable() {
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(1_000L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(null);

        assertThatThrownBy(
                () -> store.restoreVersion(1L, 1, 3, 8, "restore-key", "restore-fingerprint", user))
                        .isInstanceOf(RuntimeException.class);

        verify(reportMapper, never()).update(isNull(), any());
        verify(versionMapper, never()).selectOne(any());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
        verify(draftMapper, never()).updateDraftForRestore(any(), any(), any(), any(), any(), any(),
                any());
    }

    /** 目标版本不存在或属于其他草稿时，带 draftId 条件的查询必须返回统一未找到。 */
    @Test
    void shouldRejectRestoreTargetOutsideDraft() {
        SemanticModelingDraftDO locked = draft(3, ModelingDraftConstants.STATUS_DRAFT);
        locked.setLockVersion(8);
        when(draftMapper.selectByIdForUpdate(1L)).thenReturn(locked);
        when(versionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> store.restoreVersion(1L, 99, 3, 8, "restore-key",
                "restore-fingerprint", user)).isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ModelingDraftConstants.ERROR_NOT_FOUND));

        verify(draftMapper, never()).updateDraftForRestore(any(), any(), any(), any(), any(), any(),
                any());
    }

    /** 查询遇到超过数据库租约的 RUNNING 报告时必须原子恢复并释放活动标记。 */
    @Test
    void shouldRecoverExpiredRunningValidationUsingDatabaseTime() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 601_000L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(3, ModelingDraftConstants.STATUS_DRAFT));
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(reportMapper.update(isNull(), any())).thenReturn(1);

        assertThat(store.recoverStaleValidation(1L)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SemanticModelingValidationReportDO>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reportMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet().toLowerCase()).contains("status",
                "active_marker", "finished_at");
        assertThat(updateCaptor.getValue().getParamNameValuePairs())
                .containsValue(ModelingDraftConstants.VALIDATION_SYSTEM_FAILED);
    }

    /** 租约内 RUNNING 报告不能被查询路径误恢复。 */
    @Test
    void shouldKeepFreshRunningValidation() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 599_000L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(3, ModelingDraftConstants.STATUS_DRAFT));
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);

        assertThat(store.recoverStaleValidation(1L)).isFalse();
        verify(reportMapper, never()).update(isNull(), any());
    }

    /** 正常完成先获胜时，带 RUNNING 条件的恢复更新返回零且不能覆盖成功终态。 */
    @Test
    void shouldNotOverwriteValidationCompletedConcurrently() {
        Date databaseNow = new Date(1_800_000L);
        SemanticModelingValidationReportDO active =
                report(9L, 3, ModelingDraftConstants.VALIDATION_RUNNING, 0);
        active.setCreatedAt(new Date(databaseNow.getTime() - 601_000L));
        active.setFinishedAt(null);
        active.setActiveMarker(1);
        when(draftMapper.selectByIdForUpdate(1L))
                .thenReturn(draft(3, ModelingDraftConstants.STATUS_DRAFT));
        when(reportMapper.selectOne(any())).thenReturn(active);
        when(reportMapper.selectCurrentTimestamp()).thenReturn(databaseNow);
        when(reportMapper.update(isNull(), any())).thenReturn(0);

        assertThat(store.recoverStaleValidation(1L)).isFalse();
        verify(reportMapper).update(isNull(), any());
    }

    /** 构造草稿主记录。 */
    private SemanticModelingDraftDO draft(int versionNo, String status) {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(1L);
        draft.setCurrentVersionNo(versionNo);
        draft.setStatus(status);
        return draft;
    }

    /** 构造已完成验证报告。 */
    private SemanticModelingValidationReportDO report(Long id, int versionNo, String status,
            int blockingCount) {
        SemanticModelingValidationReportDO report = new SemanticModelingValidationReportDO();
        report.setId(id);
        report.setDraftId(1L);
        report.setDraftVersionId(20L);
        report.setDraftVersionNo(versionNo);
        report.setStatus(status);
        report.setBlockingCount(blockingCount);
        report.setFinishedAt(new Date());
        report.setRequiredCheckResults(requiredChecksJson());
        return report;
    }

    /** 构造十项均完成的门禁 JSON。 */
    private String requiredChecksJson() {
        List<ModelingValidationCheckResult> checks =
                ModelingValidationGate.requiredCheckIds().stream()
                        .map(category -> ModelingValidationCheckResult.builder().category(category)
                                .status(ModelingDraftConstants.VALIDATION_PASSED).summary("通过")
                                .checkedCount(1).passedCount(1).failedCount(0).mode("TEST").build())
                        .toList();
        try {
            return new ObjectMapper().writeValueAsString(checks);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法构造测试门禁 JSON", exception);
        }
    }
}
