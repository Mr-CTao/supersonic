package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRouteAnalyzeReq;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRouteConfirmReq;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRouteResp;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRoutingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 语义资产路由认证 REST 接口。
 *
 * <p>职责：提供异步分析、详情轮询、澄清重分析和最终确认；不生成草稿、不写正式语义资产、不发布或
 * 执行 SQL。Controller 无共享可变状态，幂等、租约和乐观锁由应用服务及数据库保证。</p>
 */
@RestController
@RequestMapping("/api/semantic/modeling/asset-routes")
public class SemanticAssetRoutingController {

    private final SemanticAssetRoutingService routingService;

    /**
     * 创建路由 Controller。
     *
     * @param routingService 路由应用服务。
     */
    public SemanticAssetRoutingController(SemanticAssetRoutingService routingService) {
        this.routingService = routingService;
    }

    /**
     * 异步发起语义资产路由分析。
     *
     * @param idempotencyKey 必填幂等键。
     * @param request 分析范围。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 202 和可轮询的路由详情。
     */
    @PostMapping
    public ResponseEntity<SemanticAssetRouteResp> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SemanticAssetRouteAnalyzeReq request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return ResponseEntity.accepted().body(routingService.create(request, idempotencyKey, user));
    }

    /**
     * 查询路由分析详情。
     *
     * @param id 路由 ID。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 当前路由详情。
     */
    @GetMapping("/{id}")
    public SemanticAssetRouteResp get(@PathVariable("id") Long id,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return routingService.get(id, user);
    }

    /**
     * 确认、覆盖或提交业务答案重新分析。
     *
     * @param id 路由 ID。
     * @param idempotencyKey 必填确认幂等键。
     * @param request 确认请求。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 确认后或重新分析中的路由详情。
     */
    @PostMapping("/{id}/confirm")
    public SemanticAssetRouteResp confirm(@PathVariable("id") Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SemanticAssetRouteConfirmReq request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return routingService.confirm(id, request, idempotencyKey, user);
    }
}
