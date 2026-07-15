package com.tencent.supersonic.headless.server.rest;

import com.google.common.collect.Lists;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.ModelSchema;
import com.tencent.supersonic.headless.api.pojo.request.FieldRemovedReq;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelHealthResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticValidationResult;
import com.tencent.supersonic.headless.api.pojo.response.UnAvailableItemResp;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.semantic.diagnostic.ModelHealthService;
import com.tencent.supersonic.headless.server.semantic.diagnostic.SemanticModelValidationReq;
import com.tencent.supersonic.headless.server.semantic.diagnostic.SemanticModelValidationService;
import com.tencent.supersonic.headless.server.service.ModelService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语义模型管理接口。
 *
 * <p>
 * 职责：提供模型 CRUD、确定性 SQL 校验和最小健康摘要；健康详情只对具备模型管理权限的 用户开放，避免普通查询用户获取内部错误码与 traceId。
 */
@RestController
@RequestMapping("/api/semantic/model")
public class ModelController {

    private final ModelService modelService;
    private final SemanticModelValidationService semanticModelValidationService;
    private final ModelHealthService modelHealthService;

    public ModelController(ModelService modelService,
            SemanticModelValidationService semanticModelValidationService,
            ModelHealthService modelHealthService) {
        this.modelService = modelService;
        this.semanticModelValidationService = semanticModelValidationService;
        this.modelHealthService = modelHealthService;
    }

    /**
     * 对模型 SQL 执行数据源与 SuperSonic 双重校验。
     *
     * @param validationReq SQL、数据源与可选完整模型定义。
     * @param request HTTP 请求，用于解析当前用户。
     * @param response HTTP 响应。
     * @return 各校验项独立结果及内容摘要。
     */
    @PostMapping("/validateSql")
    public SemanticValidationResult validateSql(
            @RequestBody SemanticModelValidationReq validationReq, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return semanticModelValidationService.validate(validationReq, user);
    }

    /**
     * 获取当前节点最近观测到的模型健康摘要。
     *
     * @param modelId 模型 ID。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 不包含 SQL 和凭证的健康快照。
     */
    @GetMapping("/{modelId}/health")
    public ModelHealthResp getHealth(@PathVariable("modelId") Long modelId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        requireModelAdmin(modelId, user);
        return modelHealthService.getHealth(modelId);
    }

    /**
     * 重新校验已保存模型，不修改模型内容。
     *
     * @param modelId 模型 ID。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 新的确定性校验报告。
     */
    @PostMapping("/{modelId}/revalidate")
    public SemanticValidationResult revalidate(@PathVariable("modelId") Long modelId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        ModelResp model = modelService.getModel(modelId);
        requireModelAdmin(modelId, user);
        return semanticModelValidationService.revalidate(model, user);
    }

    /** 校验用户是否能管理指定模型，禁止仅凭可猜测 ID 读取健康信息。 */
    private void requireModelAdmin(Long modelId, User user) {
        ModelResp model = modelService.getModel(modelId);
        boolean allowed =
                modelService.getModelListWithAuth(user, model.getDomainId(), AuthType.ADMIN)
                        .stream().anyMatch(item -> modelId.equals(item.getId()));
        if (!allowed) {
            throw new InvalidPermissionException("无模型管理权限");
        }
    }

    @PostMapping("/createModel")
    public Boolean createModel(@RequestBody ModelReq modelReq, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        modelService.createModel(modelReq, user);
        return true;
    }

    @PostMapping("/createModelBatch")
    public Boolean createModelBatch(@RequestBody ModelBuildReq modelBuildReq,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        modelService.createModel(modelBuildReq, user);
        return true;
    }

    @PostMapping("/updateModel")
    public Boolean updateModel(@RequestBody ModelReq modelReq, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        modelService.updateModel(modelReq, user);
        return true;
    }

    @DeleteMapping("/deleteModel/{modelId}")
    public Boolean deleteModel(@PathVariable("modelId") Long modelId, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        modelService.deleteModel(modelId, user);
        return true;
    }

    @GetMapping("/getModelList/{domainId}")
    public List<ModelResp> getModelList(@PathVariable("domainId") Long domainId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return modelService.getModelListWithAuth(user, domainId, AuthType.ADMIN);
    }

    @GetMapping("/getModel/{id}")
    public ModelResp getModel(@PathVariable("id") Long id) {
        return modelService.getModel(id);
    }

    @GetMapping("/getModelListByIds/{modelIds}")
    public List<ModelResp> getModelListByIds(@PathVariable("modelIds") String modelIds) {
        List<Long> ids = Arrays.stream(modelIds.split(",")).map(Long::parseLong)
                .collect(Collectors.toList());
        ModelFilter modelFilter = new ModelFilter();
        modelFilter.setIds(ids);
        return modelService.getModelList(modelFilter);
    }

    @GetMapping("/getAllModelByDomainId")
    public List<ModelResp> getAllModelByDomainId(@RequestParam("domainId") Long domainId) {
        return modelService.getAllModelByDomainIds(Lists.newArrayList(domainId));
    }

    @GetMapping("/getModelDatabase/{modelId}")
    public DatabaseResp getModelDatabase(@PathVariable("modelId") Long modelId) {
        return modelService.getDatabaseByModelId(modelId);
    }

    @PostMapping("/batchUpdateStatus")
    public Boolean batchUpdateStatus(@RequestBody MetaBatchReq metaBatchReq,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        modelService.batchUpdateStatus(metaBatchReq, user);
        return true;
    }

    @PostMapping("/getUnAvailableItem")
    public UnAvailableItemResp getUnAvailableItem(@RequestBody FieldRemovedReq fieldRemovedReq) {
        return modelService.getUnAvailableItem(fieldRemovedReq);
    }

    @PostMapping("/buildModelSchema")
    public Map<String, ModelSchema> buildModelSchema(@RequestBody ModelBuildReq modelBuildReq)
            throws SQLException {
        return modelService.buildModelSchema(modelBuildReq);
    }
}
