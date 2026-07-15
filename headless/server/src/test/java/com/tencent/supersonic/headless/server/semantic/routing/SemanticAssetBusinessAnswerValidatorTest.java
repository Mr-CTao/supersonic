package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 路由业务答案白名单校验测试。
 *
 * <p>
 * 职责：固定问题 key、JSON 标量类型、单选白名单和文本长度边界；测试只使用局部不可变集合， 不涉及共享状态或并发资源。
 * </p>
 */
class SemanticAssetBusinessAnswerValidatorTest {

    /** 合法的单选、布尔和文本答案应一次性通过。 */
    @Test
    void shouldAcceptAnswersMatchingPersistedQuestionSchema() {
        Map<String, Object> answers = Map.of("grain", "MATERIAL_AND_BATCH", "positive_stock_only",
                true, "calculation_note", "排除退货批次");

        assertDoesNotThrow(
                () -> SemanticAssetBusinessAnswerValidator.validate(questions(), answers));
    }

    /** 未知 key 不得写入确认快照。 */
    @Test
    void shouldRejectUnknownAnswerKey() {
        Map<String, Object> answers = Map.of("grain", "MATERIAL_AND_BATCH", "positive_stock_only",
                true, "calculation_note", "说明", "unexpected", "value");

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> SemanticAssetBusinessAnswerValidator.validate(questions(), answers));

        assertEquals("BUSINESS_ANSWER_UNKNOWN", exception.getErrorCode());
    }

    /** 单选题只接受持久化 option key，不能提交 label 或任意值。 */
    @Test
    void shouldRejectSingleSelectValueOutsideOptionWhitelist() {
        Map<String, Object> answers =
                Map.of("grain", "物料和批次", "positive_stock_only", true, "calculation_note", "说明");

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> SemanticAssetBusinessAnswerValidator.validate(questions(), answers));

        assertEquals("BUSINESS_ANSWER_INVALID", exception.getErrorCode());
    }

    /** 对象、数组等嵌套值不能绕过题型标量约束。 */
    @Test
    void shouldRejectNestedAnswerValue() {
        Map<String, Object> answers = Map.of("grain", Map.of("key", "MATERIAL_AND_BATCH"),
                "positive_stock_only", true, "calculation_note", "说明");

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> SemanticAssetBusinessAnswerValidator.validate(questions(), answers));

        assertEquals("BUSINESS_ANSWER_INVALID", exception.getErrorCode());
    }

    /** 单条答案均未超限时，序列化后的整体 JSON 仍必须受路由上下文上限约束。 */
    @Test
    void shouldRejectOversizedSerializedAnswerPayload() {
        Map<String, Object> answers = new LinkedHashMap<>();
        for (int index = 0; index < 40; index++) {
            answers.put("answer_" + index, "a".repeat(1_800));
        }

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> SemanticAssetBusinessAnswerValidator.validatePayload(answers,
                        new ObjectMapper()));

        assertEquals("BUSINESS_ANSWERS_TOO_LARGE", exception.getErrorCode());
    }

    /** 文本长度同时受问题配置和服务端绝对上限保护。 */
    @Test
    void shouldRejectTextLongerThanQuestionLimit() {
        Map<String, Object> answers = Map.of("grain", "MATERIAL_AND_BATCH", "positive_stock_only",
                true, "calculation_note", "123456789");

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> SemanticAssetBusinessAnswerValidator.validate(questions(), answers));

        assertEquals("BUSINESS_ANSWER_INVALID", exception.getErrorCode());
    }

    /** 布尔题不接受字符串 true，避免前后端类型歧义。 */
    @Test
    void shouldRejectBooleanString() {
        Map<String, Object> answers = Map.of("grain", "MATERIAL_AND_BATCH", "positive_stock_only",
                "true", "calculation_note", "说明");

        SemanticAssetRoutingException exception = assertThrows(SemanticAssetRoutingException.class,
                () -> SemanticAssetBusinessAnswerValidator.validate(questions(), answers));

        assertEquals("BUSINESS_ANSWER_INVALID", exception.getErrorCode());
    }

    /** 创建覆盖三种受支持题型的稳定问题快照。 */
    private List<SemanticAssetBusinessQuestion> questions() {
        return List.of(
                SemanticAssetBusinessQuestion.builder().key("grain").question("统计粒度？")
                        .required(true).answerType("SINGLE_SELECT")
                        .options(List.of(
                                SemanticAssetBusinessQuestion.QuestionOption.builder()
                                        .key("MATERIAL").label("物料").build(),
                                SemanticAssetBusinessQuestion.QuestionOption.builder()
                                        .key("MATERIAL_AND_BATCH").label("物料和批次").build()))
                        .build(),
                SemanticAssetBusinessQuestion.builder().key("positive_stock_only")
                        .question("仅统计正库存？").required(true).answerType("BOOLEAN").build(),
                SemanticAssetBusinessQuestion.builder().key("calculation_note").question("补充说明")
                        .required(true).answerType("TEXT").maxLength(8).build());
    }
}
