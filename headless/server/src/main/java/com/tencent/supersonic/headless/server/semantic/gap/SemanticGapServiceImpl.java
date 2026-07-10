package com.tencent.supersonic.headless.server.semantic.gap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 语义缺口服务实现。
 *
 * <p>
 * 职责说明：把 Chat BI 的失败、回退和负反馈信号归一化为可治理的 `s2_semantic_gap` 记录，并提供管理端查询和状态流转接口。 安全说明：用户问题、反馈、SQL 和
 * S2SQL 在写库前会做基础脱敏和长度限制，避免列表页暴露长文本或常见敏感号码。并发说明： Chat BI 主链路通过专用线程池异步提交采集任务；任务内部再按
 * assistant/domain/failureType/normalizedQuestion 使用分段锁串行化读后写， 保护
 * occurrence_count、negative_feedback_count 和 priority_score 的一致性；不同 key 分散到不同锁段，避免阻塞整个问答链路。
 * </p>
 */
@Slf4j
@Service
public class SemanticGapServiceImpl implements SemanticGapService {

    private static final int QUESTION_MAX_LENGTH = 1000;
    private static final int REASON_MAX_LENGTH = 1500;
    private static final int SQL_MAX_LENGTH = 4000;
    private static final int FEEDBACK_MAX_LENGTH = 1500;
    private static final int RECENT_QUESTIONS_MAX_LENGTH = 3000;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String SYSTEM_CREATOR = "system";
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final Object[] LOCK_STRIPES =
            IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    private final SemanticGapMapper semanticGapMapper;
    private final ThreadPoolExecutor semanticGapCaptureExecutor;

    /**
     * 创建语义缺口服务。
     *
     * @param semanticGapMapper 缺口表 Mapper。
     * @param semanticGapCaptureExecutor 语义缺口采集专用线程池。
     */
    public SemanticGapServiceImpl(SemanticGapMapper semanticGapMapper,
            @Qualifier("semanticGapCaptureExecutor") ThreadPoolExecutor semanticGapCaptureExecutor) {
        this.semanticGapMapper = semanticGapMapper;
        this.semanticGapCaptureExecutor = semanticGapCaptureExecutor;
    }

    /**
     * 采集并聚合同类缺口。
     *
     * @param eventReq 问答链路上报事件。
     * @return 创建或更新后的缺口。
     * @throws IllegalArgumentException 当问题文本为空时抛出，避免产生不可治理的空缺口。
     *
     * @example SemanticGapEventReq req = new SemanticGapEventReq(); req.setQuestion("查询库存占用情况");
     *          req.setFailureType(SemanticGapFailureType.NO_SELECTED_PARSE);
     *          semanticGapService.capture(req);
     */
    @Override
    public SemanticGapDO capture(SemanticGapEventReq eventReq) {
        if (eventReq == null || StringUtils.isBlank(eventReq.getQuestion())) {
            throw new IllegalArgumentException("semantic gap question can not be blank");
        }
        String normalizedQuestion = normalizeQuestion(eventReq.getQuestion());
        String failureType = normalizeFailureType(eventReq.getFailureType()).name();
        String lockKey = buildAggregationKey(eventReq, normalizedQuestion, failureType);
        synchronized (selectLock(lockKey)) {
            SemanticGapDO current =
                    findAggregationTarget(eventReq, normalizedQuestion, failureType);
            if (current == null) {
                return createGap(eventReq, normalizedQuestion, failureType);
            }
            return updateGap(current, eventReq);
        }
    }

