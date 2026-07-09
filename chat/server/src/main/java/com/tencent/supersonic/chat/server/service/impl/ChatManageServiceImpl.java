package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.chat.api.pojo.enums.MemoryReviewResult;
import com.tencent.supersonic.chat.api.pojo.enums.MemoryStatus;
import com.tencent.supersonic.chat.api.pojo.request.*;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.api.pojo.response.ShowCaseResp;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatParseDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.QueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.persistence.repository.ChatRepository;
import com.tencent.supersonic.chat.server.pojo.ChatMemory;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.service.MemoryService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.api.pojo.response.QueryState;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapEventReq;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapFailureType;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatManageServiceImpl implements ChatManageService {

    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private ChatQueryRepository chatQueryRepository;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private SemanticGapService semanticGapService;
    @Autowired
    @Qualifier("semanticGapCaptureExecutor")
    private ThreadPoolExecutor semanticGapCaptureExecutor;

    @Override
    public Long addChat(User user, String chatName, Integer agentId) {
        ChatDO chatDO = new ChatDO();
        chatDO.setChatName(chatName);
        chatDO.setCreator(user.getName());
        chatDO.setCreateTime(getCurrentTime());
        chatDO.setIsDelete(0);
        chatDO.setLastTime(getCurrentTime());
        chatDO.setLastQuestion("Hello, welcome to using supersonic");
        chatDO.setIsTop(0);
        chatDO.setAgentId(agentId);
        return chatRepository.createChat(chatDO);
    }

    @Override
    public List<ChatDO> getAll(String userName, Integer agentId) {
        return chatRepository.getAll(userName, agentId);
    }

    @Override
    public boolean updateChatName(Long chatId, String chatName, String userName) {
        return chatRepository.updateChatName(chatId, chatName, getCurrentTime(), userName);
    }

    @Override
    public boolean updateFeedback(Long id, Integer score, String feedback) {
        QueryDO intelligentQueryDO = new QueryDO();
        intelligentQueryDO.setId(id);
        intelligentQueryDO.setQuestionId(id);
        intelligentQueryDO.setScore(score);
        intelligentQueryDO.setFeedback(feedback);

        // enable or disable memory based on user feedback
        if (score >= 5 || score <= 1) {
            ChatMemoryFilter memoryFilter = ChatMemoryFilter.builder().queryId(id).build();
            List<ChatMemory> memories = memoryService.getMemories(memoryFilter);
            memories.forEach(m -> {
                MemoryStatus status = score >= 5 ? MemoryStatus.ENABLED : MemoryStatus.DISABLED;
                MemoryReviewResult reviewResult =
                        score >= 5 ? MemoryReviewResult.POSITIVE : MemoryReviewResult.NEGATIVE;
                ChatMemoryUpdateReq memoryUpdateReq = ChatMemoryUpdateReq.builder().id(m.getId())
                        .status(status).humanReviewRet(reviewResult)
                        .humanReviewCmt("Reviewed as per user feedback").build();
                memoryService.updateMemory(memoryUpdateReq, User.getDefaultUser());
            });
        }

        boolean updated = chatRepository.updateFeedback(intelligentQueryDO);
        captureNegativeFeedbackGap(id, score, feedback, updated);
        return updated;
    }

    @Override
    public boolean updateChatIsTop(Long chatId, int isTop) {
        return chatRepository.updateConversionIsTop(chatId, isTop);
    }

    @Override
    public Boolean deleteChat(Long chatId, String userName) {
        return chatRepository.deleteChat(chatId, userName);
    }

    @Override
    public PageInfo<QueryResp> queryInfo(PageQueryInfoReq pageQueryInfoReq, long chatId) {
        PageInfo<QueryResp> queryRespPageInfo =
                chatQueryRepository.getChatQuery(pageQueryInfoReq, chatId);
        if (CollectionUtils.isEmpty(queryRespPageInfo.getList())) {
            return queryRespPageInfo;
        }
        fillParseInfo(queryRespPageInfo.getList());
        return queryRespPageInfo;
    }

    @Override
    public Long createChatQuery(ChatParseReq chatParseReq) {
        return chatQueryRepository.createChatQuery(chatParseReq);
    }

    @Override
    public QueryResp getChatQuery(Long queryId) {
        return chatQueryRepository.getChatQuery(queryId);
    }

    @Override
    public ChatQueryDO getChatQueryDO(Long queryId) {
        return chatQueryRepository.getChatQueryDO(queryId);
    }

    @Override
    public List<QueryResp> getChatQueries(Integer chatId) {
        List<QueryResp> queries = chatQueryRepository.getChatQueries(chatId);
        fillParseInfo(queries);
        return queries;
    }

    @Override
    public ShowCaseResp queryShowCase(PageQueryInfoReq pageQueryInfoReq, int agentId) {
        ShowCaseResp showCaseResp = new ShowCaseResp();
        showCaseResp.setCurrent(pageQueryInfoReq.getCurrent());
        showCaseResp.setPageSize(pageQueryInfoReq.getPageSize());
        List<QueryResp> queryResps = chatQueryRepository.queryShowCase(pageQueryInfoReq, agentId);
        if (CollectionUtils.isEmpty(queryResps)) {
            return showCaseResp;
        }
        queryResps.removeIf(queryResp -> {
            if (queryResp.getQueryResult() == null) {
                return true;
            }
            if (queryResp.getQueryResult().getResponse() != null) {
                return false;
            }
            if (CollectionUtils.isEmpty(queryResp.getQueryResult().getQueryResults())) {
                return true;
            }
            Map<String, Object> data = queryResp.getQueryResult().getQueryResults().get(0);
            return CollectionUtils.isEmpty(data);
        });
        queryResps = new ArrayList<>(queryResps.stream()
                .collect(Collectors.toMap(QueryResp::getQueryText, Function.identity(),
                        (existing, replacement) -> existing, LinkedHashMap::new))
                .values());
        fillParseInfo(queryResps);
        Map<Long, List<QueryResp>> showCaseMap =
                queryResps.stream().collect(Collectors.groupingBy(QueryResp::getChatId));
        showCaseResp.setShowCaseMap(showCaseMap);
        return showCaseResp;
    }

    private void fillParseInfo(List<QueryResp> queryResps) {
        List<Long> queryIds =
                queryResps.stream().map(QueryResp::getQuestionId).collect(Collectors.toList());
        List<ChatParseDO> chatParseDOs = chatQueryRepository.getParseInfoList(queryIds);
        if (CollectionUtils.isEmpty(chatParseDOs)) {
            return;
        }
        Map<Long, List<ChatParseDO>> chatParseMap =
                chatParseDOs.stream().collect(Collectors.groupingBy(ChatParseDO::getQuestionId));
        for (QueryResp queryResp : queryResps) {
            List<ChatParseDO> chatParseDOList = chatParseMap.get(queryResp.getQuestionId());
            if (CollectionUtils.isEmpty(chatParseDOList)) {
                continue;
            }
            List<SemanticParseInfo> parseInfos = chatParseDOList.stream()
                    .map(chatParseDO -> JsonUtil.toObject(chatParseDO.getParseInfo(),
                            SemanticParseInfo.class))
                    .sorted(Comparator.comparingDouble(SemanticParseInfo::getScore).reversed())
                    .collect(Collectors.toList());
            queryResp.setParseInfos(parseInfos);
        }
    }

    /** 用户负反馈是语义缺口的重要来源；只在反馈更新成功且分数明确较低时提交后台采集任务。 */
    private void captureNegativeFeedbackGap(Long queryId, Integer score, String feedback,
            boolean updated) {
        if (!updated || score == null || score > 1) {
            return;
        }
        try {
            semanticGapCaptureExecutor.execute(
                    () -> captureNegativeFeedbackGapSafely(queryId, score, feedback));
        } catch (RejectedExecutionException e) {
            log.warn("semantic gap capture queue is full, drop feedback event for queryId {}",
                    queryId, e);
        }
    }

    /** 后台组装并写入负反馈缺口，异常只记录日志，避免反馈接口被治理链路反向打失败。 */
    private void captureNegativeFeedbackGapSafely(Long queryId, Integer score, String feedback) {
        try {
            QueryResp queryResp = getChatQuery(queryId);
            if (queryResp == null) {
                log.warn("skip negative feedback semantic gap because query is missing, queryId {}",
                        queryId);
                return;
            }
            SemanticGapEventReq eventReq = buildNegativeFeedbackGapEvent(queryResp, score, feedback);
            semanticGapService.capture(eventReq);
        } catch (Exception e) {
            log.warn("failed to capture negative feedback semantic gap for queryId {}", queryId, e);
        }
    }

    /** 组装负反馈事件并按反馈内容细分失败类型。 */
    private SemanticGapEventReq buildNegativeFeedbackGapEvent(QueryResp queryResp, Integer score,
            String feedback) {
        SemanticGapEventReq eventReq = new SemanticGapEventReq();
        eventReq.setQuestion(queryResp.getQueryText());
        eventReq.setQueryId(queryResp.getQuestionId());
        eventReq.setChatId(queryResp.getChatId());
        eventReq.setAssistantId(queryResp.getAgentId());
        eventReq.setFailureType(classifyNegativeFeedback(queryResp, feedback));
        eventReq.setFailureReason("user negative feedback score: " + score);
        eventReq.setFeedback(feedback);
        if (queryResp.getQueryResult() != null) {
            eventReq.setGeneratedSql(queryResp.getQueryResult().getQuerySql());
            if (queryResp.getQueryResult().getChatContext() != null
                    && queryResp.getQueryResult().getChatContext().getSqlInfo() != null) {
                eventReq.setS2sql(queryResp.getQueryResult().getChatContext().getSqlInfo()
                        .getCorrectedS2SQL());
            }
        }
        return eventReq;
    }

    /** 根据反馈关键词和结果上下文把低分反馈归入更具体的语义缺口类型。 */
    private SemanticGapFailureType classifyNegativeFeedback(QueryResp queryResp, String feedback) {
        if (containsAny(feedback, "模型", "主题域", "数据集", "数据源", "表", "口径不对",
                "字段不对", "选错", "匹配错", "不是这个")) {
            return SemanticGapFailureType.WRONG_MODEL_MATCHED;
        }
        if (isSuccessfulEmptyResult(queryResp)
                && containsAny(feedback, "应该有", "为空", "没数据", "无数据", "没有数据",
                        "查不到", "没查到", "空结果", "结果为空")) {
            return SemanticGapFailureType.EMPTY_RESULT_SUSPECTED;
        }
        return SemanticGapFailureType.USER_NEGATIVE_FEEDBACK;
    }

    /** 判断问答结果是否成功返回但没有结构化数据。 */
    private boolean isSuccessfulEmptyResult(QueryResp queryResp) {
        QueryResult queryResult = queryResp == null ? null : queryResp.getQueryResult();
        return queryResult != null && QueryState.SUCCESS.equals(queryResult.getQueryState())
                && queryResult.getResponse() == null
                && CollectionUtils.isEmpty(queryResult.getQueryResults());
    }

    /** 判断文本是否包含任一关键词；空反馈直接返回 false。 */
    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        return Arrays.stream(keywords).anyMatch(text::contains);
    }

    @Override
    public ChatQueryDO saveQueryResult(ChatExecuteReq chatExecuteReq, QueryResult queryResult) {
        ChatQueryDO chatQueryDO = chatQueryRepository.getChatQueryDO(chatExecuteReq.getQueryId());
        chatQueryDO.setQuestionId(chatExecuteReq.getQueryId());
        chatQueryDO.setQueryResult(JsonUtil.toString(queryResult));
        chatQueryDO.setQueryState(1);
        updateQuery(chatQueryDO);
        chatRepository.updateLastQuestion(chatExecuteReq.getChatId().longValue(),
                chatExecuteReq.getQueryText(), getCurrentTime());
        return chatQueryDO;
    }

    @Override
    public int updateQuery(ChatQueryDO chatQueryDO) {
        return chatQueryRepository.updateChatQuery(chatQueryDO);
    }

    @Override
    public void deleteQuery(Long queryId) {
        ChatQueryDO chatQuery = chatQueryRepository.getChatQueryDO(queryId);
        if (Objects.nonNull(chatQuery)) {
            chatQuery.setQueryState(0);
            chatQueryRepository.updateChatQuery(chatQuery);
        }
    }

    @Override
    public void updateParseCostTime(ChatParseResp chatParseResp) {
        ChatQueryDO chatQueryDO = chatQueryRepository.getChatQueryDO(chatParseResp.getQueryId());
        chatQueryDO.setParseTimeCost(JsonUtil.toString(chatParseResp.getParseTimeCost()));
        updateQuery(chatQueryDO);
    }

    @Override
    public List<ChatParseDO> batchAddParse(ChatParseReq chatParseReq, ChatParseResp chatParseResp) {
        List<SemanticParseInfo> candidateParses = chatParseResp.getSelectedParses();
        return chatQueryRepository.batchSaveParseInfo(chatParseReq, chatParseResp, candidateParses);
    }

    private String getCurrentTime() {
        SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return tempDate.format(new java.util.Date());
    }

    @Override
    public SemanticParseInfo getParseInfo(Long questionId, int parseId) {
        ChatParseDO chatParseDO = chatQueryRepository.getParseInfo(questionId, parseId);
        if (chatParseDO == null) {
            return null;
        } else {
            return JSONObject.parseObject(chatParseDO.getParseInfo(), SemanticParseInfo.class);
        }
    }

    @Override
    public List<SemanticParseInfo> getParseInfos(Long questionId) {
        List<ChatParseDO> chatParseDOs =
                chatQueryRepository.getParseInfoList(Collections.singletonList(questionId));
        return chatParseDOs.stream().map(chatParseDO -> JSONObject
                .parseObject(chatParseDO.getParseInfo(), SemanticParseInfo.class))
                .collect(Collectors.toList());
    }
}
