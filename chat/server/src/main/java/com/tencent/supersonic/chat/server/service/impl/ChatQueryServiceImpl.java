package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import com.tencent.supersonic.chat.api.pojo.request.ChatExecuteReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatParseReq;
import com.tencent.supersonic.chat.api.pojo.request.ChatQueryDataReq;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.executor.ChatQueryExecutor;
import com.tencent.supersonic.chat.server.parser.ChatQueryParser;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.pojo.ExecuteContext;
import com.tencent.supersonic.chat.server.pojo.ParseContext;
import com.tencent.supersonic.chat.server.processor.execute.DataInterpretProcessor;
import com.tencent.supersonic.chat.server.processor.execute.ExecuteResultProcessor;
import com.tencent.supersonic.chat.server.processor.parse.ParseResultProcessor;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.service.ChatQueryService;
import com.tencent.supersonic.chat.server.util.ComponentFactory;
import com.tencent.supersonic.chat.server.util.QueryReqConverter;
import com.tencent.supersonic.common.jsqlparser.FieldExpression;
import com.tencent.supersonic.common.jsqlparser.SqlAddHelper;
import com.tencent.supersonic.common.jsqlparser.SqlRemoveHelper;
import com.tencent.supersonic.common.jsqlparser.SqlReplaceHelper;
import com.tencent.supersonic.common.jsqlparser.SqlSelectHelper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.SqlInfo;
import com.tencent.supersonic.headless.api.pojo.request.DimensionValueReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryFilter;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.api.pojo.response.SearchResult;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticTranslateResp;
import com.tencent.supersonic.headless.chat.query.QueryManager;
import com.tencent.supersonic.headless.chat.query.SemanticQuery;
import com.tencent.supersonic.headless.chat.query.llm.s2sql.LLMSqlQuery;
import com.tencent.supersonic.headless.core.translator.parser.calcite.SemanticModelCompileException;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.facade.service.SemanticLayerService;
import com.tencent.supersonic.headless.server.semantic.diagnostic.ModelHealthService;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapEventReq;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapFailureType;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapPropertyKeys;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chat BI 查询解析与执行主服务。
 *
 * <p>职责：编排 parser/processor/executor，捕获语义缺口并传播结构化模型诊断。并发说明：服务为
 * 单例但不保存请求级可变状态；请求上下文与诊断对象均在单次调用内创建。
 */
@Slf4j
@Service
public class ChatQueryServiceImpl implements ChatQueryService {

    private static final double LOW_CONFIDENCE_SCORE_THRESHOLD = 10.0D;

    @Autowired
    private ChatManageService chatManageService;
    @Autowired
    private ChatLayerService chatLayerService;
    @Autowired
    private SemanticLayerService semanticLayerService;
    @Autowired
    @Lazy
    private AgentService agentService;
    @Autowired
    private SemanticGapService semanticGapService;
    @Autowired
    private ModelHealthService modelHealthService;

    private final List<ChatQueryParser> chatQueryParsers = ComponentFactory.getChatParsers();
    private final List<ChatQueryExecutor> chatQueryExecutors = ComponentFactory.getChatExecutors();
    private final List<ParseResultProcessor> parseResultProcessors =
            ComponentFactory.getParseProcessors();
    private final List<ExecuteResultProcessor> executeResultProcessors =
            ComponentFactory.getExecuteProcessors();

    @Override
    public List<SearchResult> search(ChatParseReq chatParseReq) {
        ParseContext parseContext = buildParseContext(chatParseReq, null);
        Agent agent = parseContext.getAgent();
        if (!agent.enableSearch()) {
            return Lists.newArrayList();
        }
        QueryNLReq queryNLReq = QueryReqConverter.buildQueryNLReq(parseContext);
        return chatLayerService.retrieve(queryNLReq);
    }

    @Override
    public ChatParseResp parse(ChatParseReq chatParseReq) {
        Long queryId = chatParseReq.getQueryId();
        if (Objects.isNull(queryId)) {
            queryId = chatManageService.createChatQuery(chatParseReq);
            chatParseReq.setQueryId(queryId);
        }

        ParseContext parseContext = buildParseContext(chatParseReq, new ChatParseResp(queryId));
        for (ChatQueryParser parser : chatQueryParsers) {
            if (parser.accept(parseContext)) {
                try {
                    parser.parse(parseContext);
                } catch (SemanticModelCompileException exception) {
                    // 精确诊断保存在请求上下文，NO_SELECTED_PARSE 只能在没有该根因时兜底。
                    parseContext.getResponse().setState(ParseResp.ParseState.FAILED);
                    parseContext.getResponse()
                            .setErrorMsg(exception.getDiagnostic().getUserMessage());
                    parseContext.getResponse().setDiagnostic(exception.getDiagnostic());
                    captureParserExceptionGap(chatParseReq, parser, exception);
                    break;
                } catch (RuntimeException e) {
                    captureParserExceptionGap(chatParseReq, parser, e);
                    throw e;
                }
            }
        }

        for (ParseResultProcessor processor : parseResultProcessors) {
            if (processor.accept(parseContext)) {
                processor.process(parseContext);
            }
        }

        if (!parseContext.needFeedback()) {
            parseContext.getResponse().getParseTimeCost().setParseTime(System.currentTimeMillis()
                    - parseContext.getResponse().getParseTimeCost().getParseStartTime());
            chatManageService.batchAddParse(chatParseReq, parseContext.getResponse());
            chatManageService.updateParseCostTime(parseContext.getResponse());
        }

        captureNoSelectedParseGap(chatParseReq, parseContext);
        captureLowConfidenceGap(chatParseReq, parseContext);
        return parseContext.getResponse();
    }

