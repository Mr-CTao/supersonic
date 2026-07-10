-- 20260710 AI semantic modeling phase 3: structured draft and immutable versions.
-- This migration only creates isolated draft tables. It never writes formal semantic assets
-- and intentionally declares no foreign keys so rollback remains independent of phase 1/2 data.

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
    `lock_version`           INT NOT NULL DEFAULT 0,
    `generation_started_at`  TIMESTAMP DEFAULT NULL,
    `generation_finished_at` TIMESTAMP DEFAULT NULL,
    `draft_json`             CLOB DEFAULT NULL,
    `raw_output`             CLOB DEFAULT NULL,
    `repaired_output`        CLOB DEFAULT NULL,
    `error_code`             VARCHAR(64) DEFAULT NULL,
    `error_message`          VARCHAR(1500) DEFAULT NULL,
    `created_by`             VARCHAR(100) NOT NULL,
    `created_at`             TIMESTAMP NOT NULL,
    `updated_by`             VARCHAR(100) NOT NULL,
    `updated_at`             TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_semantic_modeling_draft` IS
    'AI semantic modeling structured draft; statuses are GENERATING, DRAFT and GENERATION_FAILED';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_idempotency`
    ON `s2_semantic_modeling_draft` (`created_by`, `idempotency_key`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_source`
    ON `s2_semantic_modeling_draft` (`source_type`, `source_id`, `status`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_list`
    ON `s2_semantic_modeling_draft` (`data_source_id`, `id`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_generation`
    ON `s2_semantic_modeling_draft` (`status`, `generation_started_at`);

CREATE TABLE IF NOT EXISTS `s2_semantic_modeling_draft_version`
(
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    `draft_id`            BIGINT NOT NULL,
    `version_no`          INT NOT NULL,
    `draft_json`          CLOB NOT NULL,
    `change_source`       VARCHAR(32) NOT NULL,
    `change_summary`      VARCHAR(1000) DEFAULT NULL,
    `llm_conversation_id` BIGINT DEFAULT NULL,
    `created_by`          VARCHAR(100) NOT NULL,
    `created_at`          TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_semantic_modeling_draft_version` IS
    'Immutable AI semantic modeling draft snapshots; change source is AI_GENERATED or MANUAL_SAVE';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_version`
    ON `s2_semantic_modeling_draft_version` (`draft_id`, `version_no`);
