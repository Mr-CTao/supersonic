package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.llm.LlmConversationCreateReq;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmConversationResp;
import com.tencent.supersonic.common.llm.LlmInvocationLogQueryReq;
import com.tencent.supersonic.common.llm.LlmInvocationLogResp;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import com.tencent.supersonic.common.persistence.dataobject.LlmModelCapabilityDO;
import com.tencent.supersonic.common.pojo.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM Conversation Gateway 最小调试接口。
 *
 * <p>职责说明：提供阶段 1 验证所需的创建会话、追加消息调用模型、查询会话详情和查询模型能力接口。该 Controller 只调用
 * Gateway 基础能力，不创建语义缺口、不生成 AI 草稿、不发布语义资产。</p>
 *
 * <p>并发说明：Controller 本身不保存请求状态；同会话追加顺序由 {@link LlmConversationGatewayService} 内部按 conversationId
 * 互斥保护。</p>
 */
@RestController
@RequestMapping({"/api/llm", "/openapi/llm"})
public class LlmConversationGatewayController {

    private final LlmConversationGatewayService gatewayService;

    /**
     * 创建 Controller。
     *
     * @param gatewayService LLM Conversation Gateway 服务。
     */
    public LlmConversationGatewayController(LlmConversationGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * 创建 LLM 会话。
     *
     * @param req 创建会话请求。
     * @param httpServletRequest HTTP 请求。
     * @param httpServletResponse HTTP 响应。
     * @return 会话摘要。
     */
    @PostMapping("/conversations")
    public LlmConversationResp createConversation(@RequestBody LlmConversationCreateReq req,
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        User user = UserHolder.findUser(httpServletRequest, httpServletResponse);
        return gatewayService.createConversation(req, user);
    }

    /**
     * 追加用户消息并调用模型。
     *
     * @param conversationId 会话 ID。
     * @param req 用户消息和调用参数。
     * @return assistant 响应或统一错误。
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public LlmMessageCreateResp appendMessageAndChat(@PathVariable("conversationId") Long conversationId,
            @RequestBody LlmMessageCreateReq req) {
        return gatewayService.appendMessageAndChat(conversationId, req);
    }

    /**
     * 查询会话详情。
     *
     * @param conversationId 会话 ID。
     * @return 会话详情和本地消息列表。
     */
    @GetMapping("/conversations/{conversationId}")
    public LlmConversationResp getConversation(@PathVariable("conversationId") Long conversationId) {
        return gatewayService.getConversation(conversationId);
    }

    /**
     * 查询当前用户可见模型能力。
     *
     * @param httpServletRequest HTTP 请求。
     * @param httpServletResponse HTTP 响应。
     * @return 模型能力列表。
     */
    @GetMapping("/models/capabilities")
    public List<LlmModelCapabilityDO> listCapabilities(HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        User user = UserHolder.findUser(httpServletRequest, httpServletResponse);
        return gatewayService.listCapabilities(user);
    }

    /**
     * 保存模型能力配置。
     *
     * @param capability 前端编辑后的模型能力。
     * @return 保存后的模型能力。
     */
    @PutMapping("/models/capabilities")
    public LlmModelCapabilityDO saveCapability(@RequestBody LlmModelCapabilityDO capability) {
        return gatewayService.saveCapability(capability);
    }

    /**
     * 查询调用日志列表。
     *
     * @param req 筛选条件。
     * @return 脱敏调用日志列表。
     */
    @PostMapping("/invocation-logs/search")
    public List<LlmInvocationLogResp> listInvocationLogs(@RequestBody LlmInvocationLogQueryReq req) {
        return gatewayService.listInvocationLogs(req);
    }

    /**
     * 查询调用日志详情。
     *
     * @param id 调用日志 ID。
     * @return 脱敏调用日志详情。
     */
    @GetMapping("/invocation-logs/{id}")
    public LlmInvocationLogResp getInvocationLog(@PathVariable("id") Long id) {
        return gatewayService.getInvocationLog(id);
    }
}
