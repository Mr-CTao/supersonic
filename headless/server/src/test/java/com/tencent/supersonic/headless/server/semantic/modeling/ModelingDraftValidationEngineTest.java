package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.DateModeUtils;
import com.tencent.supersonic.common.util.SqlFilterUtils;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.chat.query.rule.metric.MetricModelQuery;
import com.tencent.supersonic.headless.core.translator.SemanticTranslator;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.SensitiveFieldDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.UncertaintyDraft;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段 4 草稿验证引擎单元测试。
 *
 * <p>
 * 职责说明：验证敏感字段漏标、样例问法真实语义链路、SQL AST 只读检查和未处理不确定项的门禁组合。 测试只构造内存草稿和字段元数据，不连接数据源、不执行 SQL，也不写正式语义资产。
 * </p>
 */
class ModelingDraftValidationEngineTest {

    private ModelingDraftValidationEngine engine;
    private User user;
    private ApplicationContext previousContext;
    private GenericApplicationContext testContext;

    /** 初始化无外部依赖的共享敏感规则和 SQL AST 检查器。 */
    @BeforeEach
    void setUp() throws Exception {
        previousContext = ContextUtils.getContext();
        testContext = new GenericApplicationContext();
        testContext.registerBean(SqlFilterUtils.class, () -> mock(SqlFilterUtils.class));
        testContext.registerBean(DateModeUtils.class, () -> mock(DateModeUtils.class));
        testContext.refresh();
        new ContextUtils().setApplicationContext(testContext);
        SemanticModelingSensitivityClassifier classifier =
                new SemanticModelingSensitivityClassifier();
        DatabaseService databaseService = mock(DatabaseService.class);
        when(databaseService.getDatabase(eq(1L), any(User.class)))
                .thenReturn(DatabaseResp.builder().id(1L).type("H2").version("2").build());
        SemanticTranslator translator = mock(SemanticTranslator.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            com.tencent.supersonic.headless.core.pojo.QueryStatement statement =
                    invocation.getArgument(0);
            statement.setSql("SELECT SUM(amount) FROM orders WHERE amount IS NOT NULL LIMIT "
                    + statement.getLimit());
            return null;
        }).when(translator).translate(any());
        // RuleSqlParser 通过现有 QueryManager 注册表发现查询类型；单元测试显式注册最小指标查询。
        new MetricModelQuery();
        DraftSemanticValidationService semanticValidationService =
                new DraftSemanticValidationService(databaseService,
                        new DraftSemanticSchemaFactory(), new DraftSemanticSchemaMapper(),
                        translator, new ModelingSqlReadOnlyChecker(), classifier,
                        new SemanticModelingProperties());
        engine = new ModelingDraftValidationEngine(classifier, semanticValidationService);
        user = User.getDefaultUser();
    }

    /** 恢复全局 Spring 上下文，避免单元测试污染其他 parser 测试。 */
    @AfterEach
    void tearDown() {
        new ContextUtils().setApplicationContext(previousContext);
        testContext.close();
    }

    /** 敏感字段已声明且样例问法可解析时应生成可提交的真实语义只读预览。 */
    @Test
    void shouldPassDeclaredSensitiveFieldAndMatchedQuestion() {
        ModelingDraftPayload payload = payload(true, List.of("订单金额是多少"), List.of());

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 20);

        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(outcome.getBlockingItems()).isEmpty();
        assertThat(outcome.getSampleQuestionResults()).singleElement().satisfies(result -> {
            assertThat(result.getMatched()).isTrue();
            assertThat(result.getValidationMode()).isEqualTo("DRAFT_SEMANTIC_PIPELINE");
            assertThat(result.getReadOnly()).isTrue();
            assertThat(result.getSqlPreview()).startsWith("SELECT ").contains(" LIMIT 20");
        });
        assertThat(outcome.getSqlSafetyResult().getStatus()).isEqualTo("PASSED");
    }

    /** 高置信敏感字段漏标且没有样例问法时必须同时产生敏感与样例阻塞项。 */
    @Test
    void shouldBlockUndeclaredSensitiveFieldAndMissingQuestions() {
        ModelingDraftPayload payload = payload(false, List.of(), List.of());

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 10);

        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
        assertThat(outcome.getBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("SENSITIVE_FIELD_UNDECLARED", "SAMPLE_QUESTION_MISSING");
        assertThat(outcome.getSensitiveFieldResult().getStatus()).isEqualTo("FAILED");
        assertThat(outcome.getSqlSafetyResult().getStatus()).isEqualTo("FAILED");
    }

    /** 名称冲突虽展示为警告，但对应未处理不确定项仍必须阻断提交审批。 */
    @Test
    void shouldKeepConflictWarningAndBlockUnresolvedUncertainty() {
        UncertaintyDraft uncertainty = new UncertaintyDraft();
        uncertainty.setKey("uncertainty_1");
        uncertainty.setModelKey("orders_model");
        uncertainty.setCategory("ALIAS_CONFLICT");
        uncertainty.setSeverity("WARNING");
        uncertainty.setReason("订单金额与既有指标重名");
        ModelingDraftPayload payload = payload(true, List.of("订单金额是多少"), List.of(uncertainty));

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 20);

        assertThat(outcome.getConflictResult().getStatus()).isEqualTo("WARNING");
        assertThat(outcome.getWarningItems()).extracting(ModelingValidationFinding::getCode)
                .contains("NAME_OR_ALIAS_CONFLICT");
        assertThat(outcome.getBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("UNRESOLVED_UNCERTAINTY");
        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
    }

    /** 显式 HIGH/CRITICAL 声明是强证据，即使字段名未命中启发式规则也禁止 NONE。 */
    @Test
    void shouldBlockExplicitHighSensitivityFieldUsingNoneMasking() {
        ModelingDraftPayload payload = payload(true, List.of("订单金额是多少"), List.of());
        SensitiveFieldDraft bankAccount = new SensitiveFieldDraft();
        bankAccount.setField("bank_account");
        bankAccount.setLevel("CRITICAL");
        bankAccount.setMaskingStrategy("NONE");
        bankAccount.setReason("银行账号由管理员显式标记为关键敏感字段");
        payload.getModels().get(0).getSensitiveFields().add(bankAccount);

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 20);

        assertThat(outcome.getSensitiveFieldResult().getStatus()).isEqualTo("FAILED");
        assertThat(outcome.getBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("SENSITIVE_FIELD_UNMASKED");
        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
    }

    /** 服务端元数据中的 bank_account 即使未显式标为 CRITICAL，也必须按高置信规则阻塞漏标。 */
    @Test
    void shouldBlockUndeclaredBankAccountFromMetadata() {
        ModelingDraftPayload payload = payload(true, List.of("订单金额是多少"), List.of());
        payload.getModels().get(0).getSensitiveFields()
                .removeIf(item -> "bank_account".equals(item.getField()));

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 20);

        assertThat(outcome.getSensitiveFieldResult().getStatus()).isEqualTo("FAILED");
        assertThat(outcome.getBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("SENSITIVE_FIELD_UNDECLARED");
        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
    }

    /** 十项必需检查全部完成且无业务 finding 时才允许 PASSED。 */
    @Test
    void shouldPassOnlyWhenAllRequiredChecksPass() {
        ModelingDraftValidationOutcome outcome = outcomeWithRequiredStatus(null, null);

        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(outcome.effectiveBlockingItems()).isEmpty();
    }

    /** 必需检查 WARNING 且无阻塞项时总体状态应为 WARNING。 */
    @Test
    void shouldKeepWarningRequiredCheckAsWarning() {
        ModelingDraftValidationOutcome outcome =
                outcomeWithRequiredStatus(ModelingValidationGate.CHECK_RETRIEVAL_POLLUTION,
                        ModelingDraftConstants.VALIDATION_WARNING);

        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_WARNING);
    }

    /** 必需检查 FAILED 必须直接阻断，即使原始 blockingItems 为空。 */
    @Test
    void shouldFailClosedWhenRequiredCheckFails() {
        ModelingDraftValidationOutcome outcome =
                outcomeWithRequiredStatus(ModelingValidationGate.CHECK_SQL_READ_ONLY,
                        ModelingDraftConstants.VALIDATION_FAILED);

        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
        assertThat(outcome.effectiveBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("REQUIRED_CHECK_FAILED");
    }

    /** 性能检查 NOT_RUN 不能得到 PASSED，并应生成可展示阻塞项。 */
    @Test
    void shouldFailClosedWhenPerformanceCheckIsNotRun() {
        ModelingDraftValidationOutcome outcome =
                outcomeWithRequiredStatus(ModelingValidationGate.CHECK_PERFORMANCE_RISK,
                        ModelingDraftConstants.VALIDATION_NOT_RUN);

        assertThat(outcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
        assertThat(outcome.effectiveBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("REQUIRED_CHECK_NOT_RUN");
    }

    /** null 检查对象和未知状态都必须按不完整报告 fail-closed。 */
    @Test
    void shouldFailClosedWhenRequiredCheckIsNullOrUnknown() {
        ModelingDraftValidationOutcome nullOutcome = outcomeWithNullRequiredCheck();
        ModelingDraftValidationOutcome unknownOutcome =
                outcomeWithRequiredStatus(ModelingValidationGate.CHECK_SAMPLE_QUESTION, "MYSTERY");

        assertThat(nullOutcome.resolveStatus()).isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
        assertThat(unknownOutcome.resolveStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
        assertThat(unknownOutcome.effectiveBlockingItems())
                .extracting(ModelingValidationFinding::getCode)
                .contains("REQUIRED_CHECK_STATUS_INVALID");
    }

    /** 明确高频词会生成不含原词的结构化阻塞项。 */
    @Test
    void shouldBlockExplicitRetrievalPollution() {
        ModelingDraftPayload payload = payload(true, List.of("订单金额是多少"), List.of());
        payload.getModels().get(0).setBizName("数据");

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 20);

        assertThat(outcome.getBlockingItems())
                .filteredOn(item -> "RETRIEVAL_HIGH_FREQUENCY_POLLUTION".equals(item.getCode()))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.getObjectType()).isEqualTo("MODEL");
                    assertThat(finding.getObjectKey()).isEqualTo("orders_model");
                    assertThat(finding.getMessage()).doesNotContain("数据");
                });
    }

    /** 边界通用别名只生成 warning，正常业务名称不应误报。 */
    @Test
    void shouldWarnForGenericAliasWithoutFlaggingNormalBusinessNames() {
        ModelingDraftPayload payload = payload(true, List.of("订单金额是多少"), List.of());
        payload.getModels().get(0).getMetrics().get(0)
                .setAliases(new ArrayList<>(List.of("订单总额", "状态")));

        ModelingDraftValidationOutcome outcome = engine.validate(payload, columns(), 1L, user, 20);

        assertThat(outcome.getWarningItems()).extracting(ModelingValidationFinding::getCode)
                .contains("RETRIEVAL_GENERIC_NAME_RISK");
        assertThat(outcome.getBlockingItems()).extracting(ModelingValidationFinding::getCode)
                .doesNotContain("RETRIEVAL_HIGH_FREQUENCY_POLLUTION");
    }

    /** 构造十项默认 PASSED、可覆盖一个状态的门禁结果。 */
    private ModelingDraftValidationOutcome outcomeWithRequiredStatus(String category,
            String status) {
        List<ModelingValidationCheckResult> checks =
                ModelingValidationGate.requiredCheckIds().stream()
                        .map(checkId -> ModelingValidationCheckResult.builder().category(checkId)
                                .status(checkId.equals(category) ? status
                                        : ModelingDraftConstants.VALIDATION_PASSED)
                                .summary("测试检查").checkedCount(1).passedCount(1).failedCount(0)
                                .mode("TEST").build())
                        .toList();
        return ModelingDraftValidationOutcome.builder().plannedObjects(List.of())
                .requiredCheckResults(checks).sampleQuestionResults(List.of())
                .blockingItems(List.of()).warningItems(List.of()).build();
    }

    /** 构造包含 null 必需检查对象的报告。 */
    private ModelingDraftValidationOutcome outcomeWithNullRequiredCheck() {
        List<ModelingValidationCheckResult> checks =
                new ArrayList<>(outcomeWithRequiredStatus(null, null).getRequiredCheckResults());
        checks.set(0, null);
        return ModelingDraftValidationOutcome.builder().plannedObjects(List.of())
                .requiredCheckResults(checks).sampleQuestionResults(List.of())
                .blockingItems(List.of()).warningItems(List.of()).build();
    }

    /** 构造最小阶段 3 已校验草稿。 */
    private ModelingDraftPayload payload(boolean declareSensitive, List<String> questions,
            List<UncertaintyDraft> uncertainties) {
        MetricDraft metric = new MetricDraft();
        metric.setKey("order_amount");
        metric.setName("order_amount");
        metric.setBizName("订单金额");
        metric.setField("amount");
        metric.setAggregation("SUM");
        metric.setExpression("SUM(amount)");
        metric.setAliases(List.of("订单总额"));
        metric.setFilters(new ArrayList<>());

        ModelDraft model = new ModelDraft();
        model.setKey("orders_model");
        model.setName("orders_model");
        model.setBizName("订单");
        model.setBaseTable("orders");
        model.setDimensions(new ArrayList<>());
        model.setMetrics(new ArrayList<>(List.of(metric)));
        model.setSampleQuestions(new ArrayList<>(questions));
        model.setSensitiveFields(new ArrayList<>());
        if (declareSensitive) {
            SensitiveFieldDraft sensitive = new SensitiveFieldDraft();
            sensitive.setField("customer_phone");
            sensitive.setLevel("HIGH");
            sensitive.setMaskingStrategy("HASH");
            sensitive.setReason("客户手机号");
            model.getSensitiveFields().add(sensitive);
            SensitiveFieldDraft bankAccount = new SensitiveFieldDraft();
            bankAccount.setField("bank_account");
            bankAccount.setLevel("HIGH");
            bankAccount.setMaskingStrategy("HASH");
            bankAccount.setReason("结算账号");
            model.getSensitiveFields().add(bankAccount);
        }

        ModelingDraftPayload payload = new ModelingDraftPayload();
        payload.setSchemaVersion(ModelingDraftConstants.SCHEMA_VERSION);
        payload.setBusinessGoal("分析订单金额");
        payload.setModels(new ArrayList<>(List.of(model)));
        payload.setTerms(new ArrayList<>());
        payload.setUncertainties(new ArrayList<>(uncertainties));
        return payload;
    }

    /** 构造包含一个高置信敏感字段的真实元数据快照。 */
    private Map<String, List<DBColumn>> columns() {
        return Map.of("orders",
                List.of(new DBColumn("amount", "DECIMAL", "订单金额", null),
                        new DBColumn("customer_phone", "VARCHAR", "客户手机号", null),
                        new DBColumn("bank_account", "VARCHAR", "结算账号", null)));
    }
}
