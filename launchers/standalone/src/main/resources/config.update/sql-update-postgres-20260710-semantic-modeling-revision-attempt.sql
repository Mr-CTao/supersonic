-- 20260710 AI semantic modeling phase 4: persistent revision attempts and cross-instance leases.
-- The table coordinates isolated draft revision only and stores no Provider prompt or response body.

CREATE TABLE IF NOT EXISTS s2_semantic_modeling_revision_attempt (
    id BIGSERIAL PRIMARY KEY,
    draft_id bigint NOT NULL,
    base_version_no integer NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(128) NOT NULL,
    status varchar(32) NOT NULL,
    active_marker integer DEFAULT NULL,
    lease_started_at timestamp NOT NULL,
    lease_expires_at timestamp NOT NULL,
    result_version_id bigint DEFAULT NULL,
    result_version_no integer DEFAULT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    error_code varchar(64) DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL,
    finished_at timestamp DEFAULT NULL
);

COMMENT ON TABLE s2_semantic_modeling_revision_attempt IS
    'Persistent cross-instance leases for isolated AI draft revision; no formal semantic asset writes';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_revision_attempt_request
    ON s2_semantic_modeling_revision_attempt (draft_id, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_revision_attempt_active
    ON s2_semantic_modeling_revision_attempt (draft_id, active_marker);
CREATE INDEX IF NOT EXISTS idx_semantic_revision_attempt_draft
    ON s2_semantic_modeling_revision_attempt (draft_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_revision_attempt_lease
    ON s2_semantic_modeling_revision_attempt (status, lease_expires_at);
