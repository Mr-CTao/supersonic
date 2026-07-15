package com.tencent.supersonic.headless.server.semantic.routing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.schema.ValidateResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.modeling.SemanticModelingSensitivityClassifier;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 受限 LLM 语义比较适配器。
 *
 * <p>
 * 职责：向可选 Provider 只发送脱敏候选 handle 摘要，严格校验固定 JSON 字段、禁止 SQL/正式 ID，
 * 并最多修复一次结构。该组件不在事务中调用；只有确定性规则不足时才允许进入 Provider，Provider 缺失或失败均 fail-closed，不能伪装成纯规则结果。
 * </p>
 */
@Component
public class SemanticAssetRoutingAdvisor {

    private static final Set<String> ALLOWED_ROOT_FIELDS =
            Set.of("recommendedAction", "candidateHandle", "intent", "coveredCapabilities",
                    "missingCapabilities", "businessQuestions", "explanation");
    private static final Set<String> PROHIBITED_FIELDS = Set.of("sql", "assetid", "modelid",
            "formalassetid", "targetassetid", "databasepassword", "token");
    private static final Pattern PROHIBITED_TEXT = Pattern.compile(
            "(?is)\\b(select\\s+.+?\\s+from|insert\\s+into|update\\s+\\S+\\s+set|delete\\s+from)\\b"
                    + "|(?i)(model|asset|target_asset)[_\\s-]*id\\s*[:=]\\s*\\d+"
                    + "|模型\\s*ID\\s*[:：=]?\\s*\\d+");

    private final ObjectMapper objectMapper;
    private final JSONSchema adviceJsonSchema;
    private final Optional<SemanticAssetRoutingAdviceProvider> provider;
    private final SemanticModelingSensitivityClassifier sensitivityClassifier;

