-- 20260714 AI semantic modeling phase 5: approval, release steps, knowledge refresh and rollback.
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `approved_by` VARCHAR(100) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `approved_at` TIMESTAMP DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `approval_reason` VARCHAR(1000) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `rejected_by` VARCHAR(100) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `rejected_at` TIMESTAMP DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `s2_semantic_release`
(
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `release_no` VARCHAR(64) NOT NULL,
    `draft_id` BIGINT NOT NULL,
    `draft_version_id` BIGINT NOT NULL,
    `draft_version_no` INT NOT NULL,
    `validation_report_id` BIGINT NOT NULL,
    `release_status` VARCHAR(32) NOT NULL,
    `released_objects` CLOB NOT NULL,
    `dict_reload_status` VARCHAR(32) NOT NULL,
    `embedding_reload_status` VARCHAR(32) NOT NULL,
    `approved_by` VARCHAR(100) NOT NULL,
    `released_by` VARCHAR(100) NOT NULL,
    `released_at` TIMESTAMP DEFAULT NULL,
    `rollback_from_release_id` BIGINT DEFAULT NULL,
    `rollback_reason` VARCHAR(1000) DEFAULT NULL,
    `rolled_back_by` VARCHAR(100) DEFAULT NULL,
    `rolled_back_at` TIMESTAMP DEFAULT NULL,
    `error_message` VARCHAR(1000) DEFAULT NULL,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `created_at` TIMESTAMP NOT NULL,
    `updated_at` TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_release_no`
    ON `s2_semantic_release` (`release_no`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_release_draft`
    ON `s2_semantic_release` (`draft_id`);
CREATE INDEX IF NOT EXISTS `idx_semantic_release_status`
    ON `s2_semantic_release` (`release_status`, `id`);

CREATE TABLE IF NOT EXISTS `s2_semantic_release_step`
(
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `release_id` BIGINT NOT NULL,
    `step_key` VARCHAR(255) NOT NULL,
    `step_type` VARCHAR(64) NOT NULL,
    `target_type` VARCHAR(32) NOT NULL,
    `target_key` VARCHAR(255) DEFAULT NULL,
    `target_name` VARCHAR(255) DEFAULT NULL,
    `target_id` BIGINT DEFAULT NULL,
    `status` VARCHAR(32) NOT NULL,
    `attempt_count` INT NOT NULL DEFAULT 1,
    `error_message` VARCHAR(1000) DEFAULT NULL,
    `started_at` TIMESTAMP DEFAULT NULL,
    `finished_at` TIMESTAMP DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL,
    `updated_at` TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_release_step`
    ON `s2_semantic_release_step` (`release_id`, `step_key`);
CREATE INDEX IF NOT EXISTS `idx_semantic_release_step_list`
    ON `s2_semantic_release_step` (`release_id`, `id`);
