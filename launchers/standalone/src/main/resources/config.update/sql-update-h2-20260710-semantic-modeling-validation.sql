-- 20260710 AI semantic modeling phase 4: multi-turn revision persistence and validation gate.
-- H2 stores isolated draft validation evidence only; no formal semantic assets are written.

ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submitted_validation_report_id` BIGINT DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submission_idempotency_key` VARCHAR(128) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submitted_by` VARCHAR(100) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `submitted_at` TIMESTAMP DEFAULT NULL;

ALTER TABLE `s2_semantic_modeling_draft_version`
    ADD COLUMN IF NOT EXISTS `request_idempotency_key` VARCHAR(128) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft_version`
    ADD COLUMN IF NOT EXISTS `request_fingerprint` VARCHAR(128) DEFAULT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_version_request`
    ON `s2_semantic_modeling_draft_version` (`draft_id`, `request_idempotency_key`);

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