    @Override
    public QueryResult execute(ChatExecuteReq chatExecuteReq) {
        QueryResult queryResult = new QueryResult();
        ExecuteContext executeContext = buildExecuteContext(chatExecuteReq);
        try {
            for (ChatQueryExecutor chatQueryExecutor : chatQueryExecutors) {
                if (chatQueryExecutor.accept(executeContext)) {
                    queryResult = chatQueryExecutor.execute(executeContext);
                    if (queryResult != null) {
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            captureExecutionExceptionGap(chatExecuteReq, executeContext.getParseInfo(), e);
            throw e;
        }

        executeContext.setResponse(queryResult);
        if (queryResult != null) {
            for (ExecuteResultProcessor processor : executeResultProcessors) {
                if (processor.accept(executeContext)) {
                    processor.process(executeContext);
                }
            }
            saveQueryResult(chatExecuteReq, queryResult);
            captureExecutionResultGap(chatExecuteReq, executeContext.getParseInfo(), queryResult);
            captureFallbackToLlmSqlGap(chatExecuteReq, executeContext.getParseInfo());
        }

        return queryResult;
    }

    @Override
    public QueryResult getTextSummary(ChatExecuteReq chatExecuteReq) {
        String text = DataInterpretProcessor.getTextSummary(chatExecuteReq.getQueryId());
        if (StringUtils.isNotBlank(text)) {
            QueryResult res = new QueryResult();
            res.setTextSummary(text);
            res.setQueryId(chatExecuteReq.getQueryId());
            return res;
        } else {
            ChatQueryDO chatQueryDo = chatManageService.getChatQueryDO(chatExecuteReq.getQueryId());
            QueryResult res = JSON.parseObject(chatQueryDo.getQueryResult(), QueryResult.class);
            return res;
        }
    }

    @Override
    public QueryResult parseAndExecute(ChatParseReq chatParseReq) {
        ChatParseResp parseResp = parse(chatParseReq);
        if (CollectionUtils.isEmpty(parseResp.getSelectedParses())) {
            log.debug("chatId:{}, agentId:{}, queryText:{}, parseResp.getSelectedParses() is empty",
                    chatParseReq.getChatId(), chatParseReq.getAgentId(),
                    chatParseReq.getQueryText());
            return null;
        }
        ChatExecuteReq executeReq = new ChatExecuteReq();
        executeReq.setQueryId(parseResp.getQueryId());
        executeReq.setParseId(parseResp.getSelectedParses().get(0).getId());
        executeReq.setQueryText(chatParseReq.getQueryText());
        executeReq.setChatId(chatParseReq.getChatId());
        executeReq.setUser(User.getDefaultUser());
        executeReq.setAgentId(chatParseReq.getAgentId());
        executeReq.setSaveAnswer(true);
        return execute(executeReq);
    }

    private ParseContext buildParseContext(ChatParseReq chatParseReq, ChatParseResp chatParseResp) {
        ParseContext parseContext = new ParseContext(chatParseReq, chatParseResp);
        Agent agent = agentService.getAgent(chatParseReq.getAgentId());
        parseContext.setAgent(agent);
        return parseContext;
    }

    private ExecuteContext buildExecuteContext(ChatExecuteReq chatExecuteReq) {
        ExecuteContext executeContext = new ExecuteContext(chatExecuteReq);
        SemanticParseInfo parseInfo = chatManageService.getParseInfo(chatExecuteReq.getQueryId(),
                chatExecuteReq.getParseId());
        Agent agent = agentService.getAgent(chatExecuteReq.getAgentId());
        executeContext.setAgent(agent);
        executeContext.setParseInfo(parseInfo);
        return executeContext;
    }

    @Override
    public Object queryData(ChatQueryDataReq chatQueryDataReq, User user) throws Exception {
        Integer parseId = chatQueryDataReq.getParseId();
        SemanticParseInfo parseInfo =
                chatManageService.getParseInfo(chatQueryDataReq.getQueryId(), parseId);
        mergeParseInfo(parseInfo, chatQueryDataReq);
        DataSetSchema dataSetSchema =
                semanticLayerService.getDataSetSchema(parseInfo.getDataSetId());

        SemanticQuery semanticQuery = QueryManager.createQuery(parseInfo.getQueryMode());
        semanticQuery.setParseInfo(parseInfo);

        try {
            if (LLMSqlQuery.QUERY_MODE.equalsIgnoreCase(parseInfo.getQueryMode())) {
                handleLLMQueryMode(chatQueryDataReq, semanticQuery, dataSetSchema, user);
            } else {
                handleRuleQueryMode(semanticQuery, dataSetSchema, user);
            }

            return executeQuery(semanticQuery, user);
        } catch (Exception e) {
            captureQueryDataExceptionGap(chatQueryDataReq, parseInfo, user, e);
            throw e;
        }
    }

    private List<String> getFieldsFromSql(SemanticParseInfo parseInfo) {
        SqlInfo sqlInfo = parseInfo.getSqlInfo();
        if (Objects.isNull(sqlInfo) || StringUtils.isNotBlank(sqlInfo.getCorrectedS2SQL())) {
            return new ArrayList<>();
        }
        return SqlSelectHelper.getAllSelectFields(sqlInfo.getCorrectedS2SQL());
    }

    private void handleLLMQueryMode(ChatQueryDataReq chatQueryDataReq, SemanticQuery semanticQuery,
            DataSetSchema dataSetSchema, User user) throws Exception {
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        String rebuiltS2SQL;
        if (checkMetricReplace(chatQueryDataReq, parseInfo)) {
            log.info("rebuild S2SQL with adjusted metrics!");
            SchemaElement metricToReplace = chatQueryDataReq.getMetrics().iterator().next();
            rebuiltS2SQL = replaceMetrics(parseInfo, metricToReplace);
        } else {
            log.info("rebuild S2SQL with adjusted filters!");
            rebuiltS2SQL = replaceFilters(chatQueryDataReq, parseInfo, dataSetSchema);
        }
        // reset SqlInfo and request re-translation
        parseInfo.getSqlInfo().setCorrectedS2SQL(rebuiltS2SQL);
        parseInfo.getSqlInfo().setParsedS2SQL(rebuiltS2SQL);
        parseInfo.getSqlInfo().setQuerySQL(null);
        SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
        SemanticTranslateResp explain = semanticLayerService.translate(semanticQueryReq, user);
        parseInfo.getSqlInfo().setQuerySQL(explain.getQuerySQL());
    }

    private void handleRuleQueryMode(SemanticQuery semanticQuery, DataSetSchema dataSetSchema,
            User user) {
        log.info("rule begin replace metrics and revise filters!");
        validFilter(semanticQuery.getParseInfo().getDimensionFilters());
        validFilter(semanticQuery.getParseInfo().getMetricFilters());
        semanticQuery.buildS2Sql(dataSetSchema);
    }

    private QueryResult executeQuery(SemanticQuery semanticQuery, User user) throws Exception {
        SemanticQueryReq semanticQueryReq = semanticQuery.buildSemanticQueryReq();
        SemanticParseInfo parseInfo = semanticQuery.getParseInfo();
        QueryResult queryResult = doExecution(semanticQueryReq, parseInfo.getQueryMode(), user);
        queryResult.setChatContext(semanticQuery.getParseInfo());
        parseInfo.getSqlInfo().setQuerySQL(queryResult.getQuerySql());
        return queryResult;
    }

    private boolean checkMetricReplace(ChatQueryDataReq chatQueryDataReq,
            SemanticParseInfo parseInfo) {
        List<String> oriFields = getFieldsFromSql(parseInfo);
        Set<SchemaElement> metrics = chatQueryDataReq.getMetrics();
        if (CollectionUtils.isEmpty(oriFields) || CollectionUtils.isEmpty(metrics)) {
            return false;
        }
        List<String> metricNames =
                metrics.stream().map(SchemaElement::getName).collect(Collectors.toList());
        return !oriFields.containsAll(metricNames);
    }

    private String replaceFilters(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema) {
        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.info("correctorSql before replacing:{}", correctorSql);
        // get where filter and having filter
        List<FieldExpression> whereExpressionList =
                SqlSelectHelper.getWhereExpressions(correctorSql);

        // replace where filter
        List<Expression> addWhereConditions = new ArrayList<>();
        Set<String> removeWhereFieldNames =
                updateFilters(whereExpressionList, queryData.getDimensionFilters(),
                        parseInfo.getDimensionFilters(), addWhereConditions);

        Map<String, Map<String, String>> filedNameToValueMap = new HashMap<>();
        Set<String> removeDataFieldNames = updateDateInfo(queryData, parseInfo, dataSetSchema,
                filedNameToValueMap, whereExpressionList, addWhereConditions);
        removeWhereFieldNames.addAll(removeDataFieldNames);

        correctorSql = SqlReplaceHelper.replaceValue(correctorSql, filedNameToValueMap);
        correctorSql = SqlRemoveHelper.removeWhereCondition(correctorSql, removeWhereFieldNames);

        // replace having filter
        List<FieldExpression> havingExpressionList =
                SqlSelectHelper.getHavingExpressions(correctorSql);
        List<Expression> addHavingConditions = new ArrayList<>();
        Set<String> removeHavingFieldNames =
                updateFilters(havingExpressionList, queryData.getDimensionFilters(),
                        parseInfo.getDimensionFilters(), addHavingConditions);
        correctorSql = SqlReplaceHelper.replaceHavingValue(correctorSql, new HashMap<>());
        correctorSql = SqlRemoveHelper.removeHavingCondition(correctorSql, removeHavingFieldNames);

        correctorSql = SqlAddHelper.addWhere(correctorSql, addWhereConditions);
        correctorSql = SqlAddHelper.addHaving(correctorSql, addHavingConditions);
        log.info("correctorSql after replacing:{}", correctorSql);
        return correctorSql;
    }

    private String replaceMetrics(SemanticParseInfo parseInfo, SchemaElement metric) {
        List<String> oriMetrics = parseInfo.getMetrics().stream().map(SchemaElement::getName)
                .collect(Collectors.toList());
        String correctorSql = parseInfo.getSqlInfo().getCorrectedS2SQL();
        log.info("before replaceMetrics:{}", correctorSql);
        log.info("filteredMetrics:{},metrics:{}", oriMetrics, metric);
        Map<String, Pair<String, String>> fieldMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(oriMetrics) && !oriMetrics.contains(metric.getName())) {
            fieldMap.put(oriMetrics.get(0), Pair.of(metric.getName(), metric.getDefaultAgg()));
            correctorSql = SqlReplaceHelper.replaceAggFields(correctorSql, fieldMap);
        }
        log.info("after replaceMetrics:{}", correctorSql);
        return correctorSql;
    }

    private QueryResult doExecution(SemanticQueryReq semanticQueryReq, String queryMode, User user)
            throws Exception {
        SemanticQueryResp queryResp = semanticLayerService.queryByReq(semanticQueryReq, user);
        QueryResult queryResult = new QueryResult();

        if (queryResp != null) {
            queryResult.setQueryAuthorization(queryResp.getQueryAuthorization());
            queryResult.setQuerySql(queryResp.getSql());
            queryResult.setQueryResults(queryResp.getResultList());
            queryResult.setQueryColumns(queryResp.getColumns());
        } else {
            queryResult.setQueryResults(new ArrayList<>());
            queryResult.setQueryColumns(new ArrayList<>());
        }

        queryResult.setQueryMode(queryMode);
        queryResult.setQueryState(QueryState.SUCCESS);
        return queryResult;
    }

    private Set<String> updateDateInfo(ChatQueryDataReq queryData, SemanticParseInfo parseInfo,
            DataSetSchema dataSetSchema, Map<String, Map<String, String>> filedNameToValueMap,
            List<FieldExpression> fieldExpressionList, List<Expression> addConditions) {
        Set<String> removeFieldNames = new HashSet<>();
        if (Objects.isNull(queryData.getDateInfo())) {
            return removeFieldNames;
        }
        if (queryData.getDateInfo().getUnit() > 1) {
            queryData.getDateInfo()
                    .setStartDate(DateUtils.getBeforeDate(queryData.getDateInfo().getUnit() + 1));
            queryData.getDateInfo().setEndDate(DateUtils.getBeforeDate(0));
        }
        SchemaElement partitionDimension = dataSetSchema.getPartitionDimension();
        // startDate equals to endDate
        for (FieldExpression fieldExpression : fieldExpressionList) {
            if (partitionDimension.getName().equals(fieldExpression.getFieldName())) {
                // first remove,then add
                removeFieldNames.add(partitionDimension.getName());
                GreaterThanEquals greaterThanEquals = new GreaterThanEquals();
                addTimeFilters(queryData.getDateInfo().getStartDate(), greaterThanEquals,
                        addConditions, partitionDimension);
                MinorThanEquals minorThanEquals = new MinorThanEquals();
                addTimeFilters(queryData.getDateInfo().getEndDate(), minorThanEquals, addConditions,
                        partitionDimension);
                break;
            }
        }
        for (FieldExpression fieldExpression : fieldExpressionList) {
            for (QueryFilter queryFilter : queryData.getDimensionFilters()) {
                if (queryFilter.getOperator().equals(FilterOperatorEnum.LIKE)
                        && FilterOperatorEnum.LIKE.getValue()
                                .equalsIgnoreCase(fieldExpression.getOperator())) {
                    Map<String, String> replaceMap = new HashMap<>();
                    String preValue = fieldExpression.getFieldValue().toString();
                    String curValue = queryFilter.getValue().toString();
                    if (preValue.startsWith("%")) {
                        curValue = "%" + curValue;
                    }
                    if (preValue.endsWith("%")) {
                        curValue = curValue + "%";
                    }
                    replaceMap.put(preValue, curValue);
                    filedNameToValueMap.put(fieldExpression.getFieldName(), replaceMap);
                    break;
                }
            }
        }
        parseInfo.setDateInfo(queryData.getDateInfo());
        return removeFieldNames;
    }

    private <T extends ComparisonOperator> void addTimeFilters(String date, T comparisonExpression,
            List<Expression> addConditions, SchemaElement partitionDimension) {
        Column column = new Column(partitionDimension.getName());
        StringValue stringValue = new StringValue(date);
        comparisonExpression.setLeftExpression(column);
        comparisonExpression.setRightExpression(stringValue);
        addConditions.add(comparisonExpression);
    }

    private Set<String> updateFilters(List<FieldExpression> fieldExpressionList,
            Set<QueryFilter> metricFilters, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        Set<String> removeFieldNames = new HashSet<>();
        if (CollectionUtils.isEmpty(metricFilters)) {
            return removeFieldNames;
        }

        for (QueryFilter dslQueryFilter : metricFilters) {
            for (FieldExpression fieldExpression : fieldExpressionList) {
                if (fieldExpression.getFieldName() != null
                        && fieldExpression.getFieldName().contains(dslQueryFilter.getName())) {
                    removeFieldNames.add(dslQueryFilter.getName());
                    handleFilter(dslQueryFilter, contextMetricFilters, addConditions);
                    break;
                }
            }
        }
        return removeFieldNames;
    }

    private void handleFilter(QueryFilter dslQueryFilter, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        FilterOperatorEnum operator = dslQueryFilter.getOperator();

        if (operator == FilterOperatorEnum.IN) {
            addWhereInFilters(dslQueryFilter, new InExpression(), contextMetricFilters,
                    addConditions);
        } else {
            ComparisonOperator expression = FilterOperatorEnum.createExpression(operator);
            if (Objects.nonNull(expression)) {
                addWhereFilters(dslQueryFilter, expression, contextMetricFilters, addConditions);
            }
        }
    }

    // add in condition to sql where condition
    private void addWhereInFilters(QueryFilter dslQueryFilter, InExpression inExpression,
            Set<QueryFilter> contextMetricFilters, List<Expression> addConditions) {
        Column column = new Column(dslQueryFilter.getName());
        ParenthesedExpressionList parenthesedExpressionList = new ParenthesedExpressionList<>();
        List<String> valueList =
                JsonUtil.toList(JsonUtil.toString(dslQueryFilter.getValue()), String.class);
        if (CollectionUtils.isEmpty(valueList)) {
            return;
        }
        valueList.forEach(o -> {
            StringValue stringValue = new StringValue(o);
            parenthesedExpressionList.add(stringValue);
        });
        inExpression.setLeftExpression(column);
        inExpression.setRightExpression(parenthesedExpressionList);
        addConditions.add(inExpression);
        contextMetricFilters.forEach(o -> {
            if (o.getName().equals(dslQueryFilter.getName())) {
                o.setValue(dslQueryFilter.getValue());
                o.setOperator(dslQueryFilter.getOperator());
            }
        });
    }

    // add where filter
    private void addWhereFilters(QueryFilter dslQueryFilter,
            ComparisonOperator comparisonExpression, Set<QueryFilter> contextMetricFilters,
            List<Expression> addConditions) {
        String columnName = dslQueryFilter.getName();
        if (StringUtils.isNotBlank(dslQueryFilter.getFunction())) {
            columnName = dslQueryFilter.getFunction() + "(" + dslQueryFilter.getName() + ")";
        }
        if (Objects.isNull(dslQueryFilter.getValue())) {
            return;
        }
        Column column = new Column(columnName);
        comparisonExpression.setLeftExpression(column);
        if (StringUtils.isNumeric(dslQueryFilter.getValue().toString())) {
            LongValue longValue =
                    new LongValue(Long.parseLong(dslQueryFilter.getValue().toString()));
            comparisonExpression.setRightExpression(longValue);
        } else {
            StringValue stringValue = new StringValue(dslQueryFilter.getValue().toString());
            comparisonExpression.setRightExpression(stringValue);
        }
        addConditions.add(comparisonExpression);
        contextMetricFilters.forEach(o -> {
            if (o.getName().equals(dslQueryFilter.getName())) {
                o.setValue(dslQueryFilter.getValue());
                o.setOperator(dslQueryFilter.getOperator());
            }
        });
    }

    private void mergeParseInfo(SemanticParseInfo parseInfo, ChatQueryDataReq queryData) {
        if (Objects.nonNull(queryData.getDateInfo())) {
            parseInfo.setDateInfo(queryData.getDateInfo());
        }
        if (LLMSqlQuery.QUERY_MODE.equals(parseInfo.getQueryMode())) {
            return;
        }
        if (!CollectionUtils.isEmpty(queryData.getDimensions())) {
            parseInfo.setDimensions(queryData.getDimensions());
        }
        if (!CollectionUtils.isEmpty(queryData.getMetrics())) {
            parseInfo.setMetrics(queryData.getMetrics());
        }
        if (!CollectionUtils.isEmpty(queryData.getDimensionFilters())) {
            parseInfo.setDimensionFilters(queryData.getDimensionFilters());
        }
        if (!CollectionUtils.isEmpty(queryData.getMetricFilters())) {
            parseInfo.setMetricFilters(queryData.getMetricFilters());
        }

        parseInfo.setSqlInfo(new SqlInfo());
    }

    private void validFilter(Set<QueryFilter> filters) {
        Iterator<QueryFilter> iterator = filters.iterator();
        while (iterator.hasNext()) {
            QueryFilter queryFilter = iterator.next();
            Object queryFilterValue = queryFilter.getValue();
            if (Objects.isNull(queryFilterValue)) {
                iterator.remove();
                continue;
            }
            List<String> collection = new ArrayList<>();
            if (queryFilterValue instanceof List) {
                collection.addAll((List) queryFilterValue);
            } else if (queryFilterValue instanceof String) {
                collection.add((String) queryFilterValue);
            }
            if (FilterOperatorEnum.IN.equals(queryFilter.getOperator())
                    && CollectionUtils.isEmpty(collection)) {
                iterator.remove();
            }
        }
    }

    @Override
    public Object queryDimensionValue(DimensionValueReq dimensionValueReq, User user) {
        Integer agentId = dimensionValueReq.getAgentId();
        Agent agent = agentService.getAgent(agentId);
        dimensionValueReq.setDataSetIds(agent.getDataSetIds());
        return semanticLayerService.queryDimensionValue(dimensionValueReq, user);
    }

    public void saveQueryResult(ChatExecuteReq chatExecuteReq, QueryResult queryResult) {
        // The history record only retains the query result of the first parse
        if (chatExecuteReq.getParseId() > 1) {
            return;
        }
        chatManageService.saveQueryResult(chatExecuteReq, queryResult);
    }

    /** 捕获解析后没有 selectedParses 的缺口，放在 service 层以覆盖 parse 和 query 两种调用方式。 */
    private void captureNoSelectedParseGap(ChatParseReq chatParseReq, ParseContext parseContext) {
        if (parseContext.needFeedback()
                || !CollectionUtils.isEmpty(parseContext.getResponse().getSelectedParses())) {
            return;
        }
        SemanticGapEventReq eventReq = buildBaseGapEvent(chatParseReq.getQueryText(),
                chatParseReq.getQueryId(), chatParseReq.getChatId(), chatParseReq.getAgentId(),
                chatParseReq.getUser());
        if (parseContext.getResponse().getDiagnostic() != null) {
            eventReq.setFailureType(SemanticGapFailureType.TECHNICAL_VALIDATION_FAILED);
            fillSemanticDiagnostic(eventReq, parseContext.getResponse().getDiagnostic());
            eventReq.setFailureReason(parseContext.getResponse().getDiagnostic().getUserMessage());
        } else {
            eventReq.setFailureType(SemanticGapFailureType.NO_SELECTED_PARSE);
            eventReq.setFailureReason("parser error,no selectedParses");
        }
        captureGapSafely(eventReq);
    }

    /** 捕获 parser 循环中的运行时异常；采集后继续抛出，保持原 API 异常语义。 */
    private void captureParserExceptionGap(ChatParseReq chatParseReq, ChatQueryParser parser,
            RuntimeException exception) {
        SemanticGapEventReq eventReq = buildBaseGapEvent(chatParseReq.getQueryText(),
                chatParseReq.getQueryId(), chatParseReq.getChatId(), chatParseReq.getAgentId(),
                chatParseReq.getUser());
        if (exception instanceof SemanticModelCompileException compileException) {
            eventReq.setFailureType(SemanticGapFailureType.TECHNICAL_VALIDATION_FAILED);
            fillSemanticDiagnostic(eventReq, compileException.getDiagnostic());
            eventReq.setFailureReason(compileException.getDiagnostic().getUserMessage());
        } else {
            eventReq.setFailureType(SemanticGapFailureType.PARSER_EXCEPTION);
            eventReq.setFailureReason(String.format("%s: %s", parser.getClass().getSimpleName(),
                    StringUtils.defaultIfBlank(exception.getMessage(),
                            exception.getClass().getSimpleName())));
        }
        captureGapSafely(eventReq);
    }

    /** 捕获规则解析候选置信度偏低的信号，不影响后续执行或候选展示。 */
    private void captureLowConfidenceGap(ChatParseReq chatParseReq, ParseContext parseContext) {
        if (parseContext.needFeedback()
                || CollectionUtils.isEmpty(parseContext.getResponse().getSelectedParses())) {
            return;
        }
        SemanticParseInfo topParse = parseContext.getResponse().getSelectedParses().get(0);
        if (topParse == null || LLMSqlQuery.QUERY_MODE.equalsIgnoreCase(topParse.getQueryMode())
                || topParse.getScore() >= LOW_CONFIDENCE_SCORE_THRESHOLD) {
            return;
        }
        SemanticGapEventReq eventReq = buildBaseGapEvent(chatParseReq.getQueryText(),
                chatParseReq.getQueryId(), chatParseReq.getChatId(), chatParseReq.getAgentId(),
                chatParseReq.getUser());
        fillParseDiagnostics(eventReq, topParse);
        eventReq.setFailureType(SemanticGapFailureType.LOW_CONFIDENCE);
        eventReq.setFailureReason(String.format("highest parse score %.2f is below %.2f",
                topParse.getScore(), LOW_CONFIDENCE_SCORE_THRESHOLD));
        captureGapSafely(eventReq);
    }

    /** 捕获 execute 接口中 SQL 或语义执行抛出的异常，同时保持原异常继续向上抛出。 */
    private void captureExecutionExceptionGap(ChatExecuteReq chatExecuteReq,
            SemanticParseInfo parseInfo, RuntimeException exception) {
        SemanticGapEventReq eventReq = buildBaseGapEvent(chatExecuteReq.getQueryText(),
                chatExecuteReq.getQueryId(), chatExecuteReq.getChatId(),
                chatExecuteReq.getAgentId(), chatExecuteReq.getUser());
        fillParseDiagnostics(eventReq, parseInfo);
        if (exception instanceof SemanticModelCompileException compileException) {
            eventReq.setFailureType(SemanticGapFailureType.TECHNICAL_VALIDATION_FAILED);
            fillSemanticDiagnostic(eventReq, compileException.getDiagnostic());
            eventReq.setFailureReason(compileException.getDiagnostic().getUserMessage());
        } else {
            eventReq.setFailureType(SemanticGapFailureType.SQL_EXECUTION_ERROR);
            eventReq.setFailureReason(exception.getMessage());
        }
        captureGapSafely(eventReq);
    }

    /** 把安全结构化诊断复制到缺口事件，禁止复制完整 SQL 或堆栈。 */
    private void fillSemanticDiagnostic(SemanticGapEventReq eventReq,
            com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic diagnostic) {
        eventReq.setDiagnosticStage(
                diagnostic.getStage() == null ? null : diagnostic.getStage().name());
        eventReq.setErrorCode(diagnostic.getCode() == null ? null : diagnostic.getCode().name());
        eventReq.setTraceId(diagnostic.getTraceId());
        eventReq.setErrorLine(diagnostic.getLine());
        eventReq.setErrorColumn(diagnostic.getColumn());
        eventReq.setErrorToken(diagnostic.getToken());
        eventReq.setSuggestion(diagnostic.getSuggestion());
        if (diagnostic.getModelId() != null) {
            eventReq.setMatchedModelIds(String.valueOf(diagnostic.getModelId()));
            modelHealthService.recordQueryFailure(
                    java.util.Collections.singletonList(diagnostic.getModelId()), diagnostic);
        }
    }

    /** 捕获执行返回非 SUCCESS 的缺口，避免只有抛异常的失败才进入缺口池。 */
    private void captureExecutionResultGap(ChatExecuteReq chatExecuteReq,
            SemanticParseInfo parseInfo, QueryResult queryResult) {
        if (QueryState.SUCCESS.equals(queryResult.getQueryState())) {
            return;
        }
        SemanticGapEventReq eventReq = buildBaseGapEvent(chatExecuteReq.getQueryText(),
                chatExecuteReq.getQueryId(), chatExecuteReq.getChatId(),
                chatExecuteReq.getAgentId(), chatExecuteReq.getUser());
        fillParseDiagnostics(eventReq, parseInfo);
        eventReq.setGeneratedSql(queryResult.getQuerySql());
        eventReq.setFailureType(SemanticGapFailureType.SQL_EXECUTION_ERROR);
        eventReq.setFailureReason(StringUtils.defaultIfBlank(queryResult.getErrorMsg(),
                String.format("query state is %s", queryResult.getQueryState())));
        captureGapSafely(eventReq);
    }

    /** 捕获 queryData 分步查询中的语义 SQL 执行异常。 */
    private void captureQueryDataExceptionGap(ChatQueryDataReq chatQueryDataReq,
            SemanticParseInfo parseInfo, User user, Exception exception) {
        SemanticGapEventReq eventReq =
                buildBaseGapEvent(null, chatQueryDataReq.getQueryId(), null, null, user);
        fillParseDiagnostics(eventReq, parseInfo);
        if (StringUtils.isBlank(eventReq.getQuestion())) {
            eventReq.setQuestion(parseInfo == null ? "queryId:" + chatQueryDataReq.getQueryId()
                    : parseInfo.getTextInfo());
        }
        if (StringUtils.isBlank(eventReq.getQuestion())) {
            eventReq.setQuestion("queryId:" + chatQueryDataReq.getQueryId());
        }
        eventReq.setFailureType(SemanticGapFailureType.SQL_EXECUTION_ERROR);
        eventReq.setFailureReason(exception.getMessage());
        captureGapSafely(eventReq);
    }

    /** 捕获回退到 LLM SQL 模式的信号，阶段 2 只沉淀缺口，不触碰 LLM Gateway。 */
    private void captureFallbackToLlmSqlGap(ChatExecuteReq chatExecuteReq,
            SemanticParseInfo parseInfo) {
        if (!isSemanticGapFallbackParse(parseInfo)) {
            return;
        }
        SemanticGapEventReq eventReq = buildBaseGapEvent(chatExecuteReq.getQueryText(),
                chatExecuteReq.getQueryId(), chatExecuteReq.getChatId(),
                chatExecuteReq.getAgentId(), chatExecuteReq.getUser());
        fillParseDiagnostics(eventReq, parseInfo);
        eventReq.setFailureType(SemanticGapFailureType.FALLBACK_TO_LLM_SQL);
        Object reason = parseInfo.getProperties().get(SemanticGapPropertyKeys.FALLBACK_REASON);
        eventReq.setFailureReason(
                StringUtils.defaultIfBlank(reason == null ? null : String.valueOf(reason),
                        "query selected semantic gap fallback LLM SQL mode"));
        captureGapSafely(eventReq);
    }

    /** 判断 parseInfo 是否来自语义缺口定义的真实 fallback，而不是普通 LLM_S2SQL。 */
    private boolean isSemanticGapFallbackParse(SemanticParseInfo parseInfo) {
        if (parseInfo == null || !LLMSqlQuery.QUERY_MODE.equalsIgnoreCase(parseInfo.getQueryMode())
                || parseInfo.getProperties() == null) {
            return false;
        }
        Object fallback = parseInfo.getProperties().get(SemanticGapPropertyKeys.FALLBACK);
        return Boolean.TRUE.equals(fallback) || Boolean.parseBoolean(String.valueOf(fallback));
    }

    /** 构造缺口采集基础事件，统一处理用户、会话和问题上下文。 */
    private SemanticGapEventReq buildBaseGapEvent(String question, Long queryId, Integer chatId,
            Integer agentId, User user) {
        SemanticGapEventReq eventReq = new SemanticGapEventReq();
        eventReq.setQuestion(question);
        eventReq.setQueryId(queryId);
        eventReq.setChatId(chatId == null ? null : chatId.longValue());
        eventReq.setAssistantId(agentId);
        if (user != null) {
            eventReq.setUserId(user.getId());
            eventReq.setUserName(user.getName());
        }
        return eventReq;
    }

    /** 从语义解析结果提取模型、指标、维度、SQL 和 S2SQL 诊断上下文。 */
    private void fillParseDiagnostics(SemanticGapEventReq eventReq, SemanticParseInfo parseInfo) {
        if (parseInfo == null) {
            return;
        }
        if (StringUtils.isBlank(eventReq.getQuestion())) {
            eventReq.setQuestion(parseInfo.getTextInfo());
        }
        if (parseInfo.getSqlInfo() != null) {
            eventReq.setGeneratedSql(
                    StringUtils.defaultIfBlank(parseInfo.getSqlInfo().getQuerySQL(),
                            parseInfo.getSqlInfo().getCorrectedQuerySQL()));
            eventReq.setS2sql(StringUtils.defaultIfBlank(parseInfo.getSqlInfo().getCorrectedS2SQL(),
                    parseInfo.getSqlInfo().getParsedS2SQL()));
        }
        eventReq.setMatchedMetricIds(joinSchemaElementIds(parseInfo.getMetrics()));
        eventReq.setMatchedDimensionIds(joinSchemaElementIds(parseInfo.getDimensions()));
        eventReq.setMatchedModelIds(joinMatchedModelIds(parseInfo));
    }

    /** 拼接 SchemaElement ID，前端用于诊断展示，不在这里反查名称以避免 N+1 查询。 */
    private String joinSchemaElementIds(Set<SchemaElement> elements) {
        if (CollectionUtils.isEmpty(elements)) {
            return null;
        }
        return elements.stream().map(SchemaElement::getId).filter(Objects::nonNull)
                .map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 拼接模型 ID；当元素缺少 model 时退化为 dataSetId，保留第一版可用诊断线索。 */
    private String joinMatchedModelIds(SemanticParseInfo parseInfo) {
        Set<Long> modelIds = new HashSet<>();
        if (parseInfo.getDataSet() != null && parseInfo.getDataSet().getModel() != null) {
            modelIds.add(parseInfo.getDataSet().getModel());
        }
        if (!CollectionUtils.isEmpty(parseInfo.getMetrics())) {
            parseInfo.getMetrics().stream().map(SchemaElement::getModel).filter(Objects::nonNull)
                    .forEach(modelIds::add);
        }
        if (!CollectionUtils.isEmpty(parseInfo.getDimensions())) {
            parseInfo.getDimensions().stream().map(SchemaElement::getModel).filter(Objects::nonNull)
                    .forEach(modelIds::add);
        }
        if (CollectionUtils.isEmpty(modelIds) && parseInfo.getDataSetId() != null) {
            modelIds.add(parseInfo.getDataSetId());
        }
        return modelIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 缺口采集不能拖慢或阻断问答主链路，因此异常只记日志。 */
    private void captureGapSafely(SemanticGapEventReq eventReq) {
        try {
            semanticGapService.captureAsync(eventReq);
        } catch (Exception e) {
            log.warn("failed to capture semantic gap for queryId {}", eventReq.getQueryId(), e);
        }
    }
}
