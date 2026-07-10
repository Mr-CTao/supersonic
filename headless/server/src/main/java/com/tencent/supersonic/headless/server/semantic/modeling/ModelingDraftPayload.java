package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 语义建模草稿 JSON Schema 1.0 的类型化载体。
 *
 * <p>
 * 职责说明：表达模型、维度、指标、域级术语、敏感字段、示例问法和不确定项。所有关联均使用 草稿本地 key，不引用正式语义资产 ID。该对象只承载隔离草稿，不提供任何发布或正式元数据转换能力。
 * 并发说明：实例仅在单次请求或 Worker 内使用，不作为共享缓存。
 * </p>
 */
@Data
@NoArgsConstructor
public class ModelingDraftPayload {

    /** 固定为 1.0，用于未来兼容升级。 */
    private String schemaVersion = ModelingDraftConstants.SCHEMA_VERSION;

    /** 管理员输入并经 LLM 细化的业务目标。 */
    private String businessGoal;

    /** 可选目标主题域，只作上下文与草稿展示，不会修改主题域。 */
    private TargetDomain targetDomain;

    /** 一个或多个单基表模型草稿。 */
    private List<ModelDraft> models = new ArrayList<>();

    /** 主题域级草稿术语。 */
    private List<TermDraft> terms = new ArrayList<>();

    /** 无法安全自动决策的内容。 */
    private List<UncertaintyDraft> uncertainties = new ArrayList<>();

    /** 主题域摘要。 */
    @Data
    @NoArgsConstructor
    public static class TargetDomain {
        private Long domainId;
        private String name;
        private String bizName;
        private String description;
    }

    /** 单一 baseTable 对应的模型草稿。 */
    @Data
    @NoArgsConstructor
    public static class ModelDraft {
        private String key;
        private String name;
        private String bizName;
        private String description;
        private String baseTable;
        private String primaryTimeField;
        private List<DimensionDraft> dimensions = new ArrayList<>();
        private List<MetricDraft> metrics = new ArrayList<>();
        private List<SensitiveFieldDraft> sensitiveFields = new ArrayList<>();
        private List<String> sampleQuestions = new ArrayList<>();
    }

    /** 基于单个物理字段的维度草稿。 */
    @Data
    @NoArgsConstructor
    public static class DimensionDraft {
        private String key;
        private String name;
        private String bizName;
        private String field;
        private String description;
        private String semanticType;
        private List<String> aliases = new ArrayList<>();
    }

    /** 受限为单表单列聚合的指标草稿。 */
    @Data
    @NoArgsConstructor
    public static class MetricDraft {
        private String key;
        private String name;
        private String bizName;
        private String field;
        private String aggregation;
        private String expression;
        private String description;
        private List<String> aliases = new ArrayList<>();
        private List<MetricFilterDraft> filters = new ArrayList<>();
    }

    /** 指标的结构化过滤条件，禁止承载 SQL 片段。 */
    @Data
    @NoArgsConstructor
    public static class MetricFilterDraft {
        private String field;
        private String operator;
        private List<String> values = new ArrayList<>();
    }

    /** 域级术语及其草稿本地目标。 */
    @Data
    @NoArgsConstructor
    public static class TermDraft {
        private String key;
        private String name;
        private String description;
        private List<String> aliases = new ArrayList<>();
        private List<TermTargetDraft> targets = new ArrayList<>();
    }

    /** 术语目标，只允许 DIMENSION 或 METRIC。 */
    @Data
    @NoArgsConstructor
    public static class TermTargetDraft {
        private String type;
        private String objectKey;
    }

    /** 需要管理端明确展示的敏感字段建议。 */
    @Data
    @NoArgsConstructor
    public static class SensitiveFieldDraft {
        private String field;
        private String level;
        private String maskingStrategy;
        private String reason;
    }

    /** 无法自动确定或超出第一版单表边界的事项。 */
    @Data
    @NoArgsConstructor
    public static class UncertaintyDraft {
        private String key;
        private String modelKey;
        private String objectKey;
        private String field;
        private String category;
        private String severity;
        private String reason;
    }
}
