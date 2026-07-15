package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 语义建模草稿 JSON Schema 1.0/2.0 的类型化载体。
 *
 * <p>
 * 职责说明：兼容历史完整新建草稿，并为已确认的 EXTEND_EXISTING 路由表达目标资产快照和
 * additions-only 增量。所有可编辑关联均使用草稿本地 key；正式资产只通过服务端确认的路由摘要引用，
 * 不接受 LLM 生成的资产 ID。该对象只承载隔离草稿，不提供发布或正式元数据转换能力。并发说明：
 * 实例仅在单次请求或 Worker 内使用，不作为共享缓存。
 * </p>
 */
@Data
@NoArgsConstructor
public class ModelingDraftPayload {

    /** 历史草稿为 1.0，路由感知草稿为 2.0。 */
    private String schemaVersion = ModelingDraftConstants.SCHEMA_VERSION;

    /** 路由确认动作；历史记录为空时按 CREATE_NEW 兼容读取。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String action;

    /** 服务端生成的安全路由摘要，不包含内部评分、SQL 条件值或未授权资产。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RouteSummaryDraft routeSummary;

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

    /** EXTEND_EXISTING 的只读目标资产快照。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TargetAssetDraft targetAsset;

    /** EXTEND_EXISTING 仅保存的新增对象，不复制正式模型全文。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AdditionsDraft additions;

    /** 对既有描述或别名的受控 before/after 修改建议。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ModificationDraft> modifications;

    /** 增强后必须重新验证的业务问法。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> regressionQuestions;

    /**
     * 返回供现有字段、敏感性和样例问法校验器使用的请求内视图。
     *
     * <p>EXTEND_EXISTING 只把 additions 映射成一个临时模型，绝不把正式目标资产复制进草稿；
     * 返回对象与原 additions 共享增量列表，使规范化结果仍写回增量结构。CREATE_NEW 和历史草稿直接返回
     * 当前对象。</p>
     *
     * @return 仅用于确定性校验的请求局部视图。
     */
    public ModelingDraftPayload validationView() {
        if (!ModelingDraftConstants.ACTION_EXTEND_EXISTING.equals(action) || additions == null
                || targetAsset == null) {
            return this;
        }
        ModelingDraftPayload view = new ModelingDraftPayload();
        view.setSchemaVersion(schemaVersion);
        view.setAction(action);
        view.setBusinessGoal(businessGoal);
        view.setTargetDomain(targetDomain);
        view.setUncertainties(uncertainties);
        view.setTerms(additions.getTerms());

        ModelDraft incrementalModel = new ModelDraft();
        incrementalModel.setKey(targetAsset.getCandidateHandle());
        incrementalModel.setName(targetAsset.getName());
        incrementalModel.setBizName(targetAsset.getCandidateHandle());
        incrementalModel.setDescription("目标资产上的已确认增量；正式资产内容不复制到草稿");
        incrementalModel.setBaseTable(targetAsset.getBaseTable());
        incrementalModel.setDimensions(additions.getDimensions());
        incrementalModel.setMetrics(additions.getMetrics());
        incrementalModel.setSensitiveFields(additions.getSensitiveFields());
        incrementalModel.setValidationAliases(additions.getAliases());
        // 回归问法必须进入与普通样例相同的真实 mapper/parser/translator 链路。这里在请求内
        // 合并并去重，既不改写持久化 JSON，也避免同一句问法被 Provider/Translator 重复验证。
        List<String> validationQuestions = new ArrayList<>();
        if (additions.getSampleQuestions() != null) {
            validationQuestions.addAll(additions.getSampleQuestions());
        }
        if (regressionQuestions != null) {
            for (String question : regressionQuestions) {
                if (!validationQuestions.contains(question)) {
                    validationQuestions.add(question);
                }
            }
        }
        incrementalModel.setSampleQuestions(validationQuestions);
        view.setModels(new ArrayList<>(List.of(incrementalModel)));
        return view;
    }

    /** 路由确认摘要，只提供管理员可理解的业务证据。 */
    @Data
    @NoArgsConstructor
    public static class RouteSummaryDraft {
        private Long routeAnalysisId;
        private String decisionSource;
        private String explanation;
        private List<String> coveredCapabilities = new ArrayList<>();
        private List<String> missingCapabilities = new ArrayList<>();
        private List<String> queryOperations = new ArrayList<>();
        private Map<String, Object> businessAnswers = new LinkedHashMap<>();
    }

    /** 已确认目标资产的不可漂移快照；candidateHandle 由服务端生成。 */
    @Data
    @NoArgsConstructor
    public static class TargetAssetDraft {
        private String candidateHandle;
        private String assetType;
        private String name;
        private Long baseVersion;
        private String baseTable;
    }

    /** 增量草稿允许新增的对象集合。 */
    @Data
    @NoArgsConstructor
    public static class AdditionsDraft {
        private List<DimensionDraft> dimensions = new ArrayList<>();
        private List<MetricDraft> metrics = new ArrayList<>();
        private List<TermDraft> terms = new ArrayList<>();
        private List<SensitiveFieldDraft> sensitiveFields = new ArrayList<>();
        private List<String> aliases = new ArrayList<>();
        private List<String> sampleQuestions = new ArrayList<>();
    }

    /** 对既有对象描述或别名的受控增量建议。 */
    @Data
    @NoArgsConstructor
    public static class ModificationDraft {
        private String objectType;
        private String objectKey;
        private String field;
        private Object beforeValue;
        private Object afterValue;
        private String reason;
    }

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

        /**
         * EXTEND_EXISTING 请求内校验视图中的目标模型新增别名。
         *
         * <p>该字段只让隔离 parser 和检索污染检查看到 {@code additions.aliases}，不属于
         * 1.0/2.0 持久化 Schema，因此必须忽略 JSON 序列化，避免规范化结果出现未知字段。</p>
         */
        @JsonIgnore
        private List<String> validationAliases = new ArrayList<>();
    }

    /** 基于单个物理字段的维度草稿。 */
    @Data
    @NoArgsConstructor
    public static class DimensionDraft {
        private String key;
        private String name;
        private String bizName;
        private String field;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private DerivedFieldDraft derivation;
        private String description;
        private String semanticType;
        private List<String> aliases = new ArrayList<>();
    }

    /**
     * 不包含任意 SQL 的派生字段描述。
     *
     * <p>第一版仅允许日期差，结束值只能是当前日期或同表字段。发布阶段必须再由正式语义 API
     * 将该结构翻译成目标数据库表达式，本任务不会执行或持久化拼接 SQL。</p>
     */
    @Data
    @NoArgsConstructor
    public static class DerivedFieldDraft {
        private String operator;
        private String startField;
        private String endType;
        private String endField;
        private String unit;
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
