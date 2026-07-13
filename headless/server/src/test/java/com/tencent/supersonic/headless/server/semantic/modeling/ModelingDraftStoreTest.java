package com.tencent.supersonic.headless.server.semantic.modeling;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftAttemptDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftVersionDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftAttemptMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftVersionMapper;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapStatus;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftStore.CreateResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 语义建模草稿短事务存储服务测试。
 *
 * <p>
 * 职责说明：使用 Mockito 验证 Gap 行锁去重、生成成功的原子版本快照、人工保存的乐观锁与版本递增， 以及超时恢复时主草稿和 Gap 的状态联动。测试不会启动 Spring
 * 容器或连接数据库，也不会访问正式语义 模型、维度、指标和术语表。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ModelingDraftStoreTest {

    private static final Long DRAFT_ID = 101L;
    private static final Long GAP_ID = 201L;
    private static final Long CONVERSATION_ID = 301L;
    private static final String IDEMPOTENCY_KEY = "gap-draft-201";
    private static final String DRAFT_JSON = "{\"schemaVersion\":\"1.0\"}";

    @Mock
    private SemanticModelingDraftMapper draftMapper;

    @Mock
    private SemanticModelingDraftAttemptMapper attemptMapper;

    @Mock
    private SemanticModelingDraftVersionMapper versionMapper;

    @Mock
    private SemanticGapMapper gapMapper;

    @Mock
    private ModelingDraftRevisionStore revisionStore;

    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<SemanticModelingDraftDO>> draftUpdateCaptor;

    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<SemanticGapDO>> gapUpdateCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final User user = User.getDefaultUser();

    private ModelingDraftStore store;

    /**
     * 初始化 MyBatis-Plus Lambda 列缓存。
     *
     * <p>
     * 生产环境由 Mapper 扫描自动完成该步骤；纯 Mockito 测试不启动 Spring/MyBatis，因此需显式注册
     * 两个条件构造器涉及的实体，确保测试执行的是与运行时一致的列解析逻辑。
     * </p>
     */
    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "modeling-draft-store-test");
        TableInfoHelper.initTableInfo(assistant, SemanticModelingDraftDO.class);
        TableInfoHelper.initTableInfo(assistant, SemanticModelingDraftAttemptDO.class);
        TableInfoHelper.initTableInfo(assistant, SemanticGapDO.class);
    }

    /** 创建只含 Mockito 依赖的存储服务，保证各测试互不共享状态。 */
    @BeforeEach
    void setUp() {
        store = new ModelingDraftStore(draftMapper, attemptMapper, versionMapper, gapMapper,
                objectMapper, revisionStore);
    }

    /** 同一 Gap 已存在活动草稿时必须复用该记录，不得再插入草稿或重复改变 Gap 状态。 */
    @Test
    void shouldReuseActiveDraftForSameGapWithoutInsert() {
        ModelingDraftGenerateReq request = newGapRequest();
        SemanticGapDO lockedGap = newGap(SemanticGapStatus.PENDING_ANALYSIS);
        SemanticModelingDraftDO activeDraft =
                newDraft(ModelingDraftConstants.STATUS_PENDING_APPROVAL, 2, 3);
        when(draftMapper.selectByIdempotencyKey(user.getName(), IDEMPOTENCY_KEY)).thenReturn(null);
        when(gapMapper.selectByIdForUpdate(GAP_ID)).thenReturn(lockedGap);
        when(draftMapper.selectOne(any())).thenReturn(activeDraft);

        CreateResult result = store.createGenerating(request, IDEMPOTENCY_KEY, "fingerprint", user);

        assertSame(activeDraft, result.draft());
        assertTrue(result.replay());
        verify(gapMapper).selectByIdForUpdate(GAP_ID);
        ArgumentCaptor<LambdaQueryWrapper<SemanticModelingDraftDO>> activeQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(draftMapper).selectOne(activeQueryCaptor.capture());
        activeQueryCaptor.getValue().getSqlSegment();
        assertTrue(activeQueryCaptor.getValue().getParamNameValuePairs()
                .containsValue(ModelingDraftConstants.STATUS_PENDING_APPROVAL));
        verify(draftMapper, never()).insert(any(SemanticModelingDraftDO.class));
        verify(gapMapper, never()).updateById(any(SemanticGapDO.class));
    }

    /** 成功生成必须把主表置为 DRAFT、插入 AI 版本 1，并把来源 Gap 转为等待确认。 */
    @Test
    void shouldCompleteGenerationWithVersionOneAndWaitingConfirmationGap() {
        SemanticModelingDraftDO persistedDraft =
                newDraft(ModelingDraftConstants.STATUS_GENERATING, 0, 0);
        when(draftMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.update(isNull(), any())).thenReturn(1);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(persistedDraft);

        boolean completed = store.completeGeneration(DRAFT_ID, 1, DRAFT_JSON, "raw", "repaired",
                CONVERSATION_ID, "generate-request", "repair-request", user);

        assertTrue(completed);
        verify(draftMapper).update(isNull(), draftUpdateCaptor.capture());
        Map<String, Object> draftUpdateValues =
                draftUpdateCaptor.getValue().getParamNameValuePairs();
        assertTrue(draftUpdateValues.containsValue(ModelingDraftConstants.STATUS_DRAFT));
        assertTrue(draftUpdateValues.containsValue(1));

        ArgumentCaptor<SemanticModelingDraftVersionDO> versionCaptor =
                ArgumentCaptor.forClass(SemanticModelingDraftVersionDO.class);
        verify(versionMapper).insert(versionCaptor.capture());
        SemanticModelingDraftVersionDO version = versionCaptor.getValue();
        assertEquals(DRAFT_ID, version.getDraftId());
        assertEquals(1, version.getVersionNo());
        assertEquals(DRAFT_JSON, version.getDraftJson());
        assertEquals(ModelingDraftConstants.VERSION_AI_GENERATED, version.getChangeSource());
        assertEquals("AI 修复后生成", version.getChangeSummary());
        assertEquals(CONVERSATION_ID, version.getLlmConversationId());

        verify(gapMapper).update(isNull(), gapUpdateCaptor.capture());
        LambdaUpdateWrapper<SemanticGapDO> gapUpdate = gapUpdateCaptor.getValue();
        // MyBatis-Plus 会在渲染 SQL 条件时才把 where 参数写入参数表，先渲染可避免测试误判。
        gapUpdate.getSqlSegment();
        Map<String, Object> gapUpdateValues = gapUpdate.getParamNameValuePairs();
        assertTrue(gapUpdateValues.containsValue(SemanticGapStatus.DRAFTING.name()));
        assertTrue(gapUpdateValues.containsValue(SemanticGapStatus.WAITING_CONFIRMATION.name()));
    }

    /** 人工保存必须从当前版本递增一次，并使用 MANUAL_SAVE 变更来源保存不可变快照。 */
    @Test
    void shouldIncrementVersionForManualSave() {
        SemanticModelingDraftDO current = newDraft(ModelingDraftConstants.STATUS_DRAFT, 1, 4);
        current.setLlmConversationId(CONVERSATION_ID);
        SemanticModelingDraftDO saved = newDraft(ModelingDraftConstants.STATUS_DRAFT, 2, 5);
        when(draftMapper.updateDraftWithVersion(eq(DRAFT_ID), eq(4), eq(DRAFT_JSON), eq(2),
                eq(user.getName()), any(Date.class))).thenReturn(1);
        when(draftMapper.selectById(DRAFT_ID)).thenReturn(saved);

        SemanticModelingDraftDO result =
                store.saveVersion(current, 4, DRAFT_JSON, "补充订单状态维度", user);

        assertSame(saved, result);
        verify(draftMapper).updateDraftWithVersion(eq(DRAFT_ID), eq(4), eq(DRAFT_JSON), eq(2),
                eq(user.getName()), any(Date.class));
        ArgumentCaptor<SemanticModelingDraftVersionDO> versionCaptor =
                ArgumentCaptor.forClass(SemanticModelingDraftVersionDO.class);
        verify(versionMapper).insert(versionCaptor.capture());
        SemanticModelingDraftVersionDO version = versionCaptor.getValue();
        assertEquals(2, version.getVersionNo());
        assertEquals(ModelingDraftConstants.VERSION_MANUAL_SAVE, version.getChangeSource());
        assertEquals("补充订单状态维度", version.getChangeSummary());
        assertEquals(CONVERSATION_ID, version.getLlmConversationId());
        InOrder lockOrder = inOrder(revisionStore, draftMapper);
        lockOrder.verify(revisionStore).assertNoActiveRevision(DRAFT_ID, user);
        lockOrder.verify(draftMapper).updateDraftWithVersion(eq(DRAFT_ID), eq(4), eq(DRAFT_JSON),
                eq(2), eq(user.getName()), any(Date.class));
    }

    /** 旧 lockVersion 条件更新失败时必须返回 409，且不得插入孤立版本快照。 */
    @Test
    void shouldReturnConflictForStaleLockVersionWithoutVersionInsert() {
        SemanticModelingDraftDO current = newDraft(ModelingDraftConstants.STATUS_DRAFT, 1, 5);
        when(draftMapper.updateDraftWithVersion(eq(DRAFT_ID), eq(4), eq(DRAFT_JSON), eq(2),
                eq(user.getName()), any(Date.class))).thenReturn(0);

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> store.saveVersion(current, 4, DRAFT_JSON, null, user));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(ModelingDraftConstants.ERROR_CONFLICT, exception.getErrorCode());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
        verify(draftMapper, never()).selectById(DRAFT_ID);
    }

    /** 活动 AI 修订必须在乐观更新前阻止人工保存，避免同一基线产生两个版本。 */
    @Test
    void shouldRejectManualSaveWhileAiRevisionIsRunning() {
        SemanticModelingDraftDO current = newDraft(ModelingDraftConstants.STATUS_DRAFT, 1, 4);
        doThrow(new ModelingDraftException(HttpStatus.CONFLICT,
                ModelingDraftConstants.ERROR_REVISION_RUNNING, "AI 正在修订")).when(revisionStore)
                        .assertNoActiveRevision(DRAFT_ID, user);

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> store.saveVersion(current, 4, DRAFT_JSON, null, user));

        assertEquals(ModelingDraftConstants.ERROR_REVISION_RUNNING, exception.getErrorCode());
        verify(draftMapper, never()).updateDraftWithVersion(any(), any(), any(), any(), any(),
                any());
        verify(versionMapper, never()).insert(any(SemanticModelingDraftVersionDO.class));
    }

    /** 超时批处理必须把所有命中草稿标记失败，并仅将 Gap 来源记录回退为待分析。 */
    @Test
    void shouldFailStaleDraftsAndRollBackGapStatus() {
        SemanticModelingDraftDO gapDraft = newDraft(ModelingDraftConstants.STATUS_GENERATING, 0, 0);
        SemanticModelingDraftDO dataSourceDraft =
                newDraft(ModelingDraftConstants.STATUS_GENERATING, 0, 0);
        dataSourceDraft.setId(102L);
        dataSourceDraft.setSourceType(ModelingDraftConstants.SOURCE_DATA_SOURCE);
        dataSourceDraft.setSourceId(null);
        when(draftMapper.selectList(any())).thenReturn(List.of(gapDraft, dataSourceDraft),
                List.of());
        when(draftMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.update(isNull(), any())).thenReturn(1);
        when(gapMapper.update(isNull(), any())).thenReturn(1);

        int recovered = store.failStaleGenerations(new Date());

        assertEquals(2, recovered);
        verify(draftMapper, times(2)).update(isNull(), draftUpdateCaptor.capture());
        Map<String, Object> draftUpdateValues =
                draftUpdateCaptor.getAllValues().get(0).getParamNameValuePairs();
        assertTrue(
                draftUpdateValues.containsValue(ModelingDraftConstants.STATUS_GENERATION_FAILED));
        assertTrue(
                draftUpdateValues.containsValue(ModelingDraftConstants.ERROR_GENERATION_TIMEOUT));
        String draftSqlSegment = draftUpdateCaptor.getAllValues().get(0).getSqlSegment();
        assertTrue(draftSqlSegment.contains("generation_started_at"), draftSqlSegment);
        assertTrue(draftSqlSegment.contains("created_at"), draftSqlSegment);

        verify(gapMapper).update(isNull(), gapUpdateCaptor.capture());
        String gapSqlSegment = gapUpdateCaptor.getValue().getSqlSegment();
        Map<String, Object> gapUpdateValues = gapUpdateCaptor.getValue().getParamNameValuePairs();
        assertTrue(gapUpdateValues.containsValue(SemanticGapStatus.DRAFTING.name()),
                () -> gapSqlSegment + " " + gapUpdateValues);
        assertTrue(gapUpdateValues.containsValue(SemanticGapStatus.PENDING_ANALYSIS.name()));
        assertFalse(gapUpdateValues.containsValue(102L));
    }

    /** 候选 Gap 已出现新活动草稿时，恢复线程不得把 DRAFTING 状态回退为待分析。 */
    @Test
    void shouldKeepGapStatusWhenAnotherActiveDraftAppearsDuringRecovery() {
        SemanticModelingDraftDO staleGapDraft =
                newDraft(ModelingDraftConstants.STATUS_GENERATING, 0, 0);
        SemanticModelingDraftDO activeGapDraft =
                newDraft(ModelingDraftConstants.STATUS_PENDING_APPROVAL, 2, 3);
        activeGapDraft.setId(103L);
        when(draftMapper.selectList(any())).thenReturn(List.of(staleGapDraft),
                List.of(activeGapDraft));
        when(draftMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.update(isNull(), any())).thenReturn(1);

        int recovered = store.failStaleGenerations(new Date());

        assertEquals(1, recovered);
        ArgumentCaptor<LambdaQueryWrapper<SemanticModelingDraftDO>> draftQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(draftMapper, times(2)).selectList(draftQueryCaptor.capture());
        LambdaQueryWrapper<SemanticModelingDraftDO> activeQuery =
                draftQueryCaptor.getAllValues().get(1);
        activeQuery.getSqlSegment();
        assertTrue(activeQuery.getParamNameValuePairs()
                .containsValue(ModelingDraftConstants.STATUS_PENDING_APPROVAL));
        verify(gapMapper).selectByIdsForUpdate(List.of(GAP_ID));
        verify(gapMapper, never()).update(isNull(), any());
    }

    /** 构造 Gap 来源的最小合法生成请求。 */
    private ModelingDraftGenerateReq newGapRequest() {
        ModelingDraftGenerateReq request = new ModelingDraftGenerateReq();
        request.setSourceType(ModelingDraftConstants.SOURCE_SEMANTIC_GAP);
        request.setSourceId(GAP_ID);
        request.setBusinessGoal("分析订单销售额");
        request.setDataSourceId(7L);
        request.setSelectedTables(List.of("orders"));
        request.setChatModelId(3);
        return request;
    }

    /** 构造指定状态的语义缺口。 */
    private SemanticGapDO newGap(SemanticGapStatus status) {
        SemanticGapDO gap = new SemanticGapDO();
        gap.setId(GAP_ID);
        gap.setStatus(status.name());
        return gap;
    }

    /** 构造 Gap 来源草稿及其并发版本信息。 */
    private SemanticModelingDraftDO newDraft(String status, int currentVersionNo, int lockVersion) {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(DRAFT_ID);
        draft.setSourceType(ModelingDraftConstants.SOURCE_SEMANTIC_GAP);
        draft.setSourceId(GAP_ID);
        draft.setStatus(status);
        draft.setCurrentVersionNo(currentVersionNo);
        draft.setCurrentAttemptNo(1);
        draft.setLockVersion(lockVersion);
        draft.setCreatedBy(user.getName());
        draft.setUpdatedBy(user.getName());
        return draft;
    }

}
