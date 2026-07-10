package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftConstants;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftGenerateReq;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftResp;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语义缺口到阶段 3 建模草稿的认证 API 适配器。
 *
 * <p>
 * 职责说明：保留阶段 2 约定的 {@code /api/semantic/gaps/{id}/drafts} URL，并把请求委托给统一草稿服务。 该 Controller 故意不声明
 * OpenAPI 别名，防止匿名请求绕过数据源、模型和草稿权限边界。并发说明：Controller 无共享可变状态；幂等与同一缺口活动草稿去重由应用服务和数据库锁共同保证。
 * </p>
 */
@RestController
@RequestMapping("/api/semantic/gaps")
public class SemanticGapModelingDraftController {

    private final ModelingDraftService modelingDraftService;

    /**
     * 创建缺口草稿适配 Controller。
     *
     * @param modelingDraftService 阶段 3 草稿应用服务。
     */
    public SemanticGapModelingDraftController(ModelingDraftService modelingDraftService) {
        this.modelingDraftService = modelingDraftService;
    }

    /**
     * 从语义缺口异步发起 AI 草稿。
     *
     * <p>
     * 调用示例：请求体只需携带业务目标、数据源、选表和模型；来源类型与缺口 ID 由服务端路径补齐， 避免客户端同时维护两份来源信息。
     * </p>
     *
     * @param id 缺口 ID。
     * @param idempotencyKey 必填幂等键。
     * @param request 创建请求；数据源、选表和模型为必填补充信息。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 202 和 GENERATING 草稿。
     */
    @PostMapping("/{id}/drafts")
    public ResponseEntity<ModelingDraftResp> create(@PathVariable("id") Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ModelingDraftGenerateReq request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        // 来源完全由认证路径决定，覆盖客户端同名字段可阻止路径与请求体的 Gap ID 混淆。
        request.setSourceType(ModelingDraftConstants.SOURCE_SEMANTIC_GAP);
        request.setSourceId(id);
        return ResponseEntity.accepted()
                .body(modelingDraftService.create(request, idempotencyKey, user));
    }
}