    /**
     * 异步采集并聚合同类缺口。
     *
     * @param eventReq 问答链路上报事件。
     *
     *        <p>
     *        并发说明：调用方只负责提交任务；线程池队列满时记录 warning 并丢弃采集事件，避免把缺口治理的写库压力传导到问答接口。
     *        任务内部捕获全部异常，保证采集失败不会影响业务主链路。
     *        </p>
     */
    @Override
    public void captureAsync(SemanticGapEventReq eventReq) {
        try {
            semanticGapCaptureExecutor.execute(() -> {
                try {
                    capture(eventReq);
                } catch (Exception e) {
                    log.warn("failed to capture semantic gap asynchronously for queryId {}",
                            eventReq == null ? null : eventReq.getQueryId(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("semantic gap capture queue is full, drop event for queryId {}",
                    eventReq == null ? null : eventReq.getQueryId(), e);
        }
    }

    /**
     * 按条件分页查询缺口池。
     *
     * @param queryReq 筛选和分页条件。
     * @return 分页结果。
     */
    @Override
    public PageInfo<SemanticGapDO> query(SemanticGapQueryReq queryReq) {
        SemanticGapQueryReq safeReq = queryReq == null ? new SemanticGapQueryReq() : queryReq;
        int page = safeReq.getPage() == null || safeReq.getPage() <= 0 ? DEFAULT_PAGE
                : safeReq.getPage();
        int pageSize =
                safeReq.getPageSize() == null || safeReq.getPageSize() <= 0 ? DEFAULT_PAGE_SIZE
                        : Math.min(safeReq.getPageSize(), MAX_PAGE_SIZE);
        LambdaQueryWrapper<SemanticGapDO> wrapper = buildQueryWrapper(safeReq);
        return PageHelper.startPage(page, pageSize)
                .doSelectPageInfo(() -> semanticGapMapper.selectList(wrapper));
    }

    /**
     * 查询缺口详情。
     *
     * @param id 缺口 ID。
     * @return 缺口详情。
     */
    @Override
    public SemanticGapDO get(Long id) {
        if (id == null) {
            return null;
        }
        return semanticGapMapper.selectById(id);
    }

    /**
     * 忽略语义缺口。
     *
     * @param id 缺口 ID。
     * @param req 忽略原因。
     * @param operator 操作人。
     * @return 更新后的缺口。
     */
    @Override
    public SemanticGapDO ignore(Long id, SemanticGapActionReq req, String operator) {
        SemanticGapDO gap = requireGap(id);
        validateIgnoreTransition(gap);
        Date now = new Date();
        String ignoreReason =
                truncate(sanitizeText(req == null ? null : req.getReason()), REASON_MAX_LENGTH);
        String updatedBy = StringUtils.defaultIfBlank(operator, SYSTEM_CREATOR);
        int updated = semanticGapMapper.update(null,
                new LambdaUpdateWrapper<SemanticGapDO>().eq(SemanticGapDO::getId, id)
                        // 状态必须在 UPDATE 时再次判断；若阶段 3 已把缺口转为 DRAFTING，
                        // PostgreSQL 等待行锁后会得到 0 行，不能用先前读到的旧状态覆盖。
                        .in(SemanticGapDO::getStatus, SemanticGapStatus.PENDING_ANALYSIS.name(),
                                SemanticGapStatus.REOPENED.name(), SemanticGapStatus.IGNORED.name())
                        .set(SemanticGapDO::getStatus, SemanticGapStatus.IGNORED.name())
                        .set(SemanticGapDO::getIgnoreReason, ignoreReason)
                        .set(SemanticGapDO::getUpdatedAt, now)
                        .set(SemanticGapDO::getUpdatedBy, updatedBy));
        if (updated != 1) {
            throw new IllegalStateException("semantic gap status changed, please reload");
        }
        return semanticGapMapper.selectById(id);
    }

    /**
     * 重新打开语义缺口。
     *
     * @param id 缺口 ID。
     * @param operator 操作人。
     * @return 更新后的缺口。
     */
    @Override
    public SemanticGapDO reopen(Long id, String operator) {
        SemanticGapDO gap = requireGap(id);
        validateReopenTransition(gap);
        Date now = new Date();
        String updatedBy = StringUtils.defaultIfBlank(operator, SYSTEM_CREATOR);
        // MyBatis-Plus 默认跳过 null 字段，重新打开必须显式清空忽略原因，避免详情页继续展示旧处理意见。
        LambdaUpdateWrapper<SemanticGapDO> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SemanticGapDO::getId, id)
                .eq(SemanticGapDO::getStatus, SemanticGapStatus.IGNORED.name())
                .set(SemanticGapDO::getStatus, SemanticGapStatus.REOPENED.name())
                .set(SemanticGapDO::getIgnoreReason, null).set(SemanticGapDO::getUpdatedAt, now)
                .set(SemanticGapDO::getUpdatedBy, updatedBy);
        if (semanticGapMapper.update(null, wrapper) != 1) {
            throw new IllegalStateException("semantic gap status changed, please reload");
        }
        return semanticGapMapper.selectById(id);
    }

    /**
     * 阶段 2 草稿占位入口。
     *
     * @param id 缺口 ID。
     * @return 未启用响应。
     */
    @Override
    public SemanticGapDraftResp createDraftPlaceholder(Long id) {
        requireGap(id);
        return SemanticGapDraftResp.builder().gapId(id).enabled(false)
                .message("阶段 2 仅提供语义缺口池，AI 草稿生成将在后续阶段启用。").build();
    }

    /** 构建列表筛选条件，所有筛选都使用索引友好的等值或前缀可控条件。 */
    private LambdaQueryWrapper<SemanticGapDO> buildQueryWrapper(SemanticGapQueryReq queryReq) {
        LambdaQueryWrapper<SemanticGapDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(queryReq.getAssistantId() != null, SemanticGapDO::getAssistantId,
                queryReq.getAssistantId());
        wrapper.eq(queryReq.getDomainId() != null, SemanticGapDO::getDomainId,
                queryReq.getDomainId());
        wrapper.eq(queryReq.getDataSourceId() != null, SemanticGapDO::getDataSourceId,
                queryReq.getDataSourceId());
        wrapper.eq(StringUtils.isNotBlank(queryReq.getFailureType()), SemanticGapDO::getFailureType,
                queryReq.getFailureType());
        wrapper.eq(StringUtils.isNotBlank(queryReq.getStatus()), SemanticGapDO::getStatus,
                queryReq.getStatus());
        if (StringUtils.isNotBlank(queryReq.getKeyword())) {
            String keyword = sanitizeText(queryReq.getKeyword());
            wrapper.and(w -> w.like(SemanticGapDO::getQuestion, keyword).or()
                    .like(SemanticGapDO::getFailureReason, keyword).or()
                    .like(SemanticGapDO::getFeedback, keyword));
        }
        Date startTime = parseTime(queryReq.getStartTime(), false);
        Date endTime = parseTime(queryReq.getEndTime(), true);
        wrapper.ge(startTime != null, SemanticGapDO::getLastSeenAt, startTime);
        wrapper.le(endTime != null, SemanticGapDO::getLastSeenAt, endTime);
        wrapper.orderByDesc(SemanticGapDO::getPriorityScore)
                .orderByDesc(SemanticGapDO::getLastSeenAt);
        return wrapper;
    }

    /** 查找当前事件对应的聚合目标。 */
    private SemanticGapDO findAggregationTarget(SemanticGapEventReq eventReq,
            String normalizedQuestion, String failureType) {
        LambdaQueryWrapper<SemanticGapDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SemanticGapDO::getNormalizedQuestion, normalizedQuestion)
                .eq(SemanticGapDO::getFailureType, failureType);
        if (eventReq.getAssistantId() == null) {
            wrapper.isNull(SemanticGapDO::getAssistantId);
        } else {
            wrapper.eq(SemanticGapDO::getAssistantId, eventReq.getAssistantId());
        }
        if (eventReq.getDomainId() == null) {
            wrapper.isNull(SemanticGapDO::getDomainId);
        } else {
            wrapper.eq(SemanticGapDO::getDomainId, eventReq.getDomainId());
        }
        wrapper.last("limit 1");
        return semanticGapMapper.selectOne(wrapper);
    }

    /** 创建首次出现的缺口记录。 */
    private SemanticGapDO createGap(SemanticGapEventReq eventReq, String normalizedQuestion,
            String failureType) {
        Date now = new Date();
        SemanticGapDO gap = new SemanticGapDO();
        gap.setQuestion(truncate(sanitizeText(eventReq.getQuestion()), QUESTION_MAX_LENGTH));
        gap.setNormalizedQuestion(normalizedQuestion);
        gap.setAssistantId(eventReq.getAssistantId());
        gap.setUserId(eventReq.getUserId());
        gap.setDomainId(eventReq.getDomainId());
        gap.setDataSourceId(eventReq.getDataSourceId());
        gap.setFailureType(failureType);
        gap.setFailureReason(
                truncate(sanitizeText(eventReq.getFailureReason()), REASON_MAX_LENGTH));
        gap.setMatchedModelIds(truncate(eventReq.getMatchedModelIds(), 1000));
        gap.setMatchedMetricIds(truncate(eventReq.getMatchedMetricIds(), 1000));
        gap.setMatchedDimensionIds(truncate(eventReq.getMatchedDimensionIds(), 1000));
        gap.setGeneratedSql(truncate(sanitizeSql(eventReq.getGeneratedSql()), SQL_MAX_LENGTH));
        gap.setS2sql(truncate(sanitizeSql(eventReq.getS2sql()), SQL_MAX_LENGTH));
        gap.setFeedback(truncate(sanitizeText(eventReq.getFeedback()), FEEDBACK_MAX_LENGTH));
        gap.setOccurrenceCount(1);
        gap.setNegativeFeedbackCount(isNegativeFeedback(eventReq) ? 1 : 0);
        gap.setPriorityScore(calculatePriority(gap));
        gap.setStatus(SemanticGapStatus.PENDING_ANALYSIS.name());
        gap.setCreatedAt(now);
        gap.setLastSeenAt(now);
        gap.setCreatedBy(StringUtils.defaultIfBlank(eventReq.getUserName(), SYSTEM_CREATOR));
        gap.setUpdatedAt(now);
        gap.setUpdatedBy(StringUtils.defaultIfBlank(eventReq.getUserName(), SYSTEM_CREATOR));
        gap.setSourceQueryId(eventReq.getQueryId());
        gap.setSourceChatId(eventReq.getChatId());
        gap.setRecentQuestions(gap.getQuestion());
        semanticGapMapper.insert(gap);
        return gap;
    }

    /** 更新已存在的聚合缺口。 */
    private SemanticGapDO updateGap(SemanticGapDO current, SemanticGapEventReq eventReq) {
        current.setOccurrenceCount(defaultInt(current.getOccurrenceCount()) + 1);
        if (isNegativeFeedback(eventReq)) {
            current.setNegativeFeedbackCount(defaultInt(current.getNegativeFeedbackCount()) + 1);
        }
        current.setLastSeenAt(new Date());
        current.setUpdatedAt(new Date());
        current.setUpdatedBy(StringUtils.defaultIfBlank(eventReq.getUserName(), SYSTEM_CREATOR));
        current.setFailureReason(mergeText(current.getFailureReason(), eventReq.getFailureReason(),
                REASON_MAX_LENGTH));
        current.setFeedback(
                mergeText(current.getFeedback(), eventReq.getFeedback(), FEEDBACK_MAX_LENGTH));
        current.setGeneratedSql(firstNonBlank(current.getGeneratedSql(),
                truncate(sanitizeSql(eventReq.getGeneratedSql()), SQL_MAX_LENGTH)));
        current.setS2sql(firstNonBlank(current.getS2sql(),
                truncate(sanitizeSql(eventReq.getS2sql()), SQL_MAX_LENGTH)));
        current.setMatchedModelIds(
                firstNonBlank(current.getMatchedModelIds(), eventReq.getMatchedModelIds()));
        current.setMatchedMetricIds(
                firstNonBlank(current.getMatchedMetricIds(), eventReq.getMatchedMetricIds()));
        current.setMatchedDimensionIds(
                firstNonBlank(current.getMatchedDimensionIds(), eventReq.getMatchedDimensionIds()));
        current.setSourceQueryId(eventReq.getQueryId());
        current.setSourceChatId(eventReq.getChatId());
        current.setRecentQuestions(
                appendRecentQuestion(current.getRecentQuestions(), eventReq.getQuestion()));
        current.setPriorityScore(calculatePriority(current));
        // 只更新统计和诊断列，刻意不携带 status/ignoreReason。这样阶段 3 的行锁状态流转
        // 即使与采集并发，也不会在 PostgreSQL 等待锁后被旧实体中的状态覆盖。
        LambdaUpdateWrapper<SemanticGapDO> update = new LambdaUpdateWrapper<>();
        update.eq(SemanticGapDO::getId, current.getId())
                .set(SemanticGapDO::getOccurrenceCount, current.getOccurrenceCount())
                .set(SemanticGapDO::getNegativeFeedbackCount, current.getNegativeFeedbackCount())
                .set(SemanticGapDO::getLastSeenAt, current.getLastSeenAt())
                .set(SemanticGapDO::getUpdatedAt, current.getUpdatedAt())
                .set(SemanticGapDO::getUpdatedBy, current.getUpdatedBy())
                .set(SemanticGapDO::getFailureReason, current.getFailureReason())
                .set(SemanticGapDO::getFeedback, current.getFeedback())
                .set(SemanticGapDO::getGeneratedSql, current.getGeneratedSql())
                .set(SemanticGapDO::getS2sql, current.getS2sql())
                .set(SemanticGapDO::getMatchedModelIds, current.getMatchedModelIds())
                .set(SemanticGapDO::getMatchedMetricIds, current.getMatchedMetricIds())
                .set(SemanticGapDO::getMatchedDimensionIds, current.getMatchedDimensionIds())
                .set(eventReq.getQueryId() != null, SemanticGapDO::getSourceQueryId,
                        current.getSourceQueryId())
                .set(eventReq.getChatId() != null, SemanticGapDO::getSourceChatId,
                        current.getSourceChatId())
                .set(SemanticGapDO::getRecentQuestions, current.getRecentQuestions())
                .set(SemanticGapDO::getPriorityScore, current.getPriorityScore());
        semanticGapMapper.update(null, update);
        return semanticGapMapper.selectById(current.getId());
    }

    /** 归一化问题，用于第一版轻量聚合。 */
    private String normalizeQuestion(String question) {
        String normalized = StringUtils.defaultString(question).toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("(今天|今日|昨天|昨日|本周|这周|本月|这个月|今年|近\\d+天)", "");
        normalized = normalized.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        if (StringUtils.isBlank(normalized)) {
            normalized = StringUtils.defaultString(question).trim().toLowerCase(Locale.ROOT);
        }
        return truncate(normalized, 500);
    }

    /** 标准化失败类型，避免空类型进入聚合 key。 */
    private SemanticGapFailureType normalizeFailureType(SemanticGapFailureType failureType) {
        return failureType == null ? SemanticGapFailureType.UNKNOWN : failureType;
    }

    /** 构造并发锁 key，锁粒度与聚合维度保持一致。 */
    private String buildAggregationKey(SemanticGapEventReq eventReq, String normalizedQuestion,
            String failureType) {
        return String.format("%s|%s|%s|%s", eventReq.getAssistantId(), eventReq.getDomainId(),
                failureType, normalizedQuestion);
    }

    /** 从固定锁数组选择锁段，避免为每个用户问题无限创建锁对象。 */
    private Object selectLock(String lockKey) {
        int index = Math.floorMod(Objects.hashCode(lockKey), LOCK_STRIPES.length);
        return LOCK_STRIPES[index];
    }

    /** 计算第一版优先级分数，保证高频和负反馈排在前面。 */
    private Integer calculatePriority(SemanticGapDO gap) {
        int base = defaultInt(gap.getOccurrenceCount()) * 10
                + defaultInt(gap.getNegativeFeedbackCount()) * 20 + 15;
        return base + failureTypeWeight(gap.getFailureType());
    }

    /** 按失败类型补充权重，无解析和执行失败优先处理。 */
    private int failureTypeWeight(String failureType) {
        if (SemanticGapFailureType.NO_SELECTED_PARSE.name().equals(failureType)
                || SemanticGapFailureType.PARSER_EXCEPTION.name().equals(failureType)
                || SemanticGapFailureType.SQL_EXECUTION_ERROR.name().equals(failureType)) {
            return 30;
        }
        if (SemanticGapFailureType.USER_NEGATIVE_FEEDBACK.name().equals(failureType)) {
            return 25;
        }
        if (SemanticGapFailureType.FALLBACK_TO_LLM_SQL.name().equals(failureType)) {
            return 20;
        }
        return 5;
    }

    /** 判断事件是否应累加负反馈次数。 */
    private boolean isNegativeFeedback(SemanticGapEventReq eventReq) {
        return SemanticGapFailureType.USER_NEGATIVE_FEEDBACK.equals(eventReq.getFailureType())
                || SemanticGapFailureType.WRONG_MODEL_MATCHED.equals(eventReq.getFailureType())
                || SemanticGapFailureType.EMPTY_RESULT_SUSPECTED.equals(eventReq.getFailureType());
    }

    /** 校验忽略状态流转；重复忽略保持幂等，其他后续阶段状态不允许被阶段 2 直接覆盖。 */
    private void validateIgnoreTransition(SemanticGapDO gap) {
        if (SemanticGapStatus.PENDING_ANALYSIS.name().equals(gap.getStatus())
                || SemanticGapStatus.REOPENED.name().equals(gap.getStatus())
                || SemanticGapStatus.IGNORED.name().equals(gap.getStatus())) {
            return;
        }
        throw new IllegalStateException(
                "semantic gap status " + gap.getStatus() + " can not be ignored");
    }

    /** 校验重新打开状态流转；只有明确忽略过的缺口才允许进入 REOPENED。 */
    private void validateReopenTransition(SemanticGapDO gap) {
        if (SemanticGapStatus.IGNORED.name().equals(gap.getStatus())) {
            return;
        }
        throw new IllegalStateException(
                "semantic gap status " + gap.getStatus() + " can not be reopened");
    }

    /** 管理端详情保留最近问法，避免完全相同问题之外的近似问法丢失。 */
    private String appendRecentQuestion(String recentQuestions, String question) {
        String safeQuestion = truncate(sanitizeText(question), QUESTION_MAX_LENGTH);
        if (StringUtils.isBlank(safeQuestion)) {
            return recentQuestions;
        }
        String current = StringUtils.defaultString(recentQuestions);
        if (Arrays.asList(current.split("\\n")).contains(safeQuestion)) {
            return current;
        }
        String merged = StringUtils.isBlank(current) ? safeQuestion : current + "\n" + safeQuestion;
        return truncate(merged, RECENT_QUESTIONS_MAX_LENGTH);
    }

    /** 合并诊断文本，保留不同原因但限制长度。 */
    private String mergeText(String oldText, String newText, int maxLength) {
        String sanitized = truncate(sanitizeText(newText), maxLength);
        if (StringUtils.isBlank(sanitized)) {
            return oldText;
        }
        if (StringUtils.contains(oldText, sanitized)) {
            return oldText;
        }
        String merged = StringUtils.isBlank(oldText) ? sanitized : oldText + "\n" + sanitized;
        return truncate(merged, maxLength);
    }

    /** 对用户输入做基础脱敏，第一版只处理常见手机号、邮箱和长数字串。 */
    private String sanitizeText(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        return value.replaceAll("1[3-9]\\d{9}", "1**********")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "***@***")
                .replaceAll("\\b\\d{12,}\\b", "********");
    }

    /** SQL/S2SQL 与普通文本同样脱敏，后续可替换为更完整的 SQL token 脱敏器。 */
    private String sanitizeSql(String value) {
        return sanitizeText(value);
    }

    /** 字段长度保护，避免长 SQL 或大反馈拖慢列表查询。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 返回非空旧值，否则使用新值。 */
    private String firstNonBlank(String oldValue, String newValue) {
        return StringUtils.isNotBlank(oldValue) ? oldValue : newValue;
    }

    /** 取整数默认值，避免历史空值参与优先级计算时空指针。 */
    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** 查询详情类操作需要明确失败，避免前端误认为已成功操作空记录。 */
    private SemanticGapDO requireGap(Long id) {
        SemanticGapDO gap = get(id);
        if (gap == null) {
            throw new IllegalArgumentException("semantic gap not found: " + id);
        }
        return gap;
    }

    /** 解析列表时间筛选；格式错误时记录 warning 并忽略该条件，避免阻断页面查询。 */
    private Date parseTime(String value, boolean endOfDay) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == DATE_PATTERN.length()) {
                return new SimpleDateFormat(DATE_TIME_PATTERN)
                        .parse(text + (endOfDay ? " 23:59:59" : " 00:00:00"));
            }
            return new SimpleDateFormat(DATE_TIME_PATTERN).parse(text);
        } catch (ParseException e) {
            log.warn("ignore invalid semantic gap time filter: {}", value);
            return null;
        }
    }
}
