package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.persistence.dataobject.LlmModelCapabilityDO;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.TermResp;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.GenerationContext;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.PreflightSnapshot;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TermService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 语义建模草稿可信上下文与样例脱敏单元测试。
 *
 * <p>
 * 职责说明：使用 Mockito 隔离数据源、Gateway 和正式语义只读服务，覆盖 ACL、服务端选表校验、
 * 样例默认关闭、采样失败降级、敏感值脱敏及上下文超限时优先移除样例。测试只观察传给模型的 Prompt，
 * 不连接真实数据源，也不触发任何正式语义资产写入。并发说明：每个用例创建独立属性和构建器，避免 可变限制参数在测试间共享。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ModelingDraftContextBuilderTest {

    private static final Long DATA_SOURCE_ID = 7L;
    private static final Long DOMAIN_ID = 11L;
    private static final String TABLE = "sales_order";

    @Mock
    private DatabaseService databaseService;

    @Mock
    private SemanticGapMapper gapMapper;

    @Mock
    private LlmConversationGatewayService gatewayService;

    @Mock
    private DomainService domainService;

    @Mock
    private ModelService modelService;

    @Mock
    private DimensionService dimensionService;

    @Mock
    private MetricService metricService;

    @Mock
    private TermService termService;

    private final User user = User.getDefaultUser();
    private SemanticModelingProperties properties;
    private ModelingDraftContextBuilder contextBuilder;

    /** 为每个用例创建无共享状态的上下文构建器。 */
    @BeforeEach
    void setUp() {
        properties = new SemanticModelingProperties();
        contextBuilder = new ModelingDraftContextBuilder(new ObjectMapper(), databaseService,
                gapMapper, gatewayService, domainService, modelService, dimensionService,
                metricService, termService, properties,
                new SemanticModelingSensitivityClassifier());
    }

    /** 数据源 ACL 拒绝时必须在读取模型能力、表元数据和样例之前终止。 */
    @Test
    void shouldRejectPreflightWhenDataSourceAccessIsDenied() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenThrow(new IllegalStateException("permission denied"));

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> contextBuilder.preflight(request, user));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(ModelingDraftConstants.ERROR_ACCESS_DENIED, exception.getErrorCode());
        verify(databaseService).getDatabase(DATA_SOURCE_ID, user);
        verifyNoInteractions(gatewayService);
        verify(databaseService, never()).getTables(anyLong(), any(), any());
        verify(databaseService, never()).sampleRows(anyLong(), any(), any(), anyString(), anyInt(),
                anyInt(), any());
    }

    /** 客户端选中的未知表必须被服务端真实表清单阻断，不能继续读取字段。 */
    @Test
    void shouldRejectUnknownSelectedTableFromServerMetadata() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setSelectedTables(List.of("missing_table"));
        stubAccessibleJsonModel();
        when(databaseService.getTables(DATA_SOURCE_ID, "catalog", "warehouse"))
                .thenReturn(List.of(TABLE));

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> contextBuilder.preflight(request, user));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatus());
        assertEquals(ModelingDraftConstants.ERROR_INVALID_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("missing_table"));
        verify(databaseService, never()).getDbColumns(any());
        verify(databaseService, never()).sampleRows(anyLong(), any(), any(), anyString(), anyInt(),
                anyInt(), any());
    }

    /** 请求未提供样例开关或显式关闭时均不得调用采样接口。 */
    @Test
    void shouldNeverSampleWhenSampleDataIsDefaultOrExplicitlyDisabled() throws Exception {
        ModelingDraftGenerateReq defaultRequest = newGenerateRequest();
        assertEquals(Boolean.FALSE, defaultRequest.getIncludeSampleData());
        GenerationContext defaultContext = contextBuilder.build(snapshot(defaultRequest), user);

        ModelingDraftGenerateReq disabledRequest = newGenerateRequest();
        disabledRequest.setIncludeSampleData(false);
        GenerationContext disabledContext = contextBuilder.build(snapshot(disabledRequest), user);

        assertFalse(defaultContext.userPrompt().contains("maskedSamples"));
        assertFalse(disabledContext.userPrompt().contains("maskedSamples"));
        verify(databaseService, never()).sampleRows(anyLong(), any(), any(), anyString(), anyInt(),
                anyInt(), any());
    }

    /** 开启样例后单表 JDBC 失败必须安全回退为 schema-only，不能让生成任务失败。 */
    @Test
    void shouldFallBackToSchemaOnlyWhenSamplingFails() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setIncludeSampleData(true);
        when(databaseService.sampleRows(DATA_SOURCE_ID, "catalog", "warehouse", TABLE, 3, 5, user))
                .thenThrow(new SQLException("driver timeout"));

        GenerationContext generationContext = contextBuilder.build(snapshot(request), user);

        assertTrue(generationContext.userPrompt().contains("selectedTableSchemas"));
        assertFalse(generationContext.userPrompt().contains("maskedSamples"));
        assertFalse(generationContext.userPrompt().contains("driver timeout"));
        verify(databaseService).sampleRows(DATA_SOURCE_ID, "catalog", "warehouse", TABLE, 3, 5,
                user);
    }

    /** 敏感列值及嵌入普通文本的邮箱、手机号、Token/Secret 都必须替换为固定掩码。 */
    @Test
    void shouldMaskSensitiveColumnsAndSensitiveValuePatternsInPrompt() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setIncludeSampleData(true);
        Map<String, Object> sensitiveRow = new LinkedHashMap<>();
        sensitiveRow.put("password", "plain-password-123");
        sensitiveRow.put("pwd", "P@ssw0rd!");
        sensitiveRow.put("session_cookie", "opaque-session-value");
        sensitiveRow.put("contact", "owner@example.com");
        sensitiveRow.put("external_id", "13800138000");
        sensitiveRow.put("authorization", "Bearer internal-token-value");
        sensitiveRow.put("remark_one", "请联系 owner@example.com 确认库存");
        sensitiveRow.put("remark_two", "备用电话是 13800138000，只用于应急通知");
        sensitiveRow.put("remark_three", "headers={Authorization=Bearer nested-token-123}");
        sensitiveRow.put("remark_four", "region=cn; client_secret=super-secret-123; timeout=5");
        sensitiveRow.put("remark_five", "retry with token=abc.DEF_123456 after refresh");
        sensitiveRow.put("display_name", "华北区域");
        when(databaseService.sampleRows(DATA_SOURCE_ID, "catalog", "warehouse", TABLE, 3, 5, user))
                .thenReturn(List.of(sensitiveRow));

        GenerationContext generationContext = contextBuilder.build(snapshot(request), user);
        String prompt = generationContext.userPrompt();

        assertTrue(prompt.contains("[MASKED]"));
        assertFalse(prompt.contains("plain-password-123"));
        assertFalse(prompt.contains("P@ssw0rd!"));
        assertFalse(prompt.contains("opaque-session-value"));
        assertFalse(prompt.contains("owner@example.com"));
        assertFalse(prompt.contains("13800138000"));
        assertFalse(prompt.contains("internal-token-value"));
        assertFalse(prompt.contains("请联系 owner@example.com"));
        assertFalse(prompt.contains("备用电话是 13800138000"));
        assertFalse(prompt.contains("nested-token-123"));
        assertFalse(prompt.contains("super-secret-123"));
        assertFalse(prompt.contains("abc.DEF_123456"));
        assertTrue(prompt.contains("华北区域"), "普通业务值应保留，避免把全部样例误删");
    }

    /** 上下文超限时必须优先移除样例，而保留选表 Schema 继续生成。 */
    @Test
    void shouldDropSamplesFirstWhenContextExceedsLimit() throws Exception {
        ModelingDraftGenerateReq schemaOnlyRequest = newGenerateRequest();
        GenerationContext schemaOnly = contextBuilder.build(snapshot(schemaOnlyRequest), user);
        int schemaOnlyCharacters = schemaOnly.systemPrompt().length()
                + schemaOnly.userPrompt().length() + schemaOnly.jsonSchema().toString().length();

        ModelingDraftGenerateReq sampleRequest = newGenerateRequest();
        sampleRequest.setIncludeSampleData(true);
        when(databaseService.sampleRows(DATA_SOURCE_ID, "catalog", "warehouse", TABLE, 3, 5, user))
                .thenReturn(List.of(Map.of("description", "x".repeat(500))));
        properties.setMaxContextCharacters(schemaOnlyCharacters);

        GenerationContext generationContext = contextBuilder.build(snapshot(sampleRequest), user);

        assertTrue(generationContext.userPrompt().contains("selectedTableSchemas"));
        assertFalse(generationContext.userPrompt().contains("maskedSamples"));
        assertFalse(generationContext.userPrompt().contains("x".repeat(100)));
        verify(databaseService).sampleRows(DATA_SOURCE_ID, "catalog", "warehouse", TABLE, 3, 5,
                user);
    }

    /** 主题域管理员即使当前域没有模型，也应可以把本域全部术语加入草稿上下文。 */
    @Test
    void shouldIncludeAllDomainTermsForDomainAdministratorWithoutModels() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setDomainId(DOMAIN_ID);
        stubPreflightMetadata();

        DomainResp domain = domain(DOMAIN_ID, true);
        TermResp unassociated = term(301L, "无关联域术语", List.of(), List.of());
        TermResp foreignAssociated = term(302L, "其他模型域术语", List.of(999L), List.of(998L));
        when(domainService.getDomainListWithAdminAuth(user)).thenReturn(List.of(domain));
        when(modelService.getModelListWithAuth(user, DOMAIN_ID, AuthType.ADMIN))
                .thenReturn(List.of());
        when(termService.getTermSets(Set.of(DOMAIN_ID)))
                .thenReturn(Map.of(DOMAIN_ID, List.of(unassociated, foreignAssociated)));

        PreflightSnapshot snapshot = contextBuilder.preflight(request, user);
        String existingContext = snapshot.existingSummary().toString();

        assertTrue(snapshot.existingNames().contains("无关联域术语"));
        assertTrue(snapshot.existingNames().contains("其他模型域术语"));
        assertTrue(existingContext.contains("无关联域术语"));
        assertTrue(existingContext.contains("其他模型域术语"));
        verify(termService, times(1)).getTermSets(Set.of(DOMAIN_ID));
        verifyNoInteractions(dimensionService, metricService);
    }

    /** 仅有模型 ADMIN 权限时，术语必须与已批量加载的维度或指标 ID 有交集才能进入 LLM 上下文。 */
    @Test
    void shouldFilterTermsByAuthorizedModelObjectsForModelAdministrator() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setDomainId(DOMAIN_ID);
        stubPreflightMetadata();

        DomainResp modelOnlyDomain = domain(DOMAIN_ID, false);
        ModelResp model = model(21L, "authorized_model");
        DimensionResp dimension = dimension(101L, "authorized_dimension");
        MetricResp metric = metric(201L, "authorized_metric");
        TermResp dimensionTerm = term(401L, "授权维度术语", List.of(101L), List.of());
        TermResp metricTerm = term(402L, "授权指标术语", List.of(), List.of(201L));
        TermResp emptyTerm = term(403L, "空关联术语", List.of(), List.of());
        TermResp foreignDimensionTerm = term(404L, "其他维度术语", List.of(999L), List.of());
        TermResp foreignMetricTerm = term(405L, "其他指标术语", List.of(), List.of(998L));
        when(domainService.getDomainListWithAdminAuth(user)).thenReturn(List.of(modelOnlyDomain));
        when(modelService.getModelListWithAuth(user, DOMAIN_ID, AuthType.ADMIN))
                .thenReturn(List.of(model));
        when(dimensionService.getDimensions(argThat(this::isAuthorizedModelFilter)))
                .thenReturn(List.of(dimension));
        when(metricService.getMetrics(argThat(this::isAuthorizedModelFilter)))
                .thenReturn(List.of(metric));
        when(termService.getTermSets(Set.of(DOMAIN_ID)))
                .thenReturn(Map.of(DOMAIN_ID, List.of(dimensionTerm, metricTerm, emptyTerm,
                        foreignDimensionTerm, foreignMetricTerm)));

        PreflightSnapshot snapshot = contextBuilder.preflight(request, user);
        String existingContext = snapshot.existingSummary().toString();

        assertTrue(snapshot.existingNames().contains("授权维度术语"));
        assertTrue(snapshot.existingNames().contains("授权指标术语"));
        assertFalse(snapshot.existingNames().contains("空关联术语"));
        assertFalse(snapshot.existingNames().contains("其他维度术语"));
        assertFalse(snapshot.existingNames().contains("其他指标术语"));
        assertTrue(existingContext.contains("授权维度术语"));
        assertTrue(existingContext.contains("授权指标术语"));
        assertFalse(existingContext.contains("空关联术语"));
        assertFalse(existingContext.contains("其他维度术语"));
        assertFalse(existingContext.contains("其他指标术语"));
        verify(dimensionService, times(1)).getDimensions(argThat(this::isAuthorizedModelFilter));
        verify(metricService, times(1)).getMetrics(argThat(this::isAuthorizedModelFilter));
        verify(termService, times(1)).getTermSets(Set.of(DOMAIN_ID));
    }

    /** 既有资产进入 Prompt 时每类最多 20 个，业务目标相关对象应稳定排在无关对象之前。 */
    @Test
    void shouldRankExistingAssetsAndKeepFullConflictNameSet() throws Exception {
        ModelingDraftGenerateReq request = newGenerateRequest();
        request.setDomainId(DOMAIN_ID);
        stubPreflightMetadata();
        when(domainService.getDomainListWithAdminAuth(user))
                .thenReturn(List.of(domain(DOMAIN_ID, false)));
        List<ModelResp> models = new java.util.ArrayList<>();
        for (int index = 0; index < 24; index++) {
            models.add(model(100L + index, "无关模型_" + index));
        }
        models.add(model(999L, "销售订单分析模型"));
        when(modelService.getModelListWithAuth(user, DOMAIN_ID, AuthType.ADMIN)).thenReturn(models);
        when(dimensionService.getDimensions(any())).thenReturn(List.of());
        when(metricService.getMetrics(any())).thenReturn(List.of());
        when(termService.getTermSets(Set.of(DOMAIN_ID))).thenReturn(Map.of());

        PreflightSnapshot snapshot = contextBuilder.preflight(request, user);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rankedModels =
                (List<Map<String, Object>>) snapshot.existingSummary().get("models");
        assertEquals(20, rankedModels.size());
        assertEquals("销售订单分析模型", rankedModels.get(0).get("name"));
        assertTrue(snapshot.existingNames().contains("销售订单分析模型"));
        assertTrue(snapshot.existingNames().contains("无关模型_23"), "Prompt Top-K 裁剪不能缩小本地冲突校验名称集合");
    }

    /** 模型窗口不足以预留首次输出和修复输出时必须在调用 Gateway 前明确失败。 */
    @Test
    void shouldReserveModelContextForGenerateAndRepairOutputs() {
        ModelingDraftGenerateReq request = newGenerateRequest();
        PreflightSnapshot snapshot = new PreflightSnapshot(request, Map.of(TABLE, columns()),
                Set.of(), Map.of(), Map.of(), properties.getMaxOutputTokens() * 2 + 10);

        ModelingDraftException exception = assertThrows(ModelingDraftException.class,
                () -> contextBuilder.build(snapshot, user));

        assertEquals(ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("上下文"));
        verifyNoInteractions(gatewayService);
    }

    /** 确认维度和指标批量查询仅限定当前用户可管理的模型 ID。 */
    private boolean isAuthorizedModelFilter(MetaFilter filter) {
        return filter != null && List.of(21L).equals(filter.getModelIds());
    }

    /** 准备数据源可访问且支持 JSON mode 的 Gateway 能力。 */
    private void stubAccessibleJsonModel() {
        LlmModelCapabilityDO capability = new LlmModelCapabilityDO();
        capability.setChatModelId(3);
        capability.setEnabled(true);
        capability.setSupportJsonMode(true);
        when(databaseService.getDatabase(DATA_SOURCE_ID, user))
                .thenReturn(DatabaseResp.builder().id(DATA_SOURCE_ID).build());
        when(gatewayService.listCapabilities(user)).thenReturn(List.of(capability));
    }

    /** 准备进入既有语义上下文分支前所需的数据源和真实字段元数据。 */
    private void stubPreflightMetadata() throws SQLException {
        stubAccessibleJsonModel();
        when(databaseService.getTables(DATA_SOURCE_ID, "catalog", "warehouse"))
                .thenReturn(List.of(TABLE));
        when(databaseService.getDbColumns(any())).thenReturn(Map.of(TABLE, columns()));
    }

    /** 构造带有明确域管理标记的主题域响应。 */
    private DomainResp domain(Long id, boolean hasEditPermission) {
        DomainResp domain = new DomainResp();
        domain.setId(id);
        domain.setName("sales_domain");
        domain.setBizName("销售主题域");
        domain.setHasEditPermission(hasEditPermission);
        return domain;
    }

    /** 构造当前用户可管理的模型响应。 */
    private ModelResp model(Long id, String name) {
        ModelResp model = new ModelResp();
        model.setId(id);
        model.setName(name);
        model.setBizName("授权模型");
        model.setDomainId(DOMAIN_ID);
        return model;
    }

    /** 构造授权模型下的维度响应。 */
    private DimensionResp dimension(Long id, String name) {
        DimensionResp dimension = new DimensionResp();
        dimension.setId(id);
        dimension.setName(name);
        dimension.setBizName("授权维度");
        dimension.setModelId(21L);
        return dimension;
    }

    /** 构造授权模型下的指标响应。 */
    private MetricResp metric(Long id, String name) {
        MetricResp metric = new MetricResp();
        metric.setId(id);
        metric.setName(name);
        metric.setBizName("授权指标");
        metric.setModelId(21L);
        return metric;
    }

    /** 构造具有指定维度、指标关联的主题域术语。 */
    private TermResp term(Long id, String name, List<Long> dimensionIds, List<Long> metricIds) {
        TermResp term = new TermResp();
        term.setId(id);
        term.setDomainId(DOMAIN_ID);
        term.setName(name);
        term.setRelateDimensions(dimensionIds);
        term.setRelatedMetrics(metricIds);
        return term;
    }

    /** 构造无需主题域或 Gap 上下文的最小数据源建模请求。 */
    private ModelingDraftGenerateReq newGenerateRequest() {
        ModelingDraftGenerateReq request = new ModelingDraftGenerateReq();
        request.setSourceType(ModelingDraftConstants.SOURCE_DATA_SOURCE);
        request.setBusinessGoal("分析销售订单金额趋势");
        request.setDataSourceId(DATA_SOURCE_ID);
        request.setCatalogName("catalog");
        request.setDatabaseName("warehouse");
        request.setSelectedTables(List.of(TABLE));
        request.setChatModelId(3);
        return request;
    }

    /** 直接构造已完成预检的可信快照，以便精确测试生成 Prompt 分支。 */
    private PreflightSnapshot snapshot(ModelingDraftGenerateReq request) {
        return new PreflightSnapshot(request, Map.of(TABLE, columns()), Set.of(), Map.of(),
                Map.of());
    }

    /** 构造服务端读取到的最小可信表字段集合。 */
    private List<DBColumn> columns() {
        return List.of(new DBColumn("order_date", "DATE", "下单时间", null),
                new DBColumn("amount", "DECIMAL", "订单金额", null),
                new DBColumn("password", "VARCHAR", "凭据字段", null));
    }
}
