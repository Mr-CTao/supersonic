package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * AI 语义建模草稿的结构化版本差异服务。
 *
 * <p>
 * 职责说明：递归比较两份 JSON 快照；对象数组优先按 {@code key}/{@code field} 标识匹配，普通数组按值集合比较，
 * 从而避免单纯排序变化制造噪音；所有前后值在返回管理端前复用共享敏感规则脱敏。服务只持有完成配置后的 只读 {@link ObjectMapper}
 * 和分类器，所有比较状态均为方法局部对象，Spring 单例可安全并发调用，无需加锁。
 * </p>
 */
@Component
public class ModelingDraftDiffService {

    public static final String CHANGE_ADDED = "ADDED";
    public static final String CHANGE_REMOVED = "REMOVED";
    public static final String CHANGE_CHANGED = "CHANGED";

    private static final int MAX_ITEMS = 200;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_PATH_IDENTIFIER_LENGTH = 64;
    private static final int MAX_RECURSION_DEPTH = 64;

    private final ObjectMapper objectMapper;
    private final SemanticModelingSensitivityClassifier sensitivityClassifier;

    /**
     * 创建无共享比较状态的版本差异服务。
     *
     * @param objectMapper 项目统一 JSON 映射器；仅用于读取树和安全序列化展示值。
     * @param sensitivityClassifier 版本差异展示值脱敏器。
     */
    public ModelingDraftDiffService(ObjectMapper objectMapper,
            SemanticModelingSensitivityClassifier sensitivityClassifier) {
        this.objectMapper = objectMapper;
        this.sensitivityClassifier = sensitivityClassifier;
    }

    /**
     * 比较两个草稿版本 JSON 快照。
     *
     * <p>
     * 调用示例：{@code diffService.compare(12L, 1, 2, version1Json, version2Json)}。数组中的对象若具有 {@code key}
     * 或 {@code field}，则按该标识递归比较；普通数组按集合比较，调整顺序不会产生差异。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param fromVersionNo 起始版本号。
     * @param toVersionNo 目标版本号。
     * @param beforeJson 起始版本完整 JSON。
     * @param afterJson 目标版本完整 JSON。
     * @return 至多包含 200 条明细的结构化差异响应。
     * @throws IllegalArgumentException 任一快照为空或不是合法 JSON 时抛出，异常文案不包含原始 JSON。
     */
    public ModelingDraftDiffResp compare(Long draftId, Integer fromVersionNo, Integer toVersionNo,
            String beforeJson, String afterJson) {
        JsonNode before = parseSnapshot(beforeJson, "起始版本");
        JsonNode after = parseSnapshot(afterJson, "目标版本");
        DiffAccumulator accumulator = new DiffAccumulator();
        compareNodes("$", before, after, 0, accumulator);
        return ModelingDraftDiffResp.builder().draftId(draftId).fromVersionNo(fromVersionNo)
                .toVersionNo(toVersionNo).summary(accumulator.summary())
                .items(List.copyOf(accumulator.items())).truncated(accumulator.truncated()).build();
    }

    /** 读取 JSON 树，失败时仅返回固定业务文案，避免异常回显草稿内容。 */
    private JsonNode parseSnapshot(String json, String label) {
        if (StringUtils.isBlank(json)) {
            throw new IllegalArgumentException(label + "草稿快照不能为空");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null) {
                throw new IllegalArgumentException(label + "草稿快照不能为空");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(label + "草稿快照不是合法 JSON");
        }
    }

    /** 根据节点类型递归比较，超过安全深度后退化为单条整体修改。 */
    private void compareNodes(String path, JsonNode before, JsonNode after, int depth,
            DiffAccumulator accumulator) {
        if (before.equals(after)) {
            return;
        }
        if (depth >= MAX_RECURSION_DEPTH || before.getNodeType() != after.getNodeType()) {
            accumulator.add(path, CHANGE_CHANGED, displayValue(before), displayValue(after));
            return;
        }
        if (before.isObject()) {
            compareObjects(path, before, after, depth, accumulator);
        } else if (before.isArray()) {
            compareArrays(path, (ArrayNode) before, (ArrayNode) after, depth, accumulator);
        } else {
            accumulator.add(path, CHANGE_CHANGED, displayValue(before), displayValue(after));
        }
    }

