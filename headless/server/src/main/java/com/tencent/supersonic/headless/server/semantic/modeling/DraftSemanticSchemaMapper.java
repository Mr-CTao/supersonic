package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.headless.api.pojo.DataSetSchema;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.chat.ChatQueryContext;
import com.tencent.supersonic.headless.chat.mapper.SchemaMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 未发布草稿专用的隔离内存语义映射器。
 *
 * <p>
 * 职责说明：在不写入正式知识库、词典或向量索引的前提下，使用现有 {@link SchemaMapper} 的 {@link SchemaElementMatch}
 * 数据结构，将样例问法精确映射到内存草稿 Schema。术语命中时会展开到 术语声明的维度或指标目标，使后续现有 RuleSqlParser 能按正式解析数据结构产出 selected
 * parse。 本组件无共享可变状态，所有索引均为单次调用局部变量，因此并发调用无需额外锁。
 * </p>
 */
@Component
public class DraftSemanticSchemaMapper implements SchemaMapper {

    static final String EXT_DRAFT_OBJECT_KEY = "draftObjectKey";
    static final String EXT_TERM_TARGET_IDS = "draftTermTargetIds";
    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final String TOKEN_CHARACTER = "[\\p{L}\\p{N}_]";

    /**
     * 对单个隔离草稿数据集执行确定性名称、业务名和别名映射。
     *
     * <p>
     * 调用示例：{@code mapper.map(queryContext)}。调用方必须事先把仅包含当前草稿模型的
     * {@link com.tencent.supersonic.headless.api.pojo.SemanticSchema} 放入上下文，避免与发布态资产混合。
     * </p>
     *
     * @param queryContext 只包含当前样例问法和隔离草稿 Schema 的查询上下文。
     * @throws IllegalArgumentException 上下文、问题或隔离 Schema 缺失时抛出。
     */
    @Override
    public void map(ChatQueryContext queryContext) {
        if (queryContext == null || queryContext.getRequest() == null
                || queryContext.getSemanticSchema() == null
                || StringUtils.isBlank(queryContext.getRequest().getQueryText())) {
            throw new IllegalArgumentException("隔离草稿语义映射上下文不完整");
        }

        String question = queryContext.getRequest().getQueryText();
        String normalizedQuestion = normalize(question);
        for (DataSetSchema dataSetSchema : queryContext.getSemanticSchema().getDataSetSchemaMap()
                .values()) {
            Map<Long, SchemaElement> targetElements = indexTargetElements(dataSetSchema);
            Map<String, SchemaElementMatch> matches = new LinkedHashMap<>();
            matchElements(question, normalizedQuestion, dataSetSchema.getDimensions(), matches);
            matchElements(question, normalizedQuestion, dataSetSchema.getMetrics(), matches);
            matchTerms(question, normalizedQuestion, dataSetSchema.getTerms(), targetElements,
                    matches);
            if (!matches.isEmpty()) {
                queryContext.getMapInfo().setMatchedElements(dataSetSchema.getDataSetId(),
                        new ArrayList<>(matches.values()));
            }
        }
    }

    /** 构建维度和指标 ID 索引，供术语目标在内存中展开。 */
    private Map<Long, SchemaElement> indexTargetElements(DataSetSchema dataSetSchema) {
        Map<Long, SchemaElement> elements = new LinkedHashMap<>();
        dataSetSchema.getDimensions().forEach(element -> elements.put(element.getId(), element));
        dataSetSchema.getMetrics().forEach(element -> elements.put(element.getId(), element));
        return elements;
    }

    /** 映射普通维度或指标，并按元素 ID 去重。 */
    private void matchElements(String question, String normalizedQuestion,
            Collection<SchemaElement> elements, Map<String, SchemaElementMatch> matches) {
        for (SchemaElement element : elements) {
            String token = firstMatchedToken(question, normalizedQuestion, element);
            if (token != null) {
                addMatch(matches, element, token);
            }
        }
    }

    /** 映射术语本身，并把术语命中展开为其草稿本地目标。 */
    private void matchTerms(String question, String normalizedQuestion,
            Collection<SchemaElement> terms, Map<Long, SchemaElement> targetElements,
            Map<String, SchemaElementMatch> matches) {
        for (SchemaElement term : terms) {
            String token = firstMatchedToken(question, normalizedQuestion, term);
            if (token == null) {
                continue;
            }
            addMatch(matches, term, token);
            for (Long targetId : readTargetIds(term)) {
                SchemaElement target = targetElements.get(targetId);
                if (target != null) {
                    // 目标仍保留术语原文作为 detectWord，使 parser 的真实评分反映本次术语命中。
                    addMatch(matches, target, token);
                }
            }
        }
    }

    /** 从受控 extInfo 读取工厂写入的术语目标 ID。 */
    private List<Long> readTargetIds(SchemaElement term) {
        Object raw = term.getExtInfo().get(EXT_TERM_TARGET_IDS);
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Long id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 找到问题中首次出现的非空 key、名称、业务名或别名。 */
    private String firstMatchedToken(String question, String normalizedQuestion,
            SchemaElement element) {
        Set<String> candidates = new LinkedHashSet<>();
        Object key = element.getExtInfo().get(EXT_DRAFT_OBJECT_KEY);
        if (key instanceof String value) {
            candidates.add(value);
        }
        candidates.add(element.getName());
        candidates.add(element.getBizName());
        if (element.getAlias() != null) {
            candidates.addAll(element.getAlias());
        }
        return candidates.stream().filter(StringUtils::isNotBlank)
                .filter(value -> matchesCandidate(question, normalizedQuestion, value)).findFirst()
                .orElse(null);
    }

    /**
     * 按候选类型执行保守匹配：ASCII 标识符必须满足 Unicode token 边界，中文或多词候选才允许归一包含。
     */
    private boolean matchesCandidate(String question, String normalizedQuestion, String candidate) {
        String trimmed = StringUtils.trim(candidate);
        String normalizedCandidate = normalize(trimmed);
        if (normalizedCandidate.isEmpty()) {
            return false;
        }
        if (normalizedCandidate.codePointCount(0, normalizedCandidate.length()) == 1) {
            // 单字符名称极易污染普通问法，仅允许整条问法精确等价。
            return normalizedQuestion.equals(normalizedCandidate);
        }
        if (ASCII_IDENTIFIER.matcher(trimmed).matches()) {
            Pattern bounded = Pattern.compile(
                    "(?<!" + TOKEN_CHARACTER + ")" + Pattern.quote(trimmed) + "(?!"
                            + TOKEN_CHARACTER + ")",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            return bounded.matcher(question).find();
        }
        return normalizedQuestion.contains(normalizedCandidate);
    }

    /** 添加现有 parser 可直接消费的高置信语义命中。 */
    private void addMatch(Map<String, SchemaElementMatch> matches, SchemaElement element,
            String token) {
        String key = element.getType() + ":" + element.getId();
        matches.putIfAbsent(key, SchemaElementMatch.builder().word(token).detectWord(token)
                .element(element).similarity(1.0D).build());
    }

    /** 统一忽略大小写、空白和标点，保留中英文、数字及下划线。 */
    private String normalize(String value) {
        return StringUtils.trimToEmpty(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}_]+", "");
    }
}
