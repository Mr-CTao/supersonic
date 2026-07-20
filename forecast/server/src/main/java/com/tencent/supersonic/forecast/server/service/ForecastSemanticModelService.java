package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastProfileDO;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.service.ModelService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通过现有 Headless 管理服务幂等创建 Forecast 语义模型。
 *
 * <p>
 * 本服务不直接写模型元数据表。它只基于稳定只读服务视图创建一次模型；后续事实和预测变化不会 调用模型、字典或向量索引重建。Worker 进程没有 ModelService，因此只能由
 * Standalone 管理 API 调用。
 * </p>
 */
@Service
public class ForecastSemanticModelService {

    private static final String MODEL_NAME = "WMS出入库预测";
    private static final String MODEL_BIZ_NAME = "wms_inout_forecast";
    private static final String SERVICE_VIEW = "v_forecast_latest_result";

    private final ForecastControlStore controlStore;
    private final ForecastAccessService accessService;
    private final ObjectProvider<ModelService> modelServiceProvider;
    private final ForecastProperties properties;

    /**
     * 创建语义模型服务。
     *
     * @param controlStore Forecast 控制面。
     * @param accessService ACL 服务。
     * @param modelServiceProvider Headless ModelService 延迟提供者。
     * @param properties Forecast 配置。
     */
    public ForecastSemanticModelService(ForecastControlStore controlStore,
            ForecastAccessService accessService, ObjectProvider<ModelService> modelServiceProvider,
            ForecastProperties properties) {
        this.controlStore = controlStore;
        this.accessService = accessService;
        this.modelServiceProvider = modelServiceProvider;
        this.properties = properties;
    }

    /**
     * 查找或创建“WMS出入库预测”语义模型。
     *
     * @param profileId Profile ID。
     * @param domainId 目标主题域 ID。
     * @param user 当前管理员。
     * @return 已存在或新建模型。
     * @throws InvalidArgumentException Standalone 未加载 Headless 服务或建模失败。
     */
    public ModelResp provision(Long profileId, Long domainId, User user) {
        if (domainId == null) {
            throw new InvalidArgumentException("创建预测语义模型必须选择主题域");
        }
        ForecastProfileDO profile = controlStore.requireProfile(profileId);
        accessService.requireAdmin(profile.getSourceDatabaseId(), user);
        DatabaseResp decision = accessService.requireAdmin(profile.getDecisionDatabaseId(), user);
        ModelService modelService = modelServiceProvider.getIfAvailable();
        if (modelService == null) {
            throw new InvalidArgumentException("当前进程未启用 Headless 语义模型管理服务");
        }
        ModelFilter filter = new ModelFilter(false);
        filter.setDatabaseId(profile.getDecisionDatabaseId());
        List<ModelResp> existing = modelService.getModelList(filter);
        ModelResp matched = existing.stream().filter(model -> domainId.equals(model.getDomainId()))
                .filter(model -> MODEL_BIZ_NAME.equals(model.getBizName())
                        || MODEL_NAME.equals(model.getName()))
                .findFirst().orElse(null);
        if (matched != null) {
            return matched;
        }
        ModelBuildReq request = new ModelBuildReq();
        request.setName(MODEL_NAME);
        request.setBizName(MODEL_BIZ_NAME);
        request.setDatabaseId(profile.getDecisionDatabaseId());
        request.setDomainId(domainId);
        request.setCatalog(decision.getDatabase());
        request.setDb(properties.getDecision().getSchema());
        request.setTables(List.of(SERVICE_VIEW));
        request.setBuildByLLM(false);
        try {
            List<ModelResp> created = modelService.createModel(request, user);
            if (created.isEmpty()) {
                throw new InvalidArgumentException("预测服务视图未生成可建模字段");
            }
            return created.get(0);
        } catch (InvalidArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidArgumentException("预测语义模型创建失败，请检查主题域权限和服务视图");
        }
    }
}
