package com.tencent.supersonic.chat.api.pojo.response;

import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.Text2SQLExemplar;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.ParseResp;
import com.tencent.supersonic.headless.api.pojo.response.ParseTimeCostResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic;
import lombok.Data;

import java.util.List;

/**
 * Chat BI 解析阶段响应。
 *
 * <p>职责：返回候选解析、状态、耗时以及可选的安全结构化诊断；不得承载原始异常堆栈或完整模型 SQL。
 */
@Data
public class ChatParseResp {

    private Long queryId;
    private ParseResp.ParseState state = ParseResp.ParseState.PENDING;
    private String errorMsg;
    private SemanticDiagnostic diagnostic;
    private List<SemanticParseInfo> selectedParses = Lists.newArrayList();
    private ParseTimeCostResp parseTimeCost = new ParseTimeCostResp();
    private List<Text2SQLExemplar> usedExemplars;

    public ChatParseResp(Long queryId) {
        this.queryId = queryId;
    }

}
