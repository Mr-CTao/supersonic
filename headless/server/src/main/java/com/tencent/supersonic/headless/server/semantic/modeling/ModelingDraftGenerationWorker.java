package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.llm.LlmConstants;
import com.tencent.supersonic.common.llm.LlmConversationCreateReq;
import com.tencent.supersonic.common.llm.LlmConversationGatewayService;
import com.tencent.supersonic.common.llm.LlmConversationResp;
import com.tencent.supersonic.common.llm.LlmMessageCreateReq;
import com.tencent.supersonic.common.llm.LlmMessageCreateResp;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.semantic.routing.SemanticAssetRoutingService;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.GenerationContext;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftContextBuilder.PreflightSnapshot;
import com.tencent.supersonic.headless.server.semantic.modeling.ModelingDraftValidator.ValidatedDraft;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 语义建模草稿异步生成 Worker。
 *
 * <p>
 * 职责说明：条件认领草稿后构建脱敏上下文，通过阶段 1 Gateway 在固定 JSON Schema 下生成；首次结构 或语义校验失败时在同一会话修复一次，最后使用短事务保存版本 1。LLM
 * 网络调用绝不位于数据库事务中。 并发说明：多 Worker 依赖数据库条件认领，只有一个实例能处理同一草稿。
 * </p>
 */
@Slf4j
@Component
public class ModelingDraftGenerationWorker {

