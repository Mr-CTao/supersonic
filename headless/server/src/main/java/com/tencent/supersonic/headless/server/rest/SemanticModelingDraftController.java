package com.tencent.supersonic.headless.server.rest;

import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftAttemptResp;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftGenerateReq;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftQueryReq;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftRegenerateReq;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftResp;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftSaveReq;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftService;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftVersionResp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 语义建模草稿 REST 接口。
 *
 * <p>
 * 职责说明：提供异步创建、失败草稿重新生成、attempt 历史、分页、详情、乐观锁保存和版本只读
 * 查询。接口不会暴露原始模型输出或样例，也不提供验证、审批、发布入口。并发说明：Controller 无共享可变状态；幂等、Gap 去重和乐观锁由应用服务与数据库保证。
 * </p>
 */
@RestController
@RequestMapping("/api/semantic/modeling/drafts")
public class SemanticModelingDraftController {

    private final ModelingDraftService modelingDraftService;

    /**
     * 创建草稿 Controller。
     *
     * @param modelingDraftService 草稿应用服务。
     */
    public SemanticModelingDraftController(ModelingDraftService modelingDraftService) {
        this.modelingDraftService = modelingDraftService;
    }

    /**
     * 异步创建草稿并返回 202。
     *
     * @param idempotencyKey 必填幂等键。
     * @param request 创建请求。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return GENERATING 草稿摘要或幂等重放结果。
     */
    @PostMapping
    public ResponseEntity<ModelingDraftResp> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ModelingDraftGenerateReq request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return ResponseEntity.accepted()
                .body(modelingDraftService.create(request, idempotencyKey, user));
    }

    /**
     * 分页查询当前用户可访问的草稿。
     *
     * @param request 筛选条件。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 草稿分页。
     */
    @GetMapping
    public PageInfo<ModelingDraftResp> query(@Valid @ModelAttribute ModelingDraftQueryReq request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.query(request, user);
    }

    /**
     * 查询草稿详情。
     *
     * @param id 草稿 ID。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 当前结构化草稿。
     */
    @GetMapping("/{id}")
    public ModelingDraftResp get(@PathVariable("id") Long id, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.get(id, user);
    }

    /**
     * 使用乐观锁保存完整草稿并生成下一版本。
     *
     * @param id 草稿 ID。
     * @param request 保存请求。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 保存后的草稿。
     */
    @PutMapping("/{id}")
    public ModelingDraftResp save(@PathVariable("id") Long id,
            @Valid @RequestBody ModelingDraftSaveReq request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.save(id, request, user);
    }

    /**
     * 对生成失败且尚未产生版本的草稿发起人工重新生成。
     *
     * @param id 草稿 ID。
     * @param idempotencyKey 必填幂等键。
     * @param request 仅包含锁版本、模型和样例开关的请求。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 切换为 GENERATING 的同一逻辑草稿。
     */
    @PostMapping("/{id}/regenerations")
    public ResponseEntity<ModelingDraftResp> regenerate(@PathVariable("id") Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ModelingDraftRegenerateReq request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return ResponseEntity.accepted()
                .body(modelingDraftService.regenerate(id, request, idempotencyKey, user));
    }

    /**
     * 分页查询版本摘要，不加载大 JSON 快照。
     *
     * @param id 草稿 ID。
     * @param page 页码。
     * @param pageSize 页大小。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 版本摘要分页。
     */
    @GetMapping("/{id}/versions")
    public PageInfo<ModelingDraftVersionResp> queryVersions(@PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.queryVersions(id, page, pageSize, user);
    }

    /**
     * 按需加载单个不可变版本快照。
     *
     * @param id 草稿 ID。
     * @param versionNo 版本号。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return 版本 JSON 快照。
     */
    @GetMapping("/{id}/versions/{versionNo}")
    public ModelingDraftVersionResp getVersion(@PathVariable("id") Long id,
            @PathVariable("versionNo") Integer versionNo, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.getVersion(id, versionNo, user);
    }

    /**
     * 分页查询生成尝试摘要。
     *
     * @param id 草稿 ID。
     * @param page 页码。
     * @param pageSize 页大小。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return attempt 倒序分页。
     */
    @GetMapping("/{id}/attempts")
    public PageInfo<ModelingDraftAttemptResp> queryAttempts(@PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.queryAttempts(id, page, pageSize, user);
    }

    /**
     * 查询单次生成尝试安全详情。
     *
     * @param id 草稿 ID。
     * @param attemptNo 尝试序号。
     * @param servletRequest HTTP 请求。
     * @param servletResponse HTTP 响应。
     * @return attempt 安全详情。
     */
    @GetMapping("/{id}/attempts/{attemptNo}")
    public ModelingDraftAttemptResp getAttempt(@PathVariable("id") Long id,
            @PathVariable("attemptNo") Integer attemptNo, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = UserHolder.findUser(servletRequest, servletResponse);
        return modelingDraftService.getAttempt(id, attemptNo, user);
    }
}