    /**
     * 创建受限 Advisor。
     *
     * @param objectMapper JSON 映射器。
     * @param provider 可选 LLM Provider；未配置时只使用规则。
     * @param sensitivityClassifier 共享敏感文本分类器。
     */
    @Autowired
    public SemanticAssetRoutingAdvisor(ObjectMapper objectMapper,
            Optional<SemanticAssetRoutingAdviceProvider> provider,
            SemanticModelingSensitivityClassifier sensitivityClassifier) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.adviceJsonSchema =
                JSONSchema.parseSchema(SemanticAssetRoutingGatewayAdviceProvider.JSON_SCHEMA_TEXT);
        this.provider = provider;
        this.sensitivityClassifier = sensitivityClassifier;
    }

    /**
     * 创建测试用 Advisor，复用生产敏感分类规则。
     *
     * @param objectMapper JSON 映射器。
     * @param provider 可选 LLM Provider。
     */
    SemanticAssetRoutingAdvisor(ObjectMapper objectMapper,
            Optional<SemanticAssetRoutingAdviceProvider> provider) {
        this(objectMapper, provider, new SemanticModelingSensitivityClassifier());
    }

    /**
     * 获取并校验可选 LLM 建议。
     *
     * @param businessGoal 脱敏业务目标。
     * @param candidates 已授权候选。
     * @param coverage 确定性覆盖结果。
     * @return 合法建议；Provider 不可用时为空。
     * @throws SemanticAssetRoutingException 输出越权、未知 handle 或修复后仍非法时抛出。
     */
    public Optional<SemanticAssetRoutingAdvice> advise(String businessGoal,
            List<SemanticAssetCandidate> candidates, SemanticAssetCoverageResult coverage) {
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        SemanticAssetRoutingAdvisorResult result =
                adviseInternal(new SemanticAssetRoutingAdvisorContext(null, null, null, null),
                        businessGoal, candidates, coverage, false);
        return result.advice();
    }

    /**
     * 使用路由上下文获取并校验可选 LLM 建议。
     *
     * <p>
     * 调用示例：{@code advise(routeId, version, chatModelId, user, goal, candidates,
     * coverage)}。该入口仅应在 {@link #requiresSemanticComparison(SemanticAssetCoverageResult)} 返回 true
     * 时调用；调用结果携带会话 ID 供 Store 审计。
     * </p>
     *
     * @param routeId 路由分析 ID。
     * @param analysisVersion 当前分析版本。
     * @param chatModelId 请求指定的聊天模型 ID。
     * @param user 当前认证用户。
     * @param businessGoal 已由路由服务校验的业务目标。
     * @param candidates 当前用户可见的有界候选。
     * @param coverage 确定性覆盖证据。
     * @return 已校验建议及 LLM 会话 ID。
     * @throws SemanticAssetRoutingAdvisorException Provider 缺失、失败或两次输出均非法时抛出。
     */
    public SemanticAssetRoutingAdvisorResult advise(Long routeId, Integer analysisVersion,
            Integer chatModelId, User user, String businessGoal,
            List<SemanticAssetCandidate> candidates, SemanticAssetCoverageResult coverage) {
        return adviseInternal(
                new SemanticAssetRoutingAdvisorContext(routeId, analysisVersion, chatModelId, user),
                businessGoal, candidates, coverage, true);
    }

    /**
     * 判断当前证据是否确实需要慢速语义比较。
     *
     * <p>
     * 完整覆盖、无候选且业务边界清楚、存在必答问题三类结果均可由强规则直接裁决；其余候选 覆盖、粒度或相近候选场景才调用 LLM。该方法只读取请求内快照，线程安全。
     * </p>
     *
     * @param coverage 确定性覆盖证据。
     * @return 需要调用 LLM 时返回 true。
     */
    public boolean requiresSemanticComparison(SemanticAssetCoverageResult coverage) {
        if (coverage == null) {
            return true;
        }
        SemanticAssetCoverageResult.CandidateCoverage primary = coverage.primaryCandidate();
        if (primary != null && primary.isCompleteCoverage()) {
            return false;
        }
        if (safe(coverage.getBusinessQuestions()).stream()
                .anyMatch(SemanticAssetBusinessQuestion::isRequired)) {
            return false;
        }
        return primary != null || !coverage.isBusinessBoundaryClear();
    }

    /** 编排一次生成和至多一次修复，并保持会话 ID。 */
    private SemanticAssetRoutingAdvisorResult adviseInternal(
            SemanticAssetRoutingAdvisorContext context, String businessGoal,
            List<SemanticAssetCandidate> candidates, SemanticAssetCoverageResult coverage,
            boolean failWhenProviderMissing) {
        if (provider.isEmpty()) {
            if (failWhenProviderMissing) {
                throw new SemanticAssetRoutingAdvisorException(HttpStatus.SERVICE_UNAVAILABLE,
                        "ROUTING_ADVISOR_UNAVAILABLE", "AI 语义比较服务未配置，请稍后重试", null);
            }
            return SemanticAssetRoutingAdvisorResult.ruleOnly();
        }
        SemanticAssetRoutingAdvisorRequest request =
                buildRequest(businessGoal, candidates, coverage);
        SemanticAssetRoutingProviderResult first = invokeAdvice(request, context);
        Long conversationId = first == null ? null : first.conversationId();
        try {
            SemanticAssetRoutingAdvice advice = parseAndValidate(
                    first == null ? null : first.output(), candidates, conversationId);
            return new SemanticAssetRoutingAdvisorResult(Optional.of(advice), conversationId);
        } catch (SemanticAssetRoutingAdvisorException firstFailure) {
            SemanticAssetRoutingProviderResult repaired = invokeRepair(
                    first == null ? null : first.output(), request, context, conversationId);
            if (repaired == null || StringUtils.isBlank(repaired.output())) {
                throw firstFailure;
            }
            Long repairedConversationId =
                    repaired.conversationId() == null ? conversationId : repaired.conversationId();
            SemanticAssetRoutingAdvice advice =
                    parseAndValidate(repaired.output(), candidates, repairedConversationId);
            return new SemanticAssetRoutingAdvisorResult(Optional.of(advice),
                    repairedConversationId);
        }
    }

    /** 调用首轮 Provider，并把未知异常归一化为安全错误。 */
    private SemanticAssetRoutingProviderResult invokeAdvice(
            SemanticAssetRoutingAdvisorRequest request,
            SemanticAssetRoutingAdvisorContext context) {
        try {
            return provider.orElseThrow().advise(request, context);
        } catch (SemanticAssetRoutingAdvisorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SemanticAssetRoutingAdvisorException(HttpStatus.BAD_GATEWAY,
                    "ROUTING_ADVISOR_PROVIDER_FAILED", "AI 语义比较暂时不可用，请重试分析", null);
        }
    }

    /** 调用唯一一次结构修复，不记录或解析 Provider 异常正文。 */
    private SemanticAssetRoutingProviderResult invokeRepair(String invalidOutput,
            SemanticAssetRoutingAdvisorRequest request, SemanticAssetRoutingAdvisorContext context,
            Long conversationId) {
        try {
            return provider.orElseThrow().repair(invalidOutput, request, context, conversationId);
        } catch (SemanticAssetRoutingAdvisorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SemanticAssetRoutingAdvisorException(HttpStatus.BAD_GATEWAY,
                    "ROUTING_ADVISOR_PROVIDER_FAILED", "AI 语义比较暂时不可用，请重试分析", conversationId);
        }
    }

    /** 构造不含正式 ID 的 Provider 请求。 */
    private SemanticAssetRoutingAdvisorRequest buildRequest(String businessGoal,
            List<SemanticAssetCandidate> candidates, SemanticAssetCoverageResult coverage) {
        List<SemanticAssetRoutingAdvisorRequest.AdvisorCandidate> safeCandidates = safe(candidates)
                .stream().limit(SemanticAssetRoutingConstants.MAX_CANDIDATES).map(candidate -> {
                    List<String> capabilities = new ArrayList<>();
                    capabilities.addAll(safe(candidate.getDimensionCapabilities()));
                    capabilities.addAll(safe(candidate.getMetricCapabilities()));
                    capabilities.addAll(safe(candidate.getTimeCapabilities()));
                    return SemanticAssetRoutingAdvisorRequest.AdvisorCandidate.builder()
                            .candidateHandle(candidate.getCandidateHandle())
                            .name(sanitize(candidate.getName()))
                            .bizName(sanitize(candidate.getBizName()))
                            .description(sanitize(candidate.getDescription()))
                            .grain(sanitize(safe(candidate.getGrain())))
                            .capabilities(sanitize(capabilities)).build();
                }).toList();
        return SemanticAssetRoutingAdvisorRequest.builder()
                .businessGoal(sensitivityClassifier.sanitizeText(businessGoal))
                .requestedCapabilities(coverage == null ? List.of()
                        : sanitize(safe(coverage.getRequestedCapabilities())))
                .resultOperations(
                        coverage == null ? List.of() : safe(coverage.getResultOperations()))
                .candidates(safeCandidates).build();
    }

    /** 解析固定契约并执行字段和 handle 白名单检查。 */
    private SemanticAssetRoutingAdvice parseAndValidate(String raw,
            List<SemanticAssetCandidate> candidates, Long conversationId) {
        if (StringUtils.isBlank(raw)
                || raw.length() > SemanticAssetRoutingConstants.MAX_ADVISOR_OUTPUT_CHARACTERS) {
            throw invalidOutput(conversationId);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root == null || !root.isObject()) {
                throw invalidOutput(conversationId);
            }
            ValidateResult schemaResult = adviceJsonSchema.validate(JSON.parse(raw));
            if (!schemaResult.isSuccess()) {
                throw invalidOutput(conversationId);
            }
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                if (!ALLOWED_ROOT_FIELDS.contains(names.next())) {
                    throw invalidOutput(conversationId);
                }
            }
            rejectProhibitedFields(root, conversationId);
            SemanticAssetRoutingAdvice advice =
                    objectMapper.treeToValue(root, SemanticAssetRoutingAdvice.class);
            if (advice.getRecommendedAction() == null) {
                throw invalidOutput(conversationId);
            }
            Set<String> handles = new HashSet<>();
            safe(candidates).stream().map(SemanticAssetCandidate::getCandidateHandle)
                    .filter(StringUtils::isNotBlank).forEach(handles::add);
            boolean candidateRequired = advice
                    .getRecommendedAction() == SemanticAssetRouteAction.REUSE_EXISTING
                    || advice.getRecommendedAction() == SemanticAssetRouteAction.EXTEND_EXISTING;
            if ((candidateRequired && StringUtils.isBlank(advice.getCandidateHandle()))
                    || (StringUtils.isNotBlank(advice.getCandidateHandle())
                            && !handles.contains(advice.getCandidateHandle()))
                    || (advice.getRecommendedAction() == SemanticAssetRouteAction.CREATE_NEW
                            && StringUtils.isNotBlank(advice.getCandidateHandle()))) {
                throw invalidOutput(conversationId);
            }
            advice.setExplanation(StringUtils.abbreviate(sensitivityClassifier
                    .sanitizeText(StringUtils.defaultString(advice.getExplanation())), 1000));
            return sanitizeAdvice(advice);
        } catch (SemanticAssetRoutingAdvisorException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw invalidOutput(conversationId);
        }
    }

    /** 递归拒绝 SQL、正式 ID、Token 等禁止字段名。 */
    private void rejectProhibitedFields(JsonNode node, Long conversationId) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized =
                        field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (PROHIBITED_FIELDS.contains(normalized) || normalized.endsWith("sql")) {
                    throw invalidOutput(conversationId);
                }
                rejectProhibitedFields(field.getValue(), conversationId);
            }
        } else if (node.isArray()) {
            node.forEach(item -> rejectProhibitedFields(item, conversationId));
        } else if (node.isTextual() && PROHIBITED_TEXT.matcher(node.asText()).find()) {
            // 禁止把正式 ID 或可执行 SQL 藏在 explanation/reason/name 等合法字符串字段中。
            throw invalidOutput(conversationId);
        }
    }

    /** 创建统一的非法输出错误，避免回显 Provider 原文。 */
    private SemanticAssetRoutingAdvisorException invalidOutput(Long conversationId) {
        return new SemanticAssetRoutingAdvisorException(HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_ADVISOR_OUTPUT", "AI 语义建议格式不符合安全约束，请重试分析", conversationId);
    }

    /** 对将持久化的结构化建议逐字段脱敏，避免模型回显敏感值。 */
    private SemanticAssetRoutingAdvice sanitizeAdvice(SemanticAssetRoutingAdvice advice) {
        advice.setCoveredCapabilities(sanitize(advice.getCoveredCapabilities()));
        for (SemanticAssetCapabilityGap gap : safe(advice.getMissingCapabilities())) {
            if (gap != null) {
                gap.setType(sanitize(gap.getType()));
                gap.setName(sanitize(gap.getName()));
                gap.setReason(sanitize(gap.getReason()));
            }
        }
        for (SemanticAssetBusinessQuestion question : safe(advice.getBusinessQuestions())) {
            if (question != null) {
                question.setQuestion(sanitize(question.getQuestion()));
                for (SemanticAssetBusinessQuestion.QuestionOption option : safe(
                        question.getOptions())) {
                    if (option != null) {
                        option.setLabel(sanitize(option.getLabel()));
                    }
                }
            }
        }
        SemanticAssetRoutingAdvice.Intent intent = advice.getIntent();
        if (intent != null) {
            intent.setSubject(sanitize(intent.getSubject()));
            intent.setGrain(sanitize(intent.getGrain()));
            intent.setDimensions(sanitize(intent.getDimensions()));
            intent.setMeasures(sanitize(intent.getMeasures()));
        }
        return advice;
    }

    /** 使用共享分类器脱敏单个可空文本。 */
    private String sanitize(String value) {
        return sensitivityClassifier.sanitizeText(value);
    }

    /** 使用共享分类器脱敏文本列表并返回新不可变列表。 */
    private List<String> sanitize(List<String> values) {
        return safe(values).stream().map(this::sanitize).toList();
    }

    /** 把可空列表转换为空列表。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
