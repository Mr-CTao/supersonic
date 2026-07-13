package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.ContextUtils;
import com.tencent.supersonic.common.util.DateModeUtils;
import com.tencent.supersonic.common.util.SqlFilterUtils;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.chat.query.rule.metric.MetricModelQuery;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.translator.SemanticTranslator;
import com.tencent.supersonic.headless.server.semantic.modeling.DraftSemanticValidationService.SampleValidation;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricFilterDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 隔离草稿真实语义验证服务单元测试。
 *
 * <p>
 * 职责说明：验证样例问法确实经过现有 RuleSqlParser 生成 selected parse/S2SQL，再调用现有 SemanticTranslator 接口；翻译失败、非只读
 * SQL、缺少 LIMIT 或无法映射均必须 fail-closed；同时覆盖 WHERE、JOIN、APPLY 与 LATERAL 的静态性能 AST 门禁。 测试不连接数据源、不执行
 * SQL，也不写正式语义资产。
 * </p>
 */
class DraftSemanticValidationServiceTest {

    private DatabaseService databaseService;
    private SemanticTranslator semanticTranslator;
    private DraftSemanticValidationService service;
    private User user;
    private ApplicationContext previousContext;
    private GenericApplicationContext testContext;

    /** 初始化只返回数据源方言的 mock，并注册现有最小指标规则查询。 */
    @BeforeEach
    void setUp() {
        previousContext = ContextUtils.getContext();
        testContext = new GenericApplicationContext();
        testContext.registerBean(SqlFilterUtils.class, () -> mock(SqlFilterUtils.class));
        testContext.registerBean(DateModeUtils.class, () -> mock(DateModeUtils.class));
        testContext.refresh();
        new ContextUtils().setApplicationContext(testContext);
        databaseService = mock(DatabaseService.class);
        semanticTranslator = mock(SemanticTranslator.class);
        user = User.getDefaultUser();
        when(databaseService.getDatabase(eq(1L), eq(user)))
                .thenReturn(DatabaseResp.builder().id(1L).type("H2").version("2").build());
        new MetricModelQuery();
        SemanticModelingSensitivityClassifier classifier =
                new SemanticModelingSensitivityClassifier();
        service = new DraftSemanticValidationService(databaseService,
                new DraftSemanticSchemaFactory(), new DraftSemanticSchemaMapper(),
                semanticTranslator, new ModelingSqlReadOnlyChecker(), classifier,
                new SemanticModelingProperties());
    }

    /** 负数 OFFSET 阈值配置必须安全收紧为零，不能关闭性能门禁。 */
    @Test
    void shouldFailClosedForNegativeConfiguredOffsetThreshold() {
        SemanticModelingProperties properties = new SemanticModelingProperties();
        properties.setMaxSafePreviewOffset(-1L);

        assertThat(properties.resolveMaxSafePreviewOffset()).isZero();
    }

    /** 恢复全局 Spring 上下文，避免单元测试污染其他 parser 测试。 */
    @AfterEach
    void tearDown() {
        new ContextUtils().setApplicationContext(previousContext);
        testContext.close();
    }

