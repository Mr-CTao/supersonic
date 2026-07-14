-- 20260714 AI semantic modeling phase 5: approval, release steps, knowledge refresh and rollback.
ALTER TABLE s2_semantic_modeling_draft
    ADD COLUMN IF NOT EXISTS approved_by varchar(100) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS approved_at timestamp DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS approval_reason varchar(1000) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS rejected_by varchar(100) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS rejected_at timestamp DEFAULT NULL;

CREATE TABLE IF NOT EXISTS s2_semantic_release (
    id BIGSERIAL PRIMARY KEY,
    release_no varchar(64) NOT NULL,
    draft_id bigint NOT NULL,
    draft_version_id bigint NOT NULL,
    draft_version_no integer NOT NULL,
    validation_report_id bigint NOT NULL,
    release_status varchar(32) NOT NULL,
    released_objects text NOT NULL,
    dict_reload_status varchar(32) NOT NULL,
    embedding_reload_status varchar(32) NOT NULL,
    approved_by varchar(100) NOT NULL,
    released_by varchar(100) NOT NULL,
    released_at timestamp DEFAULT NULL,
    rollback_from_release_id bigint DEFAULT NULL,
    rollback_reason varchar(1000) DEFAULT NULL,
    rolled_back_by varchar(100) DEFAULT NULL,
    rolled_back_at timestamp DEFAULT NULL,
    error_message varchar(1000) DEFAULT NULL,
    idempotency_key varchar(128) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_release_no ON s2_semantic_release (release_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_release_draft ON s2_semantic_release (draft_id);
CREATE INDEX IF NOT EXISTS idx_semantic_release_status
    ON s2_semantic_release (release_status, id DESC);

CREATE TABLE IF NOT EXISTS s2_semantic_release_step (
    id BIGSERIAL PRIMARY KEY,
    release_id bigint NOT NULL,
    step_key varchar(255) NOT NULL,
    step_type varchar(64) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_key varchar(255) DEFAULT NULL,
    target_name varchar(255) DEFAULT NULL,
    target_id bigint DEFAULT NULL,
    status varchar(32) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 1,
    error_message varchar(1000) DEFAULT NULL,
    started_at timestamp DEFAULT NULL,
    finished_at timestamp DEFAULT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_release_step
    ON s2_semantic_release_step (release_id, step_key);
CREATE INDEX IF NOT EXISTS idx_semantic_release_step_list
    ON s2_semantic_release_step (release_id, id);
