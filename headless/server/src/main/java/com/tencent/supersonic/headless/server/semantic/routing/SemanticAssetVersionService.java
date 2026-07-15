package com.tencent.supersonic.headless.server.semantic.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.supersonic.headless.api.pojo.MetaFilter;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.ModelDO;
import com.tencent.supersonic.headless.server.persistence.mapper.ModelDOMapper;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.MetricService;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 正式语义模型稳定版本快照服务。
 *
 * <p>职责：把模型主记录及其维度、指标完整内容规范化后计算 SHA-256，并截取 64 位作为路由表可存储的
 * 不透明版本令牌。该令牌不依赖数据库时间精度；子对象内容变化即会改变版本，避免仅比较
 * {@code model.updated_at} 漏掉维度或指标修改。服务只读元数据，不持有共享可变状态，线程安全。</p>
 */
@Service
public class SemanticAssetVersionService {

    private final ModelDOMapper modelDOMapper;
    private final DimensionService dimensionService;
    private final MetricService metricService;
    private final ObjectMapper objectMapper;

    /**
     * 创建语义资产版本服务。
     *
     * @param modelDOMapper 模型主记录批量查询 Mapper。
     * @param dimensionService 维度批量查询服务。
     * @param metricService 指标批量查询服务。
     * @param objectMapper 规范 JSON 序列化器。
     */
    public SemanticAssetVersionService(ModelDOMapper modelDOMapper,
            DimensionService dimensionService, MetricService metricService,
            ObjectMapper objectMapper) {
        this.modelDOMapper = modelDOMapper;
        this.dimensionService = dimensionService;
        this.metricService = metricService;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用已批量读取的模型事实计算版本，不产生额外数据库查询。
     *
     * @param model 模型主记录。
     * @param dimensions 当前模型维度。
     * @param metrics 当前模型指标。
     * @return 稳定的 64 位语义内容版本；模型不存在时返回 {@code null}。
     */
    public Long versionOf(ModelDO model, List<DimensionResp> dimensions,
            List<MetricResp> metrics) {
        if (model == null || model.getId() == null) {
            return null;
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("model", objectMapper.valueToTree(model));
        snapshot.set("dimensions", sortedArray(dimensions, DimensionResp::getId));
        snapshot.set("metrics", sortedArray(metrics, MetricResp::getId));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        } catch (JsonProcessingException exception) {
            throw new SemanticAssetRoutingException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "ASSET_VERSION_SERIALIZATION_FAILED", "语义资产版本快照生成失败");
        }
    }

    /**
     * 用四次有界批量查询计算一组模型的当前版本。
     *
     * @param modelIds 已通过 ACL 过滤的模型 ID 集合。
     * @return 模型 ID 到稳定版本的映射；已删除模型不会出现在结果中。
     */
    public Map<Long, Long> loadVersions(Collection<Long> modelIds) {
        List<Long> ids = modelIds == null ? List.of()
                : modelIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ModelDO> models = safe(modelDOMapper.selectBatchIds(ids)).stream()
                .filter(Objects::nonNull).collect(Collectors.toMap(ModelDO::getId,
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        MetaFilter filter = new MetaFilter(ids);
        Map<Long, List<DimensionResp>> dimensions = safe(dimensionService.getDimensions(filter))
                .stream()
                .filter(item -> item != null && item.getModelId() != null)
                .collect(Collectors.groupingBy(DimensionResp::getModelId));
        Map<Long, List<MetricResp>> metrics = safe(metricService.getMetrics(filter)).stream()
                .filter(item -> item != null && item.getModelId() != null)
                .collect(Collectors.groupingBy(MetricResp::getModelId));
        Map<Long, Long> versions = new LinkedHashMap<>();
        ids.forEach(id -> {
            Long version = versionOf(models.get(id), dimensions.getOrDefault(id, List.of()),
                    metrics.getOrDefault(id, List.of()));
            if (version != null) {
                versions.put(id, version);
            }
        });
        return Map.copyOf(versions);
    }

    /** 把 DTO 按稳定业务 ID 排序后序列化为数组，消除数据库返回顺序对版本的影响。 */
    private <T> ArrayNode sortedArray(List<T> values, Function<T, Long> idExtractor) {
        ArrayNode result = objectMapper.createArrayNode();
        if (values == null) {
            return result;
        }
        values.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(idExtractor,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(value -> result.add(objectMapper.valueToTree(value)));
        return result;
    }

    /** 把外部服务可能返回的 null 规范为空集合，避免版本校验因空响应产生空指针。 */
    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
