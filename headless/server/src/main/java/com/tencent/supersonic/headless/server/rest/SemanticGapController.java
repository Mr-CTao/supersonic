package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapActionReq;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapDraftResp;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapQueryReq;
import com.tencent.supersonic.headless.server.semantic.gap.SemanticGapService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语义缺口池管理接口。
 *
 * <p>职责说明：为阶段 2 管理页面提供缺口列表、详情、忽略、重新打开和“发起草稿”占位接口。本 Controller 不调用 LLM Gateway，
 * 不生成 AI 草稿，不发布正式语义资产。并发说明：Controller 不保存可变状态；同类缺口采集和状态一致性由
 * {@link SemanticGapService} 负责。</p>
 */
@RestController
@RequestMapping({"/api/semantic/gaps", "/openapi/semantic/gaps"})
public class SemanticGapController {

    private final SemanticGapService semanticGapService;

    /**
     * 创建语义缺口 Controller。
     *
     * @param semanticGapService 语义缺口服务。
     */
    public SemanticGapController(SemanticGapService semanticGapService) {
        this.semanticGapService = semanticGapService;
    }

    /**
     * 分页查询语义缺口列表。
     *
     * @param queryReq 筛选条件。
     * @return 分页缺口列表。
     */
    @GetMapping
    public PageInfo<SemanticGapDO> query(@ModelAttribute SemanticGapQueryReq queryReq) {
        return semanticGapService.query(queryReq);
    }

    /**
     * 查询缺口详情。
     *
     * @param id 缺口 ID。
     * @return 缺口详情。
     */
    @GetMapping("/{id}")
    public SemanticGapDO get(@PathVariable("id") Long id) {
        return semanticGapService.get(id);
    }

    /**
     * 忽略语义缺口。
     *
     * @param id 缺口 ID。
     * @param req 忽略原因。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 更新后的缺口。
     */
    @PostMapping("/{id}/ignore")
    public SemanticGapDO ignore(@PathVariable("id") Long id, @RequestBody SemanticGapActionReq req,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return semanticGapService.ignore(id, req, user.getName());
    }

    /**
     * 重新打开语义缺口。
     *
     * @param id 缺口 ID。
     * @param request HTTP 请求。
     * @param response HTTP 响应。
     * @return 更新后的缺口。
     */
    @PostMapping("/{id}/reopen")
    public SemanticGapDO reopen(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return semanticGapService.reopen(id, user.getName());
    }

    /**
     * 阶段 2 AI 草稿占位入口。
     *
     * @param id 缺口 ID。
     * @return 未启用提示。
     */
    @PostMapping("/{id}/drafts")
    public SemanticGapDraftResp createDraft(@PathVariable("id") Long id) {
        return semanticGapService.createDraftPlaceholder(id);
    }
}