    private final ModelingDraftStore store;
    private final ModelingDraftContextBuilder contextBuilder;
    private final ModelingDraftValidator validator;
    private final ModelingDraftRouteGuard routeGuard;
    private final SemanticAssetRoutingService routingService;
    private final LlmConversationGatewayService gatewayService;
    private final SemanticModelingProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建生成 Worker。
     *
     * @param store 草稿短事务服务。
     * @param contextBuilder 安全上下文构建器。
     * @param validator 草稿校验器。
     * @param routeGuard 路由快照不可漂移校验器。
     * @param routingService 路由权限、版本和消费绑定校验服务。
     * @param gatewayService 阶段 1 LLM Gateway。
     * @param properties 阶段 3 配置。
     * @param objectMapper JSON 映射器。
     */
    public ModelingDraftGenerationWorker(ModelingDraftStore store,
            ModelingDraftContextBuilder contextBuilder, ModelingDraftValidator validator,
            ModelingDraftRouteGuard routeGuard, SemanticAssetRoutingService routingService,
            LlmConversationGatewayService gatewayService, SemanticModelingProperties properties,
            ObjectMapper objectMapper) {
        this.store = store;
        this.contextBuilder = contextBuilder;
        this.validator = validator;
        this.routeGuard = routeGuard;
        this.routingService = routingService;
        this.gatewayService = gatewayService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成一个已持久化的草稿。
     *
     * <p>
     * 调用示例：由专用执行器执行 {@code worker.generate(draftId, attemptNo, snapshot, user)}。方法不会把 Prompt、
     * 样例或模型原文写入日志；原文仅保存到主表后端诊断字段。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param attemptNo 当前生成尝试序号。
     * @param snapshot 创建前完成的可信元数据快照。
     * @param user 创建用户，用于 Gateway 与采样 ACL。
     */
    public void generate(Long draftId, Integer attemptNo, PreflightSnapshot snapshot, User user) {
        if (!store.claimGeneration(draftId, attemptNo, user.getName())) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        // generationTimeoutSeconds 是一次 attempt 的总预算；两轮请求共享 deadline，避免修复轮越过超时恢复边界。
        long deadlineAt = calculateDeadline(startedAt);
        String rawOutput = null;
        String repairedOutput = null;
        String generateRequestId = null;
        String repairRequestId = null;
        Long conversationId = null;
        String failureStage = ModelingDraftConstants.FAILURE_STAGE_CONTEXT;
        String result = "FAILED";
        try {
            if (snapshot.request().getRouteAnalysisId() != null) {
                // Worker 认领后、调用慢速 Provider 前再检查一次，阻止已漂移目标进入 LLM 上下文。
                routingService.requireBoundRoute(snapshot.request().getRouteAnalysisId(), draftId,
                        user);
            }
            GenerationContext context = contextBuilder.build(snapshot, user);
            LlmConversationResp conversation = createConversation(draftId, snapshot, context, user);
            conversationId = conversation.getConversationId();
            store.updateConversationId(draftId, attemptNo, conversationId);

            failureStage = ModelingDraftConstants.FAILURE_STAGE_GENERATE;
            LlmMessageCreateResp firstResponse =
                    gatewayService.appendMessageAndChatWithoutTransaction(conversationId,
                            createMessage(context.userPrompt(), context.jsonSchema(), draftId,
                                    attemptNo, "generate", remainingTimeout(deadlineAt,
                                            ModelingDraftConstants.FAILURE_STAGE_GENERATE)));
            generateRequestId = extractRequestId(firstResponse);
            store.updateProviderRequestId(draftId, attemptNo, "generate", generateRequestId);
            assertProviderAvailable(firstResponse, ModelingDraftConstants.FAILURE_STAGE_GENERATE);
            rawOutput = extractRawOutput(firstResponse);
            String firstCandidate = extractCandidate(firstResponse);

            ValidatedDraft validated;
            try {
                failureStage = ModelingDraftConstants.FAILURE_STAGE_VALIDATE;
                validated = validator.validateAndNormalize(firstCandidate, context.columnsByTable(),
                        context.existingNames());
                routeGuard.validateGenerated(validated.payload(), snapshot.request());
            } catch (ModelingDraftException firstValidationError) {
                if (properties.getRepairAttempts() < 1) {
                    throw new GenerationFailure(ModelingDraftConstants.ERROR_OUTPUT_INVALID,
                            ModelingDraftConstants.FAILURE_STAGE_VALIDATE, "模型输出未通过结构化草稿校验",
                            firstValidationError.getIssues(), firstValidationError);
                }
                String repairPrompt = buildRepairPrompt(firstValidationError, context);
                failureStage = ModelingDraftConstants.FAILURE_STAGE_REPAIR;
                LlmMessageCreateResp repairResponse =
                        gatewayService.appendMessageAndChatWithoutTransaction(conversationId,
                                createMessage(repairPrompt, context.jsonSchema(), draftId,
                                        attemptNo, "repair", remainingTimeout(deadlineAt,
                                                ModelingDraftConstants.FAILURE_STAGE_REPAIR)));
                repairRequestId = extractRequestId(repairResponse);
                store.updateProviderRequestId(draftId, attemptNo, "repair", repairRequestId);
                assertProviderAvailable(repairResponse,
                        ModelingDraftConstants.FAILURE_STAGE_REPAIR);
                repairedOutput = extractRawOutput(repairResponse);
                String repairCandidate = extractCandidate(repairResponse);
                try {
                    validated = validator.validateAndNormalize(repairCandidate,
                            context.columnsByTable(), context.existingNames());
                    routeGuard.validateGenerated(validated.payload(), snapshot.request());
                } catch (ModelingDraftException repairValidationError) {
                    throw new GenerationFailure(ModelingDraftConstants.ERROR_OUTPUT_INVALID,
                            ModelingDraftConstants.FAILURE_STAGE_REPAIR, "模型输出修复后仍未通过结构化草稿校验",
                            repairValidationError.getIssues(), repairValidationError);
                }
            }

            boolean completed = store.completeGeneration(draftId, attemptNo, validated.json(),
                    rawOutput, repairedOutput, conversationId, generateRequestId, repairRequestId,
                    user);
            result = completed ? "SUCCESS" : "IGNORED";
        } catch (ModelingDraftException modelingFailure) {
            // build() 的上下文上限属于管理员可操作的业务失败，不能误报为 Provider 故障。
            String errorCode = ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE.equals(
                    modelingFailure.getErrorCode()) ? ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE
                            : ModelingDraftConstants.ERROR_OUTPUT_INVALID;
            String errorMessage = ModelingDraftConstants.ERROR_CONTEXT_TOO_LARGE.equals(errorCode)
                    ? modelingFailure.getMessage()
                    : "草稿上下文或结构未通过安全校验";
            store.failGeneration(draftId, attemptNo, errorCode, errorMessage, rawOutput,
                    repairedOutput, failureStage, modelingFailure.getIssues(), generateRequestId,
                    repairRequestId, user.getName());
            log.warn(
                    "semantic modeling draft generation failed: draftId={}, operator={}, "
                            + "source={}, tableCount={}, chatModelId={}, durationMs={}, result={}, "
                            + "errorCode={}",
                    draftId, user.getName(), snapshot.request().getSourceType(),
                    snapshot.request().getSelectedTables().size(),
                    snapshot.request().getChatModelId(), System.currentTimeMillis() - startedAt,
                    result, errorCode);
            return;
        } catch (GenerationFailure failure) {
            store.failGeneration(draftId, attemptNo, failure.errorCode(), failure.getMessage(),
                    rawOutput, repairedOutput, failure.failureStage(), failure.issues(),
                    generateRequestId, repairRequestId, user.getName());
            log.warn(
                    "semantic modeling draft generation failed: draftId={}, operator={}, "
                            + "source={}, tableCount={}, chatModelId={}, durationMs={}, result={}, "
                            + "errorCode={}",
                    draftId, user.getName(), snapshot.request().getSourceType(),
                    snapshot.request().getSelectedTables().size(),
                    snapshot.request().getChatModelId(), System.currentTimeMillis() - startedAt,
                    result, failure.errorCode());
            return;
        } catch (Exception exception) {
            store.failGeneration(draftId, attemptNo, ModelingDraftConstants.ERROR_PROVIDER,
                    "草稿生成失败，请稍后重试", rawOutput, repairedOutput, failureStage, List.of(),
                    generateRequestId, repairRequestId, user.getName());
            // 记录系统异常栈以便运维排查，但消息中没有 Prompt、样例或模型输出。
            log.error(
                    "semantic modeling draft generation error: draftId={}, operator={}, "
                            + "source={}, tableCount={}, chatModelId={}, durationMs={}, result={}",
                    draftId, user.getName(), snapshot.request().getSourceType(),
                    snapshot.request().getSelectedTables().size(),
                    snapshot.request().getChatModelId(), System.currentTimeMillis() - startedAt,
                    result, exception);
            return;
        }

        log.info(
                "semantic modeling draft generation completed: draftId={}, operator={}, source={}, "
                        + "tableCount={}, chatModelId={}, durationMs={}, result={}",
                draftId, user.getName(), snapshot.request().getSourceType(),
                snapshot.request().getSelectedTables().size(), snapshot.request().getChatModelId(),
                System.currentTimeMillis() - startedAt, result);
    }

    /** 创建 SEMANTIC_MODELING 会话并关联草稿 ID。 */
    private LlmConversationResp createConversation(Long draftId, PreflightSnapshot snapshot,
            GenerationContext context, User user) {
        LlmConversationCreateReq request = new LlmConversationCreateReq();
        request.setConversationType(ModelingDraftConstants.CONVERSATION_TYPE);
        request.setChatModelId(snapshot.request().getChatModelId());
        request.setBusinessId(String.valueOf(draftId));
        request.setSystemPrompt(context.systemPrompt());
        return gatewayService.createConversation(request, user);
    }

    /** 构造固定 JSON 输出消息请求。 */
    private LlmMessageCreateReq createMessage(String content,
            com.fasterxml.jackson.databind.JsonNode schema, Long draftId, Integer attemptNo,
            String stage, long timeoutMs) {
        LlmMessageCreateReq request = new LlmMessageCreateReq();
        request.setContent(content);
        request.setResponseFormat(LlmConstants.FORMAT_JSON);
        request.setJsonSchema(schema);
        request.setTemperature(0.1D);
        request.setMaxTokens(properties.getMaxOutputTokens());
        request.setTimeoutMs(timeoutMs);
        request.setStream(false);
        request.setRequireToolCalling(false);
        request.setIdempotencyKey("semantic-modeling-" + draftId + "-" + attemptNo + "-" + stage);
        return request;
    }

    /** 使用饱和加法计算 attempt 总截止时间，避免异常配置造成 long 溢出。 */
    private long calculateDeadline(long startedAt) {
        try {
            return Math.addExact(startedAt, properties.resolveGenerationTimeoutMillis());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /** 返回当前阶段可用的剩余总预算；预算耗尽时禁止再发起 Provider 请求。 */
    private long remainingTimeout(long deadlineAt, String failureStage) {
        long remaining = deadlineAt - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new GenerationFailure(ModelingDraftConstants.ERROR_GENERATION_TIMEOUT,
                    failureStage, "草稿生成已超过总超时限制，请稍后重新生成", List.of());
        }
        return remaining;
    }

    /** 将 Provider 失败与 JSON 可修复失败区分开。 */
    private void assertProviderAvailable(LlmMessageCreateResp response, String failureStage) {
        if (response == null) {
            throw new GenerationFailure(ModelingDraftConstants.ERROR_PROVIDER, failureStage,
                    "LLM Provider 未返回响应", List.of());
        }
        if (StringUtils.isBlank(response.getErrorCode())
                || LlmConstants.ERROR_JSON_PARSE_FAILED.equals(response.getErrorCode())) {
            return;
        }
        String code = LlmConstants.ERROR_TIMEOUT.equals(response.getErrorCode())
                ? ModelingDraftConstants.ERROR_GENERATION_TIMEOUT
                : ModelingDraftConstants.ERROR_PROVIDER;
        throw new GenerationFailure(code, failureStage, "LLM Provider 调用失败，请稍后重试", List.of());
    }

    /** 优先使用 Gateway 已解析 JSON，否则使用保留的原始 assistant 正文。 */
    private String extractCandidate(LlmMessageCreateResp response) {
        if (response.getParsedJson() != null) {
            return response.getParsedJson().toString();
        }
        return StringUtils.defaultIfBlank(response.getInternalAssistantContent(),
                response.getAssistantContent());
    }

    /** 保存真实 assistant 正文；极少数 Adapter 未返回正文时回退解析后的 JSON。 */
    private String extractRawOutput(LlmMessageCreateResp response) {
        String internalContent = StringUtils.defaultIfBlank(response.getInternalAssistantContent(),
                response.getAssistantContent());
        return StringUtils.isNotBlank(internalContent) ? internalContent
                : response.getParsedJson() == null ? null : response.getParsedJson().toString();
    }

    /** 优先保存 Gateway 调用日志 requestId，旧 Adapter 仅有 Provider ID 时兼容回退。 */
    private String extractRequestId(LlmMessageCreateResp response) {
        if (response == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(response.getRequestId(), response.getProviderRequestId());
    }

    /** 将完整契约和全部安全校验问题反馈给同一会话，要求重建而不是局部打补丁。 */
    private String buildRepairPrompt(ModelingDraftException validationError,
            GenerationContext context) {
        try {
            List<ModelingValidationIssue> issues = validationError.getIssues().stream()
                    .limit(ModelingDraftConstants.MAX_VALIDATION_ISSUES).toList();
            return "上一条 assistant 输出未通过草稿校验。不要局部补字段，必须从顶层开始重建完整 JSON；" + "不要解释，不要改变可信表字段边界。\n\n"
                    + context.outputContract() + "\n\n需要一次性修复的校验问题：\n"
                    + objectMapper.writeValueAsString(issues);
        } catch (JsonProcessingException exception) {
            throw new GenerationFailure(ModelingDraftConstants.ERROR_OUTPUT_INVALID,
                    ModelingDraftConstants.FAILURE_STAGE_REPAIR, "无法构建模型修复请求", List.of(),
                    exception);
        }
    }

    /** Worker 内部的脱敏失败类型。 */
    private static final class GenerationFailure extends RuntimeException {
        private final String errorCode;
        private final String failureStage;
        private final List<ModelingValidationIssue> issues;

        private GenerationFailure(String errorCode, String failureStage, String message,
                List<ModelingValidationIssue> issues) {
            super(message);
            this.errorCode = errorCode;
            this.failureStage = failureStage;
            this.issues = issues == null ? List.of() : List.copyOf(issues);
        }

        private GenerationFailure(String errorCode, String failureStage, String message,
                List<ModelingValidationIssue> issues, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
            this.failureStage = failureStage;
            this.issues = issues == null ? List.of() : List.copyOf(issues);
        }

        private String errorCode() {
            return errorCode;
        }

        private String failureStage() {
            return failureStage;
        }

        private List<ModelingValidationIssue> issues() {
            return issues;
        }
    }
}
