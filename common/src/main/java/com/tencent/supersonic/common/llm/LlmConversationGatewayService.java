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
import com.tencent.supersonic.common.service.ChatModelService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Conversation Gateway 核心服务。
 *
 * <p>
 * 职责说明：复用现有 `s2_chat_model` 配置创建本地会话，按 `llm_message` 顺序拼接完整 messages，选择 Provider Adapter
 * 发起非流式调用，并持久化 assistant 消息与调用日志。DeepSeek 是无状态 API，因此多轮上下文只能由本服务读取本地消息表后完整拼接， Adapter 不允许重新查询业务历史。
 * </p>
 *
 * <p>
 * 并发说明：同一会话追加消息需要保持 `message_order` 单调递增。当前阶段使用 `conversationLocks` 以 conversationId 为粒度做 JVM
 * 内互斥，并依赖数据库唯一键 `(conversation_id, message_order)` 阻断异常重复；多实例部署时仍建议后续升级为数据库乐观锁或 `SELECT ... FOR
 * UPDATE`。
 * </p>
 */
@Slf4j
@Service
public class LlmConversationGatewayService {

    private static final String DEFAULT_CONVERSATION_TYPE = "LLM_DEBUG";
    private static final String DEFAULT_USAGE_SCENE = "semantic_modeling";
    private static final int DEFAULT_CONTEXT_TOKENS = 128000;
    private static final int MESSAGE_SUMMARY_LIMIT = 800;

    private final ChatModelService chatModelService;
    private final LlmProviderAdapterRegistry adapterRegistry;
    private final LlmModelCapabilityMapper capabilityMapper;
    private final LlmConversationMapper conversationMapper;
    private final LlmMessageMapper messageMapper;
    private final LlmInvocationLogMapper invocationLogMapper;
    private final Map<Long, Object> conversationLocks = new ConcurrentHashMap<>();

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
        ChatModel chatModel = requireChatModel(chatModelId);
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
     */
    @Transactional
    public LlmMessageCreateResp appendMessageAndChat(Long conversationId, LlmMessageCreateReq req) {
        Object lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
        synchronized (lock) {
            return appendMessageAndChatLocked(conversationId, req);
        }
    }

