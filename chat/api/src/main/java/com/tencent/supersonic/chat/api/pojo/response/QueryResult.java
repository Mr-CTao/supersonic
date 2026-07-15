package com.tencent.supersonic.chat.api.pojo.response;

import com.tencent.supersonic.common.pojo.QueryAuthorization;
import com.tencent.supersonic.common.pojo.QueryColumn;
import com.tencent.supersonic.headless.api.pojo.AggregateInfo;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Chat BI 查询执行结果。
 *
 * <p>职责：承载物理查询结果和可选的语义诊断，使执行阶段失败可沿同一结构化协议返回前端。
 */
@Data
public class QueryResult {
    private Long queryId;
    private String queryMode;
    private String querySql;
    private QueryState queryState = QueryState.EMPTY;
    private List<QueryColumn> queryColumns;
    private QueryAuthorization queryAuthorization;
    private SemanticParseInfo chatContext;
    private Object response;
    private List<Map<String, Object>> queryResults;
    private String textResult;
    private String textSummary;
    private Long queryTimeCost;
    private List<SchemaElement> recommendedDimensions;
    private AggregateInfo aggregateInfo;
    private String errorMsg;
    private SemanticDiagnostic diagnostic;
}
