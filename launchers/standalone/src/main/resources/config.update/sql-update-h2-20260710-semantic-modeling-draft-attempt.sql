-- 20260710 AI semantic modeling phase 3: generation-attempt history and retry support.
-- H2 is retained for fresh-schema compatibility; stage 3 verification runs against PostgreSQL.

ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `current_attempt_no` INT NOT NULL DEFAULT 1;

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

CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_attempt_no`
    ON `s2_semantic_modeling_draft_attempt` (`draft_id`, `attempt_no`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_attempt_idempotency`
    ON `s2_semantic_modeling_draft_attempt` (`created_by`, `idempotency_key`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_attempt_list`
    ON `s2_semantic_modeling_draft_attempt` (`draft_id`, `attempt_no`);
CREATE INDEX IF NOT EXISTS `idx_semantic_draft_attempt_recovery`
    ON `s2_semantic_modeling_draft_attempt` (`status`, `started_at`);

INSERT INTO `s2_semantic_modeling_draft_attempt` (
    `draft_id`, `attempt_no`, `trigger_type`, `status`, `chat_model_id`, `include_sample`,
    `idempotency_key`, `llm_conversation_id`, `raw_output`, `repaired_output`, `error_code`,
    `error_message`, `started_at`, `finished_at`, `created_by`, `created_at`, `updated_by`, `updated_at`
)
SELECT d.`id`,
       1,
       'INITIAL',
       CASE
           WHEN d.`status` = 'DRAFT' THEN 'SUCCEEDED'
           WHEN d.`status` = 'GENERATION_FAILED' THEN 'FAILED'
           WHEN d.`status` = 'GENERATING' AND d.`generation_started_at` IS NULL THEN 'QUEUED'
           ELSE 'GENERATING'
       END,
       d.`chat_model_id`,
       d.`include_sample`,
       d.`idempotency_key`,
       d.`llm_conversation_id`,
       d.`raw_output`,
       d.`repaired_output`,
       d.`error_code`,
       d.`error_message`,
       d.`generation_started_at`,
       d.`generation_finished_at`,
       d.`created_by`,
       d.`created_at`,
       d.`updated_by`,
       d.`updated_at`
FROM `s2_semantic_modeling_draft` d
WHERE NOT EXISTS (
    SELECT 1
    FROM `s2_semantic_modeling_draft_attempt` a
    WHERE a.`draft_id` = d.`id` AND a.`attempt_no` = 1
);
