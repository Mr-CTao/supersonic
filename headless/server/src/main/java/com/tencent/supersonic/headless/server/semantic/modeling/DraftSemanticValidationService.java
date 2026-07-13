package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.Text2SQLType;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.parser.QueryTypeParser;
import com.tencent.supersonic.headless.chat.parser.SemanticParser;
import com.tencent.supersonic.headless.chat.parser.rule.RuleSqlParser;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.pojo.SqlQuery;
import com.tencent.supersonic.headless.core.translator.SemanticTranslator;
import com.tencent.supersonic.headless.server.semantic.modeling.DraftSemanticSchemaFactory.DraftModelSemanticSchema;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.MetricDraft;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftPayload.ModelDraft;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 未发布草稿样例问法的真实语义解析和只读 SQL 验证服务。
 *
 * <p>
 * 职责说明：为每个草稿模型创建隔离内存 Schema，经专用 {@link DraftSemanticSchemaMapper} 映射后，复用 现有
 * {@link RuleSqlParser}/{@link QueryTypeParser} 生成 selected parse 与 S2SQL，再调用 Spring 中现有
 * DefaultSemanticTranslator 实现生成物理 SQL。服务只调用 translate，不调用 QueryExecutor 或数据源执行 接口；解析、翻译、LIMIT
 * 或只读检查任一步失败都 fail-closed 为发布阻塞项。对翻译结果的性能检查完全基于 JSqlParser AST，覆盖 WHERE 选择性、 JOIN/APPLY/LATERAL
 * 相关性、嵌套扫描和宽投影风险，不执行 SQL，也不读取数据库统计信息。
 * </p>
 *
 * <p>
 * 并发说明：parser 实例无请求状态，Schema、上下文、AST 比较状态和结果集合均为方法局部变量；不持有共享草稿状态， 因此 Spring 单例并发调用无需额外锁。现有
 * QueryManager 注册表只读使用，不在验证期间修改。
 * </p>
 */
@Service
@Slf4j
public class DraftSemanticValidationService {

    private static final String CATEGORY_SAMPLE = ModelingValidationGate.CHECK_SAMPLE_QUESTION;
    private static final String CATEGORY_SQL = ModelingValidationGate.CHECK_SQL_READ_ONLY;
    private static final String CATEGORY_PERFORMANCE =
            ModelingValidationGate.CHECK_PERFORMANCE_RISK;
    private static final String VALIDATION_MODE = "DRAFT_SEMANTIC_PIPELINE";
    private static final String RISK_CODE_CROSS_JOIN = "SQL_CROSS_JOIN_RISK";
    private static final String RISK_CODE_IMPLICIT_COMMA_JOIN = "SQL_IMPLICIT_COMMA_JOIN_RISK";
    private static final String RISK_CODE_JOIN_CONDITION_MISSING =
            "SQL_JOIN_CONDITION_MISSING_RISK";
    private static final String RISK_CODE_JOIN_CONDITION_INEFFECTIVE =
            "SQL_JOIN_CONDITION_INEFFECTIVE_RISK";
    private static final String RISK_CODE_NATURAL_JOIN_UNRESOLVED =
            "SQL_NATURAL_JOIN_UNRESOLVED_RISK";
    private static final String RISK_CODE_APPLY_UNCORRELATED = "SQL_APPLY_UNCORRELATED_RISK";
    private static final String RISK_CODE_LATERAL_UNCORRELATED = "SQL_LATERAL_UNCORRELATED_RISK";
    private static final String RISK_CODE_ALL_COLUMNS_PROJECTION =
            "SQL_ALL_COLUMNS_PROJECTION_RISK";
    private static final String RISK_CODE_TABLE_WILDCARD_PROJECTION =
            "SQL_TABLE_WILDCARD_PROJECTION_RISK";
    private static final String RISK_CODE_LARGE_OFFSET = "SQL_LARGE_OFFSET_RISK";
    private static final String SQL_BOOLEAN_LITERAL_TRUE = "TRUE";
    private static final String SQL_BOOLEAN_LITERAL_FALSE = "FALSE";
    private static final int MAX_PREVIEW_LENGTH = 4_000;
    private static final int MAX_STATIC_SQL_NESTING_DEPTH = 64;
    private static final int MAX_BOOLEAN_PROOF_NODES = 256;
    private static final int MAX_DIRECT_DISJUNCTS = 32;
    private final DatabaseService databaseService;
    private final DraftSemanticSchemaFactory schemaFactory;
    private final DraftSemanticSchemaMapper schemaMapper;
    private final SemanticTranslator semanticTranslator;
    private final ModelingSqlReadOnlyChecker sqlReadOnlyChecker;
    private final SemanticModelingSensitivityClassifier sensitivityClassifier;
    private final long maxSafePreviewOffset;
    private final SemanticParser ruleSqlParser = new RuleSqlParser();
    private final SemanticParser queryTypeParser = new QueryTypeParser();

    /**
     * 创建真实草稿语义验证服务。
     *
     * @param databaseService 仅用于读取已授权数据源方言信息，禁止执行查询。
     * @param schemaFactory 隔离草稿 Schema 工厂。
     * @param schemaMapper 隔离草稿语义映射器。
     * @param semanticTranslator 现有 DefaultSemanticTranslator bean。
     * @param sqlReadOnlyChecker 物理 SQL AST 只读检查器。
     * @param sensitivityClassifier 报告文本统一脱敏器。
     * @param properties 阶段 4 有界验证配置。
     */
    public DraftSemanticValidationService(DatabaseService databaseService,
            DraftSemanticSchemaFactory schemaFactory, DraftSemanticSchemaMapper schemaMapper,
            SemanticTranslator semanticTranslator, ModelingSqlReadOnlyChecker sqlReadOnlyChecker,
            SemanticModelingSensitivityClassifier sensitivityClassifier,
            SemanticModelingProperties properties) {
        this.databaseService = databaseService;
        this.schemaFactory = schemaFactory;
        this.schemaMapper = schemaMapper;
        this.semanticTranslator = semanticTranslator;
        this.sqlReadOnlyChecker = sqlReadOnlyChecker;
        this.sensitivityClassifier = sensitivityClassifier;
        // 解析后保存为只读值，运行期不接受动态扩大门禁边界。
        this.maxSafePreviewOffset = properties.resolveMaxSafePreviewOffset();
    }

