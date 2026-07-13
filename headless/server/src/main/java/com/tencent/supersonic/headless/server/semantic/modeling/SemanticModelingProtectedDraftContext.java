package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单次 AI 修订请求的敏感草稿保护上下文。
 *
 * <p>
 * 职责说明：把敏感文本替换为请求内唯一、路径绑定的占位符，并在模型输出进入 schema 校验和持久化前验证占位符
 * 未被删除、修改、移动或复制，再恢复原值。映射仅存在于当前方法栈对象中，不写数据库、日志或 provider metadata， 不使用静态缓存，因此并发修订之间不会共享敏感值或占位符。
 * </p>
 */
final class SemanticModelingProtectedDraftContext {

    private static final String TOKEN_PREFIX = "__S2_PROTECTED_";

    private final SemanticModelingSensitivityClassifier classifier;
    private final String requestNonce = UUID.randomUUID().toString().replace("-", "");
    private final Map<String, ProtectedValue> valuesByToken = new LinkedHashMap<>();

    SemanticModelingProtectedDraftContext(SemanticModelingSensitivityClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * 创建供模型读取的受保护草稿副本。
     *
     * @param source 当前不可变草稿 JSON。
     * @return 仅敏感文本被路径绑定占位符替换的深拷贝。
     * @throws ModelingDraftException 输入结构为空时抛出稳定的安全校验错误。
     */
    JsonNode protect(JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull()) {
            throw invalidProtectedOutput();
        }
        JsonNode protectedCopy = source.deepCopy();
        protectNode(protectedCopy, "");
        return protectedCopy;
    }

    /**
     * 校验模型输出中的占位符完整性并恢复基线敏感值。
     *
     * @param candidate 模型返回的完整草稿 JSON。
     * @return 已恢复原始敏感文本的深拷贝，可继续执行 schema 校验和 diff。
     * @throws ModelingDraftException 占位符缺失、移动、复制、伪造或输出新增敏感值时 fail-closed。
     */
    JsonNode restore(JsonNode candidate) {
        if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
            throw invalidProtectedOutput();
        }
        JsonNode restored = candidate.deepCopy();
        Map<String, List<String>> tokenPaths = new LinkedHashMap<>();
        collectTokenPaths(restored, "", tokenPaths);
        for (ProtectedValue value : valuesByToken.values()) {
            List<String> paths = tokenPaths.getOrDefault(value.token(), List.of());
            if (paths.size() != 1 || !value.pointer().equals(paths.get(0))) {
                throw invalidProtectedOutput();
            }
            restoreValue(restored, value);
        }
        if (tokenPaths.keySet().stream().anyMatch(token -> !valuesByToken.containsKey(token))) {
            throw invalidProtectedOutput();
        }
        return restored;
    }

    /** 递归替换文本节点；路径采用 RFC 6901 JSON Pointer，确保占位符不能跨字段复用。 */
    private void protectNode(JsonNode node, String pointer) {
        if (node instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            object.fields().forEachRemaining(fields::add);
            for (Map.Entry<String, JsonNode> field : fields) {
                String childPointer = pointer + "/" + escapePointer(field.getKey());
                JsonNode child = field.getValue();
                if (child.isTextual() && classifier.containsSensitiveValue(child.textValue())) {
                    object.set(field.getKey(), protectedText(childPointer, child.textValue()));
                } else {
                    protectNode(child, childPointer);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                String childPointer = pointer + "/" + index;
                JsonNode child = array.get(index);
                if (child.isTextual() && classifier.containsSensitiveValue(child.textValue())) {
                    array.set(index, protectedText(childPointer, child.textValue()));
                } else {
                    protectNode(child, childPointer);
                }
            }
        }
    }

    /** 创建不可猜测占位符并把原值保留在请求局部映射中。 */
    private TextNode protectedText(String pointer, String original) {
        String token = TOKEN_PREFIX + requestNonce + "_" + valuesByToken.size() + "__";
        valuesByToken.put(token, new ProtectedValue(pointer, token, original));
        return TextNode.valueOf(token);
    }

    /** 收集每个占位符出现路径，同时拒绝模型新增任何真实敏感值。 */
    private void collectTokenPaths(JsonNode node, String pointer,
            Map<String, List<String>> tokenPaths) {
        if (node.isTextual()) {
            String text = node.textValue();
            if (text.startsWith(TOKEN_PREFIX)) {
                tokenPaths.computeIfAbsent(text, ignored -> new ArrayList<>()).add(pointer);
            } else if (classifier.containsSensitiveValue(text)) {
                throw invalidProtectedOutput();
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                collectTokenPaths(field.getValue(), pointer + "/" + escapePointer(field.getKey()),
                        tokenPaths);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectTokenPaths(node.get(index), pointer + "/" + index, tokenPaths);
            }
        }
    }

    /** 在已经证明路径和占位符一一对应后恢复原始文本。 */
    private void restoreValue(JsonNode root, ProtectedValue value) {
        int separator = value.pointer().lastIndexOf('/');
        String parentPointer = separator == 0 ? "" : value.pointer().substring(0, separator);
        String segment = unescapePointer(value.pointer().substring(separator + 1));
        JsonNode parent = parentPointer.isEmpty() ? root : root.at(parentPointer);
        if (parent instanceof ObjectNode object) {
            object.set(segment, TextNode.valueOf(value.original()));
        } else if (parent instanceof ArrayNode array) {
            try {
                array.set(Integer.parseInt(segment), TextNode.valueOf(value.original()));
            } catch (NumberFormatException | IndexOutOfBoundsException exception) {
                throw invalidProtectedOutput();
            }
        } else {
            throw invalidProtectedOutput();
        }
    }

    /** RFC 6901 转义防止字段名中的斜杠改变路径绑定。 */
    private String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /** 恢复 JSON Pointer 字段名。 */
    private String unescapePointer(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    /** 返回不包含路径、占位符或原始值的固定错误。 */
    private ModelingDraftException invalidProtectedOutput() {
        return new ModelingDraftException(HttpStatus.UNPROCESSABLE_ENTITY,
                ModelingDraftConstants.ERROR_OUTPUT_INVALID, "AI 修订结果未保持受保护字段，未创建新版本");
    }

    /** 请求局部敏感值绑定。 */
    private record ProtectedValue(String pointer, String token, String original) {}
}
