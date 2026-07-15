package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 路由业务答案白名单校验器。
 *
 * <p>
 * 职责：只允许回答当前持久化问题快照中的稳定 key，并按 SINGLE_SELECT、BOOLEAN、TEXT 三种题型校验 JSON
 * 标量类型、选项白名单和文本长度。校验器不修改输入集合、不保存共享状态， 因而可以被单例路由服务并发安全复用。
 * </p>
 */
public final class SemanticAssetBusinessAnswerValidator {

    private static final String SINGLE_SELECT = "SINGLE_SELECT";
    private static final String BOOLEAN = "BOOLEAN";
    private static final String TEXT = "TEXT";
    private static final int MAX_ANSWER_COUNT = 50;
    private static final int MAX_ANSWER_KEY_LENGTH = 64;
    private static final int DEFAULT_TEXT_MAX_LENGTH = 500;
    private static final int ABSOLUTE_TEXT_MAX_LENGTH = 2_000;

    private SemanticAssetBusinessAnswerValidator() {}

    /**
     * 校验确认请求中的业务答案。
     *
     * <p>
     * 调用示例：{@code validate(questions, request.getBusinessAnswers())}。未知 key、对象或数组等
     * 嵌套值、错误标量类型、非白名单选项和超长文本都会 fail-closed。
     * </p>
     *
     * @param questions 当前路由版本持久化的业务问题快照。
     * @param answers 客户端提交的答案。
     * @throws SemanticAssetRoutingException 问题快照非法、答案缺失或答案不满足白名单时抛出。
     */
    public static void validate(List<SemanticAssetBusinessQuestion> questions,
            Map<String, Object> answers) {
        validateStructure(answers);
        Map<String, SemanticAssetBusinessQuestion> questionByKey = indexQuestions(questions);
        Map<String, Object> safeAnswers = answers == null ? Map.of() : answers;

        // 先拒绝未知 key，避免客户端借 Map 扩展字段把未审计内容写入路由快照。
        for (Map.Entry<String, Object> answer : safeAnswers.entrySet()) {
            SemanticAssetBusinessQuestion question = questionByKey.get(answer.getKey());
            if (question == null) {
                throw invalidAnswer("BUSINESS_ANSWER_UNKNOWN", "提交了当前路由版本不支持的业务答案");
            }
            validateValue(question, answer.getValue());
        }

        boolean missingRequired =
                questionByKey.values().stream().filter(SemanticAssetBusinessQuestion::isRequired)
                        .anyMatch(question -> isMissing(safeAnswers.get(question.getKey())));
        if (missingRequired) {
            throw invalidAnswer("BUSINESS_ANSWERS_REQUIRED", "请先回答全部必填业务问题");
        }
    }

    /**
     * 校验业务答案的通用 JSON 边界。
     *
     * <p>调用示例：{@code validatePayload(request.getBusinessAnswers(), objectMapper)}。该方法在
     * 指纹计算和持久化之前执行，确保 Map 条目数量、key、标量值以及最终 JSON 总长度均受服务端
     * 约束；按问题选项的进一步校验仍由 {@link #validate(List, Map)} 完成。</p>
     *
     * @param answers 客户端提交的业务答案。
     * @param objectMapper 项目统一 JSON 映射器。
     * @throws SemanticAssetRoutingException 答案结构非法、序列化失败或 JSON 超限时抛出。
     */
    public static void validatePayload(Map<String, Object> answers, ObjectMapper objectMapper) {
        validateStructure(answers);
        try {
            String json = objectMapper.writeValueAsString(answers == null ? Map.of() : answers);
            if (json.length() > SemanticAssetRoutingConstants.MAX_JSON_CHARACTERS) {
                throw invalidAnswer("BUSINESS_ANSWERS_TOO_LARGE", "业务答案内容过大，请精简后重试");
            }
        } catch (JsonProcessingException exception) {
            // 不回显 Jackson 原始消息，避免把不可信答案内容带入 API 错误或日志。
            throw invalidAnswer("BUSINESS_ANSWERS_INVALID", "业务答案无法解析，请检查填写内容");
        }
    }

