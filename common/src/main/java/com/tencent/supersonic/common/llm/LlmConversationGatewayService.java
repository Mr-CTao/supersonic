package com.tencent.supersonic.common.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.supersonic.common.config.ChatModel;
import com.tencent.supersonic.common.persistence.dataobject.LlmConversationDO;
import com.tencent.supersonic.common.persistence.dataobject.LlmInvocationLogDO;
import com.tencent.supersonic.common.persistence.dataobject.LlmMessageDO;
import com.tencent.supersonic.common.persistence.dataobject.LlmModelCapabilityDO;
import com.tencent.supersonic.common.persistence.mapper.LlmConversationMapper;
import com.tencent.supersonic.common.persistence.mapper.LlmInvocationLogMapper;
import com.tencent.supersonic.common.persistence.mapper.LlmMessageMapper;
import com.tencent.supersonic.common.persistence.mapper.LlmModelCapabilityMapper;
import com.tencent.supersonic.common.pojo.ChatModelConfig;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.service.ChatModelService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LLM Conversation Gateway 核心服务。
 *
 * <p>
 * 职责说明：复用现有 `s2_chat_model` 配置创建本地会话，按 `llm_message` 顺序拼接完整 messages，选择 Provider Adapter
 * 发起非流式调用，并持久化通用调用日志。DeepSeek 是无状态 API，因此多轮上下文只能由本服务读取本地消息表后完整拼接， Adapter 不允许重新查询业务历史。
 * `SEMANTIC_MODELING` 会话含有表结构、脱敏样例和模型原文，其 assistant 输出不写入通用消息表，也不通过会话/日志 API
 * 返回；原文只通过 Jackson 忽略的内部字段交给建模 attempt 链路保存。
 * </p>
 *
 * <p>
 * 并发说明：同一会话追加消息需要保持 `message_order` 单调递增。当前阶段使用固定数量的公平条带锁按 conversationId 做 JVM 内互斥，
 * 避免按会话永久缓存锁对象造成内存增长，并依赖数据库唯一键 `(conversation_id, message_order)` 阻断异常重复。哈希碰撞只会让不同会话短暂串行，
 * 不影响正确性；多实例部署时仍建议后续升级为数据库乐观锁或 `SELECT ... FOR UPDATE`。
 * </p>
 */
@Slf4j
@Service
public class LlmConversationGatewayService {

    private static final String DEFAULT_CONVERSATION_TYPE = "LLM_DEBUG";
    private static final String SEMANTIC_MODELING_CONVERSATION_TYPE = "SEMANTIC_MODELING";
    private static final String DEFAULT_USAGE_SCENE = "semantic_modeling";
    private static final int DEFAULT_CONTEXT_TOKENS = 128000;
    private static final int KIMI_K3_CONTEXT_TOKENS = 1_000_000;
    private static final int MESSAGE_SUMMARY_LIMIT = 800;
    private static final int CONVERSATION_LOCK_STRIPE_COUNT = 64;

    private final ChatModelService chatModelService;
    private final LlmProviderAdapterRegistry adapterRegistry;
    private final LlmModelCapabilityMapper capabilityMapper;
    private final LlmConversationMapper conversationMapper;
    private final LlmMessageMapper messageMapper;
    private final LlmInvocationLogMapper invocationLogMapper;
    private final ReentrantLock[] conversationLocks = createConversationLocks();

    /**
     * 创建 Gateway 服务。
     *
     * @param chatModelService 现有模型配置服务。
     * @param adapterRegistry Provider Adapter 注册表。
     * @param capabilityMapper 模型能力 Mapper。
     * @param conversationMapper 会话 Mapper。
     * @param messageMapper 消息 Mapper。
     * @param invocationLogMapper 调用日志 Mapper。
     */
    public LlmConversationGatewayService(ChatModelService chatModelService,
            LlmProviderAdapterRegistry adapterRegistry, LlmModelCapabilityMapper capabilityMapper,
            LlmConversationMapper conversationMapper, LlmMessageMapper messageMapper,
            LlmInvocationLogMapper invocationLogMapper) {
        this.chatModelService = chatModelService;
        this.adapterRegistry = adapterRegistry;
        this.capabilityMapper = capabilityMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.invocationLogMapper = invocationLogMapper;
    }

    /**
     * 创建本地 LLM 会话。
     *
     * @param req 创建会话请求。
     * @param user 当前用户。
     * @return 会话摘要响应。
     * @throws IllegalArgumentException 当模型配置不存在或请求参数非法时抛出。
     */
    @Transactional
    public LlmConversationResp createConversation(LlmConversationCreateReq req, User user) {
        Integer chatModelId = resolveChatModelId(req.getChatModelId(), req.getProviderId());
        ChatModel chatModel = requireVisibleChatModel(chatModelId, user);
        ChatModelConfig config = requireChatModelConfig(chatModel);
        String providerType = resolveProviderType(config);
        String modelName = StringUtils.defaultIfBlank(req.getModelName(), config.getModelName());
        Date now = new Date();

        LlmConversationDO conversation = new LlmConversationDO();
        conversation.setConversationType(
                StringUtils.defaultIfBlank(req.getConversationType(), DEFAULT_CONVERSATION_TYPE));
        conversation.setChatModelId(chatModelId);
        conversation.setProviderType(providerType);
        conversation.setModelName(modelName);
        conversation.setBusinessId(req.getBusinessId());
        conversation.setStatus(LlmConstants.CONVERSATION_ACTIVE);
        conversation.setCreatedBy(user == null ? null : user.getName());
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);

