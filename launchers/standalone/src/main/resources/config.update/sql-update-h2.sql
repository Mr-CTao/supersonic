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

-- 20260710 AI semantic modeling phase 3: structured draft and immutable versions.
CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft`
(
    `id`                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    `source_type`            VARCHAR(32) NOT NULL,
    `source_id`              BIGINT DEFAULT NULL,
    `title`                  VARCHAR(255) DEFAULT NULL,
    `business_goal`          CLOB NOT NULL,
    `domain_id`              BIGINT DEFAULT NULL,
    `data_source_id`         BIGINT NOT NULL,
    `catalog_name`           VARCHAR(255) DEFAULT NULL,
    `database_name`          VARCHAR(255) DEFAULT NULL,
    `selected_tables`        CLOB NOT NULL,
    `chat_model_id`          INT NOT NULL,
    `llm_conversation_id`    BIGINT DEFAULT NULL,
    `include_sample`         BOOLEAN NOT NULL DEFAULT FALSE,
    `idempotency_key`        VARCHAR(128) NOT NULL,
    `status`                 VARCHAR(32) NOT NULL,
    `current_version_no`     INT NOT NULL DEFAULT 0,
    `current_attempt_no`     INT NOT NULL DEFAULT 1,
    `lock_version`           INT NOT NULL DEFAULT 0,
    `generation_started_at`  TIMESTAMP DEFAULT NULL,
    `generation_finished_at` TIMESTAMP DEFAULT NULL,
    `draft_json`             CLOB DEFAULT NULL,
    `raw_output`             CLOB DEFAULT NULL,
    `repaired_output`        CLOB DEFAULT NULL,
    `error_code`             VARCHAR(64) DEFAULT NULL,
    `error_message`          VARCHAR(1500) DEFAULT NULL,
    `submitted_validation_report_id` BIGINT DEFAULT NULL,
    `submission_idempotency_key` VARCHAR(128) DEFAULT NULL,
    `submitted_by`           VARCHAR(100) DEFAULT NULL,
    `submitted_at`           TIMESTAMP DEFAULT NULL,
    `created_by`             VARCHAR(100) NOT NULL,
    `created_at`             TIMESTAMP NOT NULL,
    `updated_by`             VARCHAR(100) NOT NULL,
    `updated_at`             TIMESTAMP NOT NULL
);

-- CREATE TABLE IF NOT EXISTS does not merge new columns into an existing phase-3 table.
-- Keep these ALTER statements idempotent so fresh installs, upgrades and partial retries converge.
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submitted_validation_report_id` BIGINT DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submission_idempotency_key` VARCHAR(128) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submitted_by` VARCHAR(100) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submitted_at` TIMESTAMP DEFAULT NULL;

COMMENT ON TABLE `s2_semantic_modeling_draft` IS
    'Isolated AI semantic modeling draft; phase 4 may mark PENDING_APPROVAL but never writes formal semantic assets';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_idempotency`
    ON `s2_semantic_modeling_draft` (`created_by`, `idempotency_key`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_source`
    ON `s2_semantic_modeling_draft` (`source_type`, `source_id`, `status`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_list`
    ON `s2_semantic_modeling_draft` (`data_source_id`, `id`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_generation`
    ON `s2_semantic_modeling_draft` (`status`, `generation_started_at`);

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_attempt`
(
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    `draft_id`            BIGINT NOT NULL,
    `attempt_no`          INT NOT NULL,
    `trigger_type`        VARCHAR(32) NOT NULL,
    `status`              VARCHAR(32) NOT NULL,
    `chat_model_id`       INT NOT NULL,
    `include_sample`      BOOLEAN NOT NULL DEFAULT FALSE,
    `idempotency_key`     VARCHAR(128) NOT NULL,
    `request_fingerprint` VARCHAR(128) DEFAULT NULL,
    `llm_conversation_id` BIGINT DEFAULT NULL,
    `generate_request_id` VARCHAR(128) DEFAULT NULL,
    `repair_request_id`   VARCHAR(128) DEFAULT NULL,
    `raw_output`          CLOB DEFAULT NULL,
    `repaired_output`     CLOB DEFAULT NULL,
    `failure_stage`       VARCHAR(64) DEFAULT NULL,
    `validation_issues`   CLOB DEFAULT NULL,
    `error_code`          VARCHAR(64) DEFAULT NULL,
    `error_message`       VARCHAR(1500) DEFAULT NULL,
    `started_at`          TIMESTAMP DEFAULT NULL,
    `finished_at`         TIMESTAMP DEFAULT NULL,
    `created_by`          VARCHAR(100) NOT NULL,
    `created_at`          TIMESTAMP NOT NULL,
    `updated_by`          VARCHAR(100) NOT NULL,
    `updated_at`          TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_semantic_modeling_draft_attempt` IS
    'Immutable generation-attempt history for AI semantic modeling drafts';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_attempt_no`
    ON `s2_semantic_modeling_draft_attempt` (`draft_id`, `attempt_no`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_attempt_idempotency`
    ON `s2_semantic_modeling_draft_attempt` (`created_by`, `idempotency_key`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_attempt_list`
    ON `s2_semantic_modeling_draft_attempt` (`draft_id`, `attempt_no`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_attempt_recovery`
    ON `s2_semantic_modeling_draft_attempt` (`status`, `started_at`);

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_version`
(
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    `draft_id`            BIGINT NOT NULL,
    `version_no`          INT NOT NULL,
    `draft_json`          CLOB NOT NULL,
    `change_source`       VARCHAR(32) NOT NULL,
    `change_summary`      VARCHAR(1000) DEFAULT NULL,
    `llm_conversation_id` BIGINT DEFAULT NULL,
    `request_idempotency_key` VARCHAR(128) DEFAULT NULL,
    `request_fingerprint` VARCHAR(128) DEFAULT NULL,
    `result_lock_version` INT DEFAULT NULL,
    `created_by`          VARCHAR(100) NOT NULL,
    `created_at`          TIMESTAMP NOT NULL
);

-- Existing phase-3 version tables also require real ALTER statements before the new index is built.
ALTER TABLE `s2_semantic_modeling_draft_version`
    ADD COLUMN IF NOT EXISTS `request_idempotency_key` VARCHAR(128) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft_version`
    ADD COLUMN IF NOT EXISTS `request_fingerprint` VARCHAR(128) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft_version`
    ADD COLUMN IF NOT EXISTS `result_lock_version` INT DEFAULT NULL;

COMMENT ON TABLE `s2_semantic_modeling_draft_version` IS
    'Immutable isolated draft snapshots; change source is AI_GENERATED, MANUAL_SAVE or AI_REVISED';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_version`
    ON `s2_semantic_modeling_draft_version` (`draft_id`, `version_no`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_version_request`
    ON `s2_semantic_modeling_draft_version` (`draft_id`, `request_idempotency_key`);

-- Persist Provider-call ownership so aggregate upgrades receive the same cross-instance revision lease.
CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_revision_attempt`
(
    `id`                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    `draft_id`              BIGINT NOT NULL,
    `base_version_no`       INT NOT NULL,
    `idempotency_key`       VARCHAR(128) NOT NULL,
    `request_fingerprint`   VARCHAR(128) NOT NULL,
    `status`                VARCHAR(32) NOT NULL,
    `active_marker`         INT DEFAULT NULL,
    `lease_started_at`      TIMESTAMP NOT NULL,
    `lease_expires_at`      TIMESTAMP NOT NULL,
    `result_version_id`     BIGINT DEFAULT NULL,
    `result_version_no`     INT DEFAULT NULL,
    `llm_conversation_id`   BIGINT DEFAULT NULL,
    `error_code`            VARCHAR(64) DEFAULT NULL,
    `created_by`            VARCHAR(100) NOT NULL,
    `created_at`            TIMESTAMP NOT NULL,
    `updated_by`            VARCHAR(100) NOT NULL,
    `updated_at`            TIMESTAMP NOT NULL,
    `finished_at`           TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE `s2_semantic_modeling_revision_attempt` IS
    'Persistent cross-instance leases for isolated AI draft revision; no formal semantic asset writes';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_revision_attempt_request`
    ON `s2_semantic_modeling_revision_attempt` (`draft_id`, `idempotency_key`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_revision_attempt_active`
    ON `s2_semantic_modeling_revision_attempt` (`draft_id`, `active_marker`);
CREATE INDEX IF NOT EXISTS `idx_semantic_revision_attempt_draft`
    ON `s2_semantic_modeling_revision_attempt` (`draft_id`, `id`);
CREATE INDEX IF NOT EXISTS `idx_semantic_revision_attempt_lease`
    ON `s2_semantic_modeling_revision_attempt` (`status`, `lease_expires_at`);

-- 20260710 phase 4 stores validation evidence for isolated draft versions only.
-- It never writes formal semantic assets, performs approval, publishes or rolls back objects.
CREATE TABLE IF NOT EXISTS `s2_semantic_validation_report`
(
    `id`                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    `draft_id`                 BIGINT NOT NULL,
    `draft_version_id`         BIGINT NOT NULL,
    `draft_version_no`         INT NOT NULL,
    `status`                   VARCHAR(32) NOT NULL,
    `validation_options`       CLOB DEFAULT NULL,
    `required_check_results`   CLOB DEFAULT NULL,
    `planned_objects`          CLOB DEFAULT NULL,
    `field_existence_result`   CLOB DEFAULT NULL,
    `conflict_result`          CLOB DEFAULT NULL,
    `sensitive_field_result`   CLOB DEFAULT NULL,
    `sample_question_results`  CLOB DEFAULT NULL,
    `sql_safety_result`        CLOB DEFAULT NULL,
    `performance_risk_result`  CLOB DEFAULT NULL,
    `uncertainty_result`       CLOB DEFAULT NULL,
    `blocking_items`           CLOB DEFAULT NULL,
    `warning_items`            CLOB DEFAULT NULL,
    `blocking_count`           INT NOT NULL DEFAULT 0,
    `warning_count`            INT NOT NULL DEFAULT 0,
    `active_marker`            INT DEFAULT NULL,
    `system_error_code`        VARCHAR(64) DEFAULT NULL,
    `created_by`               VARCHAR(100) NOT NULL,
    `created_at`               TIMESTAMP NOT NULL,
    `finished_at`              TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE `s2_semantic_validation_report` IS
    'Phase 4 validation reports for isolated drafts; no approval, publication or formal semantic asset writes';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_validation_active`
    ON `s2_semantic_validation_report` (`draft_id`, `active_marker`);
CREATE INDEX IF NOT EXISTS `idx_semantic_validation_draft`
    ON `s2_semantic_validation_report` (`draft_id`, `id`);
CREATE INDEX IF NOT EXISTS `idx_semantic_validation_version_status`
    ON `s2_semantic_validation_report` (`draft_version_id`, `status`);
