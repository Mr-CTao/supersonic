package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 修订敏感占位符保护上下文单元测试。
 *
 * <p>
 * 职责说明：验证请求局部占位符可以恢复原值，并对删除、修改、移动、复制及并发上下文串扰 fail-closed。测试断言只使用合成值，不输出占位符映射或真实敏感信息。
 * </p>
 */
class SemanticModelingProtectedDraftContextTest {

    private ObjectMapper objectMapper;
    private SemanticModelingSensitivityClassifier classifier;

    /** 初始化无共享可变状态的分类器和 JSON 工具。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        classifier = new SemanticModelingSensitivityClassifier();
    }

    /** 模型只修改非敏感字段时，所有敏感字段必须逐字恢复为基线值。 */
    @Test
    void shouldRestoreProtectedValuesAfterUnrelatedRevision() throws Exception {
        JsonNode source = sourceDraft();
        SemanticModelingProtectedDraftContext context =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode protectedDraft = (ObjectNode) context.protect(source);
        protectedDraft.put("businessGoal", "updated goal");

        JsonNode restored = context.restore(protectedDraft);

        assertThat(restored.path("businessGoal").asText()).isEqualTo("updated goal");
        assertThat(restored.path("ownerEmail").asText())
                .isEqualTo(source.path("ownerEmail").asText());
        assertThat(restored.path("ownerPhone").asText())
                .isEqualTo(source.path("ownerPhone").asText());
        assertThat(restored.path("credential").asText())
                .isEqualTo(source.path("credential").asText());
    }

    /** 删除或修改任一受保护占位符都必须拒绝保存。 */
    @Test
    void shouldRejectDeletedOrModifiedPlaceholder() throws Exception {
        SemanticModelingProtectedDraftContext deletedContext =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode deleted = (ObjectNode) deletedContext.protect(sourceDraft());
        deleted.remove("ownerEmail");

        assertInvalid(() -> deletedContext.restore(deleted));

        SemanticModelingProtectedDraftContext modifiedContext =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode modified = (ObjectNode) modifiedContext.protect(sourceDraft());
        modified.put("ownerPhone", modified.path("ownerPhone").asText() + "changed");

        assertInvalid(() -> modifiedContext.restore(modified));
    }

    /** 占位符移动、复制到新路径或凭空新增都必须 fail-closed。 */
    @Test
    void shouldRejectMovedCopiedOrFabricatedPlaceholder() throws Exception {
        SemanticModelingProtectedDraftContext movedContext =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode moved = (ObjectNode) movedContext.protect(sourceDraft());
        String emailToken = moved.path("ownerEmail").asText();
        moved.put("ownerEmail", moved.path("ownerPhone").asText());
        moved.put("ownerPhone", emailToken);
        assertInvalid(() -> movedContext.restore(moved));

        SemanticModelingProtectedDraftContext copiedContext =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode copied = (ObjectNode) copiedContext.protect(sourceDraft());
        copied.put("copied", copied.path("ownerEmail").asText());
        assertInvalid(() -> copiedContext.restore(copied));

        SemanticModelingProtectedDraftContext fabricatedContext =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode fabricated = (ObjectNode) fabricatedContext.protect(sourceDraft());
        fabricated.put("fabricated", "__S2_PROTECTED_not_issued__");
        assertInvalid(() -> fabricatedContext.restore(fabricated));
    }

    /** 并发请求上下文使用不同 nonce，同路径占位符不能跨请求重放。 */
    @Test
    void shouldKeepConcurrentRequestMappingsIsolated() throws Exception {
        SemanticModelingProtectedDraftContext first =
                new SemanticModelingProtectedDraftContext(classifier);
        SemanticModelingProtectedDraftContext second =
                new SemanticModelingProtectedDraftContext(classifier);
        ObjectNode firstProtected = (ObjectNode) first.protect(sourceDraft());
        ObjectNode secondProtected = (ObjectNode) second.protect(sourceDraft());

        assertThat(firstProtected.path("ownerEmail").asText())
                .isNotEqualTo(secondProtected.path("ownerEmail").asText());
        firstProtected.put("ownerEmail", secondProtected.path("ownerEmail").asText());
        assertInvalid(() -> first.restore(firstProtected));
        assertThat(second.restore(secondProtected).path("ownerEmail").asText())
                .isEqualTo(sourceDraft().path("ownerEmail").asText());
    }

    /** 构造只包含合成敏感值的最小草稿。 */
    private JsonNode sourceDraft() throws Exception {
        return objectMapper.readTree("""
                {
                  "businessGoal": "original goal",
                  "ownerEmail": "owner@example.test",
                  "ownerPhone": "13800138000",
                  "credential": "access_token=synthetic-token"
                }
                """);
    }

    /** 断言安全拒绝使用稳定错误码且不泄漏输入内容。 */
    private void assertInvalid(ThrowingOperation operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ModelingDraftException.class,
                exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ModelingDraftConstants.ERROR_OUTPUT_INVALID);
                    assertThat(exception.getMessage()).doesNotContain("owner@example.test",
                            "13800138000", "synthetic-token");
                });
    }

    /** 允许测试 lambda 抛出受检异常。 */
    @FunctionalInterface
    private interface ThrowingOperation {
        /** 执行待断言操作。 */
        void run() throws Exception;
    }
}