    /** 按排序后的字段名比较对象，保证报告顺序稳定。 */
    private void compareObjects(String path, JsonNode before, JsonNode after, int depth,
            DiffAccumulator accumulator) {
        Set<String> fieldNames = new TreeSet<>();
        before.fieldNames().forEachRemaining(fieldNames::add);
        after.fieldNames().forEachRemaining(fieldNames::add);
        for (String fieldName : fieldNames) {
            JsonNode beforeValue = before.get(fieldName);
            JsonNode afterValue = after.get(fieldName);
            String childPath = objectPath(path, fieldName);
            if (beforeValue == null) {
                accumulator.add(childPath, CHANGE_ADDED, null, displayValue(afterValue));
            } else if (afterValue == null) {
                accumulator.add(childPath, CHANGE_REMOVED, displayValue(beforeValue), null);
            } else {
                compareNodes(childPath, beforeValue, afterValue, depth + 1, accumulator);
            }
        }
    }

    /** 优先按 key/field 对齐对象数组，否则按规范化值集合比较。 */
    private void compareArrays(String path, ArrayNode before, ArrayNode after, int depth,
            DiffAccumulator accumulator) {
        Map<String, IdentifiedNode> beforeById = indexByIdentifier(before);
        Map<String, IdentifiedNode> afterById = indexByIdentifier(after);
        if (beforeById != null && afterById != null
                && (!beforeById.isEmpty() || !afterById.isEmpty())) {
            compareIdentifiedArrays(path, beforeById, afterById, depth, accumulator);
            return;
        }
        compareArrayValueSets(path, before, after, accumulator);
    }

    /** 比较带稳定业务标识的对象数组，忽略数组位置变化。 */
    private void compareIdentifiedArrays(String path, Map<String, IdentifiedNode> before,
            Map<String, IdentifiedNode> after, int depth, DiffAccumulator accumulator) {
        Set<String> identifiers = new TreeSet<>();
        identifiers.addAll(before.keySet());
        identifiers.addAll(after.keySet());
        for (String identifier : identifiers) {
            IdentifiedNode beforeItem = before.get(identifier);
            IdentifiedNode afterItem = after.get(identifier);
            IdentifiedNode pathSource = beforeItem == null ? afterItem : beforeItem;
            String itemPath = identifiedPath(path, pathSource);
            if (beforeItem == null) {
                accumulator.add(itemPath, CHANGE_ADDED, null, displayValue(afterItem.node()));
            } else if (afterItem == null) {
                accumulator.add(itemPath, CHANGE_REMOVED, displayValue(beforeItem.node()), null);
            } else {
                compareNodes(itemPath, beforeItem.node(), afterItem.node(), depth + 1, accumulator);
            }
        }
    }

    /** 普通数组按规范化值集合比较，元素重排和重复值数量变化不产生噪音。 */
    private void compareArrayValueSets(String path, ArrayNode before, ArrayNode after,
            DiffAccumulator accumulator) {
        Map<String, JsonNode> beforeValues = indexByCanonicalValue(before);
        Map<String, JsonNode> afterValues = indexByCanonicalValue(after);
        Set<String> values = new TreeSet<>();
        values.addAll(beforeValues.keySet());
        values.addAll(afterValues.keySet());
        for (String value : values) {
            JsonNode beforeItem = beforeValues.get(value);
            JsonNode afterItem = afterValues.get(value);
            if (beforeItem == null) {
                accumulator.add(path + "[]", CHANGE_ADDED, null, displayValue(afterItem));
            } else if (afterItem == null) {
                accumulator.add(path + "[]", CHANGE_REMOVED, displayValue(beforeItem), null);
            }
        }
    }

