-- Rollback for 20260708 LLM Conversation Gateway phase 1.
-- 执行前请先确认无需保留阶段 1 的会话、消息、能力和调用审计数据。
DROP TABLE IF EXISTS `s2_llm_invocation_log`;
DROP TABLE IF EXISTS `s2_llm_message`;
DROP TABLE IF EXISTS `s2_llm_conversation`;
DROP TABLE IF EXISTS `s2_llm_model_capability`;
