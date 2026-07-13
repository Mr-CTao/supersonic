package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelingDraftDiffService} 的结构化 JSON 差异单元测试。
 *
 * <p>
 * 测试使用独立 ObjectMapper 和 Service，不共享版本状态，也不访问数据库。
 * </p>
 */
class ModelingDraftDiffServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelingDraftDiffService diffService =
            new ModelingDraftDiffService(objectMapper, new SemanticModelingSensitivityClassifier());

    /** 带 key 的对象数组及标量数组只调整顺序时不应产生噪音。 */
    @Test
    void shouldIgnoreOrderChangesForIdentifiedObjectsAndScalarArrays() {
        String before = """
                {"models":[
                  {"key":"orders","aliases":["订单","单据"]},
                  {"key":"inventory","aliases":["库存","余额"]}
                ]}
                """;
        String after = """
                {"models":[
                  {"key":"inventory","aliases":["余额","库存"]},
                  {"key":"orders","aliases":["单据","订单"]}
                ]}
                """;

        ModelingDraftDiffResp result = diffService.compare(10L, 1, 2, before, after);

        assertEquals("无变更", result.getSummary());
        assertTrue(result.getItems().isEmpty());
        assertFalse(result.isTruncated());
    }

    /** 对象字段、带标识数组和标量集合的新增、删除、修改必须生成稳定路径。 */
    @Test
    void shouldReportStructuredAddedRemovedAndChangedItems() {
        String before = """
                {"models":[{"key":"orders","name":"订单模型",
                  "dimensions":[{"key":"status","field":"status","aliases":["状态","单据状态"]},
                                {"key":"owner","field":"owner_id"}]}]}
                """;
        String after = """
                {"models":[{"key":"orders","name":"订单明细模型",
                  "dimensions":[{"key":"status","field":"status","aliases":["状态","处理状态"]},
                                {"key":"warehouse","field":"warehouse_id"}]}]}
                """;

        ModelingDraftDiffResp result = diffService.compare(10L, 3, 4, before, after);
        List<ModelingDraftDiffItem> items = result.getItems();

        assertTrue(
                items.stream().anyMatch(item -> "$.models[key=orders].name".equals(item.getPath())
                        && ModelingDraftDiffService.CHANGE_CHANGED.equals(item.getChangeType())));
        assertTrue(items.stream().anyMatch(
                item -> item.getPath().equals("$.models[key=orders].dimensions[key=owner]")
                        && ModelingDraftDiffService.CHANGE_REMOVED.equals(item.getChangeType())));
        assertTrue(items.stream().anyMatch(
                item -> item.getPath().equals("$.models[key=orders].dimensions[key=warehouse]")
                        && ModelingDraftDiffService.CHANGE_ADDED.equals(item.getChangeType())));
        assertTrue(items.stream()
                .anyMatch(item -> item.getPath()
                        .equals("$.models[key=orders].dimensions[key=status].aliases[]")
                        && "单据状态".equals(item.getBeforeValue())));
        assertTrue(items.stream()
                .anyMatch(item -> item.getPath()
                        .equals("$.models[key=orders].dimensions[key=status].aliases[]")
                        && "处理状态".equals(item.getAfterValue())));
    }

    /** 差异超过 200 条时必须保留全部计数、截断明细并限制单个值长度。 */
    @Test
    void shouldBoundItemsAndDisplayedValues() throws Exception {
        ObjectNode before = objectMapper.createObjectNode();
        ObjectNode after = objectMapper.createObjectNode();
        String longBefore = "a".repeat(800);
        String longAfter = "b".repeat(800);
        for (int index = 0; index < 205; index++) {
            before.put("field_" + index, index == 0 ? longBefore : "before_" + index);
            after.put("field_" + index, index == 0 ? longAfter : "after_" + index);
        }

        ModelingDraftDiffResp result = diffService.compare(99L, 1, 2,
                objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after));

        assertEquals(200, result.getItems().size());
        assertTrue(result.isTruncated());
        assertTrue(result.getSummary().contains("修改 205 项"));
        ModelingDraftDiffItem longValueItem = result.getItems().stream()
                .filter(item -> "$.field_0".equals(item.getPath())).findFirst().orElseThrow();
        assertTrue(longValueItem.getBeforeValue().length() <= 512);
        assertTrue(longValueItem.getAfterValue().length() <= 512);
    }

    /** 非法快照必须使用固定文案失败，不能把原始 JSON 拼进异常。 */
    @Test
    void shouldRejectInvalidJsonWithoutEchoingPayload() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> diffService.compare(1L, 1, 2, "{secret-value", "{}"));

        assertFalse(exception.getMessage().contains("secret-value"));
    }

    /** 差异值包含邮箱等敏感内容时必须整段脱敏，不能回显到管理端。 */
    @Test
    void shouldMaskSensitiveDisplayedValues() {
        ModelingDraftDiffResp result =
                diffService.compare(1L, 1, 2, "{\"businessGoal\":\"联系 old@example.com\"}",
                        "{\"businessGoal\":\"联系 new@example.com\"}");

        assertThat(result.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getBeforeValue()).isEqualTo("[MASKED]");
            assertThat(item.getAfterValue()).isEqualTo("[MASKED]");
        });
    }
}