    /** 构造对象数组标识索引；缺少标识或标识重复时返回 null 并启用集合比较。 */
    private Map<String, IdentifiedNode> indexByIdentifier(ArrayNode array) {
        Map<String, IdentifiedNode> result = new TreeMap<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                return null;
            }
            String fieldName = item.hasNonNull("key") && item.get("key").isValueNode() ? "key"
                    : item.hasNonNull("field") && item.get("field").isValueNode() ? "field" : null;
            if (fieldName == null) {
                return null;
            }
            String value = item.get(fieldName).asText();
            if (StringUtils.isBlank(value)) {
                return null;
            }
            String identity = fieldName + "\u0000" + value;
            if (result.putIfAbsent(identity, new IdentifiedNode(fieldName, value, item)) != null) {
                return null;
            }
        }
        return result;
    }

    /** 将数组元素映射为顺序无关的规范值索引。 */
    private Map<String, JsonNode> indexByCanonicalValue(ArrayNode array) {
        Map<String, JsonNode> result = new TreeMap<>();
        for (JsonNode item : array) {
            result.putIfAbsent(canonicalValue(item), item);
        }
        return result;
    }

    /** 递归生成字段顺序和数组顺序无关的规范值。 */
    private String canonicalValue(JsonNode node) {
        if (node.isObject()) {
            StringBuilder builder = new StringBuilder("{");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            Map<String, JsonNode> sorted = new TreeMap<>();
            fields.forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach(
                    (name, value) -> builder.append(objectMapper.getNodeFactory().textNode(name))
                            .append(':').append(canonicalValue(value)).append(','));
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(canonicalValue(item)));
            values.sort(String::compareTo);
            return "[" + String.join(",", values) + "]";
        }
        return node.toString();
    }

    /** 生成对象字段 JSONPath，复杂字段名使用引号形式避免路径歧义。 */
    private String objectPath(String parent, String fieldName) {
        if (fieldName.matches("[A-Za-z_$][A-Za-z0-9_$-]*")) {
            return parent + "." + fieldName;
        }
        return parent + "['" + fieldName.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    /** 生成带 key/field 标识的稳定数组元素路径。 */
    private String identifiedPath(String parent, IdentifiedNode identifiedNode) {
        String value = StringUtils.abbreviate(identifiedNode.value(), MAX_PATH_IDENTIFIER_LENGTH)
                .replace("\\", "\\\\").replace("]", "\\]");
        return parent + "[" + identifiedNode.fieldName() + "=" + value + "]";
    }

    /** 把节点转换为有限长度展示值，避免版本差异响应放大大段草稿 JSON。 */
    private String displayValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        String value;
        if (node.isTextual()) {
            value = node.textValue();
        } else {
            try {
                value = objectMapper.writeValueAsString(node);
            } catch (JsonProcessingException exception) {
                value = node.toString();
            }
        }
        return StringUtils.abbreviate(sensitivityClassifier.sanitizeText(value), MAX_VALUE_LENGTH);
    }

    /** 数组对象的稳定标识及原始节点。 */
    private record IdentifiedNode(String fieldName, String value, JsonNode node) {}

    /** 方法内差异累加器，统计全部差异但仅保存前 200 条明细。 */
    private static final class DiffAccumulator {
        private final List<ModelingDraftDiffItem> items = new ArrayList<>();
        private int added;
        private int removed;
        private int changed;
        private boolean truncated;

        /** 记录计数并在容量允许时保存明细。 */
        private void add(String path, String changeType, String beforeValue, String afterValue) {
            switch (changeType) {
                case CHANGE_ADDED -> added++;
                case CHANGE_REMOVED -> removed++;
                case CHANGE_CHANGED -> changed++;
                default -> throw new IllegalArgumentException("未知差异类型");
            }
            if (items.size() < MAX_ITEMS) {
                items.add(ModelingDraftDiffItem.builder().path(path).changeType(changeType)
                        .beforeValue(beforeValue).afterValue(afterValue).build());
            } else {
                truncated = true;
            }
        }

        /** 生成包含全部差异计数的稳定汇总文案。 */
        private String summary() {
            if (added == 0 && removed == 0 && changed == 0) {
                return "无变更";
            }
            String result = String.format("新增 %d 项，删除 %d 项，修改 %d 项", added, removed, changed);
            return truncated ? result + "；仅展示前 " + MAX_ITEMS + " 项" : result;
        }

        /** 返回明细是否因为超过容量而截断。 */
        private boolean truncated() {
            return truncated;
        }

        /** 返回当前保存的明细列表。 */
        private List<ModelingDraftDiffItem> items() {
            return items;
        }
    }
}
