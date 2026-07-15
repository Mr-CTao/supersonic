package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.server.semantic.modeling.DraftSemanticSchemaFactory.DraftModelSemanticSchema;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DerivedFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.DimensionDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 隔离草稿语义 Schema 工厂测试。
 *
 * <p>
 * 职责说明：验证结构化 DATE_DIFF 只能转换成固定方言模板，且 Schema 的物理字段依赖仍指向
 * 服务端确认的起止列，不会把派生输出名称伪装成数据库列。测试只构造内存 DTO，不连接或执行 SQL。
 * </p>
 */
class DraftSemanticSchemaFactoryTest {

    private final DraftSemanticSchemaFactory factory = new DraftSemanticSchemaFactory();

    /** H2/MySQL 兼容模板应引用起始日期列和当前时间，不得引用派生输出名。 */
    @Test
    void shouldBuildSafeTimestampDiffExpressionForCurrentDate() {
        ModelDraft model = modelWithDerivedDimension("CURRENT_DATE", null, "DAY");

        DraftModelSemanticSchema schema = factory.build(model, List.of(),
                List.of(column("inbound_date", "DATE")),
                DatabaseResp.builder().id(1L).type("H2").version("2").build(), 0, 20);

        assertThat(schema.semanticSchemaResp().getDimensions()).singleElement()
                .satisfies(dimension -> {
                    assertThat(dimension.getExpr())
                            .isEqualTo("TIMESTAMPDIFF(DAY, inbound_date, CURRENT_TIMESTAMP)");
                    assertThat(dimension.getFields()).containsExactly("inbound_date")
                            .doesNotContain("sluggish_duration_days");
                });
        assertThat(schema.semanticSchemaResp().getModelResps().get(0).getModelDetail()
                .getDimensions()).singleElement()
                        .extracting(com.tencent.supersonic.headless.api.pojo.Dimension::getExpr)
                        .isEqualTo("TIMESTAMPDIFF(DAY, inbound_date, CURRENT_TIMESTAMP)");
    }

    /** PostgreSQL FIELD 结束类型应使用固定 epoch 换算，并声明两个真实物理字段依赖。 */
    @Test
    void shouldBuildSafePostgresExpressionForPhysicalEndField() {
        ModelDraft model = modelWithDerivedDimension("FIELD", "last_movement_at", "HOUR");

        DraftModelSemanticSchema schema = factory.build(model, List.of(),
                List.of(column("inbound_date", "TIMESTAMP"),
                        column("last_movement_at", "TIMESTAMP")),
                DatabaseResp.builder().id(2L).type("POSTGRESQL").version("16").build(), 0, 20);

        assertThat(schema.semanticSchemaResp().getDimensions()).singleElement()
                .satisfies(dimension -> {
                    assertThat(dimension.getExpr()).isEqualTo(
                            "FLOOR(EXTRACT(EPOCH FROM (last_movement_at - inbound_date)) / 3600)");
                    assertThat(dimension.getFields())
                            .containsExactlyInAnyOrder("inbound_date", "last_movement_at");
                });
    }

    /** 构造通用 DATE_DIFF 维度，不绑定任何生产模型或字段 ID。 */
    private ModelDraft modelWithDerivedDimension(String endType, String endField, String unit) {
        DerivedFieldDraft derivation = new DerivedFieldDraft();
        derivation.setOperator("DATE_DIFF");
        derivation.setStartField("inbound_date");
        derivation.setEndType(endType);
        derivation.setEndField(endField);
        derivation.setUnit(unit);

        DimensionDraft dimension = new DimensionDraft();
        dimension.setKey("sluggishDuration");
        dimension.setName("呆滞时长");
        dimension.setBizName("sluggish_duration_days");
        dimension.setField("sluggish_duration_days");
        dimension.setSemanticType("NUMBER");
        dimension.setDerivation(derivation);

        ModelDraft model = new ModelDraft();
        model.setKey("stockModel");
        model.setName("库存模型");
        model.setBizName("stock_model");
        model.setBaseTable("stock_summary");
        model.setDimensions(new ArrayList<>(List.of(dimension)));
        model.setMetrics(new ArrayList<>());
        model.setSensitiveFields(new ArrayList<>());
        model.setSampleQuestions(new ArrayList<>());
        return model;
    }

    /** 创建服务端字段元数据快照。 */
    private DBColumn column(String name, String type) {
        return new DBColumn(name, type, name, null);
    }
}
