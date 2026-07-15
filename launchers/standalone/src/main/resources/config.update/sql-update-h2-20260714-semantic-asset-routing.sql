-- 20260714 AI semantic asset routing before draft generation.
-- The route table persists only bounded analysis/confirmation snapshots. It has no foreign keys
-- to formal semantic metadata, so a routing failure can never mutate or publish an asset.
CREATE TABLE IF NOT EXISTS `s2_semantic_asset_route`
(
    `id`                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `source_type`                     VARCHAR(32) NOT NULL,
    `source_id`                       BIGINT DEFAULT NULL,
    `request_fingerprint`             VARCHAR(128) NOT NULL,
    `idempotency_key`                 VARCHAR(128) NOT NULL,
    `status`                          VARCHAR(32) NOT NULL,
    `business_goal`                   CLOB NOT NULL,
    `domain_id`                       BIGINT DEFAULT NULL,
    `data_source_id`                  BIGINT NOT NULL,
    `catalog_name`                    VARCHAR(255) DEFAULT NULL,
    `database_name`                   VARCHAR(255) DEFAULT NULL,
    `selected_tables`                 CLOB NOT NULL,
    `chat_model_id`                   INT NOT NULL,
    `include_sample`                  BOOLEAN NOT NULL DEFAULT FALSE,
    `candidate_snapshot`              CLOB DEFAULT NULL,
    `rule_evidence`                   CLOB DEFAULT NULL,
    `llm_advice`                      CLOB DEFAULT NULL,
    `recommended_action`              VARCHAR(32) DEFAULT NULL,
    `recommended_candidate_type`      VARCHAR(32) DEFAULT NULL,
    `recommended_candidate_id`        BIGINT DEFAULT NULL,
    `recommended_candidate_version`   BIGINT DEFAULT NULL,
    `covered_capabilities`            CLOB DEFAULT NULL,
    `missing_capabilities`            CLOB DEFAULT NULL,
    `result_operations`               CLOB DEFAULT NULL,
    `business_questions`              CLOB DEFAULT NULL,
    `decision_source`                 VARCHAR(32) DEFAULT NULL,
    `confirmed_action`                VARCHAR(32) DEFAULT NULL,
    `confirmed_candidate_type`        VARCHAR(32) DEFAULT NULL,
    `confirmed_candidate_id`          BIGINT DEFAULT NULL,
    `confirmed_candidate_version`     BIGINT DEFAULT NULL,
    `business_answers`                CLOB DEFAULT NULL,
    `override_reason`                 VARCHAR(1000) DEFAULT NULL,
    `confirmed_by`                    VARCHAR(100) DEFAULT NULL,
    `confirmed_at`                    TIMESTAMP DEFAULT NULL,
    `llm_conversation_id`             BIGINT DEFAULT NULL,
    `failure_code`                    VARCHAR(64) DEFAULT NULL,
    `failure_message`                 VARCHAR(1500) DEFAULT NULL,
    `analysis_started_at`             TIMESTAMP DEFAULT NULL,
    `analysis_completed_at`           TIMESTAMP DEFAULT NULL,
    `lease_expires_at`                TIMESTAMP DEFAULT NULL,
    `expires_at`                      TIMESTAMP DEFAULT NULL,
    `analysis_version`                INT NOT NULL DEFAULT 1,
    `confirmation_idempotency_key`    VARCHAR(128) DEFAULT NULL,
    `confirmation_request_fingerprint` VARCHAR(128) DEFAULT NULL,
    `consumed_by_draft_id`            BIGINT DEFAULT NULL,
    `consumed_at`                     TIMESTAMP DEFAULT NULL,
    `lock_version`                    INT NOT NULL DEFAULT 0,
    `created_by`                      VARCHAR(100) NOT NULL,
    `created_at`                      TIMESTAMP NOT NULL,
    `updated_by`                      VARCHAR(100) NOT NULL,
    `updated_at`                      TIMESTAMP NOT NULL
);

COMMENT ON TABLE `s2_semantic_asset_route` IS
    'Auditable semantic asset routing snapshots; never writes or publishes formal semantic assets';
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_asset_route_idempotency`
    ON `s2_semantic_asset_route` (`created_by`, `idempotency_key`);
CREATE INDEX IF NOT EXISTS `idx_semantic_asset_route_source`
    ON `s2_semantic_asset_route` (`source_type`, `source_id`, `id`);
CREATE INDEX IF NOT EXISTS `idx_semantic_asset_route_lease`
    ON `s2_semantic_asset_route` (`status`, `lease_expires_at`);
CREATE INDEX IF NOT EXISTS `idx_semantic_asset_route_confirmed_by`
    ON `s2_semantic_asset_route` (`confirmed_by`, `confirmed_at`);
CREATE INDEX IF NOT EXISTS `idx_semantic_asset_route_decision`
    ON `s2_semantic_asset_route` (`confirmed_action`, `confirmed_at`);
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_asset_route_consumed_draft`
    ON `s2_semantic_asset_route` (`consumed_by_draft_id`);

-- Historical drafts remain readable because every route binding column is nullable. The unique
-- route index prevents one confirmed snapshot from creating multiple incompatible drafts.
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `route_analysis_id` BIGINT DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `route_action` VARCHAR(32) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `route_target_asset_type` VARCHAR(32) DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `route_target_asset_id` BIGINT DEFAULT NULL;
ALTER TABLE `s2_semantic_modeling_draft`
    ADD COLUMN IF NOT EXISTS `route_target_asset_version` BIGINT DEFAULT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS `uk_semantic_draft_route_analysis`
    ON `s2_semantic_modeling_draft` (`route_analysis_id`);