    /** 问法命中指标后必须把 parser 产出的 S2SQL 交给 translator，不能自行拼接物理 SQL。 */
    @Test
    void shouldReuseRuleParserAndSemanticTranslator() throws Exception {
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            assertThat(statement.getSqlQuery().getSql()).contains("order_amount")
                    .contains("LIMIT 20");
            assertThat(statement.getOntology().getModelMap()).containsKey("orders_model");
            statement.setSql("SELECT SUM(amount) FROM orders LIMIT 20");
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).isEmpty();
        assertThat(result.sqlResult().getStatus()).isEqualTo("PASSED");
        assertThat(result.results()).singleElement().satisfies(sample -> {
            assertThat(sample.getMatched()).isTrue();
            assertThat(sample.getMatchedObjectKeys()).contains("order_amount");
            assertThat(sample.getS2sqlPreview()).contains("order_amount");
            assertThat(sample.getSqlPreview()).isEqualTo("SELECT SUM(amount) FROM orders LIMIT 20");
            assertThat(sample.getValidationMode()).isEqualTo("DRAFT_SEMANTIC_PIPELINE");
        });
        verify(semanticTranslator).translate(any(QueryStatement.class));
    }

    /** 无法映射到隔离草稿元素时不得调用 translator，并必须阻塞。 */
    @Test
    void shouldBlockQuestionWithoutSemanticMapping() throws Exception {
        SampleValidation result = service.validate(payload("完全无关的问题"), columns(), 1L, user, 20);

        assertThat(result.sqlResult().getStatus()).isEqualTo("FAILED");
        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SAMPLE_QUESTION_PARSE_FAILED");
        verify(semanticTranslator, never()).translate(any(QueryStatement.class));
    }

    /** ASCII 短 key 必须满足 Unicode token 边界，id 不能误命中 paid。 */
    @Test
    void shouldNotMatchShortAsciiKeyInsideLargerToken() throws Exception {
        ModelingDraftPayload payload = payload("paid orders");
        MetricDraft metric = payload.getModels().get(0).getMetrics().get(0);
        metric.setKey("id");
        metric.setName("id");
        metric.setBizName("id");
        metric.setAliases(new ArrayList<>());

        SampleValidation result = service.validate(payload, columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SAMPLE_QUESTION_PARSE_FAILED");
        verify(semanticTranslator, never()).translate(any(QueryStatement.class));
    }

    /** translator 生成非只读语句时必须由 AST 检查阻塞。 */
    @Test
    void shouldBlockNonReadOnlyTranslatedSql() throws Exception {
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql("DELETE FROM orders");
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .contains("NON_SELECT_STATEMENT");
        assertThat(result.results()).singleElement()
                .satisfies(sample -> assertThat(sample.getReadOnly()).isFalse());
    }

    /** 即使 SQL 只读，缺少强制 LIMIT 也不能通过验证门禁。 */
    @Test
    void shouldBlockTranslatedSqlWithoutLimit() throws Exception {
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql("SELECT SUM(amount) FROM orders");
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_LIMIT_MISSING");
        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_FAILED);
    }

    /** 物理 SQL LIMIT 即使是正整数，超过 previewLimit 也必须阻塞。 */
    @Test
    void shouldBlockTranslatedSqlExceedingPreviewLimit() throws Exception {
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql("SELECT SUM(amount) FROM orders LIMIT 100");
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_LIMIT_EXCEEDED");
    }

    /** 当前 translator 不能无损表达指标结构化过滤时必须阻塞，不能静默忽略后通过。 */
    @Test
    void shouldBlockSelectedMetricWithUnsupportedFilters() throws Exception {
        ModelingDraftPayload payload = payload("订单金额是多少");
        MetricFilterDraft filter = new MetricFilterDraft();
        filter.setField("status");
        filter.setOperator("NOT_IN");
        filter.setValues(new ArrayList<>(List.of("CLOSED", "CANCELLED")));
        payload.getModels().get(0).getMetrics().get(0).getFilters().add(filter);

        SampleValidation result = service.validate(payload, columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("METRIC_FILTER_TRANSLATION_UNSUPPORTED");
        assertThat(result.blockingItems()).hasSize(1);
        verify(semanticTranslator, never()).translate(any(QueryStatement.class));
    }

    /** 未被样例选中的过滤指标也必须形成唯一稳定阻塞项，不能依赖 selected parse 覆盖。 */
    @Test
    void shouldBlockUnselectedMetricWithUnsupportedFilters() throws Exception {
        ModelingDraftPayload payload = payload("订单金额是多少");
        MetricDraft filteredMetric = metric("closed_amount", "已关闭金额");
        MetricFilterDraft filter = new MetricFilterDraft();
        filter.setField("status");
        filter.setOperator("EQ");
        filter.setValues(new ArrayList<>(List.of("CLOSED")));
        filteredMetric.getFilters().add(filter);
        payload.getModels().get(0).getMetrics().add(filteredMetric);
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql("SELECT SUM(amount) FROM orders LIMIT 20");
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));

        SampleValidation result = service.validate(payload, columns(), 1L, user, 20);

        assertThat(result.blockingItems()).singleElement().satisfies(finding -> {
            assertThat(finding.getCode()).isEqualTo("METRIC_FILTER_TRANSLATION_UNSUPPORTED");
            assertThat(finding.getPath()).contains("closed_amount");
        });
        assertThat(result.results()).singleElement()
                .satisfies(sample -> assertThat(sample.getReadOnly()).isTrue());
        verify(semanticTranslator).translate(any(QueryStatement.class));
    }

    /** translator 抛错时不得把异常详情写进报告，统一按隔离语义链路失败阻塞。 */
    @Test
    void shouldBlockAndSanitizeTranslatorFailure() throws Exception {
        doThrow(new IllegalStateException("jdbc:secret://host token=raw")).when(semanticTranslator)
                .translate(any(QueryStatement.class));

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).singleElement().satisfies(finding -> {
            assertThat(finding.getCode()).isEqualTo("SEMANTIC_PIPELINE_FAILED");
            assertThat(finding.getMessage()).doesNotContain("jdbc", "secret", "token");
        });
    }

    /** JSqlParser 4.9 将 UNION 尾部全局 LIMIT 挂到最后分支，服务必须按集合语义接受。 */
    @Test
    void shouldAcceptUnionAllWithGlobalLimit() throws Exception {
        translateAs("SELECT 1 UNION ALL SELECT 2 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).isEmpty();
        assertThat(result.sqlReadOnlyResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
    }

    /** FETCH FIRST/NEXT 的确定正整数全局上限应与 LIMIT 使用相同安全门禁。 */
    @Test
    void shouldAcceptProvableGlobalFetchLimits() throws Exception {
        for (String sql : List.of("SELECT id FROM t FETCH FIRST 20 ROWS ONLY",
                "SELECT id FROM t FETCH NEXT 20 ROWS ONLY", "SELECT id FROM t FETCH FIRST ROW ONLY",
                "(SELECT id FROM t) FETCH FIRST 20 ROWS ONLY",
                "SELECT id FROM t OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY",
                "SELECT id FROM a UNION ALL SELECT id FROM b FETCH FIRST 20 ROWS ONLY",
                "(SELECT id FROM a UNION ALL SELECT id FROM b) FETCH FIRST 20 ROWS ONLY",
                "WITH q AS (SELECT id FROM t) SELECT id FROM q FETCH FIRST 20 ROWS ONLY")) {
            org.mockito.Mockito.reset(semanticTranslator);
            translateAs(sql);
            SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);
            assertThat(result.blockingItems()).as(sql).isEmpty();
            assertThat(result.sqlReadOnlyResult().getStatus()).as(sql)
                    .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        }
    }

    /** 动态、非正数、超限、WITH TIES、百分比及 LIMIT/FETCH 歧义必须 fail-closed。 */
    @Test
    void shouldRejectUnprovableOrAmbiguousFetchLimits() throws Exception {
        for (String sql : List.of("SELECT id FROM t FETCH FIRST ? ROWS ONLY",
                "SELECT id FROM t FETCH FIRST 1 + 1 ROWS ONLY",
                "SELECT id FROM t FETCH FIRST 0 ROWS ONLY",
                "SELECT id FROM t FETCH FIRST -1 ROWS ONLY",
                "SELECT id FROM t FETCH FIRST 21 ROWS ONLY",
                "SELECT id FROM t FETCH FIRST 20 ROWS WITH TIES",
                "SELECT id FROM t FETCH FIRST 10 PERCENT ROWS ONLY",
                "SELECT id FROM t LIMIT 20 FETCH FIRST 20 ROWS ONLY",
                "SELECT id FROM t OFFSET ? ROWS FETCH NEXT 20 ROWS ONLY")) {
            org.mockito.Mockito.reset(semanticTranslator);
            translateAs(sql);
            SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);
            assertThat(result.blockingItems()).as(sql).isNotEmpty();
            assertThat(result.blockingItems()).as(sql)
                    .extracting(ModelingValidationFinding::getCode)
                    .allMatch(code -> code.startsWith("SQL_LIMIT_"));
        }
    }

    /** 仅集合分支存在 FETCH 不能冒充约束最终结果集的全局上限。 */
    @Test
    void shouldRejectFetchLimitedUnionBranchesWithoutGlobalLimit() throws Exception {
        for (String sql : List.of(
                "(SELECT id FROM a FETCH FIRST 20 ROWS ONLY) UNION ALL SELECT id FROM b",
                "SELECT id FROM a UNION ALL (SELECT id FROM b FETCH FIRST 20 ROWS ONLY)")) {
            org.mockito.Mockito.reset(semanticTranslator);
            translateAs(sql);
            SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);
            assertThat(result.blockingItems()).as(sql)
                    .extracting(ModelingValidationFinding::getCode).contains("SQL_LIMIT_MISSING");
        }
    }

    /** FETCH 配合超阈值 OFFSET 仍必须沿用现有稳定性能风险。 */
    @Test
    void shouldWarnForLargeOffsetWithFetchLimit() throws Exception {
        assertPerformanceWarning(
                "SELECT id FROM t WHERE id > 0 OFFSET 1000000 ROWS FETCH NEXT 20 ROWS ONLY",
                "SQL_LARGE_OFFSET_RISK");
    }

    /** UNION 全局 LIMIT 等于预览上限应通过，超过上限必须拒绝。 */
    @Test
    void shouldValidateUnionGlobalLimitAgainstPreviewLimit() throws Exception {
        translateAs("SELECT 1 UNION SELECT 2 LIMIT 21");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_LIMIT_EXCEEDED");
    }

    /** UNION 无全局 LIMIT、仅某分支 LIMIT 或各括号分支分别 LIMIT 都不能证明全局上限。 */
    @Test
    void shouldRejectUnionWithoutProvableGlobalLimit() throws Exception {
        translateAs("(SELECT 1 LIMIT 2) UNION ALL (SELECT 2 LIMIT 3)");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_LIMIT_MISSING");
    }

    /** 括号分支可有内部 LIMIT，但集合尾部仍必须有可证明的全局 LIMIT。 */
    @Test
    void shouldAcceptParenthesedBranchWithGlobalUnionLimit() throws Exception {
        translateAs("(SELECT 1 LIMIT 2) UNION ALL SELECT 2 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).isEmpty();
    }

    /** 外层 SELECT 的确定 LIMIT 可以约束内部 UNION 总结果。 */
    @Test
    void shouldAcceptOuterSelectLimitAroundUnion() throws Exception {
        translateAs("SELECT * FROM (SELECT 1 UNION ALL SELECT 2) union_result LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.blockingItems()).isEmpty();
    }

    /** 参数、表达式、零和负数 LIMIT 均不能静态证明安全。 */
    @Test
    void shouldRejectDynamicZeroAndNegativeUnionLimits() throws Exception {
        for (String sql : List.of("SELECT 1 UNION ALL SELECT 2 LIMIT ?",
                "SELECT 1 UNION ALL SELECT 2 LIMIT 1 + 2", "SELECT 1 UNION ALL SELECT 2 LIMIT 0",
                "SELECT 1 UNION ALL SELECT 2 LIMIT -1")) {
            org.mockito.Mockito.reset(semanticTranslator);
            translateAs(sql);
            SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);
            assertThat(result.blockingItems()).extracting(ModelingValidationFinding::getCode)
                    .containsExactly("SQL_LIMIT_INVALID");
        }
    }

    /** 有全局上限但无过滤物理表扫描时应生成性能 warning，而不是伪装为完全通过。 */
    @Test
    void shouldWarnForUnfilteredStaticScanRisk() throws Exception {
        translateAs("SELECT SUM(amount) FROM orders LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_WARNING);
        assertThat(result.warningItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_UNFILTERED_SCAN_RISK");
    }

    /** 有确定 LIMIT 且存在过滤条件的普通查询应通过静态性能检查。 */
    @Test
    void shouldPassStaticPerformanceCheckWithFilterAndLimit() throws Exception {
        translateAs("SELECT SUM(amount) FROM orders WHERE amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** 恒真 WHERE 不会缩小基础表扫描范围，不能仅因 AST 节点存在就判定为已过滤。 */
    @Test
    void shouldWarnForTautologicalWherePredicate() throws Exception {
        for (String sql : List.of("SELECT amount FROM orders WHERE TRUE LIMIT 20",
                "SELECT amount FROM orders WHERE 1 = 1 LIMIT 20",
                "SELECT amount FROM orders WHERE 2 > 1 LIMIT 20",
                "SELECT amount FROM orders WHERE NOT FALSE LIMIT 20",
                "SELECT amount FROM orders WHERE 1 + 1 = 2 LIMIT 20",
                "SELECT amount FROM orders WHERE amount > 0 OR TRUE LIMIT 20",
                "SELECT amount FROM orders WHERE amount > 0 OR 1 = 1 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** OR 的任一可满足分支无法证明过滤有效时，整个 WHERE 都不能降低扫描风险。 */
    @Test
    void shouldWarnWhenAnyWhereOrBranchIsNotProvablyEffective() throws Exception {
        for (String sql : List.of(
                "SELECT amount FROM orders WHERE amount > 0 OR 1 + 1 = 2 LIMIT 20",
                "SELECT amount FROM orders WHERE amount = amount LIMIT 20",
                "SELECT a.id FROM a WHERE a.id > 0 OR ghost.id > 0 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** 函数、算术、一元符号及多层括号包裹的自比较都不能作为过滤选择性证明。 */
    @Test
    void shouldWarnForStructurallyEquivalentWhereOperands() throws Exception {
        for (String sql : List.of(
                "SELECT amount FROM orders "
                        + "WHERE COALESCE(amount, 0) = COALESCE(amount, 0) LIMIT 20",
                "SELECT amount FROM orders WHERE amount + 1 >= amount + 1 LIMIT 20",
                "SELECT amount FROM orders WHERE -amount = -amount LIMIT 20",
                "SELECT amount FROM orders " + "WHERE (((COALESCE((amount), (0))))) "
                        + "= ((COALESCE(amount, 0))) LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** 同一表达式的 IS NULL / IS NOT NULL 互补 OR 覆盖全部输入，不能降低扫描风险。 */
    @Test
    void shouldWarnForComplementaryNullDisjunction() throws Exception {
        for (String sql : List.of(
                "SELECT amount FROM orders "
                        + "WHERE amount IS NULL OR amount IS NOT NULL LIMIT 20",
                "SELECT amount FROM orders "
                        + "WHERE amount IS NOT NULL OR amount IS NULL LIMIT 20",
                "SELECT amount FROM orders "
                        + "WHERE (((amount IS NULL))) OR ((amount IS NOT NULL)) LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** 布尔单位元、NOT 恒真式和未建模谓词均不得仅凭内部字段引用伪造有效过滤。 */
    @Test
    void shouldFailClosedForNormalizedTautologiesAndUnsupportedPredicates() throws Exception {
        for (String sql : List.of(
                "SELECT amount FROM orders "
                        + "WHERE amount IS NULL OR (amount IS NOT NULL AND TRUE) LIMIT 20",
                "SELECT amount FROM orders "
                        + "WHERE NOT (amount IS NULL AND amount IS NOT NULL) LIMIT 20",
                "SELECT amount FROM orders " + "WHERE (((amount IS NULL OR "
                        + "((amount IS NOT NULL AND TRUE))))) LIMIT 20",
                "SELECT amount FROM orders WHERE amount LIKE '1%' LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** 超过静态证明最大嵌套深度的谓词必须 fail-closed，且不能触发线程栈溢出。 */
    @Test
    void shouldFailClosedBeyondMaximumBooleanDepth() throws Exception {
        String nestedPredicate = "(".repeat(70) + "amount > 0" + ")".repeat(70);

        assertPerformanceWarning("SELECT amount FROM orders WHERE " + nestedPredicate + " LIMIT 20",
                "SQL_UNFILTERED_SCAN_RISK");
    }

    /** 单表作用域中限定与非限定同名列绑定同一字段，不能绕过自比较或 NULL 互补门禁。 */
    @Test
    void shouldWarnForEquivalentQualifiedAndUnqualifiedColumns() throws Exception {
        for (String sql : List.of("SELECT amount FROM orders WHERE amount = orders.amount LIMIT 20",
                "SELECT amount FROM orders o WHERE amount = o.amount LIMIT 20",
                "SELECT amount FROM orders "
                        + "WHERE amount IS NULL OR orders.amount IS NOT NULL LIMIT 20",
                "SELECT amount FROM orders WHERE (((amount))) = ((orders.amount)) LIMIT 20",
                "SELECT amount FROM public.orders "
                        + "WHERE amount = public.orders.amount LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** 多表作用域中的非限定列无法唯一绑定，不能被静态证明为安全过滤。 */
    @Test
    void shouldWarnForAmbiguousUnqualifiedColumnInMultiRelationScope() throws Exception {
        assertPerformanceWarning(
                "SELECT a.id FROM a JOIN b ON a.id = b.a_id " + "WHERE id = a.id LIMIT 20",
                "SQL_UNFILTERED_SCAN_RISK");
    }

    /** 互补 NULL 判断被额外 AND 条件收窄时仍具过滤能力，且静态 FALSE/真实 AND 不回归。 */
    @Test
    void shouldPreserveProvablySelectiveWherePredicates() throws Exception {
        for (String sql : List.of("SELECT amount FROM orders WHERE amount IS NULL LIMIT 20",
                "SELECT amount FROM orders "
                        + "WHERE amount IS NULL OR (amount IS NOT NULL AND amount > 0) LIMIT 20",
                "SELECT amount FROM orders WHERE amount > 0 AND TRUE LIMIT 20",
                "SELECT amount FROM orders WHERE amount > 0 OR FALSE LIMIT 20",
                "SELECT amount FROM orders WHERE amount > 0 AND FALSE LIMIT 20",
                "SELECT amount FROM orders WHERE NOT TRUE LIMIT 20",
                "SELECT amount FROM orders WHERE 1 = 0 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformancePassed(sql);
        }
    }

    /** OR 的每个分支都依赖字段且具有约束时，应保留正常查询通过能力。 */
    @Test
    void shouldPassWhenEveryWhereOrBranchIsEffective() throws Exception {
        translateAs("SELECT amount FROM orders WHERE amount > 0 OR amount < 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** HAVING 在聚合后执行，不能代替 WHERE 约束基础表扫描规模。 */
    @Test
    void shouldWarnForHavingOnlyAggregateScan() throws Exception {
        assertPerformanceWarning(
                "SELECT customer_id, SUM(amount) FROM orders "
                        + "GROUP BY customer_id HAVING SUM(amount) > 0 LIMIT 20",
                "SQL_UNFILTERED_SCAN_RISK");
    }

    /** 外层有 WHERE/LIMIT 时，JOIN 派生表里的无过滤扫描仍必须被递归发现。 */
    @Test
    void shouldWarnForUnfilteredJoinSubquery() throws Exception {
        translateAs(
                "SELECT o.amount FROM orders o " + "JOIN (SELECT amount FROM orders) nested_orders "
                        + "ON nested_orders.amount = o.amount WHERE o.amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_WARNING);
        assertThat(result.warningItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_NESTED_UNFILTERED_SCAN_RISK");
    }

    /** 外层过滤不能掩盖 CTE 定义中的无过滤扫描。 */
    @Test
    void shouldWarnForUnfilteredCteQuery() throws Exception {
        translateAs("WITH raw_orders AS (SELECT amount FROM orders) "
                + "SELECT amount FROM raw_orders WHERE amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_WARNING);
        assertThat(result.warningItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_NESTED_UNFILTERED_SCAN_RISK");
    }

    /** SELECT 列表里的标量子查询同样需要递归检查，不能被外层过滤和 LIMIT 掩盖。 */
    @Test
    void shouldWarnForUnfilteredScalarSubquery() throws Exception {
        translateAs("SELECT (SELECT SUM(amount) FROM orders) AS total "
                + "FROM orders o WHERE o.amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_WARNING);
        assertThat(result.warningItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly("SQL_NESTED_UNFILTERED_SCAN_RISK");
    }

    /** CTE 内外均有过滤且全局 LIMIT 合法时不应误报无界扫描。 */
    @Test
    void shouldPassFilteredCteQuery() throws Exception {
        translateAs("WITH filtered_orders AS " + "(SELECT amount FROM orders WHERE amount > 0) "
                + "SELECT amount FROM filtered_orders WHERE amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** WHERE 过滤不能掩盖显式 CROSS JOIN 产生的连接中间集风险。 */
    @Test
    void shouldWarnForCrossJoinEvenWhenOuterQueryIsFiltered() throws Exception {
        assertPerformanceWarning("SELECT o.amount FROM orders o CROSS JOIN customers c "
                + "WHERE o.amount > 0 LIMIT 20", "SQL_CROSS_JOIN_RISK");
    }

    /** JSqlParser 的 simple join 表示逗号连接，必须保守提示连接条件完整性风险。 */
    @Test
    void shouldWarnForImplicitCommaJoin() throws Exception {
        assertPerformanceWarning(
                "SELECT o.amount FROM orders o, customers c WHERE o.amount > 0 LIMIT 20",
                "SQL_IMPLICIT_COMMA_JOIN_RISK");
    }

    /** 普通 JOIN 缺少 ON/USING/NATURAL 语义时可能形成笛卡尔积。 */
    @Test
    void shouldWarnForJoinWithoutCondition() throws Exception {
        assertPerformanceWarning(
                "SELECT o.amount FROM orders o JOIN customers c WHERE o.amount > 0 LIMIT 20",
                "SQL_JOIN_CONDITION_MISSING_RISK");
    }

    /** ON TRUE 和常量恒等式均不能证明右侧关系受到连接约束。 */
    @Test
    void shouldWarnForTautologicalJoinCondition() throws Exception {
        for (String predicate : List.of("TRUE", "1 = 1", "o.customer_id = c.id OR TRUE")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(
                    "SELECT o.amount FROM orders o JOIN customers c ON " + predicate
                            + " WHERE o.amount > 0 LIMIT 20",
                    "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
        }
    }

    /** 同时提及左右别名的同构操作数仍是自比较，不能据此证明 JOIN 受约束。 */
    @Test
    void shouldWarnForStructurallyEquivalentJoinOperands() throws Exception {
        for (String predicate : List.of("a.id + b.id = a.id + b.id",
                "COALESCE(a.id, b.id) = COALESCE(a.id, b.id)", "a.id + b.id >= a.id + b.id")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(
                    "SELECT a.id FROM a JOIN b ON " + predicate + " WHERE a.id > 0 LIMIT 20",
                    "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
        }
    }

    /** 单边条件或带单边 OR 分支仍会形成乘积，无法保证跨关系约束时应保守告警。 */
    @Test
    void shouldWarnWhenJoinConditionDoesNotGuaranteeCrossRelation() throws Exception {
        for (String predicate : List.of("o.amount > 0", "o.amount > 0 AND c.id > 0",
                "c.id = o.customer_id OR c.id > 0")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(
                    "SELECT o.amount FROM orders o JOIN customers c ON " + predicate
                            + " WHERE o.amount > 0 LIMIT 20",
                    "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
        }
    }

    /** 相同操作数上的等值、范围互补 OR 会覆盖全部或近似全部组合，不能作为 JOIN 约束。 */
    @Test
    void shouldWarnForComplementaryRelationalJoinPredicates() throws Exception {
        for (String predicate : List.of("a.id = b.id OR a.id <> b.id",
                "a.id > b.id OR a.id <= b.id", "a.id >= b.id OR a.id < b.id",
                "a.id = b.id OR b.id <> a.id", "a.id > b.id OR b.id >= a.id")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(
                    "SELECT a.id FROM a JOIN b ON " + predicate + " WHERE a.id > 0 LIMIT 20",
                    "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
        }
    }

    /** 不同操作数的真实跨关系 OR 和带附加右表过滤的 AND 仍应正常通过。 */
    @Test
    void shouldPassNonComplementaryRelationalJoinPredicates() throws Exception {
        for (String predicate : List.of("a.id = b.a_id OR a.alt_id = b.alt_a_id",
                "a.id = b.id AND b.active = 1")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformancePassed(
                    "SELECT a.id FROM a JOIN b ON " + predicate + " WHERE a.id > 0 LIMIT 20");
        }
    }

    /** 外层 ON 只连接右侧括号子树内部关系时，没有约束当前 SELECT 已构建的左侧。 */
    @Test
    void shouldWarnWhenOuterJoinOnlyConnectsRelationsInsideRightSubtree() throws Exception {
        assertPerformanceWarning("SELECT a.id FROM a "
                + "JOIN (b JOIN c ON b.id = c.b_id) ON b.id = c.b_id " + "WHERE a.id > 0 LIMIT 20",
                "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
    }

    /** ON 引用不属于当前左右作用域的幽灵别名时，不能被当作有效连接。 */
    @Test
    void shouldWarnWhenJoinReferencesUnknownRelationAlias() throws Exception {
        for (String predicate : List.of("c.id = ghost.id", "a.id = c.a_id AND ghost.id > 0")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(
                    "SELECT a.id FROM a JOIN c ON " + predicate + " WHERE a.id > 0 LIMIT 20",
                    "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
        }
    }

    /** 后续 JOIN 可以连接此前任一已构建左侧关系，但必须连接当前右侧关系。 */
    @Test
    void shouldPassJoinAgainstPreviouslyBuiltLeftRelation() throws Exception {
        translateAs("SELECT a.id FROM a JOIN b ON a.id = b.a_id "
                + "JOIN c ON b.id = c.b_id WHERE a.id > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** 外层 ON 明确连接 a 与括号右子树中的 b 时，嵌套 JOIN 结构应正常通过。 */
    @Test
    void shouldPassOuterJoinThatConnectsLeftToRightSubtree() throws Exception {
        translateAs("SELECT a.id FROM a " + "JOIN (b JOIN c ON b.id = c.b_id) ON a.id = b.a_id "
                + "WHERE a.id > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** 缺少可证明公共列元数据的 NATURAL JOIN 必须保守告警。 */
    @Test
    void shouldWarnForUnresolvedNaturalJoinColumns() throws Exception {
        assertPerformanceWarning("SELECT a.id FROM a NATURAL JOIN b " + "WHERE a.id > 0 LIMIT 20",
                "SQL_NATURAL_JOIN_UNRESOLVED_RISK");
    }

    /** OUTER APPLY 右侧不引用左侧关系时会逐行重复独立子查询，必须告警。 */
    @Test
    void shouldWarnForUncorrelatedOuterApply() throws Exception {
        for (String sql : List.of(
                "SELECT a.id FROM a OUTER APPLY " + "(SELECT b.id FROM b WHERE b.active = 1) q "
                        + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a OUTER APPLY " + "(SELECT a.id FROM b a WHERE a.active = 1) q "
                        + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a OUTER APPLY "
                        + "(SELECT b.id FROM b WHERE b.a_id = a.id OR b.active = 1) q "
                        + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a OUTER APPLY "
                        + "(SELECT b.id FROM b WHERE b.a_id = a.id AND ghost.id > 0) q "
                        + "WHERE a.id > 0 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_APPLY_UNCORRELATED_RISK");
        }
    }

    /** 同时包含外层和本地别名的同构自比较不能伪造 APPLY 相关性。 */
    @Test
    void shouldWarnForStructurallyEquivalentApplyCorrelation() throws Exception {
        for (String predicate : List.of("a.id + b.id = a.id + b.id",
                "COALESCE(a.id, b.id) = COALESCE(a.id, b.id)")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning("SELECT a.id FROM a OUTER APPLY "
                    + "(SELECT b.id FROM b WHERE " + predicate + ") q " + "WHERE a.id > 0 LIMIT 20",
                    "SQL_APPLY_UNCORRELATED_RISK");
        }
    }

    /** APPLY 右侧子查询明确引用当前左侧关系时，不应被无关联规则误报。 */
    @Test
    void shouldPassCorrelatedOuterApply() throws Exception {
        translateAs("SELECT a.id FROM a OUTER APPLY "
                + "(SELECT b.id FROM b WHERE b.a_id = a.id AND b.active = 1) q "
                + "WHERE a.id > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** CROSS APPLY 与 OUTER APPLY 共享左侧作用域，真实跨关系谓词应正常通过。 */
    @Test
    void shouldPassCorrelatedCrossApply() throws Exception {
        assertPerformancePassed("SELECT a.id FROM a CROSS APPLY "
                + "(SELECT b.id FROM b WHERE b.a_id = a.id AND b.active = 1) q "
                + "WHERE a.id > 0 LIMIT 20");
    }

    /** APPLY/LATERAL 仅由互补 OR 提供外层引用时，不能被认定为真实相关。 */
    @Test
    void shouldWarnForComplementaryApplyAndLateralCorrelation() throws Exception {
        assertPerformanceWarning("SELECT a.id FROM a OUTER APPLY "
                + "(SELECT b.id FROM b WHERE b.a_id = a.id OR b.a_id <> a.id) q "
                + "WHERE a.id > 0 LIMIT 20", "SQL_APPLY_UNCORRELATED_RISK");
        org.mockito.Mockito.reset(semanticTranslator);
        assertPerformanceWarning("SELECT a.id FROM a JOIN LATERAL "
                + "(SELECT b.id FROM b WHERE b.a_id > a.id OR b.a_id <= a.id) q ON TRUE "
                + "WHERE a.id > 0 LIMIT 20", "SQL_LATERAL_UNCORRELATED_RISK");
    }

    /** 相关 JOIN/CROSS JOIN LATERAL 的右侧已约束左侧时，不应落入普通 JOIN 或 CROSS 风险。 */
    @Test
    void shouldPassCorrelatedLateralJoins() throws Exception {
        for (String sql : List.of("SELECT a.id FROM a JOIN LATERAL "
                + "(SELECT b.id FROM b WHERE b.a_id = a.id) q ON TRUE " + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a CROSS JOIN LATERAL "
                        + "(SELECT b.id FROM b WHERE b.a_id = a.id) q "
                        + "WHERE a.id > 0 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformancePassed(sql);
        }
    }

    /** 非 CROSS LATERAL 即使右侧相关，也必须具备 ON、USING 或其他明确连接限定。 */
    @Test
    void shouldWarnWhenQualifiedLateralJoinMissesOuterCondition() throws Exception {
        for (String joinType : List.of("JOIN", "INNER JOIN", "LEFT JOIN")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning("SELECT a.id FROM a " + joinType + " LATERAL "
                    + "(SELECT b.id FROM b WHERE b.a_id = a.id) q " + "WHERE a.id > 0 LIMIT 20",
                    "SQL_JOIN_CONDITION_MISSING_RISK");
        }
    }

    /** 非相关、别名遮蔽、OR 单边或幽灵别名均不能伪造 LATERAL 相关性。 */
    @Test
    void shouldWarnForUnprovableLateralCorrelation() throws Exception {
        for (String sql : List.of("SELECT a.id FROM a JOIN LATERAL "
                + "(SELECT b.id FROM b WHERE b.active = 1) q ON TRUE " + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a CROSS JOIN LATERAL "
                        + "(SELECT b.id FROM b WHERE b.active = 1) q " + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a CROSS JOIN LATERAL "
                        + "(SELECT a.id FROM b a WHERE a.active = 1) q "
                        + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a JOIN LATERAL "
                        + "(SELECT b.id FROM b WHERE b.a_id = a.id OR b.active = 1) q ON TRUE "
                        + "WHERE a.id > 0 LIMIT 20",
                "SELECT a.id FROM a JOIN LATERAL "
                        + "(SELECT b.id FROM b WHERE b.a_id = a.id AND ghost.id > 0) q ON TRUE "
                        + "WHERE a.id > 0 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_LATERAL_UNCORRELATED_RISK");
        }
    }

    /** 相关 LATERAL 的外层 ON 若引用未知别名，仍必须返回明确 JOIN 条件风险。 */
    @Test
    void shouldWarnForUnknownAliasInLateralOnCondition() throws Exception {
        assertPerformanceWarning("SELECT a.id FROM a JOIN LATERAL "
                + "(SELECT b.id FROM b WHERE b.a_id = a.id) q ON ghost.id = q.id "
                + "WHERE a.id > 0 LIMIT 20", "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
    }

    /** SELECT * 即使带过滤和全局 LIMIT，仍应提示不必要字段和行宽风险。 */
    @Test
    void shouldWarnForAllColumnsProjection() throws Exception {
        assertPerformanceWarning("SELECT * FROM orders WHERE amount > 0 LIMIT 20",
                "SQL_ALL_COLUMNS_PROJECTION_RISK");
    }

    /** table.* 与裸星号 AST 不同，必须单独覆盖而不能依赖字符串匹配。 */
    @Test
    void shouldWarnForTableWildcardProjection() throws Exception {
        assertPerformanceWarning("SELECT o.* FROM orders o WHERE o.amount > 0 LIMIT 20",
                "SQL_TABLE_WILDCARD_PROJECTION_RISK");
    }

    /** 有明确 ON 条件、显式字段、过滤和 LIMIT 的普通 JOIN 不应被误报。 */
    @Test
    void shouldPassConditionedJoinWithExplicitProjection() throws Exception {
        translateAs("SELECT o.amount FROM orders o JOIN customers c "
                + "ON c.id = o.customer_id WHERE o.amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** 恒真子句与真实过滤、跨关系连接约束通过 AND 组合时，不应误伤有效查询。 */
    @Test
    void shouldPassRealConstraintsCombinedWithTautologies() throws Exception {
        translateAs("SELECT o.amount FROM orders o JOIN customers c "
                + "ON TRUE AND c.id = o.customer_id " + "WHERE 1 = 1 AND o.amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** OR 的每个可满足分支都跨关系时，连接约束仍可被静态证明。 */
    @Test
    void shouldPassWhenEveryJoinOrBranchCrossesRelations() throws Exception {
        translateAs("SELECT o.amount FROM orders o JOIN customers c "
                + "ON c.id = o.customer_id OR c.parent_id = o.customer_id "
                + "WHERE o.amount > 0 LIMIT 20");

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** JSqlParser 4.9 的 ISNULL/NOTNULL 方言节点必须按真实极性识别为全集覆盖。 */
    @Test
    void shouldWarnForIsnullAndNotnullComplement() throws Exception {
        assertPerformanceWarning(
                "SELECT amount FROM orders " + "WHERE amount ISNULL OR amount NOTNULL LIMIT 20",
                "SQL_UNFILTERED_SCAN_RISK");
    }

    /** 引用标识符需按方言保留大小写语义，同时识别小写 quoted/unquoted 的同一绑定。 */
    @Test
    void shouldUseDialectAwareIdentifierNormalization() throws Exception {
        for (String sql : List.of(
                "SELECT amount FROM orders WHERE \"amount\" = orders.amount LIMIT 20",
                "SELECT amount FROM orders WHERE \"amount\" IS NULL "
                        + "OR orders.amount IS NOT NULL LIMIT 20",
                "SELECT amount FROM orders WHERE `amount` = orders.amount LIMIT 20",
                "SELECT amount FROM orders WHERE `amount` IS NULL "
                        + "OR orders.amount IS NOT NULL LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
    }

    /** 无语义一元正号和数值字面量类型差异不能伪装成有效过滤或 JOIN。 */
    @Test
    void shouldNormalizeUnaryPlusAndExactNumericValues() throws Exception {
        for (String sql : List.of("SELECT amount FROM orders WHERE amount = +amount LIMIT 20",
                "SELECT amount FROM orders WHERE amount + 1 = amount + 1.0 LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            assertPerformanceWarning(sql, "SQL_UNFILTERED_SCAN_RISK");
        }
        org.mockito.Mockito.reset(semanticTranslator);
        assertPerformanceWarning(
                "SELECT a.id FROM a JOIN b "
                        + "ON a.id + b.id = +(a.id + b.id) WHERE a.id > 0 LIMIT 20",
                "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
    }

    /** 三分支关系析取覆盖整个有序域时，不得被当作有效过滤或连接条件。 */
    @Test
    void shouldWarnForMultiBranchRelationalUniverseCoverage() throws Exception {
        assertPerformanceWarning(
                "SELECT amount FROM orders "
                        + "WHERE amount < 0 OR amount = 0 OR amount > 0 LIMIT 20",
                "SQL_UNFILTERED_SCAN_RISK");
        org.mockito.Mockito.reset(semanticTranslator);
        assertPerformanceWarning("SELECT a.id FROM a JOIN b "
                + "ON a.id < b.id OR a.id = b.id OR a.id > b.id " + "WHERE a.id > 0 LIMIT 20",
                "SQL_JOIN_CONDITION_INEFFECTIVE_RISK");
        org.mockito.Mockito.reset(semanticTranslator);
        assertPerformanceWarning(
                "SELECT a.id FROM a OUTER APPLY "
                        + "(SELECT b.id FROM b WHERE b.a_id < a.id OR b.a_id = a.id "
                        + "OR b.a_id > a.id) q WHERE a.id > 0 LIMIT 20",
                "SQL_APPLY_UNCORRELATED_RISK");
    }

    /** 非全集区间以及每个 OR 分支都真实跨关系时仍应通过静态性能门禁。 */
    @Test
    void shouldPreserveSelectiveRelationalPartitions() throws Exception {
        assertPerformancePassed(
                "SELECT amount FROM orders " + "WHERE amount < 0 OR amount > 10 LIMIT 20");
        org.mockito.Mockito.reset(semanticTranslator);
        assertPerformancePassed("SELECT a.id FROM a JOIN b "
                + "ON a.id < b.id OR a.id > b.parent_id WHERE a.id > 0 LIMIT 20");
    }

    /** ParenthesedSelect 自身的 LIMIT 必须被识别为最终结果集的全局限制。 */
    @Test
    void shouldAcceptGlobalLimitOnParenthesedSelectRoots() throws Exception {
        for (String sql : List.of("(SELECT id FROM t) LIMIT 20",
                "(SELECT id FROM a UNION SELECT id FROM b) LIMIT 20")) {
            org.mockito.Mockito.reset(semanticTranslator);
            translateAs(sql);
            SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);
            assertThat(result.blockingItems()).isEmpty();
        }
    }

    /** 大 OFFSET 必须产生稳定风险，动态、负值和表达式 OFFSET 继续 fail-closed。 */
    @Test
    void shouldWarnForLargeOffsetAndRejectUnprovableOffset() throws Exception {
        assertPerformanceWarning("SELECT id FROM t WHERE id > 0 LIMIT 20 OFFSET 1000000000",
                "SQL_LARGE_OFFSET_RISK");
        org.mockito.Mockito.reset(semanticTranslator);
        assertPerformancePassed("SELECT id FROM t WHERE id > 0 LIMIT 20 OFFSET 100");
        for (String sql : List.of("SELECT id FROM t WHERE id > 0 LIMIT 20 OFFSET -1",
                "SELECT id FROM t WHERE id > 0 LIMIT 20 OFFSET ?",
                "SELECT id FROM t WHERE id > 0 LIMIT 20 OFFSET 1 + 1")) {
            org.mockito.Mockito.reset(semanticTranslator);
            translateAs(sql);
            SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);
            assertThat(result.blockingItems()).isNotEmpty();
        }
    }

    /** 断言单条 SQL 被静态性能检查归类为指定 warning，且不影响只读 SQL 检查通过。 */
    private void assertPerformanceWarning(String sql, String expectedCode) throws Exception {
        translateAs(sql);

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_WARNING);
        assertThat(result.warningItems()).extracting(ModelingValidationFinding::getCode)
                .containsExactly(expectedCode);
    }

    /** 断言单条 SQL 的静态性能门禁通过且没有附带 warning。 */
    private void assertPerformancePassed(String sql) throws Exception {
        translateAs(sql);

        SampleValidation result = service.validate(payload("订单金额是多少"), columns(), 1L, user, 20);

        assertThat(result.performanceResult().getStatus())
                .isEqualTo(ModelingDraftConstants.VALIDATION_PASSED);
        assertThat(result.warningItems()).isEmpty();
    }

    /** 配置 translator 返回指定 SQL；测试仍不会执行该 SQL。 */
    private void translateAs(String sql) throws Exception {
        doAnswer(invocation -> {
            QueryStatement statement = invocation.getArgument(0);
            statement.setSql(sql);
            return null;
        }).when(semanticTranslator).translate(any(QueryStatement.class));
    }

    /** 构造单模型单指标草稿。 */
    private ModelingDraftPayload payload(String question) {
        MetricDraft metric = metric("order_amount", "订单金额");

        ModelDraft model = new ModelDraft();
        model.setKey("orders_model");
        model.setName("orders_model");
        model.setBizName("订单");
        model.setBaseTable("orders");
        model.setDimensions(new ArrayList<>());
        model.setMetrics(new ArrayList<>(List.of(metric)));
        model.setSensitiveFields(new ArrayList<>());
        model.setSampleQuestions(new ArrayList<>(List.of(question)));

        ModelingDraftPayload payload = new ModelingDraftPayload();
        payload.setBusinessGoal("分析订单金额");
        payload.setModels(new ArrayList<>(List.of(model)));
        payload.setTerms(new ArrayList<>());
        payload.setUncertainties(new ArrayList<>());
        return payload;
    }

    /** 构造可被现有 RuleSqlParser 解析的单字段聚合指标。 */
    private MetricDraft metric(String key, String bizName) {
        MetricDraft metric = new MetricDraft();
        metric.setKey(key);
        metric.setName(key);
        metric.setBizName(bizName);
        metric.setField("amount");
        metric.setAggregation("SUM");
        metric.setExpression("SUM(amount)");
        metric.setAliases(new ArrayList<>());
        metric.setFilters(new ArrayList<>());
        return metric;
    }

    /** 构造真实字段元数据快照。 */
    private Map<String, List<DBColumn>> columns() {
        return Map.of("orders", List.of(new DBColumn("amount", "DECIMAL", "订单金额", null)));
    }

}
