-- 20260710 AI semantic modeling phase 3: structured draft and immutable versions.
-- This migration only creates isolated draft tables. It never writes formal semantic assets
-- and intentionally declares no foreign keys so rollback remains independent of phase 1/2 data.

CREATE TABLE IF NOT EXISTS s2_semantic_modeling_draft (
    id BIGSERIAL PRIMARY KEY,
    source_type varchar(32) NOT NULL,
    source_id bigint DEFAULT NULL,
    title varchar(255) DEFAULT NULL,
    business_goal text NOT NULL,
    domain_id bigint DEFAULT NULL,
    data_source_id bigint NOT NULL,
    catalog_name varchar(255) DEFAULT NULL,
    database_name varchar(255) DEFAULT NULL,
    selected_tables text NOT NULL,
    chat_model_id integer NOT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    include_sample boolean NOT NULL DEFAULT false,
    idempotency_key varchar(128) NOT NULL,
    status varchar(32) NOT NULL,
    current_version_no integer NOT NULL DEFAULT 0,
    lock_version integer NOT NULL DEFAULT 0,
    generation_started_at timestamp DEFAULT NULL,
    generation_finished_at timestamp DEFAULT NULL,
    draft_json text DEFAULT NULL,
    raw_output text DEFAULT NULL,
    repaired_output text DEFAULT NULL,
    error_code varchar(64) DEFAULT NULL,
    error_message varchar(1500) DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_modeling_draft IS
    'AI semantic modeling structured draft; statuses are GENERATING, DRAFT and GENERATION_FAILED';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_idempotency
    ON s2_semantic_modeling_draft (created_by, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_source
    ON s2_semantic_modeling_draft (source_type, source_id, status);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_list
    ON s2_semantic_modeling_draft (data_source_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_generation
    ON s2_semantic_modeling_draft (status, generation_started_at);

CREATE TABLE IF NOT EXISTS s2_semantic_modeling_draft_version (
    id BIGSERIAL PRIMARY KEY,
    draft_id bigint NOT NULL,
    version_no integer NOT NULL,
    draft_json text NOT NULL,
    change_source varchar(32) NOT NULL,
    change_summary varchar(1000) DEFAULT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_modeling_draft_version IS
    'Immutable AI semantic modeling draft snapshots; change source is AI_GENERATED or MANUAL_SAVE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_version
    ON s2_semantic_modeling_draft_version (draft_id, version_no);
