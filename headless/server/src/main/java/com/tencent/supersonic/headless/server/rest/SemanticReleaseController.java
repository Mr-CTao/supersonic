package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticApprovalDecisionReq;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticApprovalResp;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseQueryReq;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseResp;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticReleaseService;
import com.tencent.supersonic.headless.server.semantic.modeling.release.SemanticRollbackReq;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 语义建模阶段 5 审批、发布、知识刷新与回滚接口。
 *
 * <p>
 * 职责说明：解析当前登录用户、执行请求校验并委托 {@link SemanticReleaseService}。所有写接口
 * 由服务层再次校验系统管理员权限；发布请求必须携带幂等键，回滚范围不能由客户端传对象 ID。
 * </p>
 */
@Validated
@RestController
@RequestMapping("/api/semantic/modeling")
public class SemanticReleaseController {

    private final SemanticReleaseService semanticReleaseService;

    /**
     * 创建阶段 5 控制器。
     *
     * @param semanticReleaseService 审批发布编排服务。
     */
    public SemanticReleaseController(SemanticReleaseService semanticReleaseService) {
        this.semanticReleaseService = semanticReleaseService;
    }

    /**
     * 分页查询待审批与已做决定的草稿。
     *
     * @param query 查询条件。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 审批摘要分页。
     */
    @GetMapping("/approvals")
    public PageInfo<SemanticApprovalResp> queryApprovals(@Valid SemanticReleaseQueryReq query,
            HttpServletRequest request, HttpServletResponse response) {
        return semanticReleaseService.queryApprovals(query, user(request, response));
    }

    /**
     * 审批通过待审批草稿。
     *
     * @param draftId 草稿 ID。
     * @param body 可选审批备注。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return true 表示审批完成。
     */
    @PostMapping("/drafts/{draftId}/approve")
    public boolean approve(@PathVariable Long draftId,
            @Valid @RequestBody(required = false) SemanticApprovalDecisionReq body,
            HttpServletRequest request, HttpServletResponse response) {
        return semanticReleaseService.approve(draftId, body, user(request, response));
    }

    /**
     * 拒绝待审批草稿。
     *
     * @param draftId 草稿 ID。
     * @param body 拒绝原因。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return true 表示拒绝完成。
     */
    @PostMapping("/drafts/{draftId}/reject")
    public boolean reject(@PathVariable Long draftId,
            @Valid @RequestBody SemanticApprovalDecisionReq body, HttpServletRequest request,
            HttpServletResponse response) {
        return semanticReleaseService.reject(draftId, body, user(request, response));
    }

    /**
     * 发布审批通过的 AI 新增语义对象。
     *
     * @param draftId 草稿 ID。
     * @param idempotencyKey 客户端会话内稳定幂等键。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 完整发布状态。
     */
    @PostMapping("/drafts/{draftId}/release")
    public SemanticReleaseResp release(@PathVariable Long draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request,
            HttpServletResponse response) {
        return semanticReleaseService.release(draftId, idempotencyKey, user(request, response));
    }

    /**
     * 分页查询发布审计记录。
     *
     * @param query 查询条件。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 发布记录分页。
     */
    @GetMapping("/releases")
    public PageInfo<SemanticReleaseResp> queryReleases(@Valid SemanticReleaseQueryReq query,
            HttpServletRequest request, HttpServletResponse response) {
        return semanticReleaseService.queryReleases(query, user(request, response));
    }

    /**
     * 查询发布对象、刷新状态与全部步骤。
     *
     * @param releaseId 发布 ID。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 发布详情。
     */
    @GetMapping("/releases/{releaseId}")
    public SemanticReleaseResp getRelease(@PathVariable Long releaseId, HttpServletRequest request,
            HttpServletResponse response) {
        return semanticReleaseService.getRelease(releaseId, user(request, response));
    }

    /**
     * 只重试失败的 dict/embedding 刷新步骤。
     *
     * @param releaseId 发布 ID。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 重试后的发布详情。
     */
    @PostMapping("/releases/{releaseId}/knowledge/retry")
    public SemanticReleaseResp retryKnowledge(@PathVariable Long releaseId,
            HttpServletRequest request, HttpServletResponse response) {
        return semanticReleaseService.retryKnowledge(releaseId, user(request, response));
    }

    /**
     * 回滚发布步骤登记的 AI 新增对象。
     *
     * @param releaseId 发布 ID。
     * @param body 回滚原因；对象范围完全由服务端发布步骤决定。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 回滚后的发布详情。
     */
    @PostMapping("/releases/{releaseId}/rollback")
    public SemanticReleaseResp rollback(@PathVariable Long releaseId,
            @Valid @RequestBody SemanticRollbackReq body, HttpServletRequest request,
            HttpServletResponse response) {
        return semanticReleaseService.rollback(releaseId, body, user(request, response));
    }

    /** 从统一认证上下文读取当前用户。 */
    private User user(HttpServletRequest request, HttpServletResponse response) {
        return UserHolder.findUser(request, response);
    }
}
