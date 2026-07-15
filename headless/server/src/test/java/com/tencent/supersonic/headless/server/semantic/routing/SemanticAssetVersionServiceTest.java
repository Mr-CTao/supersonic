package com.tencent.supersonic.headless.server.semantic.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.ModelDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ModelDOMapper;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 语义资产稳定版本回归测试。
 *
 * <p>验证子对象内容变化必然改变版本，同时批量查询返回顺序不会造成无意义版本漂移。</p>
 */
class SemanticAssetVersionServiceTest {

    /** 维度内容变化必须改变模型版本，即使模型主记录没有更新时间变化。 */
    @Test
    void shouldChangeVersionWhenDimensionChanges() {
        SemanticAssetVersionService service = service();
        ModelDO model = model();
        DimensionResp dimension = dimension(11L, "material_code");
        Long baseline = service.versionOf(model, List.of(dimension), List.of());

        dimension.setExpr("material_no");
        Long changed = service.versionOf(model, List.of(dimension), List.of());

        assertNotEquals(baseline, changed);
    }

    /** 相同语义对象的返回顺序不同仍应得到相同版本。 */
    @Test
    void shouldIgnoreBatchResultOrder() {
        SemanticAssetVersionService service = service();
        ModelDO model = model();
        DimensionResp first = dimension(11L, "material_code");
        DimensionResp second = dimension(12L, "batch_no");

        assertEquals(service.versionOf(model, List.of(first, second), List.of()),
                service.versionOf(model, List.of(second, first), List.of()));
    }

    /** 创建只使用纯版本计算分支的服务。 */
    private SemanticAssetVersionService service() {
        return new SemanticAssetVersionService(mock(ModelDOMapper.class),
                mock(DimensionService.class), mock(MetricService.class), new ObjectMapper());
    }

    /** 构造固定模型主记录。 */
    private ModelDO model() {
        ModelDO model = new ModelDO();
        model.setId(7L);
        model.setName("inventory_summary");
        return model;
    }

    /** 构造一个可变更表达式的维度。 */
    private DimensionResp dimension(Long id, String expression) {
        DimensionResp dimension = new DimensionResp();
        dimension.setId(id);
        dimension.setModelId(7L);
        dimension.setName("物料编码");
        dimension.setExpr(expression);
        return dimension;
    }
}