    /**
     * 对所有模型样例问法执行真实 mapper、parser 和 translator 验证。
     *
     * <p>
     * 调用示例：{@code service.validate(payload, columns, dataSourceId, user, 20)}。返回的 SQL 仅为
     * 脱敏限长预览，服务不会连接数据源执行，也不会将 synthetic Schema 发布到正式语义层。
     * </p>
     *
     * @param payload 已通过阶段 3 校验的草稿。
     * @param columnsByTable 服务端重新读取的真实表字段。
     * @param dataSourceId 草稿数据源 ID。
     * @param user 当前管理员，用于再次复核数据源 ACL。
     * @param previewLimit parser 与 translator 的结果行数上限。
     * @return 样例结果、SQL 检查摘要和阻塞项。
     * @throws IllegalArgumentException 输入不完整时抛出；上层会转换为系统失败门禁报告。
     */
    public SampleValidation validate(ModelingDraftPayload payload,
            Map<String, List<DBColumn>> columnsByTable, Long dataSourceId, User user,
            int previewLimit) {
        if (payload == null || dataSourceId == null || user == null || previewLimit <= 0) {
            throw new IllegalArgumentException("草稿语义验证输入不完整");
        }
        DatabaseResp database = databaseService.getDatabase(dataSourceId, user);
        if (database == null || StringUtils.isBlank(database.getType())) {
            throw new IllegalArgumentException("草稿数据源不存在或缺少方言信息");
        }

        List<ModelingSampleQuestionResult> results = new ArrayList<>();
        List<ModelingValidationFinding> blockers = new ArrayList<>();
        List<ModelingValidationFinding> warnings = new ArrayList<>();
        int checked = 0;
        int passed = 0;
        int failed = 0;
        int performanceExpected = 0;
        int performanceChecked = 0;
        int performancePassed = 0;
        int performanceFailed = 0;
        int performanceWarnings = 0;
        List<ModelDraft> models = payload.getModels() == null ? List.of() : payload.getModels();
        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            ModelDraft model = models.get(modelIndex);
            List<MetricDraft> unsupportedFilters = unsupportedFilteredMetrics(model);
            checked += unsupportedFilters.size();
            failed += unsupportedFilters.size();
            for (MetricDraft metric : unsupportedFilters) {
                blockers.add(finding(CATEGORY_SQL, "METRIC_FILTER_TRANSLATION_UNSUPPORTED",
                        "$.models[" + model.getKey() + "].metrics[" + metric.getKey() + "].filters",
                        "指标包含当前隔离翻译器无法无损表达的结构化过滤口径，验证拒绝放行", model.getKey()));
            }
            List<String> questions =
                    model.getSampleQuestions() == null ? List.of() : model.getSampleQuestions();
            if (questions.isEmpty()) {
                checked++;
                failed++;
                performanceExpected++;
                blockers.add(finding(CATEGORY_SAMPLE, "SAMPLE_QUESTION_MISSING",
                        "$.models[" + model.getKey() + "].sampleQuestions",
                        "模型至少需要一个可通过真实语义链路验证的样例问法", model.getKey()));
                continue;
            }
            DraftModelSemanticSchema schema = schemaFactory.build(model, payload.getTerms(),
                    columnsFor(columnsByTable, model.getBaseTable()), database, modelIndex,
                    previewLimit);
            for (String question : questions) {
                checked++;
                performanceExpected++;
                QuestionValidation validation = validateQuestion(model, question, schema, user,
                        previewLimit, database.getType());
                results.add(validation.result());
                if (validation.passed()) {
                    passed++;
                } else {
                    failed++;
                }
                if (validation.finding() != null && blockers.stream()
                        .noneMatch(item -> sameFinding(item, validation.finding()))) {
                    blockers.add(validation.finding());
                }
                PerformanceInspection performance = validation.performance();
                if (performance.completed()) {
                    performanceChecked++;
                    if (ModelingDraftConstants.VALIDATION_FAILED.equals(performance.status())) {
                        performanceFailed++;
                    } else if (ModelingDraftConstants.VALIDATION_WARNING
                            .equals(performance.status())) {
                        performanceWarnings++;
                    } else {
                        performancePassed++;
                    }
                }
                if (validation.performanceFinding() != null) {
                    List<ModelingValidationFinding> target = ModelingDraftConstants.FINDING_BLOCKING
                            .equals(validation.performanceFinding().getSeverity()) ? blockers
                                    : warnings;
                    if (target.stream().noneMatch(
                            item -> sameFinding(item, validation.performanceFinding()))) {
                        target.add(validation.performanceFinding());
                    }
                }
            }
        }
        ModelingValidationCheckResult sqlResult = ModelingValidationCheckResult.builder()
                .category(CATEGORY_SQL).status(failed == 0 && checked > 0 ? "PASSED" : "FAILED")
                .summary(failed == 0 && checked > 0
                        ? "所有样例均经隔离语义 mapper/parser/translator 生成带 LIMIT 的只读 SQL，且未执行"
                        : "存在过滤口径无法无损表达、无法解析、无法翻译、LIMIT 非法或未通过只读 AST 检查的项")
                .checkedCount(checked).passedCount(passed).failedCount(failed).mode(VALIDATION_MODE)
                .build();
        ModelingValidationCheckResult performanceResult = performanceResult(performanceExpected,
                performanceChecked, performancePassed, performanceFailed, performanceWarnings);
        return new SampleValidation(List.copyOf(results), sqlResult,
                copyCheck(sqlResult, ModelingValidationGate.CHECK_SAMPLE_QUESTION, "样例问法命中与语义映射检查"),
                copyCheck(sqlResult, ModelingValidationGate.CHECK_SEMANTIC_SQL_GENERATION,
                        "S2SQL 与物理 SQL 生成检查"),
                copyCheck(sqlResult, ModelingValidationGate.CHECK_SQL_READ_ONLY,
                        "物理 SQL 单语句只读 AST 检查"),
                performanceResult, List.copyOf(blockers), List.copyOf(warnings));
    }

    /** 对单个样例执行隔离真实语义链路，任一步异常均转换为脱敏阻塞项。 */
    private QuestionValidation validateQuestion(ModelDraft model, String question,
            DraftModelSemanticSchema schema, User user, int previewLimit, String databaseType) {
        String safeQuestion = sensitivityClassifier.sanitizeText(question);
        try {
            ParseResp parseResp = parse(question, schema, user);
            List<SemanticParseInfo> selectedParses = parseResp.getSelectedParses();
            if (CollectionUtils.isEmpty(selectedParses)) {
                return failed(model, safeQuestion, "SAMPLE_QUESTION_PARSE_FAILED",
                        "样例问法未产生 selected parse，无法通过真实语义验证");
            }
            SemanticParseInfo selected = selectedParses.get(0);
            String s2sql = selected.getSqlInfo() == null ? null
                    : StringUtils.defaultIfBlank(selected.getSqlInfo().getCorrectedS2SQL(),
                            selected.getSqlInfo().getParsedS2SQL());
            if (StringUtils.isBlank(s2sql)) {
                return failed(model, safeQuestion, "S2SQL_NOT_AVAILABLE",
                        "selected parse 未生成有效 S2SQL");
            }
            if (containsSelectedMetricFilters(model, selected)) {
                // 现有 translator 不消费 MetricDefineParams.filterSql；静默忽略会把错误口径伪装为通过。
                return failedWithoutFinding(model, safeQuestion,
                        "命中指标包含结构化过滤口径，当前隔离翻译无法无损表达，验证拒绝放行");
            }
            String physicalSql = translate(selected, s2sql, schema, user, previewLimit);
            if (StringUtils.isBlank(physicalSql)) {
                return failed(model, safeQuestion, "SQL_TRANSLATION_FAILED", "现有语义翻译器未生成物理 SQL");
            }
            ModelingSqlReadOnlyChecker.CheckResult readOnly =
                    sqlReadOnlyChecker.validate(physicalSql, databaseType);
            if (!readOnly.readOnly()) {
                return failed(model, safeQuestion, readOnly.code(), "翻译后的 SQL 未通过单条只读查询检查");
            }
            SqlLimitValidation limitValidation = validateLimit(physicalSql, previewLimit);
            if (!limitValidation.valid()) {
                return failed(model, safeQuestion, limitValidation.code(),
                        limitValidation.message(), PerformanceInspection.failed(), null);
            }
            PerformanceInspection performance = inspectPerformanceRisk(physicalSql);
            ModelingValidationFinding performanceFinding =
                    performance.passed() ? null : performanceFinding(model, performance);
            ModelingSampleQuestionResult result = ModelingSampleQuestionResult.builder()
                    .modelKey(model.getKey()).question(safeQuestion).matched(true)
                    .matchedObjectKeys(matchedObjectKeys(selected)).validationMode(VALIDATION_MODE)
                    .s2sqlPreview(safePreview(s2sql)).sqlPreview(safePreview(physicalSql))
                    .readOnly(true).message("已通过隔离草稿语义链路和只读 AST 检查；SQL 未执行").build();
            return new QuestionValidation(result, null, true, performance, performanceFinding);
        } catch (Exception exception) {
            RuntimeException sanitized = new RuntimeException(
                    "Draft semantic validation failure type: " + exception.getClass().getName());
            sanitized.setStackTrace(exception.getStackTrace());
            // 只记录模型 key 和异常类型/堆栈，不记录 question、SQL 或可能含凭据的原始异常消息。
            log.warn("draft semantic pipeline failed: modelKey={}", model.getKey(), sanitized);
            return failed(model, safeQuestion, "SEMANTIC_PIPELINE_FAILED", "隔离语义解析或翻译失败，验证拒绝放行");
        }
    }

    /** 使用现有 RuleSqlParser 与 QueryTypeParser 生成真正的 selected parse。 */
    private ParseResp parse(String question, DraftModelSemanticSchema schema, User user) {
        QueryNLReq request = new QueryNLReq();
        request.setQueryText(question);
        request.setDataSetIds(Set.of(schema.dataSetId()));
        request.setText2SQLType(Text2SQLType.NONE);
        request.setUser(user);

        ChatQueryContext context = new ChatQueryContext(request);
        context.setSemanticSchema(schema.semanticSchema());
        context.setModelIdToDataSetIds(Map.of());
        schemaMapper.map(context);

        ParseResp response = new ParseResp(question);
        context.setParseResp(response);
        if (context.getMapInfo().isEmpty()) {
            response.setState(ParseResp.ParseState.FAILED);
            response.setErrorMsg("No draft semantic entities mapped");
            return response;
        }
        ruleSqlParser.parse(context);
        queryTypeParser.parse(context);
        List<SemanticParseInfo> selected = context.getCandidateQueries().stream()
                .map(query -> query.getParseInfo()).filter(Objects::nonNull).toList();
        response.setSelectedParses(selected);
        response.setState(
                selected.isEmpty() ? ParseResp.ParseState.FAILED : ParseResp.ParseState.COMPLETED);
        return response;
    }

    /** 调用现有语义翻译器；本方法不会调用任何 QueryExecutor。 */
    private String translate(SemanticParseInfo selected, String s2sql,
            DraftModelSemanticSchema schema, User user, int previewLimit) throws Exception {
        SqlQuery sqlQuery = new SqlQuery();
        sqlQuery.setSql(s2sql);
        // 与 S2SemanticLayerService.buildSqlQueryStatement 对齐，供 mergeOntologyQuery 替换内层表。
        sqlQuery.setTable(Constants.TABLE_PREFIX + schema.dataSetId());
        QueryStatement statement = new QueryStatement();
        statement.setDataSetId(schema.dataSetId());
        statement.setDataSetName(schema.dataSetName());
        statement.setSemanticSchema(schema.semanticSchemaResp());
        statement.setOntology(schema.ontology());
        statement.setSqlQuery(sqlQuery);
        statement.setIsS2SQL(true);
        statement.setEnableOptimize(true);
        statement.setLimit(previewLimit);
        statement.setUser(user);
        semanticTranslator.translate(statement);
        return statement.getSql();
    }

    /** 检查 selected parse 命中的草稿指标是否包含当前 translator 无法无损表达的过滤口径。 */
    private boolean containsSelectedMetricFilters(ModelDraft model, SemanticParseInfo selected) {
        Set<String> selectedKeys = selected.getMetrics().stream()
                .map(element -> element.getExtInfo()
                        .get(DraftSemanticSchemaMapper.EXT_DRAFT_OBJECT_KEY))
                .filter(String.class::isInstance).map(String.class::cast)
                .collect(java.util.stream.Collectors.toSet());
        return model.getMetrics() != null && model.getMetrics().stream()
                .filter(metric -> selectedKeys.contains(metric.getKey()))
                .anyMatch(metric -> metric.getFilters() != null && !metric.getFilters().isEmpty());
    }

    /** 返回模型内所有当前 translator 无法无损表达的过滤指标。 */
    private List<MetricDraft> unsupportedFilteredMetrics(ModelDraft model) {
        if (model.getMetrics() == null) {
            return List.of();
        }
        return model.getMetrics().stream()
                .filter(metric -> metric.getFilters() != null && !metric.getFilters().isEmpty())
                .toList();
    }

    /** 使用 JSqlParser AST 验证 LIMIT/FETCH 必须是确定的正整数且不超过本次预览上限。 */
    private SqlLimitValidation validateLimit(String sql, int previewLimit) {
        Select select = parseSelectPreservingRoot(sql);
        return validateSelectLimit(select, previewLimit);
    }

    /**
     * 解析并保留 ParenthesedSelect 根节点；公共 SqlSelectHelper 会剥离根括号并丢失挂在外层的 LIMIT/OFFSET。
     */
    private Select parseSelectPreservingRoot(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select select) {
                return select;
            }
            throw new IllegalArgumentException("SQL 根节点不是查询");
        } catch (Exception exception) {
            // 不传播 parser 原始消息，避免异常链携带 SQL 条件值。
            throw new IllegalArgumentException("SQL 无法安全解析");
        }
    }

    /**
     * 按 AST 结构验证结果集全局 LIMIT/FETCH；JSqlParser 4.9 会把 UNION 尾部全局 LIMIT 挂到最后一个未加括号的 PlainSelect，而
     * FETCH 挂在集合根节点，因此必须结合父集合和括号结构解释节点归属。
     */
    private SqlLimitValidation validateSelectLimit(Select select, int previewLimit) {
        if (select instanceof PlainSelect plainSelect) {
            return validateResultLimitNode(plainSelect.getLimit(), plainSelect.getFetch(),
                    plainSelect.getOffset(), previewLimit);
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            if (parenthesedSelect.getSelect() == null) {
                return SqlLimitValidation.invalid("SQL_LIMIT_INVALID", "翻译后的括号查询结构无法确定，验证拒绝放行");
            }
            // ParenthesedSelect 自身的 LIMIT/FETCH 约束最终结果集，必须优先于内部根节点检查。
            return parenthesedSelect.getLimit() == null && parenthesedSelect.getFetch() == null
                    ? validateSelectLimit(parenthesedSelect.getSelect(), previewLimit)
                    : validateResultLimitNode(parenthesedSelect.getLimit(),
                            parenthesedSelect.getFetch(), parenthesedSelect.getOffset(),
                            previewLimit);
        }
        if (select instanceof SetOperationList setOperationList
                && !CollectionUtils.isEmpty(setOperationList.getSelects())) {
            if (setOperationList.getLimit() != null || setOperationList.getFetch() != null) {
                return validateResultLimitNode(setOperationList.getLimit(),
                        setOperationList.getFetch(), setOperationList.getOffset(), previewLimit);
            }
            List<Select> branches = setOperationList.getSelects();
            Select lastBranch = branches.get(branches.size() - 1);
            // 未加括号的最后分支承载集合尾部 LIMIT；加括号则是分支自身 LIMIT，不能当作全局上限。
            if (!(lastBranch instanceof PlainSelect lastPlain) || lastPlain.getLimit() == null) {
                return SqlLimitValidation.invalid("SQL_LIMIT_MISSING",
                        "翻译后的集合查询缺少可证明的全局 LIMIT，验证拒绝放行");
            }
            for (int index = 0; index < branches.size() - 1; index++) {
                Select branch = branches.get(index);
                if (branch instanceof PlainSelect plainSelect
                        && (plainSelect.getLimit() != null || plainSelect.getFetch() != null)) {
                    return SqlLimitValidation.invalid("SQL_LIMIT_INVALID",
                            "集合分支与尾部 LIMIT 归属存在歧义，无法证明全局上限");
                }
            }
            return validateResultLimitNode(lastPlain.getLimit(), null, lastPlain.getOffset(),
                    previewLimit);
        }
        return SqlLimitValidation.invalid("SQL_LIMIT_MISSING",
                "翻译后的 SQL 缺少强制 LIMIT 或 FETCH，验证拒绝放行");
    }

    /** 同一结果层只能选择 LIMIT 或 FETCH；二者并存时归属含糊，必须拒绝。 */
    private SqlLimitValidation validateResultLimitNode(Limit limit, Fetch fetch,
            Offset selectOffset, int previewLimit) {
        if (limit != null && fetch != null) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID",
                    "翻译后的 SQL 同时包含 LIMIT 和 FETCH，无法证明唯一全局上限");
        }
        return fetch == null ? validateLimitNode(limit, selectOffset, previewLimit)
                : validateFetchNode(fetch, selectOffset, previewLimit);
    }

    /** 验证单个 LIMIT AST 节点，参数、表达式、零值、负值和超上限均 fail-closed。 */
    private SqlLimitValidation validateLimitNode(Limit limit, Offset selectOffset,
            int previewLimit) {
        if (limit == null) {
            return SqlLimitValidation.invalid("SQL_LIMIT_MISSING", "翻译后的 SQL 缺少强制 LIMIT，验证拒绝放行");
        }
        Expression rowCount = limit.getRowCount();
        if (!(rowCount instanceof LongValue longValue)) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID",
                    "翻译后的 SQL LIMIT 必须是确定的正整数，验证拒绝放行");
        }
        long value = longValue.getValue();
        if (value <= 0) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID", "翻译后的 SQL LIMIT 必须大于零，验证拒绝放行");
        }
        if (value > previewLimit) {
            return SqlLimitValidation.invalid("SQL_LIMIT_EXCEEDED",
                    "翻译后的 SQL LIMIT 超过本次预览上限，验证拒绝放行");
        }
        Expression offset = selectOffset == null ? limit.getOffset() : selectOffset.getOffset();
        if (selectOffset != null && limit.getOffset() != null) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID", "翻译后的 SQL OFFSET 归属存在歧义，验证拒绝放行");
        }
        return validateOffsetNode(offset);
    }

    /**
     * 验证原生 FETCH AST；只接受绝对行数与 ROW(S) ONLY，百分比和 WITH TIES 都不能证明硬上限。
     */
    private SqlLimitValidation validateFetchNode(Fetch fetch, Offset selectOffset,
            int previewLimit) {
        List<String> parameters = fetch.getFetchParameters() == null ? List.of()
                : fetch.getFetchParameters().stream().filter(Objects::nonNull)
                        .map(value -> value.toUpperCase(Locale.ROOT)).toList();
        boolean rowOnly = parameters.equals(List.of("ROW", "ONLY"));
        boolean rowsOnly = parameters.equals(List.of("ROWS", "ONLY"));
        if (!rowOnly && !rowsOnly) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID",
                    "翻译后的 SQL FETCH 必须使用确定行数和 ONLY，验证拒绝放行");
        }

        Expression rowCount = fetch.getExpression();
        long value;
        if (rowCount == null && rowOnly) {
            // SQL 标准省略数量的单数 ROW 形式语义明确为 1，AST 参数可直接证明该边界。
            value = 1L;
        } else if (rowCount instanceof LongValue longValue) {
            value = longValue.getValue();
        } else {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID",
                    "翻译后的 SQL FETCH 必须是确定的正整数，验证拒绝放行");
        }
        if (value <= 0) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID", "翻译后的 SQL FETCH 必须大于零，验证拒绝放行");
        }
        if (value > previewLimit) {
            return SqlLimitValidation.invalid("SQL_LIMIT_EXCEEDED",
                    "翻译后的 SQL FETCH 超过本次预览上限，验证拒绝放行");
        }
        return validateOffsetNode(selectOffset == null ? null : selectOffset.getOffset());
    }

    /** OFFSET 必须是可静态证明的非负整数字面量；大值风险由性能门禁统一产生 WARNING。 */
    private SqlLimitValidation validateOffsetNode(Expression offset) {
        if (offset != null
                && (!(offset instanceof LongValue longOffset) || longOffset.getValue() < 0)) {
            return SqlLimitValidation.invalid("SQL_LIMIT_INVALID",
                    "翻译后的 SQL OFFSET 必须是确定的非负整数，验证拒绝放行");
        }
        return SqlLimitValidation.success();
    }

    /**
     * 在不执行 SQL、也不虚构表行数的前提下检查可静态确认的性能风险。
     *
     * <p>
     * 全局 LIMIT 已由前置 AST 门禁保证。集合查询、笛卡尔积、宽投影和无过滤物理表扫描仍可能在返回少量结果前处理大量数据， 因此标记为 WARNING；投影明确、连接条件完整且具有
     * WHERE/HAVING 的普通查询可静态通过。
     * </p>
     */
    private PerformanceInspection inspectPerformanceRisk(String sql) {
        PerformanceRiskScan scan = new PerformanceRiskScan();
        Select select = parseSelectPreservingRoot(sql);
        scan.largeOffset = hasLargeGlobalOffset(select);
        scanPerformanceRisk(select, 0, scan);
        return scan.toInspection();
    }

    /**
     * 从真正限制最终结果集的根节点提取 OFFSET；表达式或参数已由 LIMIT 门禁 fail-closed，此处只产生性能 WARNING。
     */
    private boolean hasLargeGlobalOffset(Select select) {
        Expression offset = globalOffsetExpression(select);
        return offset instanceof LongValue value && value.getValue() > maxSafePreviewOffset;
    }

    /** 解析 PlainSelect、括号根和集合尾部的全局 OFFSET，分支 OFFSET 不冒充全局偏移。 */
    private Expression globalOffsetExpression(Select select) {
        if (select == null) {
            return null;
        }
        if (select.getOffset() != null) {
            return select.getOffset().getOffset();
        }
        if (select.getLimit() != null && select.getLimit().getOffset() != null) {
            return select.getLimit().getOffset();
        }
        if (select instanceof ParenthesedSelect parenthesedSelect && select.getLimit() == null) {
            return globalOffsetExpression(parenthesedSelect.getSelect());
        }
        if (select instanceof SetOperationList setOperationList
                && !CollectionUtils.isEmpty(setOperationList.getSelects())) {
            Select last =
                    setOperationList.getSelects().get(setOperationList.getSelects().size() - 1);
            return last instanceof PlainSelect ? globalOffsetExpression(last) : null;
        }
        return null;
    }

    /** 递归检查当前 SELECT、WITH 定义、集合分支、JOIN/派生表和标量子查询。 */
    private void scanPerformanceRisk(Select select, int depth, PerformanceRiskScan scan) {
        scanPerformanceRisk(select, depth, scan, Set.of());
    }

    /** 递归检查 SELECT，并仅为 APPLY/LATERAL 等语法允许相关引用的场景携带外层可见关系。 */
    private void scanPerformanceRisk(Select select, int depth, PerformanceRiskScan scan,
            Set<String> outerRelations) {
        if (select == null || scan == null) {
            if (scan != null) {
                scan.unresolved = true;
            }
            return;
        }
        if (depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            // 防止恶意深层 AST 耗尽线程栈；无法完成全量遍历时必须保守告警。
            scan.unresolved = true;
            return;
        }
        if (!CollectionUtils.isEmpty(select.getWithItemsList())) {
            select.getWithItemsList().forEach(
                    withItem -> scanPerformanceRisk(withItem.getSelect(), depth + 1, scan));
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            scanPerformanceRisk(parenthesedSelect.getSelect(), depth + 1, scan, outerRelations);
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            scan.setOperation = true;
            if (CollectionUtils.isEmpty(setOperationList.getSelects())) {
                scan.unresolved = true;
                return;
            }
            setOperationList.getSelects().forEach(
                    branch -> scanPerformanceRisk(branch, depth + 1, scan, outerRelations));
            return;
        }
        if (!(select instanceof PlainSelect plainSelect)) {
            scan.unresolved = true;
            return;
        }

        ExpressionScope filterScope = selectExpressionScope(plainSelect, outerRelations);
        if (plainSelect.getFromItem() != null
                && !hasEffectiveBaseFilter(plainSelect.getWhere(), filterScope)) {
            // HAVING 在聚合后才执行，不能据此声称基础表扫描已被约束。
            if (depth == 0) {
                scan.unfilteredTopLevel = true;
            } else {
                scan.unfilteredNested = true;
            }
        }
        scanFromItem(plainSelect.getFromItem(), depth + 1, scan, outerRelations);
        scanJoinSequence(plainSelect.getFromItem(), plainSelect.getJoins(), depth + 1, scan,
                outerRelations);
        if (!CollectionUtils.isEmpty(plainSelect.getSelectItems())) {
            plainSelect.getSelectItems().forEach(item -> {
                Expression projection = item.getExpression();
                if (projection instanceof AllTableColumns) {
                    scan.tableWildcardProjection = true;
                } else if (projection instanceof AllColumns) {
                    scan.allColumnsProjection = true;
                }
                scanExpression(projection, depth + 1, scan);
            });
        }
        scanExpression(plainSelect.getWhere(), depth + 1, scan);
        scanExpression(plainSelect.getHaving(), depth + 1, scan);
        scanExpression(plainSelect.getQualify(), depth + 1, scan);
        if (plainSelect.getGroupBy() != null
                && plainSelect.getGroupBy().getGroupByExpressions() != null) {
            for (Object groupExpression : plainSelect.getGroupBy().getGroupByExpressions()) {
                if (groupExpression instanceof Expression expression) {
                    scanExpression(expression, depth + 1, scan);
                } else {
                    scan.unresolved = true;
                }
            }
        }
        if (!CollectionUtils.isEmpty(plainSelect.getOrderByElements())) {
            plainSelect.getOrderByElements()
                    .forEach(item -> scanExpression(item.getExpression(), depth + 1, scan));
        }
    }

    /** 遍历 FROM 项，并仅在调用方语法允许相关引用时携带外层关系作用域。 */
    private void scanFromItem(FromItem fromItem, int depth, PerformanceRiskScan scan,
            Set<String> outerRelations) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            scanPerformanceRisk(parenthesedSelect.getSelect(), depth, scan, outerRelations);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
            scanFromItem(parenthesedFromItem.getFromItem(), depth, scan, outerRelations);
            scanJoinSequence(parenthesedFromItem.getFromItem(), parenthesedFromItem.getJoins(),
                    depth, scan, outerRelations);
            return;
        }
        if (fromItem instanceof TableFunction tableFunction) {
            scanExpression(tableFunction.getFunction(), depth, scan);
        }
    }

    /**
     * 按 SQL 左结合顺序维护本层与合法相关查询继承的可见关系。
     *
     * <p>
     * APPLY 与 LATERAL 的右侧可以读取当前左侧关系，因此递归扫描其内部 JOIN 时必须携带外层作用域；普通派生表仍由调用方传入空集合，避免把非法外层引用误判为已绑定。
     * </p>
     */
    private void scanJoinSequence(FromItem baseFromItem, List<Join> joins, int depth,
            PerformanceRiskScan scan, Set<String> outerRelations) {
        if (CollectionUtils.isEmpty(joins)) {
            return;
        }
        Set<String> visibleLeftRelations = new LinkedHashSet<>(outerRelations);
        visibleLeftRelations.addAll(relationQualifiers(baseFromItem));
        ExpressionScope visibleLeftScope = ExpressionScope.fromQualifiers(outerRelations)
                .append(expressionScope(baseFromItem));
        for (Join join : joins) {
            scanJoin(join, visibleLeftRelations, visibleLeftScope, depth, scan);
            if (join != null) {
                visibleLeftRelations.addAll(relationQualifiers(join.getRightItem()));
                visibleLeftScope = visibleLeftScope.append(expressionScope(join.getRightItem()));
            }
        }
    }

    /**
     * 检查连接类型、左右作用域和条件完整性，并继续递归扫描右侧派生表及 ON 表达式。
     *
     * <p>
     * JSqlParser 4.9 将 APPLY 表示为 {@link Join#isApply()}，却将 PostgreSQL LATERAL 表示为右侧
     * {@link LateralSubSelect}；CROSS JOIN LATERAL 还会同时设置 cross 标记。两者都允许右侧读取当前左侧， 但必须先证明真实相关性，不能因
     * LATERAL/APPLY 关键字本身豁免笛卡尔积风险。
     * </p>
     */
    private void scanJoin(Join join, Set<String> leftRelations, ExpressionScope leftScope,
            int depth, PerformanceRiskScan scan) {
        if (join == null) {
            scan.unresolved = true;
            return;
        }
        Set<String> rightRelations = relationQualifiers(join.getRightItem());
        ExpressionScope rightScope = expressionScope(join.getRightItem());
        boolean lateral = join.getRightItem() instanceof LateralSubSelect;
        boolean rightItemCanSeeLeftRelations = join.isApply() || lateral;
        if (lateral) {
            boolean correlated = hasRightItemCorrelation(join.getRightItem(), leftRelations);
            if (!correlated) {
                scan.lateralUncorrelated = true;
            } else if (join.isNatural()) {
                // 无列元数据时无法证明 NATURAL LATERAL 存在公共列，保持既有保守风险语义。
                scan.naturalJoinUnresolved = true;
            } else if (!join.isCross() && CollectionUtils.isEmpty(join.getOnExpressions())
                    && CollectionUtils.isEmpty(join.getUsingColumns())) {
                // 相关性只约束右侧执行，不能替代非 CROSS LATERAL 必需的外层连接限定。
                scan.joinConditionMissing = true;
            } else if (!hasOnlyKnownJoinConditionReferences(join, leftRelations, rightRelations)) {
                // LATERAL 子查询相关并不代表外层 ON 中的幽灵别名合法，仍需 fail-closed。
                scan.joinConditionIneffective = true;
            }
        } else if (join.isApply()) {
            if (!hasRightItemCorrelation(join.getRightItem(), leftRelations)) {
                scan.applyUncorrelated = true;
            }
        } else if (join.isCross()) {
            scan.crossJoin = true;
        } else if (join.isSimple()) {
            // JSqlParser 4.9 使用 simple join 表示 FROM a, b；即使 WHERE 看似有关联条件也保守告警。
            scan.implicitCommaJoin = true;
        } else if (join.isNatural()) {
            // 当前扫描没有可靠列绑定可证明公共列；NATURAL 无公共列时会退化为笛卡尔积。
            scan.naturalJoinUnresolved = true;
        } else {
            if (CollectionUtils.isEmpty(join.getOnExpressions())
                    && CollectionUtils.isEmpty(join.getUsingColumns())) {
                scan.joinConditionMissing = true;
            } else if (CollectionUtils.isEmpty(join.getUsingColumns())
                    && !hasEffectiveJoinConstraint(join, leftRelations, rightRelations,
                            leftScope.append(rightScope))) {
                // ON 节点存在不代表连接受约束；恒真或无法关联右侧关系时必须保守告警。
                scan.joinConditionIneffective = true;
            }
        }
        scanFromItem(join.getRightItem(), depth, scan,
                rightItemCanSeeLeftRelations ? leftRelations : Set.of());
        if (!CollectionUtils.isEmpty(join.getOnExpressions())) {
            join.getOnExpressions().forEach(expression -> scanExpression(expression, depth, scan));
        }
    }

    /** LATERAL 的外层 ON 只能引用当前左侧与右侧输出别名；TRUE 等无列常量仍允许继续处理。 */
    private boolean hasOnlyKnownJoinConditionReferences(Join join, Set<String> leftRelations,
            Set<String> rightRelations) {
        if (CollectionUtils.isEmpty(join.getOnExpressions())) {
            return true;
        }
        Set<String> knownRelations = new LinkedHashSet<>(leftRelations);
        knownRelations.addAll(rightRelations);
        return join.getOnExpressions().stream()
                .allMatch(expression -> hasOnlyKnownRelationReferences(expression, knownRelations));
    }

    /** WHERE 必须能按布尔分支证明存在有效约束，不能仅因任意位置出现列引用就通过。 */
    private boolean hasEffectiveBaseFilter(Expression where, ExpressionScope visibleScope) {
        return guaranteesEffectiveFilter(where, visibleScope);
    }

    /**
     * 按 SQL 布尔结构证明过滤有效性。
     *
     * <p>
     * AND 任一可执行分支有约束即可缩小结果；OR 的每个可满足分支都必须有约束。无法常量折叠且不依赖运行时列的分支保持 fail-closed。
     * </p>
     */
    private boolean guaranteesEffectiveFilter(Expression expression, ExpressionScope visibleScope) {
        return guaranteesEffectiveFilter(expression, visibleScope, 0, new BooleanProofBudget());
    }

    /** 使用有界递归证明单个布尔节点是否限制基础表命中行。 */
    private boolean guaranteesEffectiveFilter(Expression expression, ExpressionScope visibleScope,
            int depth, BooleanProofBudget budget) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH || !budget.consume()) {
            return false;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return guaranteesEffectiveFilter(parenthesis.getExpression(), visibleScope, depth + 1,
                    budget);
        }
        if (expression instanceof AndExpression andExpression) {
            return guaranteesEffectiveFilter(andExpression.getLeftExpression(), visibleScope,
                    depth + 1, budget)
                    || guaranteesEffectiveFilter(andExpression.getRightExpression(), visibleScope,
                            depth + 1, budget);
        }
        if (expression instanceof OrExpression orExpression) {
            ComplementaryCheck complementary =
                    checkComplementaryDisjunction(expression, visibleScope, depth);
            if (complementary != ComplementaryCheck.ABSENT) {
                // 明确互补或无法在分支上限内完成证明时，都不能声称基础扫描具有选择性。
                return false;
            }
            return guaranteesEffectiveFilter(orExpression.getLeftExpression(), visibleScope,
                    depth + 1, budget)
                    && guaranteesEffectiveFilter(orExpression.getRightExpression(), visibleScope,
                            depth + 1, budget);
        }
        StaticTruth truth = evaluateStaticTruth(expression, depth);
        if (truth == StaticTruth.FALSE) {
            // 常量 FALSE 可直接折叠为空结果，不会触发基础表全量处理。
            return true;
        }
        if (truth == StaticTruth.TRUE) {
            return false;
        }
        return isProvablyEffectiveAtomicPredicate(expression, visibleScope);
    }

    /** 仅白名单关系比较和直接列 NULL 判断可作为原子过滤证明，其他 AST 节点保持 fail-closed。 */
    private boolean isProvablyEffectiveAtomicPredicate(Expression expression,
            ExpressionScope visibleScope) {
        if (isSupportedRelationalPredicate(expression)) {
            return hasStructurallyDistinctRelationalOperands(expression, visibleScope)
                    && hasBoundRuntimeColumnReference(expression, visibleScope);
        }
        if (expression instanceof IsNullExpression isNullExpression) {
            Expression operand = unwrapParentheses(isNullExpression.getLeftExpression(), 0);
            return operand instanceof Column
                    && hasBoundRuntimeColumnReference(operand, visibleScope);
        }
        return false;
    }

    /** 只剥离有界括号；深度耗尽返回 null，调用方据此保守拒绝未知结构。 */
    private Expression unwrapParentheses(Expression expression, int depth) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            return null;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return unwrapParentheses(parenthesis.getExpression(), depth + 1);
        }
        return expression;
    }

    /**
     * 判断关系谓词的左右 AST 是否已被证明为不同结构。
     *
     * <p>
     * 结构相同的运行时表达式即使同时包含多个关系别名，也可能只是 {@code X = X} 或 {@code X >= X}；SQL NULL 三值逻辑又使其无法统一折叠为 Java
     * 布尔常量。因此只有明确得到 DIFFERENT 才能作为 WHERE、JOIN 或相关性证明， EQUIVALENT 与无法完整比较的 UNKNOWN 均保守告警。
     * </p>
     */
    private boolean hasStructurallyDistinctRelationalOperands(Expression expression,
            ExpressionScope visibleScope) {
        if (!(expression instanceof BinaryExpression binaryExpression)) {
            return false;
        }
        return compareExpressionStructure(binaryExpression.getLeftExpression(),
                binaryExpression.getRightExpression(),
                visibleScope) == ExpressionStructure.DIFFERENT;
    }

    /** 检测直接 OR 析取项中的 NULL 或关系运算互补对；超出分支上限时返回 UNKNOWN。 */
    private ComplementaryCheck checkComplementaryDisjunction(Expression expression,
            ExpressionScope visibleScope, int depth) {
        List<Expression> disjuncts = new ArrayList<>();
        if (!collectDirectDisjuncts(expression, disjuncts, depth)) {
            return ComplementaryCheck.UNKNOWN;
        }
        if (disjuncts.size() < 2) {
            return ComplementaryCheck.ABSENT;
        }
        List<Expression> reducedDisjuncts = new ArrayList<>(disjuncts.size());
        for (Expression disjunct : disjuncts) {
            // 每个分支只规约一次，避免两两比较时重复遍历同一 AST 子树。
            reducedDisjuncts.add(reduceBooleanIdentity(disjunct, 0));
        }
        for (int leftIndex = 0; leftIndex < reducedDisjuncts.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < reducedDisjuncts
                    .size(); rightIndex++) {
                if (areComplementaryPredicates(reducedDisjuncts.get(leftIndex),
                        reducedDisjuncts.get(rightIndex), visibleScope)) {
                    return ComplementaryCheck.PRESENT;
                }
            }
        }
        ComplementaryCheck domainCoverage =
                checkRelationalDomainCoverage(reducedDisjuncts, visibleScope);
        if (domainCoverage != ComplementaryCheck.ABSENT) {
            return domainCoverage;
        }
        return ComplementaryCheck.ABSENT;
    }

    /**
     * 证明同一对操作数上的多个关系分支是否覆盖小于、等于和大于三个有序域分区。 任一分支不是可绑定的关系谓词时返回 UNKNOWN，防止 AND 子树或未知节点被错误展开。
     */
    private ComplementaryCheck checkRelationalDomainCoverage(List<Expression> disjuncts,
            ExpressionScope visibleScope) {
        if (disjuncts.size() < 3 || disjuncts.size() > MAX_DIRECT_DISJUNCTS) {
            return ComplementaryCheck.ABSENT;
        }
        if (!(disjuncts.get(0)instanceof BinaryExpression baseline)) {
            return ComplementaryCheck.UNKNOWN;
        }
        int coveredPartitions = 0;
        for (Expression disjunct : disjuncts) {
            if (!(disjunct instanceof BinaryExpression binary)) {
                return ComplementaryCheck.UNKNOWN;
            }
            RelationalOperator operator = relationalOperator(disjunct);
            if (operator == null) {
                return ComplementaryCheck.UNKNOWN;
            }
            boolean sameDirection = equivalentExpressionPair(baseline.getLeftExpression(),
                    baseline.getRightExpression(), binary.getLeftExpression(),
                    binary.getRightExpression(), visibleScope);
            boolean reverseDirection = equivalentExpressionPair(baseline.getLeftExpression(),
                    baseline.getRightExpression(), binary.getRightExpression(),
                    binary.getLeftExpression(), visibleScope);
            if (!sameDirection && !reverseDirection) {
                return ComplementaryCheck.UNKNOWN;
            }
            coveredPartitions |=
                    relationPartitionMask(reverseDirection ? operator.reverse() : operator);
        }
        return coveredPartitions == 0b111 ? ComplementaryCheck.PRESENT : ComplementaryCheck.ABSENT;
    }

    /** 把关系运算符映射为小于、等于、大于三个互斥分区的位掩码。 */
    private int relationPartitionMask(RelationalOperator operator) {
        return switch (operator) {
            case LESS_THAN -> 0b001;
            case EQUALS -> 0b010;
            case GREATER_THAN -> 0b100;
            case LESS_THAN_OR_EQUALS -> 0b011;
            case NOT_EQUALS -> 0b101;
            case GREATER_THAN_OR_EQUALS -> 0b110;
        };
    }

    /**
     * 仅按 OR 结合律展开直接析取项；AND 子树保持原子项，避免把 {@code X IS NULL OR (X IS NOT NULL AND Y > 0)} 误判为恒真。
     */
    private boolean collectDirectDisjuncts(Expression expression, List<Expression> disjuncts,
            int depth) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH
                || disjuncts.size() >= MAX_DIRECT_DISJUNCTS) {
            return false;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return collectDirectDisjuncts(parenthesis.getExpression(), disjuncts, depth + 1);
        }
        if (expression instanceof OrExpression orExpression) {
            return collectDirectDisjuncts(orExpression.getLeftExpression(), disjuncts, depth + 1)
                    && collectDirectDisjuncts(orExpression.getRightExpression(), disjuncts,
                            depth + 1);
        }
        disjuncts.add(expression);
        return true;
    }

    /** 在不改写原 AST 的前提下剥离括号及 AND TRUE、OR FALSE 等布尔单位元。 */
    private Expression reduceBooleanIdentity(Expression expression, int depth) {
        return reduceBooleanIdentity(expression, depth, new BooleanProofBudget());
    }

    /** 在单次局部节点预算内规约布尔单位元；预算耗尽时返回 null 并保持 fail-closed。 */
    private Expression reduceBooleanIdentity(Expression expression, int depth,
            BooleanProofBudget budget) {
        if (!budget.consume()) {
            return null;
        }
        Expression current = unwrapParentheses(expression, depth);
        if (current == null) {
            return null;
        }
        if (current instanceof AndExpression andExpression) {
            StaticTruth leftTruth =
                    evaluateStaticTruth(andExpression.getLeftExpression(), depth + 1, budget);
            StaticTruth rightTruth =
                    evaluateStaticTruth(andExpression.getRightExpression(), depth + 1, budget);
            if (leftTruth == StaticTruth.FALSE || rightTruth == StaticTruth.FALSE) {
                return null;
            }
            if (leftTruth == StaticTruth.TRUE) {
                return reduceBooleanIdentity(andExpression.getRightExpression(), depth + 1, budget);
            }
            if (rightTruth == StaticTruth.TRUE) {
                return reduceBooleanIdentity(andExpression.getLeftExpression(), depth + 1, budget);
            }
        }
        if (current instanceof OrExpression orExpression) {
            StaticTruth leftTruth =
                    evaluateStaticTruth(orExpression.getLeftExpression(), depth + 1, budget);
            StaticTruth rightTruth =
                    evaluateStaticTruth(orExpression.getRightExpression(), depth + 1, budget);
            if (leftTruth == StaticTruth.TRUE || rightTruth == StaticTruth.TRUE) {
                return null;
            }
            if (leftTruth == StaticTruth.FALSE) {
                return reduceBooleanIdentity(orExpression.getRightExpression(), depth + 1, budget);
            }
            if (rightTruth == StaticTruth.FALSE) {
                return reduceBooleanIdentity(orExpression.getLeftExpression(), depth + 1, budget);
            }
        }
        return current;
    }

    /** 比较规约后的两个析取项是否构成已知互补谓词。 */
    private boolean areComplementaryPredicates(Expression first, Expression second,
            ExpressionScope visibleScope) {
        if (first == null || second == null) {
            return false;
        }
        return areComplementaryNullPredicates(first, second, visibleScope)
                || areComplementaryRelationalPredicates(first, second, visibleScope);
    }

    /** SQL 的 IS NULL/IS NOT NULL 结果本身不为 NULL，互补对覆盖同一输入的全部取值。 */
    private boolean areComplementaryNullPredicates(Expression first, Expression second,
            ExpressionScope visibleScope) {
        if (!(first instanceof IsNullExpression firstNull)
                || !(second instanceof IsNullExpression secondNull)) {
            return false;
        }
        NullPredicatePolarity firstPolarity = nullPredicatePolarity(firstNull);
        NullPredicatePolarity secondPolarity = nullPredicatePolarity(secondNull);
        if (firstPolarity == NullPredicatePolarity.UNKNOWN
                || secondPolarity == NullPredicatePolarity.UNKNOWN
                || firstPolarity == secondPolarity) {
            return false;
        }
        return compareExpressionStructure(firstNull.getLeftExpression(),
                secondNull.getLeftExpression(), visibleScope) == ExpressionStructure.EQUIVALENT;
    }

    /**
     * JSqlParser 4.9 分别用 not、useIsNull、useNotNull 表示四种 NULL 谓词，不能只读取 isNot。
     */
    private NullPredicatePolarity nullPredicatePolarity(IsNullExpression expression) {
        if (expression.isUseNotNull()) {
            return NullPredicatePolarity.NOT_NULL;
        }
        if (expression.isUseIsNull()) {
            return expression.isNot() ? NullPredicatePolarity.NOT_NULL : NullPredicatePolarity.NULL;
        }
        return expression.isNot() ? NullPredicatePolarity.NOT_NULL : NullPredicatePolarity.NULL;
    }

    /** 识别相同操作数上的关系运算互补对，并兼容第二个谓词左右操作数交换。 */
    private boolean areComplementaryRelationalPredicates(Expression first, Expression second,
            ExpressionScope visibleScope) {
        if (!(first instanceof BinaryExpression firstBinary)
                || !(second instanceof BinaryExpression secondBinary)) {
            return false;
        }
        RelationalOperator firstOperator = relationalOperator(first);
        RelationalOperator secondOperator = relationalOperator(second);
        if (firstOperator == null || secondOperator == null) {
            return false;
        }
        boolean sameDirection = equivalentExpressionPair(firstBinary.getLeftExpression(),
                firstBinary.getRightExpression(), secondBinary.getLeftExpression(),
                secondBinary.getRightExpression(), visibleScope);
        if (sameDirection && firstOperator.complement() == secondOperator) {
            return true;
        }
        boolean reversedDirection = equivalentExpressionPair(firstBinary.getLeftExpression(),
                firstBinary.getRightExpression(), secondBinary.getRightExpression(),
                secondBinary.getLeftExpression(), visibleScope);
        return reversedDirection && firstOperator.complement() == secondOperator.reverse();
    }

    /** 两组有序操作数都必须安全证明等价，UNKNOWN 不能用于互补证明。 */
    private boolean equivalentExpressionPair(Expression firstLeft, Expression firstRight,
            Expression secondLeft, Expression secondRight, ExpressionScope visibleScope) {
        return compareExpressionStructure(firstLeft, secondLeft,
                visibleScope) == ExpressionStructure.EQUIVALENT
                && compareExpressionStructure(firstRight, secondRight,
                        visibleScope) == ExpressionStructure.EQUIVALENT;
    }

    /** 把白名单关系 AST 类型映射为结构化运算符，不读取表达式文本。 */
    private RelationalOperator relationalOperator(Expression expression) {
        if (expression instanceof EqualsTo) {
            return RelationalOperator.EQUALS;
        }
        if (expression instanceof NotEqualsTo) {
            return RelationalOperator.NOT_EQUALS;
        }
        if (expression instanceof GreaterThan) {
            return RelationalOperator.GREATER_THAN;
        }
        if (expression instanceof GreaterThanEquals) {
            return RelationalOperator.GREATER_THAN_OR_EQUALS;
        }
        if (expression instanceof MinorThan) {
            return RelationalOperator.LESS_THAN;
        }
        if (expression instanceof MinorThanEquals) {
            return RelationalOperator.LESS_THAN_OR_EQUALS;
        }
        return null;
    }

    /**
     * 对两个表达式执行有界 AST 结构比较；括号差异被忽略，未知节点不借助 SQL 文本猜测。
     *
     * <p>
     * 该结果只在当前方法栈内参与门禁判断，不写入 finding、日志或 API，避免暴露表名、字段名和条件值。
     * </p>
     */
    private ExpressionStructure compareExpressionStructure(Expression left, Expression right,
            ExpressionScope visibleScope) {
        return compareExpressionStructure(left, right, visibleScope, 0, new BooleanProofBudget());
    }

    /** 递归比较表达式节点并共享局部预算，深度或节点数耗尽时返回 UNKNOWN。 */
    private ExpressionStructure compareExpressionStructure(Expression left, Expression right,
            ExpressionScope visibleScope, int depth, BooleanProofBudget budget) {
        if (depth > MAX_STATIC_SQL_NESTING_DEPTH || !budget.consume()) {
            return ExpressionStructure.UNKNOWN;
        }
        if (left == right) {
            return ExpressionStructure.EQUIVALENT;
        }
        if (left == null || right == null) {
            return ExpressionStructure.DIFFERENT;
        }
        if (left instanceof Parenthesis parenthesis) {
            return compareExpressionStructure(parenthesis.getExpression(), right, visibleScope,
                    depth + 1, budget);
        }
        if (right instanceof Parenthesis parenthesis) {
            return compareExpressionStructure(left, parenthesis.getExpression(), visibleScope,
                    depth + 1, budget);
        }
        if (left instanceof SignedExpression signed && signed.getSign() == '+') {
            return compareExpressionStructure(signed.getExpression(), right, visibleScope,
                    depth + 1, budget);
        }
        if (right instanceof SignedExpression signed && signed.getSign() == '+') {
            return compareExpressionStructure(left, signed.getExpression(), visibleScope, depth + 1,
                    budget);
        }
        ExpressionStructure numericComparison = compareNumericLiteralStructure(left, right);
        if (numericComparison != ExpressionStructure.UNKNOWN) {
            return numericComparison;
        }
        if (isProvablyDifferentScalarKind(left, right)) {
            return ExpressionStructure.DIFFERENT;
        }
        if (!left.getClass().equals(right.getClass())) {
            // 不同 AST 类可能只是方言或无语义包装差异；未经显式证明不能声称两个表达式不同。
            return ExpressionStructure.UNKNOWN;
        }
        if (left instanceof Column leftColumn && right instanceof Column rightColumn) {
            return sameColumnReference(leftColumn, rightColumn, visibleScope);
        }
        if (left instanceof Function leftFunction && right instanceof Function rightFunction) {
            return compareFunctionStructure(leftFunction, rightFunction, visibleScope, depth + 1,
                    budget);
        }
        if (left instanceof SignedExpression leftSigned
                && right instanceof SignedExpression rightSigned) {
            if (leftSigned.getSign() != rightSigned.getSign()) {
                return ExpressionStructure.DIFFERENT;
            }
            return compareExpressionStructure(leftSigned.getExpression(),
                    rightSigned.getExpression(), visibleScope, depth + 1, budget);
        }
        if (left instanceof BinaryExpression leftBinary
                && right instanceof BinaryExpression rightBinary) {
            return combineStructureComparisons(
                    compareExpressionStructure(leftBinary.getLeftExpression(),
                            rightBinary.getLeftExpression(), visibleScope, depth + 1, budget),
                    compareExpressionStructure(leftBinary.getRightExpression(),
                            rightBinary.getRightExpression(), visibleScope, depth + 1, budget));
        }
        return compareLiteralStructure(left, right);
    }

    /** 比较 COALESCE 等普通函数的名称、标志和有序参数；复杂装饰保持 UNKNOWN。 */
    private ExpressionStructure compareFunctionStructure(Function left, Function right,
            ExpressionScope visibleScope, int depth, BooleanProofBudget budget) {
        if (!sameFunctionIdentity(left, right)) {
            return ExpressionStructure.DIFFERENT;
        }
        if (hasUnsupportedFunctionDecoration(left) || hasUnsupportedFunctionDecoration(right)) {
            return ExpressionStructure.UNKNOWN;
        }
        return compareExpressionLists(left.getParameters(), right.getParameters(), visibleScope,
                depth + 1, budget);
    }

    /** 函数标识符遵循现有限定名的大小写无关语义，同时保留参数修饰标志。 */
    private boolean sameFunctionIdentity(Function left, Function right) {
        return StringUtils.equalsIgnoreCase(left.getName(), right.getName())
                && sameIdentifierPath(left.getMultipartName(), right.getMultipartName())
                && left.isAllColumns() == right.isAllColumns()
                && left.isIgnoreNulls() == right.isIgnoreNulls()
                && left.isDistinct() == right.isDistinct() && left.isUnique() == right.isUnique()
                && left.isEscaped() == right.isEscaped();
    }

    /** 带命名参数、KEEP、属性或函数内排序的节点暂不尝试完整等价证明。 */
    private boolean hasUnsupportedFunctionDecoration(Function function) {
        return function.getNamedParameters() != null || function.getAttribute() != null
                || function.getKeep() != null
                || !CollectionUtils.isEmpty(function.getOrderByElements());
    }

    /** 按 addNormalizedIdentifier 的大小写无关规则比较多段函数名。 */
    private boolean sameIdentifierPath(List<String> left, List<String> right) {
        if (left == null || right == null || left.size() != right.size()) {
            return left == right;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!StringUtils.equalsIgnoreCase(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** 逐项比较函数参数，参数顺序属于 AST 结构的一部分。 */
    private ExpressionStructure compareExpressionLists(ExpressionList<?> left,
            ExpressionList<?> right, ExpressionScope visibleScope, int depth,
            BooleanProofBudget budget) {
        if (left == right) {
            return ExpressionStructure.EQUIVALENT;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return ExpressionStructure.DIFFERENT;
        }
        ExpressionStructure result = ExpressionStructure.EQUIVALENT;
        for (int index = 0; index < left.size(); index++) {
            if (!budget.hasRemaining()) {
                return ExpressionStructure.UNKNOWN;
            }
            result = combineStructureComparisons(result, compareExpressionStructure(left.get(index),
                    right.get(index), visibleScope, depth + 1, budget));
            if (result == ExpressionStructure.DIFFERENT) {
                return result;
            }
        }
        return result;
    }

    /** 合并有序子节点比较；任一明确差异即可证明整体不同，未知信息不得伪装为等价。 */
    private ExpressionStructure combineStructureComparisons(ExpressionStructure left,
            ExpressionStructure right) {
        if (left == ExpressionStructure.DIFFERENT || right == ExpressionStructure.DIFFERENT) {
            return ExpressionStructure.DIFFERENT;
        }
        return left == ExpressionStructure.EQUIVALENT && right == ExpressionStructure.EQUIVALENT
                ? ExpressionStructure.EQUIVALENT
                : ExpressionStructure.UNKNOWN;
    }

    /** 比较门禁所需的常见字面量；其他相同类型节点保持 UNKNOWN。 */
    private ExpressionStructure compareLiteralStructure(Expression left, Expression right) {
        boolean equivalent;
        if (left instanceof NullValue) {
            equivalent = true;
        } else if (left instanceof LongValue leftValue && right instanceof LongValue rightValue) {
            equivalent = leftValue.getValue() == rightValue.getValue();
        } else if (left instanceof DoubleValue leftValue
                && right instanceof DoubleValue rightValue) {
            equivalent = Double.compare(leftValue.getValue(), rightValue.getValue()) == 0;
        } else if (left instanceof StringValue leftValue
                && right instanceof StringValue rightValue) {
            equivalent = Objects.equals(leftValue.getValue(), rightValue.getValue());
        } else if (left instanceof DateValue leftValue && right instanceof DateValue rightValue) {
            equivalent = Objects.equals(leftValue.getValue(), rightValue.getValue());
        } else if (left instanceof TimeValue leftValue && right instanceof TimeValue rightValue) {
            equivalent = Objects.equals(leftValue.getValue(), rightValue.getValue());
        } else if (left instanceof TimestampValue leftValue
                && right instanceof TimestampValue rightValue) {
            equivalent = Objects.equals(leftValue.getValue(), rightValue.getValue());
        } else if (left instanceof HexValue leftValue && right instanceof HexValue rightValue) {
            equivalent = StringUtils.equalsIgnoreCase(leftValue.getValue(), rightValue.getValue());
        } else {
            return ExpressionStructure.UNKNOWN;
        }
        return equivalent ? ExpressionStructure.EQUIVALENT : ExpressionStructure.DIFFERENT;
    }

    /** 使用精确十进制语义比较 LongValue 与 DoubleValue，避免 1 和 1.0 因 AST 类不同被误判。 */
    private ExpressionStructure compareNumericLiteralStructure(Expression left, Expression right) {
        BigDecimal leftValue = numericLiteralValue(left);
        BigDecimal rightValue = numericLiteralValue(right);
        if (leftValue == null || rightValue == null) {
            return ExpressionStructure.UNKNOWN;
        }
        return leftValue.compareTo(rightValue) == 0 ? ExpressionStructure.EQUIVALENT
                : ExpressionStructure.DIFFERENT;
    }

    /** 把受支持数值字面量转换为无格式差异的 BigDecimal；其他表达式保持未知。 */
    private BigDecimal numericLiteralValue(Expression expression) {
        if (expression instanceof LongValue value) {
            return BigDecimal.valueOf(value.getValue());
        }
        if (expression instanceof DoubleValue value && Double.isFinite(value.getValue())) {
            return BigDecimal.valueOf(value.getValue());
        }
        return null;
    }

    /**
     * 仅对明确支持的列引用与字面量种类证明不同；其余 AST class 不同仍保持 UNKNOWN。
     */
    private boolean isProvablyDifferentScalarKind(Expression left, Expression right) {
        return left instanceof Column && isSupportedLiteral(right)
                || right instanceof Column && isSupportedLiteral(left);
    }

    /** 返回结构比较器已经显式实现精确语义的字面量类型。 */
    private boolean isSupportedLiteral(Expression expression) {
        return expression instanceof NullValue || expression instanceof LongValue
                || expression instanceof DoubleValue || expression instanceof StringValue
                || expression instanceof DateValue || expression instanceof TimeValue
                || expression instanceof TimestampValue || expression instanceof HexValue;
    }

    /** 按作用域绑定比较两个列引用；缺少唯一绑定信息时返回 UNKNOWN，绝不猜测为 DIFFERENT。 */
    private ExpressionStructure sameColumnReference(Column left, Column right,
            ExpressionScope visibleScope) {
        ExpressionStructure columnIdentity =
                compareSqlIdentifier(left.getColumnName(), right.getColumnName());
        if (columnIdentity != ExpressionStructure.EQUIVALENT) {
            return columnIdentity;
        }
        boolean leftQualified = hasColumnQualifier(left);
        boolean rightQualified = hasColumnQualifier(right);
        if (!leftQualified && !rightQualified) {
            return ExpressionStructure.EQUIVALENT;
        }
        if (leftQualified != rightQualified) {
            Table qualifiedTable = leftQualified ? left.getTable() : right.getTable();
            int binding = visibleScope.resolve(normalizedTableQualifiers(qualifiedTable));
            return visibleScope.bindingCount() == 1 && binding == 0 ? ExpressionStructure.EQUIVALENT
                    : ExpressionStructure.UNKNOWN;
        }
        Set<String> leftQualifiers = normalizedTableQualifiers(left.getTable());
        Set<String> rightQualifiers = normalizedTableQualifiers(right.getTable());
        if (leftQualifiers.stream().anyMatch(rightQualifiers::contains)) {
            return ExpressionStructure.EQUIVALENT;
        }
        int leftBinding = visibleScope.resolve(leftQualifiers);
        int rightBinding = visibleScope.resolve(rightQualifiers);
        if (leftBinding < 0 || rightBinding < 0) {
            return ExpressionStructure.UNKNOWN;
        }
        return leftBinding == rightBinding ? ExpressionStructure.EQUIVALENT
                : ExpressionStructure.DIFFERENT;
    }

    /** 判断 Column 是否带有非空关系限定名。 */
    private boolean hasColumnQualifier(Column column) {
        return column != null && column.getTable() != null
                && StringUtils.isNotBlank(column.getTable().getFullyQualifiedName());
    }

    /** 未能常量折叠的谓词必须只依赖当前作用域列，否则无法证明其能缩小扫描范围。 */
    private boolean hasBoundRuntimeColumnReference(Expression expression,
            ExpressionScope visibleScope) {
        if (expression == null) {
            return false;
        }
        boolean[] state = {false, false};
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                if (isSqlBooleanLiteral(column)) {
                    return;
                }
                state[0] = true;
                if (hasColumnQualifier(column)) {
                    state[1] |=
                            visibleScope.resolve(normalizedTableQualifiers(column.getTable())) < 0;
                } else if (visibleScope.bindingCount() != 1) {
                    // 多关系作用域中的非限定列无法唯一绑定，不能证明谓词安全。
                    state[1] = true;
                }
            }

            @Override
            public void visit(ParenthesedSelect parenthesedSelect) {
                // 子查询列不能证明当前基础表存在有效 WHERE 过滤。
            }

            @Override
            public void visit(Select nestedSelect) {
                // 嵌套查询由独立扫描负责，此处只判断当前谓词作用域。
            }
        });
        return state[0] && !state[1];
    }

    /**
     * 证明 ON 条件确实限制当前 JOIN 的右侧关系。
     *
     * <p>
     * 常量 FALSE 可由数据库直接折叠为空结果，因此没有笛卡尔积风险；其他条件必须同时连接当前 SELECT 已构建的左侧关系和本次右侧关系。无法从限定列名证明归属时返回 false，保持
     * fail-closed 的 WARNING。
     * </p>
     */
    private boolean hasEffectiveJoinConstraint(Join join, Set<String> leftRelations,
            Set<String> rightRelations, ExpressionScope visibleScope) {
        StaticTruth combinedTruth = StaticTruth.TRUE;
        for (Expression onExpression : join.getOnExpressions()) {
            combinedTruth = combinedTruth.and(evaluateStaticTruth(onExpression));
        }
        if (combinedTruth == StaticTruth.FALSE) {
            return true;
        }
        if (combinedTruth == StaticTruth.TRUE) {
            return false;
        }
        return !leftRelations.isEmpty() && !rightRelations.isEmpty()
                && join.getOnExpressions().stream()
                        .anyMatch(expression -> guaranteesCrossRelationConstraint(expression,
                                leftRelations, rightRelations, visibleScope));
    }

    /**
     * 判断单个 ON 布尔分支是否保证存在跨关系谓词。
     *
     * <p>
     * AND 只需一个子句跨关系即可约束所有命中行；OR 的每个可满足分支都必须跨关系，否则单边分支仍会产生乘积。静态 FALSE 分支永不命中，可安全视为已约束。
     * </p>
     */
    private boolean guaranteesCrossRelationConstraint(Expression expression,
            Set<String> leftRelations, Set<String> rightRelations) {
        ExpressionScope visibleScope = ExpressionScope.fromQualifiers(leftRelations)
                .append(ExpressionScope.fromQualifiers(rightRelations));
        return guaranteesCrossRelationConstraint(expression, leftRelations, rightRelations,
                visibleScope);
    }

    /** 使用调用方维护的精确关系绑定证明跨关系约束，避免别名与 schema 限定名被错误拆分。 */
    private boolean guaranteesCrossRelationConstraint(Expression expression,
            Set<String> leftRelations, Set<String> rightRelations, ExpressionScope visibleScope) {
        if (expression == null) {
            return false;
        }
        Set<String> allowedRelations = new LinkedHashSet<>(leftRelations);
        allowedRelations.addAll(rightRelations);
        if (!hasOnlyKnownRelationReferences(expression, allowedRelations)) {
            // 即使另一个 AND 分支已跨表，未知限定名仍意味着无法证明整个 ON/关联谓词有效。
            return false;
        }
        return guaranteesCrossRelationConstraint(expression, leftRelations, rightRelations,
                visibleScope, 0, new BooleanProofBudget());
    }

    /** 在已验证关系作用域后，以有界递归应用 AND-any、OR-all 的跨关系证明规则。 */
    private boolean guaranteesCrossRelationConstraint(Expression expression,
            Set<String> leftRelations, Set<String> rightRelations, ExpressionScope visibleScope,
            int depth, BooleanProofBudget budget) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH || !budget.consume()) {
            return false;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return guaranteesCrossRelationConstraint(parenthesis.getExpression(), leftRelations,
                    rightRelations, visibleScope, depth + 1, budget);
        }
        if (expression instanceof AndExpression andExpression) {
            return guaranteesCrossRelationConstraint(andExpression.getLeftExpression(),
                    leftRelations, rightRelations, visibleScope, depth + 1, budget)
                    || guaranteesCrossRelationConstraint(andExpression.getRightExpression(),
                            leftRelations, rightRelations, visibleScope, depth + 1, budget);
        }
        if (expression instanceof OrExpression orExpression) {
            ComplementaryCheck complementary =
                    checkComplementaryDisjunction(expression, visibleScope, depth);
            if (complementary != ComplementaryCheck.ABSENT) {
                return false;
            }
            return guaranteesCrossRelationConstraint(orExpression.getLeftExpression(),
                    leftRelations, rightRelations, visibleScope, depth + 1, budget)
                    && guaranteesCrossRelationConstraint(orExpression.getRightExpression(),
                            leftRelations, rightRelations, visibleScope, depth + 1, budget);
        }
        StaticTruth truth = evaluateStaticTruth(expression, depth);
        if (truth == StaticTruth.FALSE) {
            return true;
        }
        if (truth == StaticTruth.TRUE) {
            return false;
        }
        return isSupportedRelationalPredicate(expression)
                && hasStructurallyDistinctRelationalOperands(expression, visibleScope)
                && referencesLeftAndRightRelations(expression, leftRelations, rightRelations);
    }

    /** 仅接受语义明确的关系比较作为 JOIN 约束证明，未知谓词保持 WARNING。 */
    private boolean isSupportedRelationalPredicate(Expression expression) {
        return expression instanceof EqualsTo || expression instanceof NotEqualsTo
                || expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals;
    }

    /** 确认同一谓词只引用已知作用域，并同时连接当前左侧集合与本次右侧集合。 */
    private boolean referencesLeftAndRightRelations(Expression expression,
            Set<String> leftRelations, Set<String> rightRelations) {
        Set<String> referencedRelations = new LinkedHashSet<>();
        collectReferencedRelations(expression, referencedRelations);
        Set<String> allowedRelations = new LinkedHashSet<>(leftRelations);
        allowedRelations.addAll(rightRelations);
        if (referencedRelations.isEmpty() || !allowedRelations.containsAll(referencedRelations)) {
            return false;
        }
        Set<String> leftOnly = new LinkedHashSet<>(leftRelations);
        leftOnly.removeAll(rightRelations);
        Set<String> rightOnly = new LinkedHashSet<>(rightRelations);
        rightOnly.removeAll(leftRelations);
        boolean referencesLeft = referencedRelations.stream().anyMatch(leftOnly::contains);
        boolean referencesRight = referencedRelations.stream().anyMatch(rightOnly::contains);
        return referencesLeft && referencesRight;
    }

    /** 收集当前表达式中的限定关系名；子查询由独立递归扫描处理，不参与外层 JOIN 归属证明。 */
    private void collectReferencedRelations(Expression expression, Set<String> relations) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                if (column.getTable() != null) {
                    addTableQualifiers(column.getTable(), relations);
                }
            }

            @Override
            public void visit(ParenthesedSelect parenthesedSelect) {
                // 子查询内部别名不能证明外层 JOIN 右侧受到约束。
            }

            @Override
            public void visit(Select nestedSelect) {
                // 子查询由 scanExpression 递归检查，此处只分析当前 ON 作用域。
            }
        });
    }

    /** 确认表达式中的每个限定关系都属于当前已知作用域；无列常量表达式仍可继续求值。 */
    private boolean hasOnlyKnownRelationReferences(Expression expression,
            Set<String> knownRelations) {
        Set<String> referencedRelations = new LinkedHashSet<>();
        collectReferencedRelations(expression, referencedRelations);
        return knownRelations.containsAll(referencedRelations);
    }

    /** 汇总当前 SELECT 的基础 FROM 和全部 JOIN 右侧在本层可见的关系限定名。 */
    private Set<String> selectRelationQualifiers(PlainSelect select) {
        Set<String> qualifiers = relationQualifiers(select.getFromItem());
        if (!CollectionUtils.isEmpty(select.getJoins())) {
            for (Join join : select.getJoins()) {
                if (join != null) {
                    qualifiers.addAll(relationQualifiers(join.getRightItem()));
                }
            }
        }
        return qualifiers;
    }

    /** 取得 FROM 项在当前查询作用域内可用于限定列名的别名或表名。 */
    private Set<String> relationQualifiers(FromItem fromItem) {
        Set<String> qualifiers = new LinkedHashSet<>();
        if (fromItem == null) {
            return qualifiers;
        }
        if (fromItem.getAlias() != null && StringUtils.isNotBlank(fromItem.getAlias().getName())) {
            addNormalizedIdentifier(fromItem.getAlias().getName(), qualifiers);
            return qualifiers;
        }
        if (fromItem instanceof Table table) {
            addTableQualifiers(table, qualifiers);
        } else if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
            qualifiers.addAll(relationQualifiers(parenthesedFromItem.getFromItem()));
            if (!CollectionUtils.isEmpty(parenthesedFromItem.getJoins())) {
                for (Join join : parenthesedFromItem.getJoins()) {
                    if (join != null) {
                        qualifiers.addAll(relationQualifiers(join.getRightItem()));
                    }
                }
            }
        }
        return qualifiers;
    }

    /** 构造当前 SELECT 的表达式绑定作用域，并保留外层相关关系的歧义信息。 */
    private ExpressionScope selectExpressionScope(PlainSelect select, Set<String> outerRelations) {
        ExpressionScope scope = ExpressionScope.fromQualifiers(outerRelations)
                .append(expressionScope(select.getFromItem()));
        if (!CollectionUtils.isEmpty(select.getJoins())) {
            for (Join join : select.getJoins()) {
                if (join != null) {
                    scope = scope.append(expressionScope(join.getRightItem()));
                }
            }
        }
        return scope;
    }

    /**
     * 把一个 FROM 项映射为列绑定集合；别名会遮蔽物理表名，未命名派生关系仍占一个绑定槽位。
     */
    private ExpressionScope expressionScope(FromItem fromItem) {
        if (fromItem == null) {
            return ExpressionScope.empty();
        }
        if (fromItem.getAlias() != null && StringUtils.isNotBlank(fromItem.getAlias().getName())) {
            Set<String> alias = new LinkedHashSet<>();
            addNormalizedIdentifier(fromItem.getAlias().getName(), alias);
            return ExpressionScope.single(alias);
        }
        if (fromItem instanceof Table table) {
            return ExpressionScope.single(normalizedTableQualifiers(table));
        }
        if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
            ExpressionScope scope = expressionScope(parenthesedFromItem.getFromItem());
            if (!CollectionUtils.isEmpty(parenthesedFromItem.getJoins())) {
                for (Join join : parenthesedFromItem.getJoins()) {
                    if (join != null) {
                        scope = scope.append(expressionScope(join.getRightItem()));
                    }
                }
            }
            return scope.bindingCount() == 0 ? ExpressionScope.anonymous() : scope;
        }
        return ExpressionScope.anonymous();
    }

    /** APPLY/LATERAL 右侧必须在每个可满足分支中证明依赖当前左侧作用域。 */
    private boolean hasRightItemCorrelation(FromItem rightItem, Set<String> leftRelations) {
        if (rightItem == null || leftRelations == null || leftRelations.isEmpty()) {
            return false;
        }
        return hasFromItemCorrelation(rightItem, leftRelations, 0);
    }

    /**
     * 按 FROM 项实际结构递归证明 APPLY/LATERAL 相关性，无法识别的结构保持 fail-closed。
     *
     * <p>
     * APPLY 由 Join 标记表达，LATERAL 则是 ParenthesedSelect 的专用子类；进入本方法后两者共享相同的别名遮蔽、
     * OR-all、未知限定名和最大深度约束，不保存任何单次 SQL 的可变状态。
     * </p>
     */
    private boolean hasFromItemCorrelation(FromItem fromItem, Set<String> leftRelations,
            int depth) {
        if (fromItem == null || depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            return false;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            return hasSelectCorrelation(parenthesedSelect.getSelect(), leftRelations, depth + 1);
        }
        if (fromItem instanceof TableFunction tableFunction) {
            return referencesOnlyKnownRelations(tableFunction.getFunction(), leftRelations);
        }
        if (!(fromItem instanceof ParenthesedFromItem parenthesedFromItem)) {
            return false;
        }
        if (hasFromItemCorrelation(parenthesedFromItem.getFromItem(), leftRelations, depth + 1)) {
            return true;
        }
        Set<String> localRelations = parenthesedFromItemRelationQualifiers(parenthesedFromItem);
        if (!CollectionUtils.isEmpty(parenthesedFromItem.getJoins())) {
            for (Join join : parenthesedFromItem.getJoins()) {
                if (join == null) {
                    continue;
                }
                if (!CollectionUtils.isEmpty(join.getOnExpressions())
                        && join.getOnExpressions().stream().anyMatch(
                                expression -> guaranteesCrossRelationConstraint(expression,
                                        leftRelations, localRelations))) {
                    return true;
                }
                if (hasFromItemCorrelation(join.getRightItem(), leftRelations, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 证明相关派生查询的所有集合分支均与左侧相关，避免单个独立分支放大结果集。 */
    private boolean hasSelectCorrelation(Select select, Set<String> leftRelations, int depth) {
        if (select == null || depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            return false;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return hasSelectCorrelation(parenthesedSelect.getSelect(), leftRelations, depth + 1);
        }
        if (select instanceof SetOperationList setOperationList) {
            return !CollectionUtils.isEmpty(setOperationList.getSelects())
                    && setOperationList.getSelects().stream().allMatch(
                            branch -> hasSelectCorrelation(branch, leftRelations, depth + 1));
        }
        if (select instanceof PlainSelect plainSelect) {
            return hasPlainSelectCorrelation(plainSelect, leftRelations, depth + 1);
        }
        return false;
    }

    /** 证明普通 APPLY/LATERAL 子查询存在作用于本地数据的左侧关联约束。 */
    private boolean hasPlainSelectCorrelation(PlainSelect select, Set<String> leftRelations,
            int depth) {
        if (depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            return false;
        }
        Set<String> localRelations = selectRelationQualifiers(select);
        if (localRelations.isEmpty()) {
            return hasOuterDependentScalarSelect(select, leftRelations);
        }
        if (guaranteesCrossRelationConstraint(select.getWhere(), leftRelations, localRelations)) {
            return true;
        }
        if (hasFromItemCorrelation(select.getFromItem(), leftRelations, depth + 1)) {
            return true;
        }
        if (!CollectionUtils.isEmpty(select.getJoins())) {
            for (Join join : select.getJoins()) {
                if (join == null) {
                    continue;
                }
                if (!CollectionUtils.isEmpty(join.getOnExpressions())
                        && join.getOnExpressions().stream().anyMatch(
                                expression -> guaranteesCrossRelationConstraint(expression,
                                        leftRelations, localRelations))) {
                    return true;
                }
                if (hasFromItemCorrelation(join.getRightItem(), leftRelations, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 无本地 FROM 的标量子查询只有直接依赖左侧值或恒为空时才算相关。 */
    private boolean hasOuterDependentScalarSelect(PlainSelect select, Set<String> leftRelations) {
        if (guaranteesKnownOuterDependency(select.getWhere(), leftRelations)
                || guaranteesKnownOuterDependency(select.getHaving(), leftRelations)
                || guaranteesKnownOuterDependency(select.getQualify(), leftRelations)) {
            return true;
        }
        if (CollectionUtils.isEmpty(select.getSelectItems())) {
            return false;
        }
        return select.getSelectItems().stream().anyMatch(
                item -> referencesOnlyKnownRelations(item.getExpression(), leftRelations));
    }

    /** 按 AND-any、OR-all 语义证明无 FROM 谓词在每个可满足分支都依赖左侧。 */
    private boolean guaranteesKnownOuterDependency(Expression expression,
            Set<String> leftRelations) {
        ExpressionScope visibleScope = ExpressionScope.fromQualifiers(leftRelations);
        return guaranteesKnownOuterDependency(expression, leftRelations, visibleScope, 0,
                new BooleanProofBudget());
    }

    /** 有界递归证明标量查询谓词真实依赖外层，而非仅包含外层别名的自恒真表达式。 */
    private boolean guaranteesKnownOuterDependency(Expression expression, Set<String> leftRelations,
            ExpressionScope visibleScope, int depth, BooleanProofBudget budget) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH || !budget.consume()) {
            return false;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return guaranteesKnownOuterDependency(parenthesis.getExpression(), leftRelations,
                    visibleScope, depth + 1, budget);
        }
        if (expression instanceof AndExpression andExpression) {
            return guaranteesKnownOuterDependency(andExpression.getLeftExpression(), leftRelations,
                    visibleScope, depth + 1, budget)
                    || guaranteesKnownOuterDependency(andExpression.getRightExpression(),
                            leftRelations, visibleScope, depth + 1, budget);
        }
        if (expression instanceof OrExpression orExpression) {
            ComplementaryCheck complementary =
                    checkComplementaryDisjunction(expression, visibleScope, depth);
            if (complementary != ComplementaryCheck.ABSENT) {
                return false;
            }
            return guaranteesKnownOuterDependency(orExpression.getLeftExpression(), leftRelations,
                    visibleScope, depth + 1, budget)
                    && guaranteesKnownOuterDependency(orExpression.getRightExpression(),
                            leftRelations, visibleScope, depth + 1, budget);
        }
        StaticTruth truth = evaluateStaticTruth(expression, depth);
        if (truth == StaticTruth.FALSE) {
            return true;
        }
        if (truth == StaticTruth.TRUE) {
            return false;
        }
        return isProvablyEffectiveAtomicPredicate(expression, visibleScope)
                && referencesOnlyKnownRelations(expression, leftRelations);
    }

    /** 表达式必须至少引用一个已知限定关系，且不能夹带未知或被遮蔽的限定名。 */
    private boolean referencesOnlyKnownRelations(Expression expression,
            Set<String> knownRelations) {
        Set<String> referencedRelations = new LinkedHashSet<>();
        collectReferencedRelations(expression, referencedRelations);
        return !referencedRelations.isEmpty() && knownRelations.containsAll(referencedRelations);
    }

    /** 收集括号 FROM 内部可见关系；外层括号别名不会遮蔽其内部 ON 的绑定语义。 */
    private Set<String> parenthesedFromItemRelationQualifiers(
            ParenthesedFromItem parenthesedFromItem) {
        Set<String> relations = relationQualifiers(parenthesedFromItem.getFromItem());
        if (!CollectionUtils.isEmpty(parenthesedFromItem.getJoins())) {
            for (Join join : parenthesedFromItem.getJoins()) {
                if (join != null) {
                    relations.addAll(relationQualifiers(join.getRightItem()));
                }
            }
        }
        return relations;
    }

    /** 同时记录表短名和全限定名，兼容带 schema 与不带 schema 的列引用。 */
    private void addTableQualifiers(Table table, Set<String> qualifiers) {
        addNormalizedIdentifier(table.getName(), qualifiers);
        addNormalizedIdentifier(table.getFullyQualifiedName(), qualifiers);
    }

    /** 返回表短名和 schema 全限定名的不可变归一化集合。 */
    private Set<String> normalizedTableQualifiers(Table table) {
        Set<String> qualifiers = new LinkedHashSet<>();
        if (table != null) {
            addTableQualifiers(table, qualifiers);
        }
        return Set.copyOf(qualifiers);
    }

    /** 以 SQL 标识符大小写无关语义保存限定名。 */
    private void addNormalizedIdentifier(String identifier, Set<String> identifiers) {
        if (StringUtils.isNotBlank(identifier)) {
            String normalized = normalizeSqlIdentifier(identifier);
            if (normalized != null) {
                identifiers.add(normalized);
            }
        }
    }

    /**
     * 比较引用标识符：未引用名按 SQL 方言共同的 ASCII 大小写折叠处理；引用名保留大小写。 小写引用名可与同值未引用名安全等价，其他跨引用形式因方言配置不足返回 UNKNOWN。
     */
    private ExpressionStructure compareSqlIdentifier(String left, String right) {
        String normalizedLeft = normalizeSqlIdentifier(left);
        String normalizedRight = normalizeSqlIdentifier(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return ExpressionStructure.UNKNOWN;
        }
        if (Objects.equals(normalizedLeft, normalizedRight)) {
            return ExpressionStructure.EQUIVALENT;
        }
        if (normalizedLeft.startsWith("quoted:") || normalizedRight.startsWith("quoted:")) {
            return ExpressionStructure.UNKNOWN;
        }
        return ExpressionStructure.DIFFERENT;
    }

    /** 保留 quoted identifier 的大小写语义；小写 quoted 名与方言折叠后的未引用名使用同一键。 */
    private String normalizeSqlIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return null;
        }
        String value = identifier.trim();
        boolean quoted = value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("`") && value.endsWith("`")));
        if (!quoted) {
            return "folded:" + value.toLowerCase(Locale.ROOT);
        }
        String content = value.substring(1, value.length() - 1);
        return content.equals(content.toLowerCase(Locale.ROOT)) ? "folded:" + content
                : "quoted:" + content;
    }

    /** 对明显常量布尔表达式执行三值求值；包含运行时列或函数时返回 UNKNOWN。 */
    private StaticTruth evaluateStaticTruth(Expression expression) {
        return evaluateStaticTruth(expression, 0, new BooleanProofBudget());
    }

    /** 有界递归执行常量三值求值，深层或未知结构保持 UNKNOWN。 */
    private StaticTruth evaluateStaticTruth(Expression expression, int depth) {
        return evaluateStaticTruth(expression, depth, new BooleanProofBudget());
    }

    /** 在共享节点预算内执行常量三值求值，平衡型宽 AST 也不会造成无界遍历。 */
    private StaticTruth evaluateStaticTruth(Expression expression, int depth,
            BooleanProofBudget budget) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH || !budget.consume()) {
            return StaticTruth.UNKNOWN;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return evaluateStaticTruth(parenthesis.getExpression(), depth + 1, budget);
        }
        if (expression instanceof NotExpression notExpression) {
            return evaluateStaticTruth(notExpression.getExpression(), depth + 1, budget).not();
        }
        if (expression instanceof AndExpression andExpression) {
            return evaluateStaticTruth(andExpression.getLeftExpression(), depth + 1, budget).and(
                    evaluateStaticTruth(andExpression.getRightExpression(), depth + 1, budget));
        }
        if (expression instanceof OrExpression orExpression) {
            return evaluateStaticTruth(orExpression.getLeftExpression(), depth + 1, budget)
                    .or(evaluateStaticTruth(orExpression.getRightExpression(), depth + 1, budget));
        }
        StaticScalar scalar = staticScalar(expression, depth + 1, budget);
        if (scalar != null && scalar.type() == StaticScalarType.BOOLEAN) {
            return Boolean.TRUE.equals(scalar.value()) ? StaticTruth.TRUE : StaticTruth.FALSE;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return evaluateStaticComparison(binaryExpression, depth + 1, budget);
        }
        return StaticTruth.UNKNOWN;
    }

    /** 比较两侧均为常量的关系表达式；无法排除方言或排序规则影响时保守返回 UNKNOWN。 */
    private StaticTruth evaluateStaticComparison(BinaryExpression expression, int depth,
            BooleanProofBudget budget) {
        if (depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            return StaticTruth.UNKNOWN;
        }
        StaticScalar left = staticScalar(expression.getLeftExpression(), depth + 1, budget);
        StaticScalar right = staticScalar(expression.getRightExpression(), depth + 1, budget);
        Integer comparison = compareStaticScalars(left, right);
        if (comparison == null) {
            return StaticTruth.UNKNOWN;
        }
        boolean result;
        if (expression instanceof EqualsTo) {
            result = comparison == 0;
        } else if (expression instanceof NotEqualsTo) {
            result = comparison != 0;
        } else if (expression instanceof GreaterThan) {
            result = comparison > 0;
        } else if (expression instanceof GreaterThanEquals) {
            result = comparison >= 0;
        } else if (expression instanceof MinorThan) {
            result = comparison < 0;
        } else if (expression instanceof MinorThanEquals) {
            result = comparison <= 0;
        } else {
            return StaticTruth.UNKNOWN;
        }
        return result ? StaticTruth.TRUE : StaticTruth.FALSE;
    }

    /** 提取不会读取列、参数或运行时函数的标量常量。 */
    private StaticScalar staticScalar(Expression expression, int depth, BooleanProofBudget budget) {
        if (expression == null || depth > MAX_STATIC_SQL_NESTING_DEPTH || !budget.consume()) {
            return null;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return staticScalar(parenthesis.getExpression(), depth + 1, budget);
        }
        if (expression instanceof LongValue longValue) {
            return new StaticScalar(StaticScalarType.NUMBER,
                    BigDecimal.valueOf(longValue.getValue()));
        }
        if (expression instanceof DoubleValue doubleValue) {
            return new StaticScalar(StaticScalarType.NUMBER,
                    BigDecimal.valueOf(doubleValue.getValue()));
        }
        if (expression instanceof StringValue stringValue) {
            return new StaticScalar(StaticScalarType.STRING, stringValue.getValue());
        }
        if (expression instanceof SignedExpression signedExpression) {
            return signedNumericScalar(signedExpression, depth + 1, budget);
        }
        if (expression instanceof Column column && isSqlBooleanLiteral(column)) {
            if (StringUtils.equalsIgnoreCase(column.getColumnName(), SQL_BOOLEAN_LITERAL_TRUE)) {
                return new StaticScalar(StaticScalarType.BOOLEAN, Boolean.TRUE);
            }
            if (StringUtils.equalsIgnoreCase(column.getColumnName(), SQL_BOOLEAN_LITERAL_FALSE)) {
                return new StaticScalar(StaticScalarType.BOOLEAN, Boolean.FALSE);
            }
        }
        return null;
    }

    /** JSqlParser 4.9 将 TRUE/FALSE 解析为无表限定的 Column，需要显式识别。 */
    private boolean isSqlBooleanLiteral(Column column) {
        return column != null && column.getTable() == null
                && (StringUtils.equalsIgnoreCase(column.getColumnName(), SQL_BOOLEAN_LITERAL_TRUE)
                        || StringUtils.equalsIgnoreCase(column.getColumnName(),
                                SQL_BOOLEAN_LITERAL_FALSE));
    }

    /** 应用一元正负号；非数值或未知符号不能参与静态求值。 */
    private StaticScalar signedNumericScalar(SignedExpression signedExpression, int depth,
            BooleanProofBudget budget) {
        if (depth > MAX_STATIC_SQL_NESTING_DEPTH) {
            return null;
        }
        StaticScalar nested = staticScalar(signedExpression.getExpression(), depth + 1, budget);
        if (nested == null || nested.type() != StaticScalarType.NUMBER) {
            return null;
        }
        BigDecimal value = (BigDecimal) nested.value();
        if (signedExpression.getSign() == '-') {
            return new StaticScalar(StaticScalarType.NUMBER, value.negate());
        }
        return signedExpression.getSign() == '+' ? nested : null;
    }

    /** 返回同类型常量的比较结果；不同字符串受数据库排序规则影响，不做不安全推断。 */
    private Integer compareStaticScalars(StaticScalar left, StaticScalar right) {
        if (left == null || right == null || left.type() != right.type()) {
            return null;
        }
        return switch (left.type()) {
            case NUMBER -> ((BigDecimal) left.value()).compareTo((BigDecimal) right.value());
            case BOOLEAN -> Boolean.compare((Boolean) left.value(), (Boolean) right.value());
            case STRING -> Objects.equals(left.value(), right.value()) ? 0 : null;
        };
    }

    /** 使用 JSqlParser 表达式访问器发现 SELECT、WHERE、HAVING 等位置的标量子查询。 */
    private void scanExpression(Expression expression, int depth, PerformanceRiskScan scan) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect parenthesedSelect) {
                scanPerformanceRisk(parenthesedSelect.getSelect(), depth, scan);
            }

            @Override
            public void visit(Select nestedSelect) {
                scanPerformanceRisk(nestedSelect, depth, scan);
            }
        });
    }

    /** 汇总静态性能检查，任何未运行项都不能伪装成通过。 */
    private ModelingValidationCheckResult performanceResult(int expected, int checked, int passed,
            int failed, int warningCount) {
        String status;
        String summary;
        if (failed > 0 || checked < expected) {
            status = checked == 0 ? ModelingDraftConstants.VALIDATION_NOT_RUN
                    : ModelingDraftConstants.VALIDATION_FAILED;
            summary = checked == 0 ? "SQL 未生成或未通过安全检查，静态性能检查未运行" : "部分 SQL 无法完成静态性能风险检查";
        } else if (warningCount > 0) {
            status = ModelingDraftConstants.VALIDATION_WARNING;
            summary = "结果上限有效，但存在无法静态确认容量的扫描或复杂查询风险";
        } else {
            status = ModelingDraftConstants.VALIDATION_PASSED;
            summary = "所有 SQL 均具备确定全局结果上限，未发现明确静态大查询风险";
        }
        return ModelingValidationCheckResult.builder().category(CATEGORY_PERFORMANCE).status(status)
                .summary(summary).checkedCount(checked).passedCount(passed)
                .failedCount(Math.max(failed, expected - checked)).mode("STATIC_SQL_AST").build();
    }

    /** 复制聚合状态为独立必需检查，避免仅靠 blockingItems 间接推断。 */
    private ModelingValidationCheckResult copyCheck(ModelingValidationCheckResult source,
            String category, String summaryPrefix) {
        return ModelingValidationCheckResult.builder().category(category).status(source.getStatus())
                .summary(summaryPrefix + "：" + source.getSummary())
                .checkedCount(source.getCheckedCount()).passedCount(source.getPassedCount())
                .failedCount(source.getFailedCount()).mode(source.getMode()).build();
    }

    /** 从 selected parse 的实际命中元素提取草稿本地 key。 */
    private List<String> matchedObjectKeys(SemanticParseInfo selected) {
        Set<String> keys = new LinkedHashSet<>();
        for (SchemaElementMatch match : selected.getElementMatches()) {
            Object key = match.getElement().getExtInfo()
                    .get(DraftSemanticSchemaMapper.EXT_DRAFT_OBJECT_KEY);
            if (key instanceof String value && StringUtils.isNotBlank(value)) {
                keys.add(value);
            }
        }
        return List.copyOf(keys);
    }

    /** 创建单样例 fail-closed 结果和阻塞项。 */
    private QuestionValidation failed(ModelDraft model, String safeQuestion, String code,
            String message) {
        return failed(model, safeQuestion, code, message, PerformanceInspection.notRun(), null);
    }

    /** 创建单样例失败，并显式携带性能检查是否已运行。 */
    private QuestionValidation failed(ModelDraft model, String safeQuestion, String code,
            String message, PerformanceInspection performance,
            ModelingValidationFinding performanceFinding) {
        ModelingSampleQuestionResult result =
                ModelingSampleQuestionResult.builder().modelKey(model.getKey())
                        .question(safeQuestion).matched(false).matchedObjectKeys(List.of())
                        .validationMode(VALIDATION_MODE).readOnly(false).message(message).build();
        String category = code.startsWith("SAMPLE_") ? CATEGORY_SAMPLE : CATEGORY_SQL;
        return new QuestionValidation(result, finding(category, code,
                "$.models[" + model.getKey() + "].sampleQuestions", message, model.getKey()), false,
                performance, performanceFinding);
    }

    /** 创建已由全局指标 finding 覆盖的样例失败结果，避免重复阻塞项。 */
    private QuestionValidation failedWithoutFinding(ModelDraft model, String safeQuestion,
            String message) {
        ModelingSampleQuestionResult result =
                ModelingSampleQuestionResult.builder().modelKey(model.getKey())
                        .question(safeQuestion).matched(false).matchedObjectKeys(List.of())
                        .validationMode(VALIDATION_MODE).readOnly(false).message(message).build();
        return new QuestionValidation(result, null, false, PerformanceInspection.notRun(), null);
    }

    /** 判断两个 finding 是否代表同一个稳定阻塞项。 */
    private boolean sameFinding(ModelingValidationFinding left, ModelingValidationFinding right) {
        return Objects.equals(left.getCode(), right.getCode())
                && Objects.equals(left.getPath(), right.getPath());
    }

    /** 创建不包含原始值的发布阻塞项。 */
    private ModelingValidationFinding finding(String category, String code, String path,
            String message, String modelKey) {
        return ModelingValidationFinding.builder().category(category).code(code)
                .severity(ModelingDraftConstants.FINDING_BLOCKING).path(path).message(message)
                .modelKey(modelKey).build();
    }

    /** 创建不含 SQL 文本、表名或条件值的性能 finding。 */
    private ModelingValidationFinding performanceFinding(ModelDraft model,
            PerformanceInspection performance) {
        return ModelingValidationFinding.builder().category(CATEGORY_PERFORMANCE)
                .code(performance.code()).severity(ModelingDraftConstants.FINDING_WARNING)
                .path("$.models[" + model.getKey() + "].sampleQuestions")
                .message(performance.message()).modelKey(model.getKey()).objectType("MODEL")
                .objectKey(model.getKey()).build();
    }

    /** 对报告中的 S2SQL/SQL 统一脱敏并限长，避免验证表存入敏感字面量。 */
    private String safePreview(String value) {
        String sanitized = sensitivityClassifier.sanitizeText(value);
        return StringUtils.abbreviate(sanitized, MAX_PREVIEW_LENGTH);
    }

    /** 按大小写无关表名读取服务端字段快照。 */
    private List<DBColumn> columnsFor(Map<String, List<DBColumn>> columnsByTable, String table) {
        if (columnsByTable == null) {
            return List.of();
        }
        return columnsByTable.entrySet().stream()
                .filter(entry -> StringUtils.equalsIgnoreCase(entry.getKey(), table))
                .map(Map.Entry::getValue).filter(Objects::nonNull).findFirst().orElse(List.of());
    }

    /** 单条样例结果和可选阻塞项。 */
    private record QuestionValidation(ModelingSampleQuestionResult result,
            ModelingValidationFinding finding, boolean passed, PerformanceInspection performance,
            ModelingValidationFinding performanceFinding) {}

    /** 互补 OR 检查结果；UNKNOWN 表示深度或分支上限内无法完成安全证明。 */
    private enum ComplementaryCheck {
        PRESENT, ABSENT, UNKNOWN
    }

    /** NULL 谓词的真实极性；UNKNOWN 用于 JSqlParser 将来新增但尚未建模的组合。 */
    private enum NullPredicatePolarity {
        NULL, NOT_NULL, UNKNOWN
    }

    /**
     * 白名单关系运算符及其互补、反向映射。
     *
     * <p>
     * 映射完全由 AST 类型决定，不读取 SQL 文本；例如交换 {@code >} 的操作数后运算符语义变为 {@code <}。
     * </p>
     */
    private enum RelationalOperator {
        EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUALS, LESS_THAN, LESS_THAN_OR_EQUALS;

        /** 返回相同操作数方向下覆盖其余比较结果的互补运算符。 */
        private RelationalOperator complement() {
            return switch (this) {
                case EQUALS -> NOT_EQUALS;
                case NOT_EQUALS -> EQUALS;
                case GREATER_THAN -> LESS_THAN_OR_EQUALS;
                case GREATER_THAN_OR_EQUALS -> LESS_THAN;
                case LESS_THAN -> GREATER_THAN_OR_EQUALS;
                case LESS_THAN_OR_EQUALS -> GREATER_THAN;
            };
        }

        /** 返回交换左右操作数后的等价运算符。 */
        private RelationalOperator reverse() {
            return switch (this) {
                case EQUALS, NOT_EQUALS -> this;
                case GREATER_THAN -> LESS_THAN;
                case GREATER_THAN_OR_EQUALS -> LESS_THAN_OR_EQUALS;
                case LESS_THAN -> GREATER_THAN;
                case LESS_THAN_OR_EQUALS -> GREATER_THAN_OR_EQUALS;
            };
        }
    }

    /**
     * 单次 AST 证明的局部节点预算。
     *
     * <p>
     * 每个入口创建独立实例，不跨请求共享；因此无需锁且不会影响 Spring 单例线程安全。预算耗尽统一 fail-closed，防止平衡型恶意布尔树绕过单纯深度限制。
     * </p>
     */
    private static final class BooleanProofBudget {

        private int remaining = MAX_BOOLEAN_PROOF_NODES;

        /** 消耗一个待证明节点；预算耗尽返回 false。 */
        private boolean consume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }

        /** 供有序列表遍历在进入下一项前快速停止，避免预算耗尽后继续空转。 */
        private boolean hasRemaining() {
            return remaining > 0;
        }
    }

    /**
     * 当前表达式可见关系的不可变绑定上下文。
     *
     * <p>
     * 每个 binding 对应一个真实 FROM 关系，可同时包含表短名与 schema 全限定名；别名出现时只保存别名，
     * 从而保留遮蔽语义。上下文仅在当前方法栈传递，不使用线程本地、静态可变状态或跨请求缓存。
     * </p>
     */
    private static final class ExpressionScope {

        private final List<Set<String>> bindings;

        private ExpressionScope(List<Set<String>> bindings) {
            this.bindings =
                    bindings == null ? List.of() : bindings.stream().map(Set::copyOf).toList();
        }

        /** 创建不含任何关系的作用域。 */
        private static ExpressionScope empty() {
            return new ExpressionScope(List.of());
        }

        /** 创建一个没有可用限定名、但可绑定非限定列的匿名关系。 */
        private static ExpressionScope anonymous() {
            return single(Set.of());
        }

        /** 创建只含一个真实关系的作用域。 */
        private static ExpressionScope single(Set<String> qualifiers) {
            return new ExpressionScope(List.of(qualifiers == null ? Set.of() : qualifiers));
        }

        /**
         * 把缺少分组信息的外层限定名分别建为绑定；重复或歧义限定名因此只会得到 UNKNOWN。
         */
        private static ExpressionScope fromQualifiers(Set<String> qualifiers) {
            if (qualifiers == null || qualifiers.isEmpty()) {
                return empty();
            }
            List<Set<String>> bindings = new ArrayList<>();
            qualifiers.forEach(qualifier -> bindings.add(Set.of(qualifier)));
            return new ExpressionScope(bindings);
        }

        /** 返回拼接后的新作用域，不修改任一输入实例。 */
        private ExpressionScope append(ExpressionScope other) {
            if (other == null || other.bindings.isEmpty()) {
                return this;
            }
            if (bindings.isEmpty()) {
                return other;
            }
            List<Set<String>> combined = new ArrayList<>(bindings);
            combined.addAll(other.bindings);
            return new ExpressionScope(combined);
        }

        /** 返回可见真实关系数量；短名与全限定名不会重复计数。 */
        private int bindingCount() {
            return bindings.size();
        }

        /**
         * 解析限定名对应的唯一 binding 下标；不存在或同时命中多个 binding 时返回 -1。
         */
        private int resolve(Set<String> qualifiers) {
            if (qualifiers == null || qualifiers.isEmpty()) {
                return -1;
            }
            int resolved = -1;
            for (int index = 0; index < bindings.size(); index++) {
                Set<String> binding = bindings.get(index);
                if (binding.stream().noneMatch(qualifiers::contains)) {
                    continue;
                }
                if (resolved >= 0) {
                    return -1;
                }
                resolved = index;
            }
            return resolved;
        }
    }

    /** SQL 常量谓词的三值结果；UNKNOWN 表示依赖列、参数、函数或方言运行时语义。 */
    private enum StaticTruth {
        TRUE, FALSE, UNKNOWN;

        /** 按 SQL 三值逻辑合并 AND。 */
        private StaticTruth and(StaticTruth other) {
            if (this == FALSE || other == FALSE) {
                return FALSE;
            }
            return this == TRUE && other == TRUE ? TRUE : UNKNOWN;
        }

        /** 按 SQL 三值逻辑合并 OR。 */
        private StaticTruth or(StaticTruth other) {
            if (this == TRUE || other == TRUE) {
                return TRUE;
            }
            return this == FALSE && other == FALSE ? FALSE : UNKNOWN;
        }

        /** 对三值结果取反。 */
        private StaticTruth not() {
            return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
        }
    }

    /** 可安全参与静态比较的常量类型。 */
    private enum StaticScalarType {
        NUMBER, STRING, BOOLEAN
    }

    /** AST 结构比较结果；UNKNOWN 表示节点类型超出当前安全白名单或超过递归深度。 */
    private enum ExpressionStructure {
        EQUIVALENT, DIFFERENT, UNKNOWN
    }

    /** 不含原始 SQL 文本的静态常量值。 */
    private record StaticScalar(StaticScalarType type, Object value) {}

    /**
     * 单条 SQL 的递归性能风险扫描状态。
     *
     * <p>
     * 只记录结构化布尔信号，不保存 SQL 文本、条件值或字段值，避免诊断对象进入日志或 API 时泄露敏感信息。
     * </p>
     */
    private static final class PerformanceRiskScan {

        private boolean setOperation;
        private boolean crossJoin;
        private boolean implicitCommaJoin;
        private boolean joinConditionMissing;
        private boolean joinConditionIneffective;
        private boolean naturalJoinUnresolved;
        private boolean applyUncorrelated;
        private boolean lateralUncorrelated;
        private boolean allColumnsProjection;
        private boolean tableWildcardProjection;
        private boolean unfilteredTopLevel;
        private boolean unfilteredNested;
        private boolean largeOffset;
        private boolean unresolved;

        /** 按风险优先级生成单条可解释结果。 */
        private PerformanceInspection toInspection() {
            if (crossJoin) {
                return PerformanceInspection.warning(RISK_CODE_CROSS_JOIN,
                        "查询包含显式 CROSS JOIN，全局结果上限无法约束连接中间集规模");
            }
            if (implicitCommaJoin) {
                return PerformanceInspection.warning(RISK_CODE_IMPLICIT_COMMA_JOIN,
                        "查询使用逗号连接，静态分析无法证明连接条件完整，需管理员确认中间集规模");
            }
            if (joinConditionMissing) {
                return PerformanceInspection.warning(RISK_CODE_JOIN_CONDITION_MISSING,
                        "查询包含缺少 ON 或 USING 语义的连接，可能形成笛卡尔积");
            }
            if (joinConditionIneffective) {
                return PerformanceInspection.warning(RISK_CODE_JOIN_CONDITION_INEFFECTIVE,
                        "查询连接条件为恒真表达式或无法证明连接当前左右作用域，可能形成笛卡尔积");
            }
            if (naturalJoinUnresolved) {
                return PerformanceInspection.warning(RISK_CODE_NATURAL_JOIN_UNRESOLVED,
                        "查询使用 NATURAL JOIN，但静态分析无法证明左右关系存在公共连接列");
            }
            if (applyUncorrelated) {
                return PerformanceInspection.warning(RISK_CODE_APPLY_UNCORRELATED,
                        "查询使用 APPLY，但右侧表达式无法证明引用当前左侧关系，可能被逐行重复执行");
            }
            if (lateralUncorrelated) {
                return PerformanceInspection.warning(RISK_CODE_LATERAL_UNCORRELATED,
                        "查询使用 LATERAL，但右侧表达式无法证明引用当前左侧关系，可能放大连接中间集");
            }
            if (tableWildcardProjection) {
                return PerformanceInspection.warning(RISK_CODE_TABLE_WILDCARD_PROJECTION,
                        "查询使用表级通配符投影，可能读取不必要字段并放大结果行宽");
            }
            if (allColumnsProjection) {
                return PerformanceInspection.warning(RISK_CODE_ALL_COLUMNS_PROJECTION,
                        "查询使用全字段通配符投影，可能读取不必要字段并放大结果行宽");
            }
            if (unfilteredNested) {
                return PerformanceInspection.warning("SQL_NESTED_UNFILTERED_SCAN_RISK",
                        "JOIN、CTE、派生表或标量子查询中存在无过滤扫描，外层 LIMIT 无法约束其扫描规模");
            }
            if (largeOffset) {
                return PerformanceInspection.warning(RISK_CODE_LARGE_OFFSET,
                        "查询 OFFSET 超过安全预览阈值，可能在返回少量结果前扫描大量记录");
            }
            if (unresolved) {
                return PerformanceInspection.warning("SQL_NESTED_QUERY_RUNTIME_RISK",
                        "嵌套查询结构无法被完整静态分析，需管理员确认运行时扫描规模");
            }
            if (setOperation) {
                return PerformanceInspection.warning("SQL_SET_OPERATION_RUNTIME_RISK",
                        "集合查询虽有全局结果上限，仍需管理员确认各分支运行时扫描规模");
            }
            if (unfilteredTopLevel) {
                return PerformanceInspection.warning("SQL_UNFILTERED_SCAN_RISK",
                        "查询虽有结果上限，但存在无过滤物理表扫描风险，无法静态确认表容量");
            }
            return PerformanceInspection.success();
        }
    }

    /** 单条 SQL 静态性能检查结果。 */
    private record PerformanceInspection(String status, String code, String message,
            boolean completed) {

        /** 创建通过结果。 */
        private static PerformanceInspection success() {
            return new PerformanceInspection(ModelingDraftConstants.VALIDATION_PASSED, null, null,
                    true);
        }

        /** 创建非阻塞风险结果。 */
        private static PerformanceInspection warning(String code, String message) {
            return new PerformanceInspection(ModelingDraftConstants.VALIDATION_WARNING, code,
                    message, true);
        }

        /** LIMIT 非法等可静态确认风险。 */
        private static PerformanceInspection failed() {
            return new PerformanceInspection(ModelingDraftConstants.VALIDATION_FAILED, null, null,
                    true);
        }

        /** 前置链路失败时明确标记未运行。 */
        private static PerformanceInspection notRun() {
            return new PerformanceInspection(ModelingDraftConstants.VALIDATION_NOT_RUN, null, null,
                    false);
        }

        /** 判断是否没有性能风险。 */
        private boolean passed() {
            return ModelingDraftConstants.VALIDATION_PASSED.equals(status);
        }
    }

    /** 物理 SQL LIMIT/FETCH 的保守 AST 校验结果。 */
    private record SqlLimitValidation(boolean valid, String code, String message) {

        /** 创建通过结果。 */
        private static SqlLimitValidation success() {
            return new SqlLimitValidation(true, null, null);
        }

        /** 创建阻塞结果。 */
        private static SqlLimitValidation invalid(String code, String message) {
            return new SqlLimitValidation(false, code, message);
        }
    }

    /** 所有样例结果、独立必需检查及阻塞/警告项。 */
    public record SampleValidation(List<ModelingSampleQuestionResult> results,
            ModelingValidationCheckResult sqlResult,
            ModelingValidationCheckResult sampleQuestionResult,
            ModelingValidationCheckResult sqlGenerationResult,
            ModelingValidationCheckResult sqlReadOnlyResult,
            ModelingValidationCheckResult performanceResult,
            List<ModelingValidationFinding> blockingItems,
            List<ModelingValidationFinding> warningItems) {}
}
