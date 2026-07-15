package com.tencent.supersonic.headless.server.service.impl;

import com.tencent.supersonic.headless.api.pojo.request.DataSetFilterReq;
import com.tencent.supersonic.headless.api.pojo.request.SchemaFilterReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetSchemaResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticSchemaResp;
import com.tencent.supersonic.headless.server.service.DataSetService;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.ModelRelaService;
import com.tencent.supersonic.headless.server.service.ModelService;
import com.tencent.supersonic.headless.server.service.TermService;
import com.tencent.supersonic.headless.server.utils.StatUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 验证模型事件只失效包含目标模型的 Schema 缓存项。
 */
class SchemaServiceImplCacheTest {

    @Test
    void shouldInvalidateOnlyCacheEntriesContainingTargetModel() {
        SchemaServiceImpl service = new SchemaServiceImpl(mock(ModelService.class),
                mock(DimensionService.class), mock(MetricService.class), mock(DomainService.class),
                mock(DataSetService.class), mock(ModelRelaService.class), mock(StatUtils.class),
                mock(TermService.class), mock(DatabaseService.class));
        DataSetFilterReq affectedDataSetKey = new DataSetFilterReq(11L);
        DataSetFilterReq unaffectedDataSetKey = new DataSetFilterReq(12L);
        service.dataSetSchemaCache.put(affectedDataSetKey,
                Collections.singletonList(schemaWithModel(7L)));
        service.dataSetSchemaCache.put(unaffectedDataSetKey,
                Collections.singletonList(schemaWithModel(8L)));

        SchemaFilterReq affectedSemanticKey = new SchemaFilterReq();
        SchemaFilterReq unaffectedSemanticKey = new SchemaFilterReq();
        affectedSemanticKey.setDataSetId(11L);
        unaffectedSemanticKey.setDataSetId(12L);
        SemanticSchemaResp affectedSemantic = new SemanticSchemaResp();
        affectedSemantic.setModelIds(Collections.singletonList(7L));
        SemanticSchemaResp unaffectedSemantic = new SemanticSchemaResp();
        unaffectedSemantic.setModelIds(Collections.singletonList(8L));
        service.semanticSchemaCache.put(affectedSemanticKey, affectedSemantic);
        service.semanticSchemaCache.put(unaffectedSemanticKey, unaffectedSemantic);

        int invalidated = service.invalidateModelSchemas(Collections.singletonList(7L));

        assertEquals(2, invalidated);
        assertNull(service.dataSetSchemaCache.getIfPresent(affectedDataSetKey));
        assertNotNull(service.dataSetSchemaCache.getIfPresent(unaffectedDataSetKey));
        assertNull(service.semanticSchemaCache.getIfPresent(affectedSemanticKey));
        assertNotNull(service.semanticSchemaCache.getIfPresent(unaffectedSemanticKey));
    }

    /** 创建只包含一个模型的缓存响应。 */
    private DataSetSchemaResp schemaWithModel(Long modelId) {
        ModelResp model = new ModelResp();
        model.setId(modelId);
        DataSetSchemaResp schema = new DataSetSchemaResp();
        schema.setModelResps(Arrays.asList(model));
        return schema;
    }
}
