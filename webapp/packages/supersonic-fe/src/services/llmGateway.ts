/**
 * LLM Conversation Gateway 前端服务封装。
 *
 * 职责：
 * - 集中管理阶段 1 Gateway 的会话、消息、模型能力和调用日志接口；
 * - 让页面组件只关心交互状态，避免 HTTP 请求散落在组件内部；
 * - 不传输 API Key、Token 或完整敏感请求体到调试日志页面。
 *
 * 并发说明：
 * - 本文件只做无状态请求封装，不维护共享状态；
 * - 重复提交、同会话顺序等并发保护由调用组件的 loading 状态和后端 Gateway 共同承担。
 */
import request from 'umi-request';

const LLM_GATEWAY_BASE_URL = '/api/llm/';

export type LlmModelCapability = {
  id?: number;
  chatModelId: number;
  providerType?: string;
  modelName: string;
  maxContextTokens?: number;
  supportStream?: boolean;
  supportJsonMode?: boolean;
  supportToolCalling?: boolean;
  supportThinking?: boolean;
  supportChatPrefixCompletion?: boolean;
  supportFimCompletion?: boolean;
  supportContextCache?: boolean;
  supportSystemPrompt?: boolean;
  recommendedTemperature?: number;
  usageScene?: string;
  enabled?: boolean;
};

export type LlmConversationCreateReq = {
  conversationType?: string;
  chatModelId?: number;
  providerId?: number;
  modelName?: string;
  businessId?: string;
  systemPrompt?: string;
};

export type LlmMessageCreateReq = {
  content: string;
  responseFormat?: 'text' | 'json';
  jsonSchema?: any;
  temperature?: number;
  maxTokens?: number;
  timeoutMs?: number;
  stream?: boolean;
  thinkingEnabled?: boolean;
  reasoningEffort?: string;
  requireToolCalling?: boolean;
  idempotencyKey?: string;
};

export type LlmInvocationLogQueryReq = {
  providerType?: string;
  modelName?: string;
  status?: string;
  errorCode?: string;
  conversationId?: number;
  startTime?: string;
  endTime?: string;
  pageNo?: number;
  pageSize?: number;
};

/**
 * 查询模型能力配置列表。
 *
 * @returns 后端统一响应，data 为模型能力数组。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function getLlmCapabilities(): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}models/capabilities`, {
    method: 'GET',
  });
}

/**
 * 保存模型能力配置。
 *
 * @param data 模型能力配置；必须包含 `chatModelId` 和 `modelName`。
 * @returns 后端统一响应，data 为保存后的模型能力。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function updateLlmCapability(data: LlmModelCapability): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}models/capabilities`, {
    method: 'PUT',
    data,
  });
}

/**
 * 创建本地 LLM 调试会话。
 *
 * @param data 会话创建参数，包括连接 ID、模型名和 System Prompt。
 * @returns 后端统一响应，data 为会话摘要。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function createLlmConversation(data: LlmConversationCreateReq): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}conversations`, {
    method: 'POST',
    data,
  });
}

/**
 * 向指定会话追加用户消息并调用模型。
 *
 * @param conversationId 会话 ID；Gateway 会基于它拼接完整 messages。
 * @param data 消息内容和输出模式、thinking、超时等调用参数。
 * @returns 后端统一响应，data 为 assistant 响应和 token/latency 等调试信息。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function sendLlmConversationMessage(
  conversationId: number,
  data: LlmMessageCreateReq,
): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}conversations/${conversationId}/messages`, {
    method: 'POST',
    data,
  });
}

/**
 * 查询会话详情。
 *
 * @param conversationId 会话 ID。
 * @returns 后端统一响应，data 为会话摘要和本地消息列表。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function getLlmConversation(conversationId: number): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}conversations/${conversationId}`, {
    method: 'GET',
  });
}

/**
 * 查询脱敏后的 LLM 调用日志列表。
 *
 * @param data 日志筛选条件和分页参数。
 * @returns 后端统一响应，data 为日志列表。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function getLlmInvocationLogs(data: LlmInvocationLogQueryReq): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}invocation-logs/search`, {
    method: 'POST',
    data,
  });
}

/**
 * 查询单条脱敏调用日志详情。
 *
 * @param id 调用日志 ID。
 * @returns 后端统一响应，data 为日志详情。
 * @throws 网络异常或请求拦截器抛出的异常。
 */
export function getLlmInvocationLogDetail(id: number): Promise<any> {
  return request(`${LLM_GATEWAY_BASE_URL}invocation-logs/${id}`, {
    method: 'GET',
  });
}
