package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftValidator.ValidatedDraft;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 语义建模草稿结构与语义校验器单元测试。
 *
 * <p>
 * 职责说明：覆盖合法草稿规范化、未知表/字段、任意指标表达式、错误术语引用以及跨表对象降级。 测试同时以无交互断言守住阶段 3 边界：校验过程只处理隔离 JSON
 * 草稿，不允许调用正式语义资产服务、 发布事件或触发其下游知识刷新。并发说明：每个测试创建独立校验器与不可变元数据，不共享可变状态。
 * </p>
 */
class ModelingDraftValidatorTest {

    private static final Set<String> FORMAL_ASSET_WRITE_DEPENDENCIES =
            Set.of("com.tencent.supersonic.headless.server.service.ModelService",
                    "com.tencent.supersonic.headless.server.service.DimensionService",
                    "com.tencent.supersonic.headless.server.service.MetricService",
                    "com.tencent.supersonic.headless.server.service.TermService",
                    "org.springframework.context.ApplicationEventPublisher");

    private final ModelingDraftValidator validator = new ModelingDraftValidator(new ObjectMapper());

    /** 验证合法草稿会补齐规范指标表达式，并保留本地术语引用。 */
    @Test
    void shouldValidateAndNormalizeLegalDraft() {
        ValidatedDraft result =
                validator.validateAndNormalize(validDraftJson(), columnsByTable(), Set.of());

        assertThat(result.payload().getModels()).hasSize(1);
        assertThat(result.payload().getModels().get(0).getMetrics()).singleElement()
                .extracting(ModelingDraftPayload.MetricDraft::getExpression)
                .isEqualTo("SUM(amount)");
        assertThat(result.payload().getTerms()).singleElement().satisfies(
                term -> assertThat(term.getTargets()).singleElement().satisfies(target -> {
                    assertThat(target.getType()).isEqualTo("METRIC");
                    assertThat(target.getObjectKey()).isEqualTo("orderAmount");
                }));
        assertThat(result.payload().getUncertainties()).isEmpty();
        assertThat(result.json()).contains("\"schemaVersion\":\"1.0\"").doesNotContain("SELECT ",
                "INSERT ", "UPDATE ", "DELETE ");
    }