    /** 校验答案只包含有限数量的短 key 及 String/Boolean JSON 标量。 */
    private static void validateStructure(Map<String, Object> answers) {
        Map<String, Object> safeAnswers = answers == null ? Map.of() : answers;
        if (safeAnswers.size() > MAX_ANSWER_COUNT) {
            throw invalidAnswer("BUSINESS_ANSWERS_TOO_MANY", "业务答案数量超过允许范围");
        }
        for (Map.Entry<String, Object> answer : safeAnswers.entrySet()) {
            String key = answer.getKey();
            Object value = answer.getValue();
            if (StringUtils.isBlank(key) || key.length() > MAX_ANSWER_KEY_LENGTH) {
                throw invalidAnswer("BUSINESS_ANSWER_INVALID", "业务答案标识不符合约束");
            }
            if (!(value instanceof String) && !(value instanceof Boolean)) {
                // 题型仅支持单选、布尔和受限文本；拒绝对象、数组、数字及任意 POJO，避免深层 JSON。
                throw invalidAnswer("BUSINESS_ANSWER_INVALID", "业务答案只能使用受支持的标量类型");
            }
            if (value instanceof String text && text.length() > ABSOLUTE_TEXT_MAX_LENGTH) {
                throw invalidAnswer("BUSINESS_ANSWER_INVALID", "业务答案文本超过允许长度");
            }
        }
    }

    /** 按稳定 key 建立问题索引；重复或非法问题快照必须 fail-closed。 */
    private static Map<String, SemanticAssetBusinessQuestion> indexQuestions(
            List<SemanticAssetBusinessQuestion> questions) {
        Map<String, SemanticAssetBusinessQuestion> indexed = new LinkedHashMap<>();
        for (SemanticAssetBusinessQuestion question : safe(questions)) {
            if (question == null || StringUtils.isBlank(question.getKey())
                    || StringUtils.isBlank(question.getAnswerType())
                    || indexed.putIfAbsent(question.getKey(), question) != null) {
                throw invalidQuestion();
            }
        }
        return indexed;
    }

    /** 根据问题类型验证单个 JSON 标量答案。 */
    private static void validateValue(SemanticAssetBusinessQuestion question, Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>
                || value.getClass().isArray()) {
            throw invalidAnswer("BUSINESS_ANSWER_INVALID", "业务答案类型不符合问题约束");
        }
        switch (question.getAnswerType()) {
            case SINGLE_SELECT -> validateSingleSelect(question, value);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw invalidAnswer("BUSINESS_ANSWER_INVALID", "布尔业务问题只能提交 true 或 false");
                }
            }
            case TEXT -> validateText(question, value);
            default -> throw invalidQuestion();
        }
    }

    /** 校验单选答案必须是持久化选项 key，而不是展示 label 或任意字符串。 */
    private static void validateSingleSelect(SemanticAssetBusinessQuestion question, Object value) {
        if (!(value instanceof String selected) || StringUtils.isBlank(selected)) {
            throw invalidAnswer("BUSINESS_ANSWER_INVALID", "单选业务问题必须提交有效选项");
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (SemanticAssetBusinessQuestion.QuestionOption option : safe(question.getOptions())) {
            if (option == null || StringUtils.isBlank(option.getKey())
                    || !allowed.add(option.getKey())) {
                throw invalidQuestion();
            }
        }
        if (allowed.isEmpty() || !allowed.contains(selected)) {
            throw invalidAnswer("BUSINESS_ANSWER_INVALID", "单选业务答案不在当前选项范围内");
        }
    }

    /** 校验自由文本为非空字符串，并同时执行题目上限与服务端绝对上限。 */
    private static void validateText(SemanticAssetBusinessQuestion question, Object value) {
        if (!(value instanceof String text) || StringUtils.isBlank(text)) {
            throw invalidAnswer("BUSINESS_ANSWER_INVALID", "文本业务问题必须提交非空文本");
        }
        int configured =
                Objects.requireNonNullElse(question.getMaxLength(), DEFAULT_TEXT_MAX_LENGTH);
        int maxLength = configured > 0 ? Math.min(configured, ABSOLUTE_TEXT_MAX_LENGTH)
                : DEFAULT_TEXT_MAX_LENGTH;
        if (text.length() > maxLength) {
            throw invalidAnswer("BUSINESS_ANSWER_INVALID", "文本业务答案超过允许长度");
        }
    }

    /** 判断必答值是否未提供或为空文本。 */
    private static boolean isMissing(Object value) {
        return value == null || (value instanceof String text && StringUtils.isBlank(text));
    }

    /** 创建不回显答案值的安全校验异常。 */
    private static SemanticAssetRoutingException invalidAnswer(String code, String message) {
        return new SemanticAssetRoutingException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 创建持久化问题快照非法异常，避免用不可信题型解释客户端值。 */
    private static SemanticAssetRoutingException invalidQuestion() {
        return new SemanticAssetRoutingException(HttpStatus.CONFLICT,
                "BUSINESS_QUESTION_SNAPSHOT_INVALID", "业务问题快照已失效，请重新分析");
    }

    /** 把可空列表转换为空列表。 */
    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
