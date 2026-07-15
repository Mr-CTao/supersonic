-- 20260714 AI semantic asset routing before draft generation.
-- The route table stores durable policy evidence and confirmation snapshots only. It deliberately
-- has no foreign keys to formal semantic metadata, preserving rollback and publication boundaries.
CREATE TABLE IF NOT EXISTS s2_semantic_asset_route (
    id BIGSERIAL PRIMARY KEY,
    source_type varchar(32) NOT NULL,
    source_id bigint DEFAULT NULL,
    request_fingerprint varchar(128) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    status varchar(32) NOT NULL,
    business_goal text NOT NULL,
    domain_id bigint DEFAULT NULL,
    data_source_id bigint NOT NULL,
    catalog_name varchar(255) DEFAULT NULL,
    database_name varchar(255) DEFAULT NULL,
    selected_tables text NOT NULL,
    chat_model_id integer NOT NULL,
    include_sample boolean NOT NULL DEFAULT false,
    candidate_snapshot text DEFAULT NULL,
    rule_evidence text DEFAULT NULL,
    llm_advice text DEFAULT NULL,
    recommended_action varchar(32) DEFAULT NULL,
    recommended_candidate_type varchar(32) DEFAULT NULL,
    recommended_candidate_id bigint DEFAULT NULL,
    recommended_candidate_version bigint DEFAULT NULL,
    covered_capabilities text DEFAULT NULL,
    missing_capabilities text DEFAULT NULL,
    result_operations text DEFAULT NULL,
    business_questions text DEFAULT NULL,
    decision_source varchar(32) DEFAULT NULL,
    confirmed_action varchar(32) DEFAULT NULL,
    confirmed_candidate_type varchar(32) DEFAULT NULL,
    confirmed_candidate_id bigint DEFAULT NULL,
    confirmed_candidate_version bigint DEFAULT NULL,
    business_answers text DEFAULT NULL,
    override_reason varchar(1000) DEFAULT NULL,
    confirmed_by varchar(100) DEFAULT NULL,
    confirmed_at timestamp DEFAULT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    failure_code varchar(64) DEFAULT NULL,
    failure_message varchar(1500) DEFAULT NULL,
    analysis_started_at timestamp DEFAULT NULL,
    analysis_completed_at timestamp DEFAULT NULL,
    lease_expires_at timestamp DEFAULT NULL,
    expires_at timestamp DEFAULT NULL,
    analysis_version integer NOT NULL DEFAULT 1,
    confirmation_idempotency_key varchar(128) DEFAULT NULL,
    confirmation_request_fingerprint varchar(128) DEFAULT NULL,
    consumed_by_draft_id bigint DEFAULT NULL,
    consumed_at timestamp DEFAULT NULL,
    lock_version integer NOT NULL DEFAULT 0,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_asset_route IS
    'Auditable semantic asset routing snapshots; never writes or publishes formal semantic assets';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_asset_route_idempotency
    ON s2_semantic_asset_route (created_by, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_semantic_asset_route_source
    ON s2_semantic_asset_route (source_type, source_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_asset_route_lease
    ON s2_semantic_asset_route (status, lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_semantic_asset_route_confirmed_by
    ON s2_semantic_asset_route (confirmed_by, confirmed_at DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_asset_route_decision
    ON s2_semantic_asset_route (confirmed_action, confirmed_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_asset_route_consumed_draft
    ON s2_semantic_asset_route (consumed_by_draft_id);

-- Nullable columns preserve historical draft compatibility; the unique index enforces a single
-- draft consumer for each confirmed route snapshot.
ALTER TABLE s2_semantic_modeling_draft
    ADD COLUMN IF NOT EXISTS route_analysis_id bigint DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS route_action varchar(32) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS route_target_asset_type varchar(32) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS route_target_asset_id bigint DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS route_target_asset_version bigint DEFAULT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_route_analysis
    ON s2_semantic_modeling_draft (route_analysis_id);