        if (StringUtils.isNotBlank(req.getSystemPrompt())) {
            saveMessage(conversation.getId(), LlmConstants.ROLE_SYSTEM, req.getSystemPrompt(), null,
                    LlmConstants.CONTENT_TYPE_TEXT, null, null, 1);
        }
        return buildConversationResp(conversation, listMessages(conversation.getId()));
    }

    /**
     * 追加用户消息并调用模型。
     *
     * @param conversationId 会话 ID。
     * @param req 用户消息和调用参数。
     * @return 模型调用结果。
     * @throws IllegalArgumentException 当会话、模型配置或调用参数非法时抛出。
     * @throws RuntimeException 当 Provider 调用或消息持久化发生未恢复异常时抛出；条带锁仍会在 {@code finally} 中释放。
     */
    @Transactional
    public LlmMessageCreateResp appendMessageAndChat(Long conversationId, LlmMessageCreateReq req) {
        ReentrantLock lock = getConversationLock(conversationId);
        lock.lock();
        try {
            return appendMessageAndChatLocked(conversationId, req);
        } finally {
            // 显式 finally 保证 Provider、数据库或参数异常均不会永久占用条带锁。
            lock.unlock();
        }
    }

    /**
     * 校验当前用户为会话创建者或超级管理员后追加消息。
     *
     * @param conversationId 会话 ID。
     * @param req 用户消息和调用参数。
     * @param user 当前登录用户。
     * @return 模型调用结果。
     * @throws InvalidPermissionException 用户无权访问该会话时抛出。
     */
    @Transactional
    public LlmMessageCreateResp appendMessageAndChat(Long conversationId, LlmMessageCreateReq req,
            User user) {
        assertConversationAccess(conversationId, user);
        return appendMessageAndChat(conversationId, req);
    }

    /**
     * 在不持有数据库事务的情况下追加消息并调用模型。
     *
     * <p>
     * 职责说明：供阶段 3 等后台编排使用，进入方法时暂停调用方事务，再复用与普通入口相同的条带锁、 消息持久化和 Provider
     * 适配逻辑。这样网络等待不会长期占用数据库连接；各条本地持久化语句独立提交， 最终业务状态仍由调用方自己的短事务保存。
     * </p>
     *
     * @param conversationId 会话 ID。
     * @param req 用户消息和调用参数。
     * @return 模型调用结果。
     * @throws RuntimeException 当 Provider 或本地持久化发生异常时抛出。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LlmMessageCreateResp appendMessageAndChatWithoutTransaction(Long conversationId,
            LlmMessageCreateReq req) {
        // 同类内部调用不会再次经过 Spring 事务代理，因此原入口的 REQUIRED 不会重新开启事务。
        return appendMessageAndChat(conversationId, req);
    }

    /**
     * 查询会话详情。
     *
     * @param conversationId 会话 ID。
     * @param user 当前登录用户。
     * @return 会话详情。
     * @throws IllegalArgumentException 当会话不存在时抛出。
     * @throws InvalidPermissionException 用户无权读取该会话时抛出。
     */
    public LlmConversationResp getConversation(Long conversationId, User user) {
        LlmConversationDO conversation = requireConversation(conversationId);
        requireConversationOwner(conversation, user);
        return buildConversationResp(conversation, listMessages(conversationId));
    }

    /**
     * 查询当前用户可见模型的 Gateway 能力。
     *
     * @param user 当前用户。
     * @return 模型能力列表。
     */
    public List<LlmModelCapabilityDO> listCapabilities(User user) {
        return chatModelService.getChatModels(user).stream().map(
                chatModel -> getCapability(chatModel.getId(), requireChatModelConfig(chatModel)))
                .sorted(Comparator.comparing(LlmModelCapabilityDO::getChatModelId)).toList();
    }

    /**
     * 保存模型能力配置。
     *
     * @param capability 前端编辑后的能力配置。
     * @param user 当前登录用户，仅超级管理员可修改模型能力门禁。
     * @return 保存后的能力配置。
     * @throws IllegalArgumentException 当必要字段缺失时抛出。
     */
    @Transactional
    public LlmModelCapabilityDO saveCapability(LlmModelCapabilityDO capability, User user) {
        if (!isSuperAdmin(user)) {
            throw new InvalidPermissionException("只有超级管理员可以修改 LLM 模型能力");
        }
        if (capability == null || capability.getChatModelId() == null
                || StringUtils.isBlank(capability.getModelName())) {
            throw new IllegalArgumentException("chatModelId and modelName are required.");
        }
        Date now = new Date();
        if (capability.getId() == null) {
            LlmModelCapabilityDO exists =
                    capabilityMapper.selectOne(new LambdaQueryWrapper<LlmModelCapabilityDO>()
                            .eq(LlmModelCapabilityDO::getChatModelId, capability.getChatModelId())
                            .eq(LlmModelCapabilityDO::getModelName, capability.getModelName())
                            .last("limit 1"));
            if (exists != null) {
                capability.setId(exists.getId());
                if (capability.getCreatedAt() == null) {
                    capability.setCreatedAt(exists.getCreatedAt());
                }
            }
        }
        if (capability.getCreatedAt() == null) {
            capability.setCreatedAt(now);
        }
        capability.setUpdatedAt(now);
        if (capability.getEnabled() == null) {
            capability.setEnabled(true);
        }
        if (capability.getId() == null) {
            capabilityMapper.insert(capability);
        } else {
            capabilityMapper.updateById(capability);
        }
        return capability;
    }

    /**
     * 查询 LLM 调用日志。
     *
     * @param req 筛选条件。
     * @param user 当前登录用户；非超级管理员仅能查询自己创建会话的日志。
     * @return 脱敏后的调用日志列表。
     */
    public List<LlmInvocationLogResp> listInvocationLogs(LlmInvocationLogQueryReq req, User user) {
        Integer pageSize = req == null || req.getPageSize() == null ? 20 : req.getPageSize();
        int safeLimit = Math.max(1, Math.min(pageSize, 200));
        String createdBy = isSuperAdmin(user) ? null : requireUserName(user);
        // 权限过滤必须在数据库内完成，不能把长期累积的会话 ID 拉入 JVM 后再拼装 IN。
        List<LlmInvocationLogDO> logs =
                invocationLogMapper.selectAccessibleLogs(req, createdBy, safeLimit);
        return buildInvocationLogResponses(logs);
    }

    /**
     * 查询调用日志详情。
     *
     * @param logId 调用日志 ID。
     * @param user 当前登录用户。
     * @return 调用日志详情。
     * @throws IllegalArgumentException 当日志不存在时抛出。
     * @throws InvalidPermissionException 用户无权读取日志所属会话时抛出。
     */
    public LlmInvocationLogResp getInvocationLog(Long logId, User user) {
        LlmInvocationLogDO logDO = invocationLogMapper.selectById(logId);
        if (logDO == null) {
            throw new IllegalArgumentException("LLM invocation log not found: " + logId);
        }
        assertConversationAccess(logDO.getConversationId(), user);
        return buildInvocationLogResp(logDO);
    }

    /**
     * 校验当前用户可访问指定会话，供 REST 调试入口复用。
     *
     * @param conversationId 会话 ID。
     * @param user 当前登录用户。
     * @throws IllegalArgumentException 会话不存在时抛出。
     * @throws InvalidPermissionException 用户既非创建者也非超级管理员时抛出。
     */
    public void assertConversationAccess(Long conversationId, User user) {
        requireConversationOwner(requireConversation(conversationId), user);
    }

    /**
     * 在已获得会话锁的情况下追加消息并调用模型。
     */
    protected LlmMessageCreateResp appendMessageAndChatLocked(Long conversationId,
            LlmMessageCreateReq req) {
        validateMessageRequest(req);
        LlmConversationDO conversation = requireConversation(conversationId);
        ChatModel chatModel = requireChatModel(conversation.getChatModelId());
        ChatModelConfig config = requireChatModelConfig(chatModel);
        LlmModelCapabilityDO capability = getCapability(chatModel.getId(), config);

        int nextOrder = nextMessageOrder(conversationId);
        String persistedContent = Boolean.FALSE.equals(req.getPersistUserContent())
                ? promptAuditSummary(req.getContent())
                : req.getContent();
        LlmMessageDO userMessage =
                saveMessage(conversationId, LlmConstants.ROLE_USER, persistedContent, null,
                        resolveContentType(req.getResponseFormat()), null, null, nextOrder);

        if (Boolean.TRUE.equals(req.getStream())) {
            return buildRejectedResp(userMessage.getId(), LlmConstants.ERROR_BAD_REQUEST,
                    "Streaming chat is reserved for later phases.");
        }
        if (Boolean.TRUE.equals(req.getRequireToolCalling())
                && !Boolean.TRUE.equals(capability.getSupportToolCalling())) {
            return buildRejectedResp(userMessage.getId(),
                    LlmConstants.ERROR_TOOL_CALLING_UNSUPPORTED,
                    "Current model capability does not enable tool calling.");
        }
        if (LlmConstants.FORMAT_JSON.equalsIgnoreCase(req.getResponseFormat())
                && !Boolean.TRUE.equals(capability.getSupportJsonMode())) {
            return buildRejectedResp(userMessage.getId(), LlmConstants.ERROR_BAD_REQUEST,
                    "Current model capability does not enable JSON mode.");
        }
        if (Boolean.TRUE.equals(req.getThinkingEnabled())
                && !Boolean.TRUE.equals(capability.getSupportThinking())) {
            return buildRejectedResp(userMessage.getId(), LlmConstants.ERROR_BAD_REQUEST,
                    "Current model capability does not enable thinking mode.");
        }
        JsonNode jsonSchema = req.getJsonSchema();
        if (LlmConstants.FORMAT_JSON.equalsIgnoreCase(req.getResponseFormat()) && jsonSchema != null
                && !jsonSchema.isObject()) {
            return buildRejectedResp(userMessage.getId(), LlmConstants.ERROR_BAD_REQUEST,
                    "jsonSchema must be a JSON object.");
        }

        LlmChatRequest adapterRequest = buildAdapterRequest(conversation, config, capability, req);
        LlmProviderAdapter adapter = adapterRegistry.getAdapter(adapterRequest.getProviderType(),
                adapterRequest.getBaseUrl(), adapterRequest.getModelName());
        LlmMessageCreateResp jsonModeRejection = validateAdapterJsonOutputMode(userMessage.getId(),
                req, adapter);
        if (jsonModeRejection != null) {
            return jsonModeRejection;
        }
        long start = System.currentTimeMillis();
        LlmChatResponse adapterResponse = adapter.chat(adapterRequest);
        long latencyMs = System.currentTimeMillis() - start;
        String requestId = resolveRequestId(adapterResponse);
        saveInvocationLog(conversation, adapterResponse, requestId, latencyMs);

        if (!adapterResponse.isSuccess()) {
            LlmMessageDO invalidAssistantMessage = saveInvalidAssistantMessage(conversation,
                    adapterResponse, nextOrder + 1);
            conversation.setStatus(LlmConstants.CONVERSATION_FAILED);
            conversation.setUpdatedAt(new Date());
            conversationMapper.updateById(conversation);
            return buildFailedInvocationResp(userMessage.getId(), invalidAssistantMessage,
                    adapterResponse, requestId, latencyMs,
                    isSemanticModelingConversation(conversation));
        }

        boolean semanticModeling = isSemanticModelingConversation(conversation);
        // 建模原文的唯一持久化位置是 attempt 表；通用消息表不保留成功/失败输出副本。
        LlmMessageDO assistantMessage = semanticModeling ? null
                : saveMessage(conversationId, LlmConstants.ROLE_ASSISTANT,
                        adapterResponse.getContent(), adapterResponse.getReasoningContent(),
                        resolveContentType(req.getResponseFormat()), adapterResponse.getToolCalls(),
                        null, nextOrder + 1);
        conversation.setStatus(LlmConstants.CONVERSATION_ACTIVE);
        conversation.setUpdatedAt(new Date());
        conversationMapper.updateById(conversation);

        return LlmMessageCreateResp.builder().messageId(userMessage.getId())
                .assistantMessageId(assistantMessage == null ? null : assistantMessage.getId())
                .assistantContent(semanticModeling ? null : adapterResponse.getContent())
                .internalAssistantContent(semanticModeling
                        ? resolveInternalAssistantContent(adapterResponse)
                        : null)
                .parsedJson(semanticModeling ? null : adapterResponse.getParsedJson())
                .reasoningContent(semanticModeling ? null : adapterResponse.getReasoningContent())
                .toolCalls(semanticModeling ? null : adapterResponse.getToolCalls())
                .status(LlmConstants.INVOCATION_SUCCESS)
                .requestId(requestId)
                .providerRequestId(adapterResponse.getProviderRequestId())
                .promptTokens(adapterResponse.getPromptTokens())
                .completionTokens(adapterResponse.getCompletionTokens())
                .totalTokens(adapterResponse.getTotalTokens()).latencyMs(latencyMs).build();
    }

    /**
     * JSON 解析失败时为普通调试会话保存原文。
     *
     * <p>
     * 语义建模的 raw/repaired output 必须只存在 attempt 表，因此返回 {@code null}，不向通用消息表写入。
     * </p>
     */
    private LlmMessageDO saveInvalidAssistantMessage(LlmConversationDO conversation,
            LlmChatResponse adapterResponse, int messageOrder) {
        if (!LlmConstants.ERROR_JSON_PARSE_FAILED.equals(adapterResponse.getErrorCode())
                || StringUtils.isBlank(adapterResponse.getContent())) {
            return null;
        }
        if (isSemanticModelingConversation(conversation)) {
            return null;
        }
        return saveMessage(conversation.getId(), LlmConstants.ROLE_ASSISTANT,
                adapterResponse.getContent(), adapterResponse.getReasoningContent(),
                LlmConstants.CONTENT_TYPE_INTERNAL_JSON_ERROR, adapterResponse.getToolCalls(), null,
                messageOrder);
    }

    /**
     * 构造 Provider 调用失败响应；无效正文只进入 Jackson 忽略的后台字段。
     */
    private LlmMessageCreateResp buildFailedInvocationResp(Long userMessageId,
            LlmMessageDO invalidAssistantMessage, LlmChatResponse adapterResponse, String requestId,
            long latencyMs, boolean semanticModeling) {
        Long assistantMessageId = invalidAssistantMessage == null ? null
                : invalidAssistantMessage.getId();
        String internalAssistantContent = LlmConstants.ERROR_JSON_PARSE_FAILED
                .equals(adapterResponse.getErrorCode()) ? adapterResponse.getContent() : null;
        return LlmMessageCreateResp.builder().messageId(userMessageId)
                .assistantMessageId(assistantMessageId)
                .internalAssistantContent(internalAssistantContent)
                .status(LlmConstants.INVOCATION_FAILED).errorCode(adapterResponse.getErrorCode())
                .errorMessage(semanticModeling ? buildSafeInvocationError(adapterResponse)
                        : adapterResponse.getErrorMessage())
                .requestId(requestId)
                .providerRequestId(adapterResponse.getProviderRequestId())
                .promptTokens(adapterResponse.getPromptTokens())
                .completionTokens(adapterResponse.getCompletionTokens())
                .totalTokens(adapterResponse.getTotalTokens()).latencyMs(latencyMs).build();
    }

    /**
     * 构造 Adapter 请求。
     */
    private LlmChatRequest buildAdapterRequest(LlmConversationDO conversation,
            ChatModelConfig config, LlmModelCapabilityDO capability, LlmMessageCreateReq req) {
        List<LlmChatMessage> messages = new ArrayList<>(
                listMessages(conversation.getId()).stream().map(this::toChatMessage).toList());
        if (Boolean.FALSE.equals(req.getPersistUserContent()) && !messages.isEmpty()) {
            // 数据库只保存摘要；仅在当前请求局部内恢复最后一条原文，既能调用 Provider 又不会跨请求留存。
            messages.get(messages.size() - 1).setContent(req.getContent());
        }
        return LlmChatRequest.builder().providerType(conversation.getProviderType())
                .baseUrl(resolveBaseUrl(config))
                .betaBaseUrl(DeepSeekProviderAdapter.DEFAULT_BETA_BASE_URL)
                .apiKey(resolveApiKey(config)).modelName(conversation.getModelName())
                .messages(messages).responseFormat(req.getResponseFormat())
                .jsonSchema(req.getJsonSchema()).temperature(resolveTemperature(req, capability))
                .maxTokens(req.getMaxTokens()).timeoutMs(resolveTimeoutMs(req, config))
                .thinkingEnabled(req.getThinkingEnabled()).reasoningEffort(req.getReasoningEffort())
                .requireToolCalling(req.getRequireToolCalling()).stream(false).build();
    }

    /** 为不落盘 Prompt 生成不可逆审计摘要，不记录任何原文片段。 */
    private String promptAuditSummary(String content) {
        String safeContent = StringUtils.defaultString(content);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(safeContent.getBytes(StandardCharsets.UTF_8)));
            return "[prompt omitted; sha256=" + digest + "; length=" + safeContent.length() + "]";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    /**
     * 保存消息行。
     */
    private LlmMessageDO saveMessage(Long conversationId, String role, String content,
            String reasoningContent, String contentType, String toolCalls, String toolCallId,
            Integer messageOrder) {
        LlmMessageDO message = new LlmMessageDO();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setReasoningContent(reasoningContent);
        message.setContentType(contentType);
        message.setToolCalls(toolCalls);
        message.setToolCallId(toolCallId);
        message.setMessageOrder(messageOrder);
        message.setTokenCount(estimateToken(content));
        message.setCreatedAt(new Date());
        messageMapper.insert(message);
        return message;
    }

    /**
     * 保存调用日志，日志失败只记录告警，不覆盖模型调用主错误。
     */
    private void saveInvocationLog(LlmConversationDO conversation, LlmChatResponse response,
            String requestId, long latencyMs) {
        try {
            LlmInvocationLogDO logDO = new LlmInvocationLogDO();
            logDO.setConversationId(conversation.getId());
            logDO.setChatModelId(conversation.getChatModelId());
            logDO.setProviderType(conversation.getProviderType());
            logDO.setModelName(conversation.getModelName());
            logDO.setRequestId(requestId);
            logDO.setPromptTokens(response.getPromptTokens());
            logDO.setCompletionTokens(response.getCompletionTokens());
            logDO.setTotalTokens(response.getTotalTokens());
            logDO.setLatencyMs(latencyMs);
            logDO.setStatus(resolveInvocationStatus(response));
            logDO.setErrorCode(response.getErrorCode());
            logDO.setErrorMessage(isSemanticModelingConversation(conversation)
                    ? truncate(buildSafeInvocationError(response))
                    : truncate(response.getErrorMessage()));
            // 建模日志只保留 requestId/usage/状态，禁止复制 Provider 响应摘要。
            logDO.setRawResponseRef(isSemanticModelingConversation(conversation) ? null
                    : truncate(response.getRawResponseRef()));
            logDO.setCreatedAt(new Date());
            invocationLogMapper.insert(logDO);
        } catch (Exception exception) {
            log.warn("Failed to save LLM invocation log for conversation {}", conversation.getId(),
                    exception);
        }
    }

    /**
     * 查询或推断模型能力。
     */
    private LlmModelCapabilityDO getCapability(Integer chatModelId, ChatModelConfig config) {
        LlmModelCapabilityDO capability =
                capabilityMapper.selectOne(new LambdaQueryWrapper<LlmModelCapabilityDO>()
                        .eq(LlmModelCapabilityDO::getChatModelId, chatModelId)
                        .eq(LlmModelCapabilityDO::getModelName, config.getModelName())
                        .last("limit 1"));
        if (capability != null) {
            return capability;
        }
        return buildInferredCapability(chatModelId, config);
    }

    /**
     * 为未建能力表行的现有模型推断 Provider 基础能力。
     *
     * <p>Kimi K3 使用独立分支声明 1M 上下文、思考、Tool Calls、严格 JSON Schema 和自动上下文缓存；FIM
     * 不属于 K3 Chat Completions 能力，因此保持关闭。</p>
     */
    private LlmModelCapabilityDO buildInferredCapability(Integer chatModelId,
            ChatModelConfig config) {
        boolean deepSeek = isDeepSeekConfig(config);
        boolean kimi = isKimiConfig(config);
        Date now = new Date();
        LlmModelCapabilityDO capability = new LlmModelCapabilityDO();
        capability.setChatModelId(chatModelId);
        capability.setProviderType(deepSeek ? LlmConstants.PROVIDER_DEEPSEEK
                : kimi ? LlmConstants.PROVIDER_KIMI : config.getProvider());
        capability.setModelName(config.getModelName());
        capability.setMaxContextTokens(kimi ? KIMI_K3_CONTEXT_TOKENS : DEFAULT_CONTEXT_TOKENS);
        capability.setSupportStream(true);
        capability.setSupportJsonMode(true);
        capability.setSupportToolCalling(deepSeek || kimi);
        capability.setSupportThinking(deepSeek || kimi);
        capability.setSupportChatPrefixCompletion(deepSeek || kimi);
        capability.setSupportFimCompletion(deepSeek);
        capability.setSupportContextCache(deepSeek || kimi);
        capability.setSupportSystemPrompt(true);
        capability.setRecommendedTemperature(
                kimi ? 1.0d : config.getTemperature() == null ? 0.0d : config.getTemperature());
        capability.setUsageScene(DEFAULT_USAGE_SCENE);
        capability.setEnabled(true);
        capability.setCreatedAt(now);
        capability.setUpdatedAt(now);
        return capability;
    }

    /**
     * 构造会话响应。
     */
    private LlmConversationResp buildConversationResp(LlmConversationDO conversation,
            List<LlmMessageDO> messages) {
        List<LlmMessageDO> visibleMessages = isSemanticModelingConversation(conversation)
                ? List.of()
                : messages == null ? List.of()
                : messages.stream().filter(message -> !isInternalMessage(message)).toList();
        return LlmConversationResp.builder().conversationId(conversation.getId())
                .chatModelId(conversation.getChatModelId())
                .providerId(conversation.getChatModelId())
                .providerType(conversation.getProviderType()).modelName(conversation.getModelName())
                .status(conversation.getStatus()).messages(visibleMessages).build();
    }

    /**
     * 构造脱敏调用日志响应。
     */
    private LlmInvocationLogResp buildInvocationLogResp(LlmInvocationLogDO logDO) {
        LlmConversationDO conversation = conversationMapper.selectById(logDO.getConversationId());
        // 建模日志不需要读取 Prompt/样例到 JVM，从数据源头阻断误暴露。
        List<LlmMessageDO> messages = isSemanticModelingConversation(conversation) ? List.of()
                : listMessages(logDO.getConversationId());
        return buildInvocationLogResp(logDO, conversation, messages);
    }

    /** 批量加载列表所需的会话和消息，避免每条日志分别执行两次查询。 */
    private List<LlmInvocationLogResp> buildInvocationLogResponses(
            List<LlmInvocationLogDO> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        Set<Long> conversationIds = new LinkedHashSet<>();
        for (LlmInvocationLogDO log : logs) {
            if (log.getConversationId() != null) {
                conversationIds.add(log.getConversationId());
            }
        }
        if (conversationIds.isEmpty()) {
            return logs.stream().map(log -> buildInvocationLogResp(log, null, List.of())).toList();
        }

        Map<Long, LlmConversationDO> conversationById = new HashMap<>();
        for (LlmConversationDO conversation : conversationMapper.selectByIds(conversationIds)) {
            if (conversation.getId() != null) {
                conversationById.put(conversation.getId(), conversation);
            }
        }
        Set<Long> visibleMessageConversationIds = new LinkedHashSet<>();
        for (Long conversationId : conversationIds) {
            if (!isSemanticModelingConversation(conversationById.get(conversationId))) {
                visibleMessageConversationIds.add(conversationId);
            }
        }
        Map<Long, List<LlmMessageDO>> messagesByConversation = new HashMap<>();
        List<LlmMessageDO> messages = visibleMessageConversationIds.isEmpty() ? List.of()
                : messageMapper.selectList(new LambdaQueryWrapper<LlmMessageDO>()
                        .in(LlmMessageDO::getConversationId, visibleMessageConversationIds)
                        .orderByAsc(LlmMessageDO::getConversationId)
                        .orderByAsc(LlmMessageDO::getMessageOrder));
        for (LlmMessageDO message : messages) {
            messagesByConversation
                    .computeIfAbsent(message.getConversationId(), ignored -> new ArrayList<>())
                    .add(message);
        }
        return logs.stream()
                .map(log -> buildInvocationLogResp(log,
                        conversationById.get(log.getConversationId()), messagesByConversation
                                .getOrDefault(log.getConversationId(), List.of())))
                .toList();
    }

    /** 使用已批量加载的会话和消息构造单条脱敏日志响应。 */
    private LlmInvocationLogResp buildInvocationLogResp(LlmInvocationLogDO logDO,
            LlmConversationDO conversation, List<LlmMessageDO> messages) {
        boolean semanticModeling = isSemanticModelingConversation(conversation);
        return LlmInvocationLogResp.builder().id(logDO.getId())
                .conversationId(logDO.getConversationId())
                .conversationType(conversation == null ? null : conversation.getConversationType())
                .chatModelId(logDO.getChatModelId()).providerType(logDO.getProviderType())
                .modelName(logDO.getModelName()).requestId(logDO.getRequestId())
                .promptTokens(logDO.getPromptTokens()).completionTokens(logDO.getCompletionTokens())
                .totalTokens(logDO.getTotalTokens()).latencyMs(logDO.getLatencyMs())
                .status(logDO.getStatus()).errorCode(logDO.getErrorCode())
                .errorMessage(logDO.getErrorMessage())
                .requestSummary(semanticModeling ? null : buildRequestSummary(messages))
                .rawResponseRef(semanticModeling ? null : logDO.getRawResponseRef())
                .hasReasoningContent(!semanticModeling && messages.stream()
                        .anyMatch(message -> StringUtils.isNotBlank(message.getReasoningContent())))
                .hasToolCalls(!semanticModeling && messages.stream()
                        .anyMatch(message -> StringUtils.isNotBlank(message.getToolCalls())))
                .createdAt(logDO.getCreatedAt()).build();
    }

    /** 判断会话是否包含需要隔离的语义建模上下文。 */
    private boolean isSemanticModelingConversation(LlmConversationDO conversation) {
        return conversation != null && SEMANTIC_MODELING_CONVERSATION_TYPE
                .equalsIgnoreCase(conversation.getConversationType());
    }

    /** 向建模 Worker 传递不会被 Jackson 序列化的模型原文。 */
    private String resolveInternalAssistantContent(LlmChatResponse response) {
        if (StringUtils.isNotBlank(response.getContent())) {
            return response.getContent();
        }
        return response.getParsedJson() == null ? null : response.getParsedJson().toString();
    }

    /** 生成不包含 Provider 原文的建模调用错误摘要。 */
    private String buildSafeInvocationError(LlmChatResponse response) {
        if (LlmConstants.ERROR_JSON_PARSE_FAILED.equals(response.getErrorCode())) {
            return "LLM returned empty or invalid JSON content";
        }
        return StringUtils.isBlank(response.getErrorCode()) ? null
                : "LLM provider call failed: " + response.getErrorCode();
    }

    /**
     * 根据本地 messages 构造脱敏请求摘要，避免保存或展示完整敏感请求体。
     */
    private String buildRequestSummary(List<LlmMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String summary = messages.stream()
                .filter(message -> !isInternalMessage(message))
                .map(message -> message.getRole() + ": " + truncate(message.getContent()))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return truncate(summary);
    }

    /**
     * 查询会话消息。
     */
    private List<LlmMessageDO> listMessages(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<LlmMessageDO>()
                .eq(LlmMessageDO::getConversationId, conversationId)
                .orderByAsc(LlmMessageDO::getMessageOrder));
    }

    /**
     * 计算下一条消息顺序。
     */
    private int nextMessageOrder(Long conversationId) {
        List<LlmMessageDO> messages = listMessages(conversationId);
        return messages.stream().map(LlmMessageDO::getMessageOrder).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
    }

    /**
     * 将持久化消息转换为 Adapter 消息。
     */
    private LlmChatMessage toChatMessage(LlmMessageDO message) {
        return LlmChatMessage.builder().role(message.getRole()).content(message.getContent())
                .reasoningContent(message.getReasoningContent()).toolCalls(message.getToolCalls())
                .toolCallId(message.getToolCallId()).build();
    }

    /** 判断消息是否仅供服务端结构修复使用，禁止出现在公开响应和日志摘要。 */
    private boolean isInternalMessage(LlmMessageDO message) {
        return message != null && LlmConstants.CONTENT_TYPE_INTERNAL_JSON_ERROR
                .equals(message.getContentType());
    }

    /**
     * 校验追加消息请求。
     */
    private void validateMessageRequest(LlmMessageCreateReq req) {
        if (req == null || StringUtils.isBlank(req.getContent())) {
            throw new IllegalArgumentException("Message content is required.");
        }
        if (!LlmConstants.FORMAT_TEXT.equalsIgnoreCase(req.getResponseFormat())
                && !LlmConstants.FORMAT_JSON.equalsIgnoreCase(req.getResponseFormat())) {
            throw new IllegalArgumentException("responseFormat must be text or json.");
        }
    }

    /**
     * 获取会话，不存在则抛错。
     */
    private LlmConversationDO requireConversation(Long conversationId) {
        LlmConversationDO conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("LLM conversation not found: " + conversationId);
        }
        return conversation;
    }

    /** 校验会话归属；超级管理员可执行平台级排障。 */
    private void requireConversationOwner(LlmConversationDO conversation, User user) {
        String userName = requireUserName(user);
        if (!isSuperAdmin(user)
                && !StringUtils.equalsIgnoreCase(conversation.getCreatedBy(), userName)) {
            throw new InvalidPermissionException("无权访问该 LLM 会话");
        }
    }

    /** 返回已认证用户名；Gateway REST 入口不允许匿名访问。 */
    private String requireUserName(User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("请先登录后访问 LLM Gateway");
        }
        return user.getName();
    }

    /** 判断当前用户是否可以执行平台级会话与日志审计。 */
    private boolean isSuperAdmin(User user) {
        return user != null && user.isSuperAdmin();
    }

    /**
     * 获取模型配置，不存在则抛错。
     */
    private ChatModel requireChatModel(Integer chatModelId) {
        ChatModel chatModel = chatModelService.getChatModel(chatModelId);
        if (chatModel == null) {
            throw new IllegalArgumentException("Chat model not found: " + chatModelId);
        }
        return chatModel;
    }

    /** 校验模型对当前用户可见，防止通过猜测 ID 消耗其他用户的 Provider 凭据。 */
    private ChatModel requireVisibleChatModel(Integer chatModelId, User user) {
        requireUserName(user);
        if (isSuperAdmin(user)) {
            return requireChatModel(chatModelId);
        }
        return chatModelService.getChatModels(user).stream()
                .filter(model -> Objects.equals(model.getId(), chatModelId)).findFirst()
                .orElseThrow(() -> new InvalidPermissionException("无权使用所选 LLM 模型"));
    }

    /**
     * 获取模型连接配置，不完整则抛错。
     */
    private ChatModelConfig requireChatModelConfig(ChatModel chatModel) {
        ChatModelConfig config = chatModel.getConfig();
        if (config == null || StringUtils.isBlank(config.getModelName())) {
            throw new IllegalArgumentException("Chat model config is incomplete.");
        }
        return config;
    }

    /**
     * 兼容 chatModelId/providerId 两种请求命名。
     */
    private Integer resolveChatModelId(Integer chatModelId, Integer providerId) {
        Integer resolved = chatModelId == null ? providerId : chatModelId;
        if (resolved == null) {
            throw new IllegalArgumentException("chatModelId or providerId is required.");
        }
        return resolved;
    }

    /** 解析 providerType，优先根据厂商特征识别 DeepSeek 与 Kimi 的 OpenAI-compatible 配置。 */
    private String resolveProviderType(ChatModelConfig config) {
        if (isDeepSeekConfig(config)) {
            return LlmConstants.PROVIDER_DEEPSEEK;
        }
        return isKimiConfig(config) ? LlmConstants.PROVIDER_KIMI : config.getProvider();
    }

    /**
     * 判断是否是 DeepSeek 配置。
     */
    private boolean isDeepSeekConfig(ChatModelConfig config) {
        String provider = StringUtils.defaultString(config.getProvider());
        String baseUrl = StringUtils.defaultString(config.getBaseUrl()).toLowerCase(Locale.ROOT);
        String modelName =
                StringUtils.defaultString(config.getModelName()).toLowerCase(Locale.ROOT);
        return LlmConstants.PROVIDER_DEEPSEEK.equalsIgnoreCase(provider)
                || baseUrl.contains("deepseek.com") || modelName.startsWith("deepseek-");
    }

    /** 判断配置是否属于 Kimi，兼容显式 KIMI provider 和历史 OPEN_AI 兼容配置。 */
    private boolean isKimiConfig(ChatModelConfig config) {
        String provider = StringUtils.defaultString(config.getProvider()).toUpperCase(Locale.ROOT);
        String baseUrl = StringUtils.defaultString(config.getBaseUrl()).toLowerCase(Locale.ROOT);
        String modelName = StringUtils.defaultString(config.getModelName()).toLowerCase(Locale.ROOT);
        return LlmConstants.PROVIDER_KIMI.equals(provider) || baseUrl.contains("moonshot.cn")
                || baseUrl.contains("moonshot.ai") || modelName.startsWith("kimi-");
    }

    /**
     * 解析普通 Chat base URL。
     */
    private String resolveBaseUrl(ChatModelConfig config) {
        return StringUtils.defaultIfBlank(config.getBaseUrl(),
                DeepSeekProviderAdapter.DEFAULT_BASE_URL);
    }

    /**
     * 解密 API Key；兼容开发期明文配置，但不输出日志。
     */
    private String resolveApiKey(ChatModelConfig config) {
        try {
            String decrypted = config.keyDecrypt();
            return StringUtils.defaultIfBlank(decrypted, config.getApiKey());
        } catch (Exception exception) {
            return config.getApiKey();
        }
    }

    /**
     * 解析温度。
     */
    private Double resolveTemperature(LlmMessageCreateReq req, LlmModelCapabilityDO capability) {
        if (req.getTemperature() != null) {
            return req.getTemperature();
        }
        return capability.getRecommendedTemperature();
    }

    /**
     * 解析超时时间。
     */
    private Long resolveTimeoutMs(LlmMessageCreateReq req, ChatModelConfig config) {
        if (req.getTimeoutMs() != null) {
            return req.getTimeoutMs();
        }
        Long seconds = config.getTimeOut();
        return seconds == null ? null : seconds * 1000;
    }

    /**
     * 解析内容类型。
     */
    private String resolveContentType(String responseFormat) {
        return LlmConstants.FORMAT_JSON.equalsIgnoreCase(responseFormat)
                ? LlmConstants.CONTENT_TYPE_JSON
                : LlmConstants.CONTENT_TYPE_TEXT;
    }

    /**
     * 根据响应状态推导日志状态。
     */
    private String resolveInvocationStatus(LlmChatResponse response) {
        if (response.isSuccess()) {
            return LlmConstants.INVOCATION_SUCCESS;
        }
        if (LlmConstants.ERROR_TIMEOUT.equals(response.getErrorCode())) {
            return LlmConstants.INVOCATION_TIMEOUT;
        }
        if (LlmConstants.ERROR_RATE_LIMITED.equals(response.getErrorCode())) {
            return LlmConstants.INVOCATION_RATE_LIMITED;
        }
        return LlmConstants.INVOCATION_FAILED;
    }

    /**
     * 校验模型能力表与 Adapter 真实 JSON 协议能力一致。
     *
     * <p>
     * 模型能力只表示管理员允许使用 JSON；协议如何传递必须由 Adapter 显式声明。严格 Schema
     * 模式没有 Schema 时无法构造合法请求，因此在调用 Provider 前拒绝。
     * </p>
     */
    private LlmMessageCreateResp validateAdapterJsonOutputMode(Long messageId,
            LlmMessageCreateReq req, LlmProviderAdapter adapter) {
        if (!LlmConstants.FORMAT_JSON.equalsIgnoreCase(req.getResponseFormat())) {
            return null;
        }
        LlmJsonOutputMode outputMode = adapter.jsonOutputMode();
        if (outputMode == null || LlmJsonOutputMode.NONE.equals(outputMode)) {
            return buildRejectedResp(messageId, LlmConstants.ERROR_JSON_OUTPUT_UNSUPPORTED,
                    "Current provider adapter does not support JSON output.");
        }
        if (LlmJsonOutputMode.JSON_SCHEMA_STRICT.equals(outputMode)
                && req.getJsonSchema() == null) {
            return buildRejectedResp(messageId, LlmConstants.ERROR_BAD_REQUEST,
                    "jsonSchema is required for strict JSON Schema output.");
        }
        return null;
    }

    /** 返回调用日志可检索的请求 ID；Provider 无 ID 时生成兼容的内部 UUID。 */
    private String resolveRequestId(LlmChatResponse response) {
        return StringUtils.defaultIfBlank(response.getProviderRequestId(),
                UUID.randomUUID().toString());
    }

    /**
     * 创建固定容量的公平条带锁；公平策略避免同一热点条带中的会话长期饥饿。
     */
    private static ReentrantLock[] createConversationLocks() {
        ReentrantLock[] locks = new ReentrantLock[CONVERSATION_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock(true);
        }
        return locks;
    }

    /**
     * 按会话 ID 稳定选择条带锁；不同会话哈希碰撞时允许保守串行以换取固定内存占用。
     */
    private ReentrantLock getConversationLock(Long conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        int stripeIndex = Math.floorMod(conversationId.hashCode(), conversationLocks.length);
        return conversationLocks[stripeIndex];
    }

    /**
     * 构造前置门禁拒绝响应。
     */
    private LlmMessageCreateResp buildRejectedResp(Long messageId, String errorCode,
            String errorMessage) {
        return LlmMessageCreateResp.builder().messageId(messageId)
                .status(LlmConstants.INVOCATION_FAILED).errorCode(errorCode)
                .errorMessage(errorMessage).build();
    }

    /**
     * 粗略估算 token 数，后续可替换为供应商 tokenizer。
     */
    private int estimateToken(String content) {
        return StringUtils.isBlank(content) ? 0 : Math.max(1, content.length() / 4);
    }

    /**
     * 截断日志字段，避免长响应占满数据库。
     */
    private String truncate(String value) {
        if (value == null || value.length() <= MESSAGE_SUMMARY_LIMIT) {
            return value;
        }
        return value.substring(0, MESSAGE_SUMMARY_LIMIT);
    }
}
