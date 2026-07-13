-- 20260710 AI semantic modeling phase 4: persistent revision attempts and cross-instance leases.
-- H2 keeps the same nullable active-marker uniqueness protocol as MySQL and PostgreSQL.

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
