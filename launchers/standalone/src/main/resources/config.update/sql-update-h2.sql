-- 20260708 LLM Conversation Gateway phase 1
-- H2 migration converted from sql-update-mysql.sql.
-- This script is idempotent for existing local H2 databases used by standalone development.

CREATE TABLE IF NOT EXISTS `s2_llm_model_capability`
(
    `id`                             BIGINT AUTO_INCREMENT PRIMARY KEY,
    `chat_model_id`                  BIGINT       NOT NULL,
    `provider_type`                  VARCHAR(64)  NOT NULL,
    `model_name`                     VARCHAR(255) NOT NULL,
    `max_context_tokens`             INT DEFAULT NULL,
    `support_stream`                 BOOLEAN DEFAULT FALSE,
    `support_json_mode`              BOOLEAN DEFAULT FALSE,
    `support_tool_calling`           BOOLEAN DEFAULT FALSE,
    `support_thinking`               BOOLEAN DEFAULT FALSE,
    `support_chat_prefix_completion` BOOLEAN DEFAULT FALSE,
    `support_fim_completion`         BOOLEAN DEFAULT FALSE,
    `support_context_cache`          BOOLEAN DEFAULT FALSE,
    `support_system_prompt`          BOOLEAN DEFAULT TRUE,
    `recommended_temperature`        DOUBLE DEFAULT NULL,
    `usage_scene`                    VARCHAR(255) DEFAULT NULL,
    `enabled`                        BOOLEAN DEFAULT TRUE,
    `created_at`                     TIMESTAMP NOT NULL,
    `updated_at`                     TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_llm_model_capability` IS 'LLM model capability table';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_llm_capability_model`
    ON `s2_llm_model_capability` (`chat_model_id`, `model_name`);

CREATE TABLE IF NOT EXISTS `s2_llm_conversation`
(
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `conversation_type` VARCHAR(64)  NOT NULL,
    `chat_model_id`     BIGINT       NOT NULL,
    `provider_type`     VARCHAR(64)  NOT NULL,
    `model_name`        VARCHAR(255) NOT NULL,
    `business_id`       VARCHAR(128) DEFAULT NULL,
    `status`            VARCHAR(32)  NOT NULL,
    `summary`           CLOB DEFAULT NULL,
    `created_by`        VARCHAR(100) DEFAULT NULL,
    `created_at`        TIMESTAMP NOT NULL,
    `updated_at`        TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_llm_conversation` IS 'LLM local conversation table';
CREATE INDEX IF NOT EXISTS `idx_llm_conversation_business`
    ON `s2_llm_conversation` (`conversation_type`, `business_id`);

CREATE TABLE IF NOT EXISTS `s2_llm_message`
(
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `conversation_id`   BIGINT      NOT NULL,
    `role`              VARCHAR(32) NOT NULL,
    `content`           CLOB DEFAULT NULL,
    `reasoning_content` CLOB DEFAULT NULL,
    `content_type`      VARCHAR(32) DEFAULT NULL,
    `tool_calls`        CLOB DEFAULT NULL,
    `tool_call_id`      VARCHAR(128) DEFAULT NULL,
    `token_count`       INT DEFAULT NULL,
    `message_order`     INT NOT NULL,
    `created_at`        TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_llm_message` IS 'LLM conversation message table';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_llm_message_order`
    ON `s2_llm_message` (`conversation_id`, `message_order`);
CREATE INDEX IF NOT EXISTS `idx_llm_message_conversation`
    ON `s2_llm_message` (`conversation_id`);

CREATE TABLE IF NOT EXISTS `s2_llm_invocation_log`
(
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `conversation_id`   BIGINT       NOT NULL,
    `chat_model_id`     BIGINT       NOT NULL,
    `provider_type`     VARCHAR(64)  NOT NULL,
    `model_name`        VARCHAR(255) NOT NULL,
    `request_id`        VARCHAR(128) DEFAULT NULL,
    `prompt_tokens`     INT DEFAULT NULL,
    `completion_tokens` INT DEFAULT NULL,
    `total_tokens`      INT DEFAULT NULL,
    `latency_ms`        BIGINT DEFAULT NULL,
    `status`            VARCHAR(32) NOT NULL,
    `error_code`        VARCHAR(64) DEFAULT NULL,
    `error_message`     VARCHAR(1000) DEFAULT NULL,
    `raw_response_ref`  VARCHAR(1200) DEFAULT NULL,
    `created_at`        TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_llm_invocation_log` IS 'LLM invocation log table';
CREATE INDEX IF NOT EXISTS `idx_llm_invocation_conversation`
    ON `s2_llm_invocation_log` (`conversation_id`);