    /** 内置最小示例必须持续通过同一 Schema 和语义校验，防止 Prompt 示例与契约漂移。 */
    @Test
    void shouldKeepBundledMinimalExampleValid() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("schema/semantic-modeling-draft-v1-example.json")) {
            assertThat(input).isNotNull();
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            ValidatedDraft result = validator.validateAndNormalize(json,
                    Map.of("selected_table", List.of()), Set.of());

            assertThat(result.payload().getSchemaVersion()).isEqualTo("1.0");
            assertThat(result.payload().getModels()).singleElement()
                    .extracting(ModelingDraftPayload.ModelDraft::getBaseTable)
                    .isEqualTo("selected_table");
        }
    }

    /** 结构预检必须一次聚合缺失和未知字段，避免修复轮只补一个 required 字段。 */
    @Test
    void shouldAggregateStructuralIssuesForWrongModelShape() {
        String wrongShape = """
                {
                  "model": {"objectKey":"orders"},
                  "exampleQuestions": []
                }
                """;

        assertThatThrownBy(
                () -> validator.validateAndNormalize(wrongShape, columnsByTable(), Set.of()))
                        .isInstanceOf(ModelingDraftException.class).satisfies(throwable -> {
                            ModelingDraftException exception = (ModelingDraftException) throwable;
                            assertThat(exception.getIssues()).hasSize(7);
                            assertThat(exception.getIssues())
                                    .extracting(ModelingValidationIssue::getCode)
                                    .contains("JSON_REQUIRED_MISSING", "JSON_UNKNOWN_PROPERTY");
                            assertThat(exception.getIssues())
                                    .extracting(ModelingValidationIssue::getPath)
                                    .contains("$.schemaVersion", "$.businessGoal", "$.models",
                                            "$.terms", "$.uncertainties", "$.model",
                                            "$.exampleQuestions");
                        });
    }

    /** 验证模型基表必须来自服务端确认的选表范围。 */
    @Test
    void shouldRejectUnknownBaseTable() {
        String json = validDraftJson().replace("\"baseTable\": \"sales_order\"",
                "\"baseTable\": \"missing_table\"");

        assertValidationIssue(json, "UNKNOWN_TABLE", "$.models[0].baseTable");
    }

    /** 验证维度字段必须存在于模型的单一基表。 */
    @Test
    void shouldRejectUnknownField() {
        String json =
                validDraftJson().replace("\"field\": \"region\"", "\"field\": \"missing_region\"");

        assertValidationIssue(json, "UNKNOWN_FIELD", "$.models[0].dimensions[0].field");
    }

    /** 验证指标不能携带规范单列聚合之外的任意 SQL 表达式。 */
    @Test
    void shouldRejectArbitraryMetricExpression() {
        String json = validDraftJson().replace("\"expression\": null",
                "\"expression\": \"SUM(amount) / COUNT(*)\"");

        assertValidationIssue(json, "ILLEGAL_METRIC_EXPRESSION",
                "$.models[0].metrics[0].expression");
    }

    /** 验证术语只能引用同一草稿内存在的维度或指标 key。 */
    @Test
    void shouldRejectUnknownTermReference() {
        String json = validDraftJson().replace("\"objectKey\": \"orderAmount\"",
                "\"objectKey\": \"missingMetric\"");

        assertValidationIssue(json, "UNKNOWN_TERM_REFERENCE", "$.terms[0].targets[0].objectKey");
    }

    /** 验证术语引用已存在对象时，声明类型仍必须与对象的实际维度/指标类型一致。 */
    @Test
    void shouldRejectMismatchedTermTargetTypeWithUnprocessableEntity() {
        String json =
                validDraftJson().replace("{\"type\": \"METRIC\", \"objectKey\": \"orderAmount\"}",
                        "{\"type\": \"DIMENSION\", \"objectKey\": \"orderAmount\"}");

        assertThatThrownBy(() -> validator.validateAndNormalize(json, columnsByTable(), Set.of()))
                .isInstanceOf(ModelingDraftException.class).satisfies(throwable -> {
                    ModelingDraftException exception = (ModelingDraftException) throwable;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getErrorCode()).isEqualTo("MODEL_OUTPUT_INVALID");
                    assertThat(exception.getIssues()).anySatisfy(issue -> {
                        assertThat(issue.getCode()).isEqualTo("TERM_TARGET_TYPE_MISMATCH");
                        assertThat(issue.getPath()).isEqualTo("$.terms[0].targets[0].type");
                    });
                });
    }

    /** 同一对象的 name、bizName 和 alias 重复时，每个归一化名称最多生成一条冲突不确定项。 */
    @Test
    void shouldDeduplicateAliasConflictWithinSameObject() {
        String json = validDraftJson().replace("\"name\": \"订单金额\"", "\"name\": \"冲突名\"")
                .replace("\"bizName\": \"order_amount_metric\"",
                        "\"bizName\": \"冲突名\"")
                .replace("\"aliases\": [\"营收金额\"]", "\"aliases\": [\"冲突名\"]");

        ValidatedDraft result =
                validator.validateAndNormalize(json, columnsByTable(), Set.of("冲突名"));

        assertThat(result.payload().getUncertainties())
                .filteredOn(uncertainty -> "ALIAS_CONFLICT".equals(uncertainty.getCategory())
                        && "orderAmount".equals(uncertainty.getObjectKey()))
                .singleElement()
                .extracting(ModelingDraftPayload.UncertaintyDraft::getReason)
                .isEqualTo("名称或别名与其他草稿对象或现有资产冲突：冲突名");
    }

    /** 验证明确的跨表对象不会进入可保存模型，而会转成待管理员确认的不确定项。 */
    @Test
    void shouldMoveCrossTableObjectToUncertainties() {
        String json = validDraftJson().replace("\"field\": \"region\"",
                "\"field\": \"customer.customer_level\"");

        ValidatedDraft result = validator.validateAndNormalize(json, columnsByTable(), Set.of());

        assertThat(result.payload().getModels().get(0).getDimensions()).isEmpty();
        assertThat(result.payload().getUncertainties()).singleElement().satisfies(uncertainty -> {
            assertThat(uncertainty.getCategory()).isEqualTo("CROSS_TABLE");
            assertThat(uncertainty.getSeverity()).isEqualTo("BLOCKING");
            assertThat(uncertainty.getModelKey()).isEqualTo("salesModel");
            assertThat(uncertainty.getObjectKey()).isEqualTo("orderRegion");
            assertThat(uncertainty.getField()).isEqualTo("customer.customer_level");
        });
        // 字段仍需保留在 uncertainty 里供管理员判断，只断言它不再作为模型维度存在。
        assertThat(result.json()).contains("\"dimensions\":[]", "\"category\":\"CROSS_TABLE\"");
    }

    /** 跨表指标移除后，其关联术语目标也应移除并降级，不得误报为真正未知引用。 */
    @Test
    void shouldDowngradeTermTargetWhenReferencedMetricMovesToUncertainties() {
        String json = validDraftJson().replace("\"field\": \"amount\"",
                "\"field\": \"customer.customer_level\"");

        ValidatedDraft result = validator.validateAndNormalize(json, columnsByTable(), Set.of());

        assertThat(result.payload().getModels().get(0).getMetrics()).isEmpty();
        assertThat(result.payload().getTerms()).singleElement().satisfies(term -> {
            assertThat(term.getKey()).isEqualTo("revenueTerm");
            assertThat(term.getTargets()).isEmpty();
        });
        assertThat(result.payload().getUncertainties()).hasSize(2).allSatisfy(
                uncertainty -> assertThat(uncertainty.getCategory()).isEqualTo("CROSS_TABLE"));
        assertThat(result.payload().getUncertainties()).anySatisfy(uncertainty -> {
            assertThat(uncertainty.getModelKey()).isEqualTo("salesModel");
            assertThat(uncertainty.getObjectKey()).isEqualTo("revenueTerm");
            assertThat(uncertainty.getField()).isEqualTo("customer.customer_level");
            assertThat(uncertainty.getReason()).contains("术语目标").contains("已移除");
        });
        assertThat(result.json()).contains("\"metrics\":[]", "\"targets\":[]")
                .doesNotContain("UNKNOWN_TERM_REFERENCE");
    }

    /** 跨表维度与指标使用同一降级规则，术语引用维度时也必须移除无效 target。 */
    @Test
    void shouldDowngradeTermTargetWhenReferencedDimensionMovesToUncertainties() {
        String json = validDraftJson()
                .replace("\"field\": \"region\"", "\"field\": \"customer.customer_level\"")
                .replace("{\"type\": \"METRIC\", \"objectKey\": \"orderAmount\"}",
                        "{\"type\": \"DIMENSION\", \"objectKey\": \"orderRegion\"}");

        ValidatedDraft result = validator.validateAndNormalize(json, columnsByTable(), Set.of());

        assertThat(result.payload().getModels().get(0).getDimensions()).isEmpty();
        assertThat(result.payload().getTerms()).singleElement()
                .satisfies(term -> assertThat(term.getTargets()).isEmpty());
        assertThat(result.payload().getUncertainties()).anySatisfy(uncertainty -> {
            assertThat(uncertainty.getCategory()).isEqualTo("CROSS_TABLE");
            assertThat(uncertainty.getObjectKey()).isEqualTo("revenueTerm");
            assertThat(uncertainty.getField()).isEqualTo("customer.customer_level");
        });
    }

    /** 状态字段即使已有别名也缺少枚举证据，仍必须进入待确认项。 */
    @Test
    void shouldFlagStatusFieldEvenWhenDimensionHasAliases() {
        String json = validDraftJson().replace("\"field\": \"region\"", "\"field\": \"status\"");

        ValidatedDraft result = validator.validateAndNormalize(json, columnsByTable(), Set.of());

        assertThat(result.payload().getUncertainties()).anySatisfy(uncertainty -> {
            assertThat(uncertainty.getCategory()).isEqualTo("UNKNOWN_STATUS");
            assertThat(uncertainty.getObjectKey()).isEqualTo("orderRegion");
            assertThat(uncertainty.getField()).isEqualTo("status");
        });
    }

    /** 验证校验器不持有正式资产写服务或事件总线，因而无法产生发布与知识刷新副作用。 */
    @Test
    void shouldRemainDetachedFromFormalAssetWritePath() {
        Stream<Class<?>> fieldTypes =
                Arrays.stream(ModelingDraftValidator.class.getDeclaredFields()).map(Field::getType);
        Stream<Class<?>> constructorTypes =
                Arrays.stream(ModelingDraftValidator.class.getDeclaredConstructors())
                        .flatMap(constructor -> constructorParameterTypes(constructor).stream());
        List<String> dependencyNames =
                Stream.concat(fieldTypes, constructorTypes).map(Class::getName).toList();

        assertThat(dependencyNames).doesNotContainAnyElementsOf(FORMAL_ASSET_WRITE_DEPENDENCIES);
    }

    /** 断言校验异常包含稳定错误码与精确 JSON 路径。 */
    private void assertValidationIssue(String json, String expectedCode, String expectedPath) {
        assertThatThrownBy(() -> validator.validateAndNormalize(json, columnsByTable(), Set.of()))
                .isInstanceOf(ModelingDraftException.class).satisfies(throwable -> {
                    ModelingDraftException exception = (ModelingDraftException) throwable;
                    assertThat(exception.getErrorCode()).isEqualTo("MODEL_OUTPUT_INVALID");
                    assertThat(exception.getIssues()).anySatisfy(issue -> {
                        assertThat(issue.getCode()).isEqualTo(expectedCode);
                        assertThat(issue.getPath()).isEqualTo(expectedPath);
                    });
                });
    }

    /** 提取构造参数类型，单独封装以保持架构边界断言易读。 */
    private List<Class<?>> constructorParameterTypes(Constructor<?> constructor) {
        return Arrays.asList(constructor.getParameterTypes());
    }

    /** 构造由服务端读取的两张选表字段元数据，第二张表仅用于跨表边界测试。 */
    private Map<String, List<DBColumn>> columnsByTable() {
        return Map.of("sales_order",
                List.of(column("order_date", "DATE"), column("region", "VARCHAR"),
                        column("status", "VARCHAR"), column("amount", "DECIMAL"),
                        column("customer_phone", "VARCHAR")),
                "customer", List.of(column("customer_level", "VARCHAR")));
    }

    /** 创建最小物理字段元数据；字段类型分类与本测试的存在性校验无关。 */
    private DBColumn column(String name, String dataType) {
        return new DBColumn(name, dataType, name, null);
    }

    /** 构造包含模型、维度、指标、敏感字段、示例问法与本地术语引用的合法草稿。 */
    private String validDraftJson() {
        return """
                {
                  "schemaVersion": "1.0",
                  "businessGoal": "分析销售订单的区域与金额趋势",
                  "targetDomain": null,
                  "models": [
                    {
                      "key": "salesModel",
                      "name": "销售订单模型",
                      "bizName": "sales_order_model",
                      "description": "面向销售分析的单表草稿",
                      "baseTable": "sales_order",
                      "primaryTimeField": "order_date",
                      "dimensions": [
                        {
                          "key": "orderRegion",
                          "name": "订单区域",
                          "bizName": "order_region",
                          "field": "region",
                          "description": "订单所属区域",
                          "semanticType": "CATEGORY",
                          "aliases": ["销售区域"]
                        }
                      ],
                      "metrics": [
                        {
                          "key": "orderAmount",
                          "name": "订单金额",
                          "bizName": "order_amount_metric",
                          "field": "amount",
                          "aggregation": "SUM",
                          "expression": null,
                          "description": "订单金额合计",
                          "aliases": ["营收金额"],
                          "filters": []
                        }
                      ],
                      "sensitiveFields": [
                        {
                          "field": "customer_phone",
                          "level": "HIGH",
                          "maskingStrategy": "MASK",
                          "reason": "客户手机号属于个人敏感信息"
                        }
                      ],
                      "sampleQuestions": ["各区域订单金额是多少？"]
                    }
                  ],
                  "terms": [
                    {
                      "key": "revenueTerm",
                      "name": "营收",
                      "description": "销售订单金额合计",
                      "aliases": ["销售额"],
                      "targets": [
                        {"type": "METRIC", "objectKey": "orderAmount"}
                      ]
                    }
                  ],
                  "uncertainties": []
                }
                """;
    }
}
