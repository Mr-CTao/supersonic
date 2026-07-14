package com.tencent.supersonic.headless.server.semantic.modeling.release;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.enums.FieldType;
import com.tencent.supersonic.headless.api.pojo.request.MetricReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricFilterDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TermService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 正式语义管理 API 适配器的请求转换与安全边界测试。
 *
 * <p>
 * 职责说明：验证模型字段从服务端数据源重新读取、AI 归属标记随请求传入现有管理服务， 以及指标过滤条件只接受结构化白名单并正确转义字面量。测试通过服务 mock 明确保证适配器 不绕过现有
 * API 直接访问正式元数据 Mapper。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class FormalSemanticAssetPublisherTest {

    private static final Long RELEASE_ID = 301L;
    private static final Long DRAFT_ID = 201L;
    private static final Long MODEL_ID = 401L;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private ModelService modelService;

    @Mock
    private DimensionService dimensionService;

    @Mock
    private MetricService metricService;

    @Mock
    private TermService termService;

    private final User admin = User.getDefaultUser();
    private FormalSemanticAssetPublisher publisher;

    /** 创建只依赖既有语义管理服务 mock 的适配器。 */
    @BeforeEach
    void setUp() {
        publisher = new FormalSemanticAssetPublisher(databaseService, modelService,
                dimensionService, metricService, termService);
    }

    /** 模型发布必须重新读取服务端字段，并把发布归属写入现有模型 API 的 ext。 */
    @Test
    void shouldCreateModelThroughExistingApiWithServerMetadataAndOwnership() throws Exception {
        SemanticModelingDraftDO draft = draft();
        ModelDraft model = model();
        ModelResp created = ModelResp.builder().build();
        created.setId(MODEL_ID);
        when(databaseService.getDatabase(7L, admin))
                .thenReturn(DatabaseResp.builder().id(7L).type("mysql").build());
        when(databaseService.getColumns(7L, "catalog_a", "warehouse", "orders")).thenReturn(
                List.of(new DBColumn("order_id", "BIGINT", "订单 ID", FieldType.categorical)));
        when(modelService.createModel(any(ModelReq.class), eq(admin))).thenReturn(created);

        ModelResp actual = publisher.createModel(RELEASE_ID, draft, model, admin);

        assertEquals(MODEL_ID, actual.getId());
        ArgumentCaptor<ModelReq> captor = ArgumentCaptor.forClass(ModelReq.class);
        verify(modelService).createModel(captor.capture(), eq(admin));
        ModelReq request = captor.getValue();
        assertEquals("catalog_a.warehouse.orders", request.getModelDetail().getTableQuery());
        assertEquals("mysql", request.getModelDetail().getDbType());
        assertEquals(List.of("order_id"), request.getModelDetail().getFields().stream()
                .map(field -> field.getFieldName()).toList());
        assertEquals(RELEASE_ID, request.getExt().get("aiSemanticReleaseId"));
        assertEquals(DRAFT_ID, request.getExt().get("aiSemanticDraftId"));
        assertEquals("model:orders", request.getExt().get("aiSemanticObjectKey"));
    }

    /** 指标过滤值必须按 SQL 字面量转义，不能把单引号后的内容解释为 SQL。 */
    @Test
    void shouldEscapeStructuredMetricFilterValuesBeforeCallingMetricApi() throws Exception {
        MetricDraft metric = metric("region", "EQ", List.of("华东' OR '1'='1"));
        MetricResp created = new MetricResp();
        created.setId(501L);
        when(metricService.createMetric(any(MetricReq.class), eq(admin))).thenReturn(created);

        publisher.createMetric(RELEASE_ID, draft(), model(), metric, MODEL_ID, admin);

        ArgumentCaptor<MetricReq> captor = ArgumentCaptor.forClass(MetricReq.class);
        verify(metricService).createMetric(captor.capture(), eq(admin));
        assertEquals("region = '华东'' OR ''1''=''1'",
                captor.getValue().getMetricDefineByFieldParams().getFilterSql());
        assertTrue(captor.getValue().getExt().containsKey("aiSemanticReleaseId"));
    }

    /** 非法过滤字段必须在调用正式指标 API 前被拒绝。 */
    @Test
    void shouldRejectUnsafeFilterFieldBeforeCallingMetricApi() {
        MetricDraft metric = metric("region) OR 1=1 --", "EQ", List.of("华东"));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> publisher
                        .createMetric(RELEASE_ID, draft(), model(), metric, MODEL_ID, admin));

        assertEquals("指标过滤字段不合法", exception.getMessage());
    }

    /** 构造绑定主题域、数据源和表命名空间的草稿主记录。 */
    private SemanticModelingDraftDO draft() {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(DRAFT_ID);
        draft.setDomainId(9L);
        draft.setDataSourceId(7L);
        draft.setCatalogName("catalog_a");
        draft.setDatabaseName("warehouse");
        return draft;
    }

    /** 构造最小单表模型草稿。 */
    private ModelDraft model() {
        ModelDraft model = new ModelDraft();
        model.setKey("model:orders");
        model.setName("订单模型");
        model.setBizName("orders_model");
        model.setBaseTable("orders");
        return model;
    }

    /** 构造带一个结构化过滤条件的字段聚合指标。 */
    private MetricDraft metric(String field, String operator, List<String> values) {
        MetricFilterDraft filter = new MetricFilterDraft();
        filter.setField(field);
        filter.setOperator(operator);
        filter.setValues(values);
        MetricDraft metric = new MetricDraft();
        metric.setKey("metric:order_count");
        metric.setName("订单数");
        metric.setBizName("order_count");
        metric.setField("order_id");
        metric.setAggregation("COUNT");
        metric.setFilters(List.of(filter));
        return metric;
    }
}
