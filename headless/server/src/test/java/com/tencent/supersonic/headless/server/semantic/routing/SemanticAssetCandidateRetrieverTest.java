package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.ModelDetail;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.ModelDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ModelDOMapper;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 语义资产候选召回的有界批量与优先级回归测试。
 *
 * <p>职责：验证大主题域在截断重型字段查询前会保留真实选表候选，避免只按名称和模型 ID
 * 取前 N 个导致高覆盖资产永远无法进入策略引擎。测试全部使用内存对象和 Mockito，不访问数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class SemanticAssetCandidateRetrieverTest {

    private static final long DATA_SOURCE_ID = 7L;
    private static final long TABLE_MATCH_MODEL_ID = 10_000L;

    @Mock
    private SemanticGapMapper gapMapper;

    @Mock
    private ModelService modelService;

    @Mock
    private DimensionService dimensionService;

    @Mock
    private MetricService metricService;

    @Mock
    private ModelDOMapper modelDOMapper;

    @Mock
    private SemanticAssetVersionService versionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final User user = User.getDefaultUser();
    private SemanticAssetCandidateRetriever retriever;

    /** 创建无共享状态的候选召回器。 */
    @BeforeEach
    void setUp() {
        retriever = new SemanticAssetCandidateRetriever(gapMapper, modelService, dimensionService,
                metricService, modelDOMapper, versionService, objectMapper);
    }

    /** 选表一致的高 ID 模型必须在候选池截断前获得优先级。 */
    @Test
    void shouldKeepSelectedTableCandidateBeforeRecallPoolTruncation() throws Exception {
        List<ModelResp> authorized = new ArrayList<>();
        List<ModelDO> details = new ArrayList<>();
        for (long id = 1; id <= SemanticAssetRoutingConstants.MAX_CANDIDATE_RECALL_POOL + 1L;
                id++) {
            boolean tableMatch = id == SemanticAssetRoutingConstants.MAX_CANDIDATE_RECALL_POOL + 1L;
            long modelId = tableMatch ? TABLE_MATCH_MODEL_ID : id;
            String tableName = tableMatch ? "inventory_stock" : "unrelated_" + id;
            authorized.add(model(modelId, tableName));
            details.add(modelDO(modelId, tableName));
        }
        when(modelService.getModelListWithAuth(eq(user), eq(null), eq(AuthType.VIEWER)))
                .thenReturn(authorized);
        when(modelService.getModelListWithAuth(eq(user), eq(null), eq(AuthType.ADMIN)))
                .thenReturn(List.of());
        when(dimensionService.getDimensions(any())).thenReturn(List.of());
        when(metricService.getMetrics(any())).thenReturn(List.of());
        when(modelDOMapper.selectBatchIds(any())).thenReturn(details);
        when(versionService.versionOf(any(), any(), any())).thenReturn(1L);

        SemanticAssetRouteAnalyzeReq request = new SemanticAssetRouteAnalyzeReq();
        request.setSourceType(SemanticAssetRoutingConstants.SOURCE_DATA_SOURCE);
        request.setBusinessGoal("分析库存趋势");
        request.setDataSourceId(DATA_SOURCE_ID);
        request.setSelectedTables(List.of("inventory_stock"));

        List<SemanticAssetCandidate> candidates = retriever.retrieve(request, user);

        assertTrue(candidates.stream()
                .anyMatch(candidate -> candidate.getAssetId().equals(TABLE_MATCH_MODEL_ID)));
    }

    /** 构造带轻量模型详情的可见模型。 */
    private ModelResp model(long id, String tableName) {
        ModelResp model = new ModelResp();
        model.setId(id);
        model.setName("model_" + id);
        model.setBizName("业务模型 " + id);
        model.setDatabaseId(DATA_SOURCE_ID);
        ModelDetail detail = new ModelDetail();
        detail.setTableQuery(tableName);
        model.setModelDetail(detail);
        return model;
    }

    /** 构造与可见模型一致的持久化详情快照。 */
    private ModelDO modelDO(long id, String tableName) throws Exception {
        ModelDO model = new ModelDO();
        model.setId(id);
        ModelDetail detail = new ModelDetail();
        detail.setTableQuery(tableName);
        model.setModelDetail(objectMapper.writeValueAsString(detail));
        return model;
    }
}