    /**
     * 查询会话详情。
     *
     * @param conversationId 会话 ID。
     * @return 会话详情。
     * @throws IllegalArgumentException 当会话不存在时抛出。
     */
    public LlmConversationResp getConversation(Long conversationId) {
        LlmConversationDO conversation = requireConversation(conversationId);
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
     * @return 保存后的能力配置。
     * @throws IllegalArgumentException 当必要字段缺失时抛出。
     */
    @Transactional
    public LlmModelCapabilityDO saveCapability(LlmModelCapabilityDO capability) {
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
     * @return 脱敏后的调用日志列表。
     */
    public List<LlmInvocationLogResp> listInvocationLogs(LlmInvocationLogQueryReq req) {
        LambdaQueryWrapper<LlmInvocationLogDO> wrapper = buildInvocationLogQuery(req);
        wrapper.orderByDesc(LlmInvocationLogDO::getCreatedAt);
        Integer pageSize = req == null || req.getPageSize() == null ? 20 : req.getPageSize();
        wrapper.last("limit " + Math.max(1, Math.min(pageSize, 200)));
        return invocationLogMapper.selectList(wrapper).stream().map(this::buildInvocationLogResp)
                .toList();
    }

    /**
     * 查询调用日志详情。
     *
     * @param logId 调用日志 ID。
     * @return 调用日志详情。
     * @throws IllegalArgumentException 当日志不存在时抛出。
     */
    public LlmInvocationLogResp getInvocationLog(Long logId) {
        LlmInvocationLogDO logDO = invocationLogMapper.selectById(logId);
        if (logDO == null) {
            throw new IllegalArgumentException("LLM invocation log not found: " + logId);
        }
        return buildInvocationLogResp(logDO);
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
        LlmMessageDO userMessage =
                saveMessage(conversationId, LlmConstants.ROLE_USER, req.getContent(), null,
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
        long start = System.currentTimeMillis();
        LlmChatResponse adapterResponse = adapter.chat(adapterRequest);
        long latencyMs = System.currentTimeMillis() - start;
        saveInvocationLog(conversation, adapterResponse, latencyMs);

        if (!adapterResponse.isSuccess()) {
            conversation.setStatus(LlmConstants.CONVERSATION_FAILED);
            conversation.setUpdatedAt(new Date());
            conversationMapper.updateById(conversation);
            return LlmMessageCreateResp.builder().messageId(userMessage.getId())
                    .status(LlmConstants.INVOCATION_FAILED)
                    .errorCode(adapterResponse.getErrorCode())
                    .errorMessage(adapterResponse.getErrorMessage())
                    .providerRequestId(adapterResponse.getProviderRequestId())
                    .promptTokens(adapterResponse.getPromptTokens())
                    .completionTokens(adapterResponse.getCompletionTokens())
                    .totalTokens(adapterResponse.getTotalTokens()).latencyMs(latencyMs).build();
        }

        LlmMessageDO assistantMessage = saveMessage(conversationId, LlmConstants.ROLE_ASSISTANT,
                adapterResponse.getContent(), adapterResponse.getReasoningContent(),
                resolveContentType(req.getResponseFormat()), adapterResponse.getToolCalls(), null,
                nextOrder + 1);
        conversation.setStatus(LlmConstants.CONVERSATION_ACTIVE);
        conversation.setUpdatedAt(new Date());
        conversationMapper.updateById(conversation);

        return LlmMessageCreateResp.builder().messageId(userMessage.getId())
                .assistantMessageId(assistantMessage.getId())
                .assistantContent(adapterResponse.getContent())
                .parsedJson(adapterResponse.getParsedJson())
                .reasoningContent(adapterResponse.getReasoningContent())
                .toolCalls(adapterResponse.getToolCalls()).status(LlmConstants.INVOCATION_SUCCESS)
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
        List<LlmChatMessage> messages =
                listMessages(conversation.getId()).stream().map(this::toChatMessage).toList();
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
            long latencyMs) {
        try {
            LlmInvocationLogDO logDO = new LlmInvocationLogDO();
            logDO.setConversationId(conversation.getId());
            logDO.setChatModelId(conversation.getChatModelId());
            logDO.setProviderType(conversation.getProviderType());
            logDO.setModelName(conversation.getModelName());
            logDO.setRequestId(response.getProviderRequestId());
            logDO.setPromptTokens(response.getPromptTokens());
            logDO.setCompletionTokens(response.getCompletionTokens());
            logDO.setTotalTokens(response.getTotalTokens());
            logDO.setLatencyMs(latencyMs);
            logDO.setStatus(resolveInvocationStatus(response));
            logDO.setErrorCode(response.getErrorCode());
            logDO.setErrorMessage(truncate(response.getErrorMessage()));
            logDO.setRawResponseRef(truncate(response.getRawResponseRef()));
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
     * 为未建能力表行的现有模型推断 DeepSeek/OpenAI-compatible 基础能力。
     */
    private LlmModelCapabilityDO buildInferredCapability(Integer chatModelId,
            ChatModelConfig config) {
        boolean deepSeek = isDeepSeekConfig(config);
        Date now = new Date();
        LlmModelCapabilityDO capability = new LlmModelCapabilityDO();
        capability.setChatModelId(chatModelId);
        capability
                .setProviderType(deepSeek ? LlmConstants.PROVIDER_DEEPSEEK : config.getProvider());
        capability.setModelName(config.getModelName());
        capability.setMaxContextTokens(DEFAULT_CONTEXT_TOKENS);
        capability.setSupportStream(true);
        capability.setSupportJsonMode(true);
        capability.setSupportToolCalling(deepSeek);
        capability.setSupportThinking(deepSeek);
        capability.setSupportChatPrefixCompletion(deepSeek);
        capability.setSupportFimCompletion(deepSeek);
        capability.setSupportContextCache(deepSeek);
        capability.setSupportSystemPrompt(true);
        capability.setRecommendedTemperature(
                config.getTemperature() == null ? 0.0d : config.getTemperature());
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
        return LlmConversationResp.builder().conversationId(conversation.getId())
                .chatModelId(conversation.getChatModelId())
                .providerId(conversation.getChatModelId())
                .providerType(conversation.getProviderType()).modelName(conversation.getModelName())
                .status(conversation.getStatus()).messages(messages).build();
    }

    /**
     * 构造调用日志筛选条件。
     */
    private LambdaQueryWrapper<LlmInvocationLogDO> buildInvocationLogQuery(
            LlmInvocationLogQueryReq req) {
        LambdaQueryWrapper<LlmInvocationLogDO> wrapper = new LambdaQueryWrapper<>();
        if (req == null) {
            return wrapper;
        }
        wrapper.eq(StringUtils.isNotBlank(req.getProviderType()),
                LlmInvocationLogDO::getProviderType, req.getProviderType());
        wrapper.eq(StringUtils.isNotBlank(req.getModelName()), LlmInvocationLogDO::getModelName,
                req.getModelName());
        wrapper.eq(StringUtils.isNotBlank(req.getStatus()), LlmInvocationLogDO::getStatus,
                req.getStatus());
        wrapper.eq(StringUtils.isNotBlank(req.getErrorCode()), LlmInvocationLogDO::getErrorCode,
                req.getErrorCode());
        wrapper.eq(req.getConversationId() != null, LlmInvocationLogDO::getConversationId,
                req.getConversationId());
        wrapper.ge(StringUtils.isNotBlank(req.getStartTime()), LlmInvocationLogDO::getCreatedAt,
                req.getStartTime());
        wrapper.le(StringUtils.isNotBlank(req.getEndTime()), LlmInvocationLogDO::getCreatedAt,
                req.getEndTime());
        return wrapper;
    }

    /**
     * 构造脱敏调用日志响应。
     */
    private LlmInvocationLogResp buildInvocationLogResp(LlmInvocationLogDO logDO) {
        LlmConversationDO conversation = conversationMapper.selectById(logDO.getConversationId());
        List<LlmMessageDO> messages = listMessages(logDO.getConversationId());
        return LlmInvocationLogResp.builder().id(logDO.getId())
                .conversationId(logDO.getConversationId())
                .conversationType(conversation == null ? null : conversation.getConversationType())
                .chatModelId(logDO.getChatModelId()).providerType(logDO.getProviderType())
                .modelName(logDO.getModelName()).requestId(logDO.getRequestId())
                .promptTokens(logDO.getPromptTokens()).completionTokens(logDO.getCompletionTokens())
                .totalTokens(logDO.getTotalTokens()).latencyMs(logDO.getLatencyMs())
                .status(logDO.getStatus()).errorCode(logDO.getErrorCode())
                .errorMessage(logDO.getErrorMessage()).requestSummary(buildRequestSummary(messages))
                .rawResponseRef(logDO.getRawResponseRef())
                .hasReasoningContent(messages.stream()
                        .anyMatch(message -> StringUtils.isNotBlank(message.getReasoningContent())))
                .hasToolCalls(messages.stream()
                        .anyMatch(message -> StringUtils.isNotBlank(message.getToolCalls())))
                .createdAt(logDO.getCreatedAt()).build();
    }

    /**
     * 根据本地 messages 构造脱敏请求摘要，避免保存或展示完整敏感请求体。
     */
    private String buildRequestSummary(List<LlmMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String summary = messages.stream()
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

    /**
     * 解析 providerType，DeepSeek OpenAI-compatible 配置优先识别为 DEEPSEEK。
     */
    private String resolveProviderType(ChatModelConfig config) {
        return isDeepSeekConfig(config) ? LlmConstants.PROVIDER_DEEPSEEK : config.getProvider();
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
