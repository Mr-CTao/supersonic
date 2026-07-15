package com.tencent.supersonic.headless.server.semantic.diagnostic;

import com.tencent.supersonic.common.pojo.DataItem;
import com.tencent.supersonic.headless.api.pojo.response.ModelHealthResp;
import com.tencent.supersonic.headless.api.pojo.response.RefreshStatus;
import com.tencent.supersonic.headless.api.pojo.response.SemanticDiagnostic;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationResult;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 维护当前应用节点最近观测到的模型健康状态。
 *
 * <p>
 * 并发决策：监听器由异步线程并发回写，使用 {@link ConcurrentHashMap#compute} 对单模型状态
 * 原子更新；状态对象永不直接暴露，读取时返回快照，避免调用方修改共享对象。该服务没有伪装 分布式一致性，跨节点状态传播仍依赖部署层事件总线。
 */
@Service
public class ModelHealthService {

    private final ConcurrentMap<Long, ModelHealthResp> healthByModel = new ConcurrentHashMap<>();

    /**
     * 记录一次确定性模型校验结果。
     *
     * @param modelId 模型 ID；新建模型尚无 ID 时忽略。
     * @param result 校验结果。
     */
    public void recordValidation(Long modelId, SemanticValidationResult result) {
        if (modelId == null || result == null) {
            return;
        }
        update(modelId, health -> {
            health.setCompileStatus(result.getOverallStatus());
            health.setLastValidatedAt(System.currentTimeMillis());
            health.setContentDigest(result.getContentDigest());
            health.setMessage(result.getOverallStatus().name());
        });
    }

    /**
     * 标记相关模型的 Schema 缓存刷新结果。
     *
     * @param modelIds 模型 ID 集合。
     * @param status 刷新状态。
     */
    public void recordSchemaCache(Collection<Long> modelIds, RefreshStatus status) {
        updateAll(modelIds, health -> health.setSchemaCacheStatus(status));
    }

    /**
     * 标记词典刷新状态。
     *
     * @param modelIds 模型 ID 集合。
     * @param status 刷新状态。
     */
    public void recordDictionary(Collection<Long> modelIds, RefreshStatus status) {
        updateAll(modelIds, health -> health.setDictionaryStatus(status));
    }

    /**
     * 标记 Embedding 刷新状态。
     *
     * @param modelIds 模型 ID 集合。
     * @param status 刷新状态。
     */
    public void recordEmbedding(Collection<Long> modelIds, RefreshStatus status) {
        updateAll(modelIds, health -> health.setEmbeddingStatus(status));
    }

    /**
     * 记录最近一次结构化问答失败，不保存用户问题或生成 SQL。
     *
     * @param modelIds 失败涉及的模型集合。
     * @param diagnostic 脱敏诊断。
     */
    public void recordQueryFailure(Collection<Long> modelIds, SemanticDiagnostic diagnostic) {
        if (diagnostic == null) {
            return;
        }
        updateAll(modelIds, health -> {
            health.setLastErrorCode(
                    diagnostic.getCode() == null ? null : diagnostic.getCode().name());
            health.setLastTraceId(diagnostic.getTraceId());
        });
    }

    /**
     * 获取模型健康快照。
     *
     * @param modelId 模型 ID。
     * @return 独立快照；尚无运行记录时返回 UNKNOWN 初始状态。
     */
    public ModelHealthResp getHealth(Long modelId) {
        ModelHealthResp source = healthByModel.get(modelId);
        if (source == null) {
            source = newHealth(modelId);
        }
        return copy(source);
    }

    /**
     * 从资产事件安全提取数字模型 ID；历史或异常非数字值不会阻断其他模型刷新。
     *
     * @param dataItems 资产事件项。
     * @return 去重后的有效模型 ID。
     */
    public static List<Long> toModelIds(Collection<DataItem> dataItems) {
        if (dataItems == null) {
            return Collections.emptyList();
        }
        return dataItems.stream().filter(Objects::nonNull).map(DataItem::getModelId)
                .filter(Objects::nonNull).map(ModelHealthService::parseModelId)
                .filter(Objects::nonNull).distinct().collect(java.util.stream.Collectors.toList());
    }

    /** 将事件中的字符串模型 ID 转为 Long，非法值按无模型上下文处理。 */
    private static Long parseModelId(String modelId) {
        try {
            return Long.valueOf(modelId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 对多个模型执行相同的原子状态更新。 */
    private void updateAll(Collection<Long> modelIds, Consumer<ModelHealthResp> updater) {
        if (modelIds == null) {
            return;
        }
        modelIds.stream().filter(Objects::nonNull).distinct().forEach(id -> update(id, updater));
    }

    /** 对单模型状态执行原子更新。 */
    private void update(Long modelId, Consumer<ModelHealthResp> updater) {
        healthByModel.compute(modelId, (id, current) -> {
            ModelHealthResp target = current == null ? newHealth(id) : current;
            updater.accept(target);
            return target;
        });
    }

    /** 创建未观测到任何刷新动作的初始状态。 */
    private ModelHealthResp newHealth(Long modelId) {
        ModelHealthResp health = new ModelHealthResp();
        health.setModelId(modelId);
        health.setMessage("尚无校验记录");
        return health;
    }

    /** 复制快照，隔离共享可变对象。 */
    private ModelHealthResp copy(ModelHealthResp source) {
        ModelHealthResp snapshot = new ModelHealthResp();
        snapshot.setModelId(source.getModelId());
        snapshot.setCompileStatus(source.getCompileStatus());
        snapshot.setLastValidatedAt(source.getLastValidatedAt());
        snapshot.setContentDigest(source.getContentDigest());
        snapshot.setSchemaCacheStatus(source.getSchemaCacheStatus());
        snapshot.setDictionaryStatus(source.getDictionaryStatus());
        snapshot.setEmbeddingStatus(source.getEmbeddingStatus());
        snapshot.setLastErrorCode(source.getLastErrorCode());
        snapshot.setLastTraceId(source.getLastTraceId());
        snapshot.setMessage(source.getMessage());
        return snapshot;
    }
}
