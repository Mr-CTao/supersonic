package com.tencent.supersonic.headless.server.semantic.modeling.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticReleaseStepDO;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapService;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftException;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.ReleaseClaim;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.RollbackClaim;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseStore.StepClaim;
import com.tencent.supersonic.headless.server.task.DictionaryReloadTask;
import com.tencent.supersonic.headless.server.task.MetaEmbeddingTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阶段 5 发布编排的知识刷新、权限和受限回滚测试。
 *
 * <p>
 * 职责说明：使用 Mockito 隔离正式语义服务和数据库，验证 dict/embedding 必须独立执行并 记账、非管理员不能触发任何写操作，以及回滚只删除发布步骤白名单中的 AI
 * 新增对象并遵守 依赖逆序。测试不启动 Spring 容器，也不访问正式元数据表。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SemanticReleaseServiceTest {

    private static final Long RELEASE_ID = 301L;
    private static final Long DRAFT_ID = 201L;

    @Mock
    private SemanticReleaseStore store;

    @Mock
    private FormalSemanticAssetPublisher publisher;

    @Mock
    private DictionaryReloadTask dictionaryReloadTask;

    @Mock
    private MetaEmbeddingTask metaEmbeddingTask;

    @Mock
    private SemanticGapService semanticGapService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final User admin = User.getDefaultUser();
    private SemanticReleaseService service;

    /** 为每个测试创建无共享状态的发布服务。 */
    @BeforeEach
    void setUp() {
        service = new SemanticReleaseService(store, publisher, dictionaryReloadTask,
                metaEmbeddingTask, semanticGapService, objectMapper);
    }

    /** dict 失败不能短路 embedding，两个结果必须分别落为失败与成功步骤。 */
    @Test
    void shouldAttemptEmbeddingAndPersistBothResultsWhenDictReloadFails() throws Exception {
        SemanticModelingDraftDO draft = draftWithEmptyPayload();
        SemanticReleaseDO release = release(SemanticReleaseConstants.RELEASE_IN_PROGRESS);
        SemanticReleaseResp expected = SemanticReleaseResp.builder().id(RELEASE_ID)
                .releaseStatus(SemanticReleaseConstants.RELEASE_FAILED).build();
        when(store.beginRelease(DRAFT_ID, "release-key", admin))
                .thenReturn(new ReleaseClaim(release, draft, true));
        mockExecutableSteps();
        doThrow(new IllegalStateException("Authorization: Bearer secret refresh failed"))
                .when(dictionaryReloadTask).reloadKnowledgeOrThrow();
        when(store.getRelease(RELEASE_ID)).thenReturn(expected);

        SemanticReleaseResp actual = service.release(DRAFT_ID, "release-key", admin);

        assertEquals(expected, actual);
        verify(dictionaryReloadTask).reloadKnowledgeOrThrow();
        verify(metaEmbeddingTask).reloadMetaEmbeddingOrThrow();
        verify(store).failStep(eq(1L), eq("Authorization=*** refresh failed"));
        verify(store).completeStep(eq(2L), isNull());
        verify(store).finishRelease(RELEASE_ID, false, "语义对象已创建，但知识刷新或缺口状态更新失败", admin);
    }

    /** 非系统管理员必须在认领发布前被拒绝，避免产生任何审计或正式对象副作用。 */
    @Test
    void shouldRejectReleaseBeforeAnyWriteForNonAdmin() {
        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> service.release(DRAFT_ID, "release-key", User.getVisitUser()));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(SemanticReleaseConstants.ERROR_ACCESS_DENIED, exception.getErrorCode());
        verifyNoInteractions(store, publisher, dictionaryReloadTask, metaEmbeddingTask,
                semanticGapService);
    }

    /** 回滚只使用成功创建步骤中的正式 ID，并按术语、指标、维度、模型的依赖逆序删除。 */
    @Test
    void shouldRollbackOnlyRecordedAiObjectsInReverseDependencyOrder() throws Exception {
        SemanticReleaseDO release = release(SemanticReleaseConstants.RELEASE_SUCCEEDED);
        when(store.beginRollback(eq(RELEASE_ID), eq("模型口径有误"), eq(admin)))
                .thenReturn(new RollbackClaim(release, true));
        when(store.successfulObjectSteps(RELEASE_ID))
                .thenReturn(List.of(objectStep(11L, SemanticReleaseConstants.TYPE_MODEL, 101L),
                        objectStep(12L, SemanticReleaseConstants.TYPE_DIMENSION, 102L),
                        objectStep(13L, SemanticReleaseConstants.TYPE_METRIC, 103L),
                        objectStep(14L, SemanticReleaseConstants.TYPE_TERM, 104L)));
        when(store.requireDraft(DRAFT_ID)).thenReturn(draftWithEmptyPayload());
        mockExecutableSteps();
        SemanticReleaseResp expected = SemanticReleaseResp.builder().id(RELEASE_ID)
                .releaseStatus(SemanticReleaseConstants.RELEASE_ROLLED_BACK).build();
        when(store.getRelease(RELEASE_ID)).thenReturn(expected);
        SemanticRollbackReq request = new SemanticRollbackReq();
        request.setReason("模型口径有误");

        SemanticReleaseResp actual = service.rollback(RELEASE_ID, request, admin);

        assertEquals(expected, actual);
        InOrder deletionOrder = inOrder(publisher);
        deletionOrder.verify(publisher).delete(SemanticReleaseConstants.TYPE_TERM, 104L, admin);
        deletionOrder.verify(publisher).delete(SemanticReleaseConstants.TYPE_METRIC, 103L, admin);
        deletionOrder.verify(publisher).delete(SemanticReleaseConstants.TYPE_DIMENSION, 102L,
                admin);
        deletionOrder.verify(publisher).delete(SemanticReleaseConstants.TYPE_MODEL, 101L, admin);
        verify(dictionaryReloadTask).reloadKnowledgeOrThrow();
        verify(metaEmbeddingTask).reloadMetaEmbeddingOrThrow();
        verify(store).finishRollback(RELEASE_ID, true, null, admin);
        verify(publisher, never()).delete(anyString(), isNull(), any(User.class));
    }

    /** 为任意步骤生成新的可执行步骤 ID，模拟数据库唯一步骤认领。 */
    private void mockExecutableSteps() {
        AtomicLong sequence = new AtomicLong();
        when(store.claimStep(eq(RELEASE_ID), any(SemanticReleaseStore.StepDescriptor.class)))
                .thenAnswer(invocation -> {
                    SemanticReleaseStepDO step = new SemanticReleaseStepDO();
                    step.setId(sequence.incrementAndGet());
                    return new StepClaim(step, true);
                });
    }

    /** 构造不关联语义缺口的空对象草稿，专注测试刷新编排。 */
    private SemanticModelingDraftDO draftWithEmptyPayload() {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(DRAFT_ID);
        try {
            draft.setDraftJson(objectMapper.writeValueAsString(new ModelingDraftPayload()));
        } catch (Exception exception) {
            throw new IllegalStateException("测试草稿序列化失败", exception);
        }
        return draft;
    }

    /** 构造发布主记录。 */
    private SemanticReleaseDO release(String status) {
        SemanticReleaseDO release = new SemanticReleaseDO();
        release.setId(RELEASE_ID);
        release.setDraftId(DRAFT_ID);
        release.setReleaseStatus(status);
        return release;
    }

    /** 构造已经成功创建且带正式 ID 的对象步骤。 */
    private SemanticReleaseStepDO objectStep(Long id, String type, Long targetId) {
        SemanticReleaseStepDO step = new SemanticReleaseStepDO();
        step.setId(id);
        step.setTargetType(type);
        step.setTargetId(targetId);
        step.setTargetKey(type.toLowerCase());
        step.setTargetName(type);
        step.setStatus(SemanticReleaseConstants.STEP_SUCCEEDED);
        return step;
    }
}
